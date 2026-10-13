@file:Suppress("TooManyFunctions") // One feed factory per Home-engine board.

package com.zillit.desktop

import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.media.AttachmentPicker.Companion.MAX_READABLE_BYTES
import com.zillit.desktop.core.database.ProjectSnapshot
import com.zillit.desktop.core.media.MediaSendTracker
import com.zillit.desktop.core.media.UploadProgress
import com.zillit.desktop.core.media.VideoSend
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.PinnedChatMedia
import com.zillit.desktop.feature.home.ui.BoardPin
import com.zillit.desktop.core.media.VideoCompressor
import com.zillit.desktop.core.media.ffmpegPosterJpeg
import com.zillit.desktop.core.media.imageThumbnailJpeg
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.domain.StorageKind
import com.zillit.desktop.feature.email.domain.storageKindOf
import com.zillit.desktop.feature.home.data.ClipAudioPlayer
import com.zillit.desktop.feature.home.data.JvmAudioRecorder
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.badgeSilenceFrom
import com.zillit.desktop.core.badges.identifierToWireTool
import com.zillit.desktop.core.badges.ledgerArrivalsFrom
import com.zillit.desktop.core.badges.wireRecords
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.zillit.desktop.feature.home.data.pdfThumbnailJpeg
import com.zillit.desktop.feature.home.data.videoThumbnailJpeg
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.ReportUnitsSource
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import java.util.UUID
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.chat.domain.MEMBER_DESIGNATION
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything that wires the home feed's platform pieces together: the picker,
 * the uploader, the microphone, poster frames, static maps, and the
 * save-and-open path for attachments. Split from `main.kt`, which only
 * assembles.
 */

/**
 * "Full Name (Designation)" — Android's `getUserNameByUserId(true)`, rule for
 * rule: the placeholder crew designation says nothing and is hidden, and the
 * designation is a LABEL KEY on the wire (`armourer_label`), so it goes
 * through the dictionary before anyone reads it (QA saw the raw keys in
 * brackets on every board and chat list, 2026-08-20).
 */
internal fun UserSnapshot.authorLine(): String =
    designationText()?.let { "$fullName ($it)" } ?: fullName

/** The designation as words, or null when the record has none worth showing. */
internal fun UserSnapshot.designationText(): String? =
    designation
        ?.takeIf { it.isNotBlank() && it != MEMBER_DESIGNATION }
        ?.let { Labels.translate(it) }

/**
 * The department as words, or null when the record has none.
 *
 * Same trap as the designation: `camera_department_label` on the wire. Read
 * [UserSnapshot.department] directly only to *match* it — against the
 * accounts/transport identifiers, or a department list's keys — never to show it.
 */
internal fun UserSnapshot.departmentText(): String? =
    department?.takeIf { it.isNotBlank() }?.let { Labels.translate(it) }

/**
 * Whether this crew member belongs in people lists.
 *
 * Android's Members filter keeps `approved`, `accepted`, `left` and `removed`
 * (`MembersVM.kt:416-420` — someone who left still has a history worth
 * opening) and drops the rest: `pending` hasn't joined yet, `rejected` never
 * will. A row with no status — older caches, thinner payloads — is presumed
 * present rather than hidden.
 */
internal fun UserSnapshot.hasJoined(): Boolean =
    status != "pending" && status != "rejected"

/**
 * On the crew now: joined, and neither left nor removed — who the Contacts
 * tab lists (Android's Contacts drops `left` and `removed`, `MembersVM.kt:473`).
 * Anywhere a person is offered to be reached, rather than read about, this is
 * the rule; history keeps the others.
 */
internal fun UserSnapshot.isActiveMember(): Boolean =
    hasJoined() && status != "left" && status != "removed"

/**
 * The map image for a shared location — Google Static Maps with the
 * production's own key from remote config. Null without a key or on any
 * failure; the location still sends, and the receivers' pin fallback
 * carries it.
 */
internal suspend fun fetchStaticMap(ready: AppGraph.Ready, point: GeoPoint): PickedMedia? =
    fetchStaticMapBytes(ready, point.lat, point.long)?.let { bytes ->
        PickedMedia(name = STATIC_MAP_NAME, contentType = STATIC_MAP_TYPE, bytes = bytes)
    }

/**
 * The same picture as bytes, for callers that upload it themselves — chat
 * hands these to its own routed uploader rather than the board's.
 *
 * The key never leaves this function: it rides the query string Google
 * requires and is never logged (the URL is not printed, and `httpClient`
 * is the plain client with body logging off).
 */
internal suspend fun fetchStaticMapBytes(ready: AppGraph.Ready, lat: Double, lng: Double): ByteArray? {
    val key = ready.remoteConfigRepository.credentials.value?.googleMapsKey
        ?.takeIf { it.isNotBlank() }
        ?: run {
            ZillitLog.w("StaticMap") { "no maps key in remote config; no picture" }
            return null
        }

    return runCatching {
        val url = "https://maps.googleapis.com/maps/api/staticmap" +
            "?center=$lat,$lng&zoom=15&size=600x400" +
            "&markers=color:red%7C$lat,$lng&key=$key"
        val response = ready.httpClient.get(url)
        if (response.status.isSuccess()) {
            response.readRawBytes()
        } else {
            // Google answers a refused key with 200 and an error PNG, so a
            // non-2xx here is worth saying out loud rather than swallowing.
            ZillitLog.w("StaticMap") { "map refused: ${response.status}" }
            null
        }
    }.onFailure { ZillitLog.w("StaticMap") { "map fetch failed: $it" } }.getOrNull()
}

private const val STATIC_MAP_NAME = "location-map.png"
private const val STATIC_MAP_TYPE = "image/png"

/**
 * What the composer can capture: picker, uploader, microphone, poster frames.
 *
 * [uploader] and [storageType] default to the open production's. A widget
 * posting into another production passes that production's pair instead —
 * which storage a file belongs in is a fact about the production receiving
 * it, not about the window doing the sending.
 */
internal fun homeMediaCapture(
    ready: AppGraph.Ready,
    uploader: AttachmentUploader = ready.attachmentUploader,
    storageType: () -> String? = { ready.projectContext?.context?.value?.project?.storageType },
    /**
     * Whether posts pin themselves to the production open when they are sent
     * ([BoardPin]) and report to the app-wide Uploads tray. True for the open
     * production's own boards; a widget posting into another production
     * passes its own uploader and is already bound to that production.
     */
    pinToOpenProject: Boolean = uploader === ready.attachmentUploader,
) = MediaCapture(
    // The same OS dialog mail attachments use. Several files become several
    // posts — the wire takes one attachment per message, as the phones send.
    // No size cap beyond what the app can hold: a large video is compressed
    // on its way out (below), not refused at the door.
    pick = { FilePicker(maxBytes = MAX_READABLE_BYTES).pick().map { it.toNoticeMedia() } },
    // The attach sheet's kind filters the same dialog — Photo, Video,
    // Document, Audio — and is checked again after the choice.
    pickOf = { kind -> attachmentPicker.pick(kind, maxBytes = MAX_READABLE_BYTES).map { it.toNoticeMedia() } },
    // The same routed uploader (S3 or Box by production) mail uses. A
    // picture's thumbnail or a video's poster frame travels first as its own
    // object; a thumbnail that fails to upload costs the thumbnail, never the
    // file. Progress says which phase it is in (UploadProgress): preparing
    // the thumbnail, compressing a large video, then uploading.
    upload = { original, onProgress ->
        // A board picture reaches here without one (only video and PDF
        // posters are cut before the card appears); chat's are cut already.
        val withThumbnail = if (original.thumbnailBytes == null && original.kind == NoticeKind.Image) {
            onProgress(UploadProgress.PREPARING)
            withThumbnail(original)
        } else {
            original
        }
        val picked = compressAsChosen(withThumbnail, onProgress)
        // The poster read path signs S3 GETs; a poster keyed into Box could
        // never be fetched. The web skips it there for the same reason.
        val storageIsAws = storageKindOf(storageType()) == StorageKind.Aws
        val thumbnailKey = picked.thumbnailBytes
            ?.takeIf { storageIsAws }
            ?.let { poster ->
                uploader
                    .upload(picked.name + "_thumb.jpg", "image/jpeg", poster)
                    .getOrNull()?.media
            }
        // Only the main file reports progress: the poster above is a few
        // kilobytes, and a bar that restarts for it would read as a glitch.
        uploader
            .upload(picked.name, picked.contentType, picked.bytes, onProgress)
            .map { stored ->
                UploadedNoticeMedia(
                    kind = picked.kind,
                    media = stored.media,
                    bucket = stored.bucket,
                    region = stored.region,
                    fileName = stored.fileName,
                    contentType = stored.contentType,
                    sizeBytes = stored.sizeBytes,
                    durationMillis = picked.durationMillis,
                    thumbnail = thumbnailKey,
                    widthPx = picked.thumbnailWidth,
                    heightPx = picked.thumbnailHeight,
                )
            }
    },
    // The system microphone; voice messages travel as WAV through the same
    // storage pipeline as any other attachment.
    recorder = JvmAudioRecorder(),
    newRecordingName = { "voice-message-${System.currentTimeMillis()}.wav" },
    // The map image for a shared location — Google Static Maps with the
    // production's own key from remote config. No key, no image; the location
    // still sends and the receivers' pin fallback carries it.
    staticMap = { point -> fetchStaticMap(ready, point) },
    // Thumbnails off the UI thread — a picture's, a video's poster frame, a
    // PDF's first page. Decoding is CPU work, not IO, but a dedicated
    // dispatcher for one thumbnail per pick is not worth owning.
    videoThumbnail = { picked -> withThumbnail(picked) },
    pin = { if (pinToOpenProject) ready.boardPin() else null },
    currentProjectId = { ready.projectContext?.context?.value?.project?.projectId },
    tracker = mediaSendTracker.takeIf { pinToOpenProject },
)

/**
 * Every outgoing file in the app — chat attachments and board posts from any
 * production — for the Uploads tray. One per process, like the picker.
 */
internal val mediaSendTracker = MediaSendTracker()

/** The open production as it is right now: its id, name, storage, and this user's id on it. */
private class OpenProject(val project: ProjectSnapshot, val options: CallOptions)

private fun AppGraph.Ready.openProject(): OpenProject? {
    val context = projectContext?.context?.value ?: return null
    val project = context.project ?: return null
    val userId = context.profile?.userId ?: return null
    return OpenProject(project, CallOptions(projectId = project.projectId, userId = userId))
}

/** A capture whose storage is the given production's, whatever opens later. */
private fun AppGraph.Ready.pinnedCapture(open: OpenProject): MediaCapture =
    homeMediaCapture(
        this,
        uploader = uploaderForProject(open.project, open.options),
        storageType = { open.project.storageType },
        pinToOpenProject = false,
    )

/** A board post bound to the production open now — see [BoardPin]. */
internal fun AppGraph.Ready.boardPin(): BoardPin? {
    val open = openProject() ?: return null
    val upload = pinnedCapture(open).upload ?: return null
    return BoardPin(open.project.projectId, open.project.name, open.options, upload)
}

/** A chat media send bound to the production open now — see [PinnedChatMedia]. */
internal fun AppGraph.Ready.pinChatMedia(): PinnedChatMedia? {
    val open = openProject() ?: return null
    val capture = pinnedCapture(open)
    val chats = chatRepositoryForProject(open.project.projectId, open.options.userId.orEmpty())
    val ready = this
    return object : PinnedChatMedia {
        override val projectId = open.project.projectId
        override val projectName = open.project.name

        override suspend fun upload(
            name: String,
            contentType: String,
            bytes: ByteArray,
            videoSend: VideoSend?,
            onProgress: (Int) -> Unit,
        ) = uploadChatMedia(ready, name, contentType, bytes, onProgress, capture, videoSend)

        override suspend fun send(
            peerId: String,
            body: String,
            uniqueId: String,
            timestampMillis: Long,
            isGroup: Boolean,
            attachment: ChatAttachment,
        ) = chats.send(peerId, body, uniqueId, timestampMillis, isGroup = isGroup, attachment = attachment)
    }
}

/** A sending chat bubble's picture: the thumbnail the upload will carry, cut ahead of it. */
internal suspend fun chatThumbnail(name: String, contentType: String, bytes: ByteArray): ByteArray? =
    withThumbnail(PickedMedia(name = name, contentType = contentType, bytes = bytes)).thumbnailBytes

/** [picked] with its thumbnail cut, or as it was when there is none to cut. */
private suspend fun withThumbnail(picked: PickedMedia): PickedMedia = withContext(Dispatchers.Default) {
    val thumbnail = when {
        picked.isPdf -> pdfThumbnailJpeg(picked.bytes)?.let { Triple(it.jpegBytes, it.widthPx, it.heightPx) }
        picked.kind == NoticeKind.Image ->
            imageThumbnailJpeg(picked.bytes)?.let { Triple(it.jpegBytes, it.widthPx, it.heightPx) }
        // jcodec reads H.264 only; FFmpeg cuts the frame for AV1, HEVC, VP9.
        picked.kind == NoticeKind.Video ->
            videoThumbnailJpeg(picked.bytes)?.let { Triple(it.jpegBytes, it.widthPx, it.heightPx) }
                ?: ffmpegPosterJpeg(picked.bytes, picked.name)?.let { Triple(it.jpegBytes, it.widthPx, it.heightPx) }
        else -> null
    }
    thumbnail?.let { (jpeg, width, height) ->
        picked.copy(thumbnailBytes = jpeg, thumbnailWidth = width, thumbnailHeight = height)
    } ?: picked
}

/**
 * A video re-encoded to the quality the sender picked in the preview — SD or
 * HD — before it uploads; anything else, or a video that would not compress,
 * comes back as it was.
 *
 * A send from a surface with no preview in front of it answers nothing, and
 * keeps the behaviour that predates the choice: HD above 100 MB, untouched
 * below it.
 *
 * Compressing reports as its own phase ([UploadProgress.compressing]), so the
 * bubble says "Compressing 40%" and then starts "Uploading" from zero rather
 * than calling the re-encode an upload. The poster was cut from the
 * original, which the compressed copy matches frame for frame.
 */
private suspend fun compressAsChosen(original: PickedMedia, onProgress: (Int) -> Unit): PickedMedia {
    val quality = original.videoSend
        ?: VideoSend.Hd.takeIf { VideoCompressor.wants(original.contentType, original.bytes.size.toLong()) }
        ?: return original
    if (quality == VideoSend.Original) return original
    onProgress(UploadProgress.compressing(0))
    // A clip most players cannot draw is converted whatever it then weighs:
    // there the point is the H.264, not the saving.
    val unplayable = VideoCompressor.unplayableFormat(original.bytes) != null
    val compressed = VideoCompressor.compress(
        name = original.name,
        bytes = original.bytes,
        quality = quality,
        requireSmaller = !unplayable,
    ) { percent ->
        onProgress(UploadProgress.compressing(percent))
    } ?: return original
    return original.copy(name = compressed.name, contentType = compressed.contentType, bytes = compressed.bytes)
}

/** Email's picker type and home's, bridged where both are in scope. */
internal fun com.zillit.desktop.feature.email.domain.PickedFile.toNoticeMedia() =
    PickedMedia(name = name, contentType = contentType, bytes = bytes)

internal fun com.zillit.desktop.core.media.PickedFile.toNoticeMedia() =
    PickedMedia(name = name, contentType = contentType, bytes = bytes)

/** One picker for every attach sheet in the app; the kind is the only thing that varies. */
internal val attachmentPicker: com.zillit.desktop.core.media.AttachmentPicker =
    com.zillit.desktop.core.media.AwtAttachmentPicker()

/**
 * Fetches an attachment, saves it to Downloads, and hands it to the OS.
 *
 * Reuses mail's store (safe filenames, no overwrites). Opening is what the
 * user asked for by clicking a file — the desktop convention for anything the
 * app does not render itself.
 */
internal fun openNoticeAttachment(ready: AppGraph.Ready, scope: CoroutineScope) =
    { attachment: NoticeAttachment ->
        scope.launch {
            val name = attachment.fileName.ifBlank { "attachment" }
            val store = DownloadsAttachmentStore()
            // Streamed from a signed link straight to disk where storage gives
            // one: a 2 GB video read into memory first ran the app out of heap.
            val link = ready.noticeMedia.streamUrl(attachment)
            val saved = if (link != null) {
                store.saveStream(name) { java.net.URI(link).toURL().openStream() }
            } else {
                val fetched = ready.noticeMedia.fetch(attachment, preview = false)
                val bytes = (fetched as? ZillitResult.Success)?.data ?: return@launch
                store.save(name, bytes)
            }
            val path = (saved as? ZillitResult.Success)?.data ?: return@launch
            openSavedFile(path)
        }
        Unit
    }

/** Hands a saved file to the OS. Failure is silent: the file is in Downloads. */
internal fun openSavedFile(path: String) {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
            desktop.open(java.io.File(path))
        }
    }
}

/** The home feed, wired to the clock and the signed-in user. */
internal fun buildHomeFeed(
    ready: AppGraph.Ready,
    /** The production's tool rights — the Document Distribution gate reads them live. */
    permissions: () -> ProjectPermissions = { ProjectPermissions.Empty },
    /** Everywhere a post can go beyond this board — see ForwardWiring. */
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
) = HomeFeedViewModel(
    repository = ready.homeFeedRepository,
    nowMillis = System::currentTimeMillis,
    // Matches Android's `unique_id`: a client value the server echoes, so an
    // optimistic card can be matched to its saved copy.
    newLocalId = { UUID.randomUUID().toString() },
    // Admins post to any unit; the unit list does not pre-apply it.
    isAdmin = { ready.projectContext?.context?.value?.isAdmin == true },
    rights = ready.rightsRequests,
    // Who may edit or delete their own replies.
    currentUserId = { ready.projectContext?.context?.value?.profile?.userId },
    media = homeMediaCapture(ready),
    onBoardViewed = { unitId -> emitBoardRead(ready, unitId) },
    // The picker's recency memory, kept per project — the preference store
    // resolves the active production itself.
    loadRecentMentions = {
        ready.preferences.get(ZillitPreferences.RecentMentions)
            .split('\n')
            .filter { it.isNotBlank() }
    },
    saveRecentMentions = { names ->
        ready.preferences.set(ZillitPreferences.RecentMentions, names.joinToString("\n"))
    },
    // The Home tab to land on — the profile's `default_unit_id`, as the
    // phones honour it. Read at load; the profile arrives beside the units.
    defaultUnitId = { ready.projectContext?.context?.value?.profile?.defaultUnitId },
    distribution = distributionHook(ready, permissions),
    reach = forward,
    boardKey = HOME_BOARD,
)


/**
 * The web's `notification:read` emit, payload for payload (`Notices.jsx`):
 * `{project_id, segment: unitId, module: "home_label"}`. This is what turns
 * into "read by" on everyone else's screens and clears the unit's badge.
 *
 * A failed emit is dropped deliberately — a receipt is a courtesy, not data,
 * and the next board view resends it; queueing reads for an offline session
 * would claim the user saw posts at a time they did not.
 */
/**
 * One crew member's profile picture, as bytes — or null, which the UI answers
 * with initials, exactly as the web does when a thumbnail is missing.
 *
 * The crew list stores the picture as a bare S3 key (`UserSnapshot.avatarUrl`,
 * despite the name), signed against the production's storage the way the web's
 * `getAwsThumbnail` signs with the picture's own bucket. A full URL — some
 * accounts carry one — is fetched directly. `S3NoticeMediaSource` caches, so a
 * board full of one person's posts costs one fetch.
 */
internal suspend fun fetchAvatar(ready: AppGraph.Ready, userId: String): ByteArray? {
    val key = ready.projectContext?.context?.value?.user(userId)
        ?.avatarUrl?.takeIf { it.isNotBlank() }
        ?: return null

    if (key.startsWith("http")) {
        return runCatching {
            val response = ready.httpClient.get(key)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull()
    }

    val target = (ready.storageTarget.target() as? ZillitResult.Success)?.data
        ?.takeIf { it.isUsable }
        ?: return null
    val stored = NoticeAttachment(media = key, bucket = target.bucket, region = target.region)
    return (ready.noticeMedia.fetch(stored, preview = false) as? ZillitResult.Success)?.data
}

internal suspend fun emitBoardRead(ready: AppGraph.Ready, unitId: String) =
    emitSegmentRead(ready, segment = unitId, module = "home_label")

/**
 * Tells the server one segment is read, and the ledger the same.
 *
 * One helper for every surface that marks itself seen — boards, chat
 * threads, and whatever comes next. The ledger is the badge: the rows it
 * flips stay flipped, whatever the server later says about them (the phones'
 * rule; the server does not even echo a badge event back for one's own read).
 */
internal suspend fun emitSegmentRead(
    ready: AppGraph.Ready,
    segment: String,
    module: String,
    referenceId: String? = null,
) {
    val projectId = ready.projectContext?.context?.value?.project?.projectId ?: return
    ready.badgeStore.markRead(ledgerReadFor(segment, referenceId))
    ready.socketEvents.emit(
        ZillitSocketEvents.Badges.NotificationRead,
        NotificationReadDto(
            projectId = projectId,
            segment = segment,
            module = module,
            timestamp = System.currentTimeMillis(),
            referenceId = referenceId,
        ),
        NotificationReadDto.serializer(),
    )
}

/**
 * Reads everything filed under one film tool.
 *
 * `notification:level:read` scoped by `tool` alone — the wire's tool label
 * (`location_tool_label`), which is what the count was keyed by; the grid's
 * `location_tool` identifier is the desktop's own vocabulary and would name
 * nothing on the server.
 */
internal suspend fun emitToolRead(ready: AppGraph.Ready, toolIdentifier: String) {
    val projectId = ready.projectContext?.context?.value?.project?.projectId ?: return
    // iOS's level-read bodies carry the time as `read_time`, not `timestamp`
    // (`FSBadgeReadModel`…); both are sent so the read is scoped either way.
    val now = System.currentTimeMillis()
    ready.socketEvents.emit(
        ZillitSocketEvents.Badges.NotificationLevelRead,
        NotificationReadDto(
            projectId = projectId,
            section = "tools_label",
            tool = identifierToWireTool(toolIdentifier),
            timestamp = now,
            readTime = now,
        ),
        NotificationReadDto.serializer(),
    )
    // Android `Info`/`Production`/`BoxSchedule`…: every row of the tool.
    ready.badgeStore.markRead(LedgerRead.Tool(identifierToWireTool(toolIdentifier)))
}


/**
 * The chat composer's microphone: the board's recorder and routed uploader,
 * ending in a [ChatAttachment] ready to ride a message envelope. One capture
 * pipeline for every place the crew speaks.
 */
internal fun chatVoice(
    ready: AppGraph.Ready,
    capture: MediaCapture = homeMediaCapture(ready),
): com.zillit.desktop.feature.chat.domain.ChatVoice =
    object : com.zillit.desktop.feature.chat.domain.ChatVoice {
        private val capture = capture

        override suspend fun start() =
            capture.recorder?.start() ?: com.zillit.desktop.core.common.ZillitResult.Failure(
                com.zillit.desktop.core.common.ZillitError.Storage(
                    technical = "no recorder wired",
                    userMessage = str(S.desktop_recording_unavailable),
                ),
            )

        override suspend fun stop():
            com.zillit.desktop.core.common.ZillitResult<
                com.zillit.desktop.feature.chat.domain.ChatAttachment,
                > {
            val recorder = capture.recorder
                ?: return com.zillit.desktop.core.common.ZillitResult.Failure(
                    com.zillit.desktop.core.common.ZillitError.Storage("no recorder wired"),
                )
            return recorder.stop().flatMap { captured ->
                val picked = captured.toPickedMedia(capture.newRecordingName())
                // No progress sink: the bubble only exists after this returns.
                val uploaded = capture.upload?.invoke(picked) { }
                    ?: com.zillit.desktop.core.common.ZillitResult.Failure(
                        com.zillit.desktop.core.common.ZillitError.Storage("no uploader wired"),
                    )
                uploaded.map { stored ->
                    com.zillit.desktop.feature.chat.domain.ChatAttachment(
                        media = stored.media,
                        name = stored.fileName,
                        contentType = stored.contentType,
                        bucket = stored.bucket.orEmpty(),
                        region = stored.region.orEmpty(),
                        thumbnail = stored.thumbnail.orEmpty(),
                        durationMillis = captured.durationMillis,
                    )
                }
            }
        }

        override fun cancel() {
            capture.recorder?.cancel()
        }
    }


/** A voice note's full bytes, for the shared player to decode. */
internal suspend fun fetchChatAudio(
    ready: AppGraph.Ready,
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
): ByteArray? =
    (
        ready.noticeMedia.fetch(
            com.zillit.desktop.feature.home.domain.NoticeAttachment(
                media = file.media,
                fileName = file.name,
                bucket = file.bucket,
                region = file.region,
            ),
            preview = false,
        ) as? ZillitResult.Success
        )?.data

/**
 * A film tool that is a notice board — Info and Confidential Info, and the
 * production report tool's unit chat.
 *
 * The web mounts the Home unit-chat component under a different REST
 * segment (`info`, `confidentialinfo`) with ONE unit, resolved from the
 * production's tool list by identifier — never from `home/unit`. The
 * repository is the Home one on that segment; the unit provider is the tool
 * entry itself, folded into a [HomeUnit] so the board's tab strip and
 * composer gate work unchanged. The report chat is the same board on the
 * report service, with no `chat/` segment ([service], [chatSegment]).
 */
internal fun AppGraph.Ready.boardFeed(
    board: String,
    toolIdentifier: String,
    permissions: () -> ProjectPermissions,
    service: ZillitService = ZillitService.Units,
    chatSegment: String = "chat",
    /** Everywhere a post can go beyond this board — see ForwardWiring. */
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel {
    val units: suspend () -> ZillitResult<List<HomeUnit>> = {
        val access = permissions().access(toolIdentifier)
        val unitId = access.unitId.orEmpty()
        if (unitId.isBlank()) {
            // The tools call has not answered yet, or the tool is off for
            // this production. Empty is "nothing to show", not an error.
            ZillitResult.Success(emptyList())
        } else {
            ZillitResult.Success(
                listOf(
                    HomeUnit(
                        id = unitId,
                        identifier = access.identifier,
                        unitName = access.unitName.orEmpty(),
                        canView = access.canView || permissions().isAdmin,
                        canPost = access.canPost || permissions().isAdmin,
                        enabled = access.enabled,
                    ),
                ),
            )
        }
    }
    return boardFeed(board, toolIdentifier, units, service, chatSegment = chatSegment, forward = forward)
}

/**
 * A notice-board tool whose tabs come from ITS OWN service — Camera & Sound
 * Report (script-notes host, `reports/unit/`), Catering (unit host,
 * `catering/unit`), Accounts (unit host, `account/unit`).
 *
 * The web mounts the same unit-chat for each with a different REST segment;
 * one right (the tool's) covers every tab. [postAlways] is Accounts'
 * ZL-17603: posting is not gated by the tool right there.
 */
@Suppress("LongParameterList") // One seam per way a board differs; a holder would rename, not reduce.
internal fun AppGraph.Ready.serviceUnitsFeed(
    toolIdentifier: String,
    board: String,
    service: ZillitService,
    unitsRoute: String,
    permissions: () -> ProjectPermissions,
    postAlways: Boolean = false,
    readModule: String = "${toolIdentifier.removeSuffix("_tool")}_label",
    /** Everywhere a post can go beyond this board — see ForwardWiring. */
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel {
    val source = ReportUnitsSource(apiClient, config, service, unitsRoute)
    val units: suspend () -> ZillitResult<List<HomeUnit>> = {
        val access = permissions().access(toolIdentifier)
        val admin = permissions().isAdmin
        source.units().map { rows ->
            rows.map { row ->
                HomeUnit(
                    id = row.id,
                    identifier = row.identifier ?: toolIdentifier,
                    unitName = row.name,
                    canView = access.canView || admin,
                    canPost = postAlways || access.canPost || admin,
                    enabled = access.enabled,
                )
            }
        }
    }
    return boardFeed(board, toolIdentifier, units, service, readModule, forward = forward)
}

/** Camera & Sound Report — `GET reports/unit/` on the script-notes host. */
internal fun AppGraph.Ready.reportsFeed(
    permissions: () -> ProjectPermissions,
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel = serviceUnitsFeed(
    toolIdentifier = REPORTS_TOOL,
    board = "reports",
    service = ZillitService.ScriptNotes,
    unitsRoute = "reports/unit/",
    permissions = permissions,
    forward = forward,
)

/**
 * Script Notes — the same board engine on the script-notes host, segment
 * `script-notes`, three system units (Script Takes, Daily Progress Report,
 * Continuity Notes) from `GET script-notes/unit`. The web's V2 page is this
 * engine verbatim; its only extras — an episode stamp on document posts for
 * television productions and a deleted-messages history view — are not here.
 */
internal fun AppGraph.Ready.scriptNotesFeed(
    permissions: () -> ProjectPermissions,
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel = serviceUnitsFeed(
    toolIdentifier = "script_notes_tool",
    board = "script-notes",
    service = ZillitService.ScriptNotes,
    unitsRoute = "script-notes/unit",
    permissions = permissions,
    forward = forward,
)

/** Catering — `GET catering/unit` on the unit host; breakfast/lunch/dinner plus the production's own. */
internal fun AppGraph.Ready.cateringFeed(
    permissions: () -> ProjectPermissions,
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel = serviceUnitsFeed(
    toolIdentifier = "catering_tool",
    board = "catering",
    service = ZillitService.Units,
    unitsRoute = "catering/unit",
    permissions = permissions,
    forward = forward,
)

/**
 * Message Accounts — `GET account/unit` on the unit host (General, Purchase
 * Orders, Petty Cash, and the production's own). The web hard-codes posting
 * on for this tool (ZL-17603).
 */
internal fun AppGraph.Ready.accountsFeed(
    permissions: () -> ProjectPermissions,
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel =
    serviceUnitsFeed(
        toolIdentifier = "accounting_tool",
        board = "account",
        service = ZillitService.Units,
        unitsRoute = "account/unit",
        permissions = permissions,
        postAlways = true,
        // The badge module is `accounts_label`, not the identifier's stem.
        readModule = "accounts_label",
        forward = forward,
    )

private const val REPORTS_TOOL = "reports_tool"

/** The board engine on any segment of any host, with the tabs handed in. */
@Suppress("LongParameterList") // One seam per way a board differs; a holder would rename, not reduce.
internal fun AppGraph.Ready.boardFeed(
    board: String,
    toolIdentifier: String,
    units: suspend () -> ZillitResult<List<HomeUnit>>,
    service: ZillitService = ZillitService.Units,
    /** The `notification:read` module; defaults to `<tool>_label`, which Accounts breaks. */
    readModule: String = "${toolIdentifier.removeSuffix("_tool")}_label",
    /** Modules the board's older notifications were filed under, read alongside [readModule]. */
    legacyReadModules: List<String> = emptyList(),
    chatSegment: String = "chat",
    /** The member an admin is answering, on rooms that route posts to one person. */
    receiver: () -> String? = { null },
    /** Wraps the repository — a room's own posting rule sits here. */
    decorate: (HomeFeedRepository) -> HomeFeedRepository = { it },
    /** Everywhere a post can go beyond this board — see ForwardWiring. */
    forward: com.zillit.desktop.core.workspace.forward.ForwardReach? = null,
): HomeFeedViewModel {
    val repository = decorate(
        HomeFeedRepositoryImpl(
            apiClient = apiClient,
            config = config,
            decrypt = noticeDecryptor,
            isAdmin = { projectContext?.context?.value?.isAdmin == true },
            nowMillis = System::currentTimeMillis,
            board = board,
            units = units,
            service = service,
            chatSegment = chatSegment,
            receiver = receiver,
        ),
    )
    return HomeFeedViewModel(
        repository = repository,
        nowMillis = System::currentTimeMillis,
        newLocalId = { UUID.randomUUID().toString() },
        isAdmin = { projectContext?.context?.value?.isAdmin == true },
        rights = rightsRequests,
        currentUserId = { projectContext?.context?.value?.profile?.userId },
        media = homeMediaCapture(this),
        // The board's read receipt names its own module label — the web
        // sends `info_label` / `confidential_info_label` here, not `home_label`.
        onBoardViewed = { unitId ->
            (listOf(readModule) + legacyReadModules).forEach { module ->
                emitSegmentRead(this, segment = unitId, module = module)
            }
        },
        loadRecentMentions = {
            preferences.get(ZillitPreferences.RecentMentions).split('\n').filter { it.isNotBlank() }
        },
        saveRecentMentions = { names ->
            preferences.set(ZillitPreferences.RecentMentions, names.joinToString("\n"))
        },
        reach = forward,
        boardKey = board,
    )
}

/**
 * The socket's word on the ledger, applied as the phones apply it.
 *
 * `notification:save` files its records; `notification:silent` drops what
 * another device or the server already dropped — reads elsewhere, deleted
 * messages and comments, rooms lost, tools and units this person may no
 * longer see (each lost tool is also re-sent as a read, as iOS's
 * `emitForBadgeDelete` does, so the server's ledger forgets too); the two
 * `badges:cleared:*` frames empty a production or the device. No refetch
 * follows any of them: the rows are the badges.
 */
internal suspend fun badgeSocketEffects(ready: AppGraph.Ready) = kotlinx.coroutines.coroutineScope {
    val open = { ready.projectContext?.context?.value?.project?.projectId }
    launch {
        ready.socketEvents.on(ZillitSocketEvents.Badges.Save).collect { message ->
            ready.badgeStore.arrived(ledgerArrivalsFrom(message.payload, fallbackProjectId = open()))
        }
    }
    launch {
        ready.socketEvents.on(ZillitSocketEvents.Badges.Silent).collect { message ->
            val silence = badgeSilenceFrom(message.payload) ?: return@collect
            ready.badgeStore.silence(silence)
            val projectId = open() ?: return@collect
            silence.lostTools.forEach { label ->
                ready.socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(projectId = projectId, segment = label, timestamp = System.currentTimeMillis()),
                    NotificationReadDto.serializer(),
                )
            }
        }
    }
    launch {
        ready.socketEvents.on(ZillitSocketEvents.Badges.ClearedUser).collect { message ->
            val named = message.payload.wireRecords().firstOrNull()
                ?.let { (it["project_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            (named ?: open())?.let { ready.badgeStore.clearProject(it) }
        }
    }
    launch {
        ready.socketEvents.on(ZillitSocketEvents.Badges.ClearedDevice).collect { ready.badgeStore.clearEverything() }
    }
}
