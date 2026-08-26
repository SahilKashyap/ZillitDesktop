@file:Suppress("TooManyFunctions") // One feed factory per Home-engine board.

package com.zillit.desktop

import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.domain.StorageKind
import com.zillit.desktop.feature.email.domain.storageKindOf
import com.zillit.desktop.feature.home.data.ClipAudioPlayer
import com.zillit.desktop.feature.home.data.JvmAudioRecorder
import com.zillit.desktop.feature.home.data.identifierToWireTool
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
import com.zillit.desktop.feature.home.domain.HomeUnit
import java.util.UUID
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.chat.domain.MEMBER_DESIGNATION
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

/** What the composer can capture: picker, uploader, microphone, poster frames. */
internal fun homeMediaCapture(ready: AppGraph.Ready) = MediaCapture(
    // The same OS dialog mail attachments use. Several files become several
    // posts — the wire takes one attachment per message, as the phones send.
    pick = { FilePicker().pick().map { it.toNoticeMedia() } },
    // The same routed uploader (S3 or Box by production) mail uses. A video's
    // poster frame travels first as its own object; a poster that fails to
    // upload costs the poster, never the video.
    upload = { picked, onProgress ->
        // The poster read path signs S3 GETs; a poster keyed into Box could
        // never be fetched. The web skips it there for the same reason.
        val storageIsAws = storageKindOf(
            ready.projectContext?.context?.value?.project?.storageType,
        ) == StorageKind.Aws
        val thumbnailKey = picked.thumbnailBytes
            ?.takeIf { storageIsAws }
            ?.let { poster ->
                ready.attachmentUploader
                    .upload(picked.name + "_thumb.jpg", "image/jpeg", poster)
                    .getOrNull()?.media
            }
        // Only the main file reports progress: the poster above is a few
        // kilobytes, and a bar that restarts for it would read as a glitch.
        ready.attachmentUploader
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
    // Frame extraction off the UI thread; jcodec is CPU work, not IO, but a
    // dedicated dispatcher for one poster per pick is not worth owning.
    videoThumbnail = { picked ->
        withContext(Dispatchers.Default) {
            val poster = when {
                picked.isPdf -> pdfThumbnailJpeg(picked.bytes)
                else -> videoThumbnailJpeg(picked.bytes)
            }
            poster?.let {
                picked.copy(
                    thumbnailBytes = it.jpegBytes,
                    thumbnailWidth = it.widthPx,
                    thumbnailHeight = it.heightPx,
                )
            } ?: picked
        }
    },
)

/** Email's picker type and home's, bridged where both are in scope. */
internal fun com.zillit.desktop.feature.email.domain.PickedFile.toNoticeMedia() =
    PickedMedia(name = name, contentType = contentType, bytes = bytes)

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
            val fetched = ready.noticeMedia.fetch(attachment, preview = false)
            val bytes = (fetched as? ZillitResult.Success)?.data ?: return@launch
            val name = attachment.fileName.ifBlank { "attachment" }
            val saved = DownloadsAttachmentStore().save(name, bytes)
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
) = HomeFeedViewModel(
    repository = ready.homeFeedRepository,
    nowMillis = System::currentTimeMillis,
    // Matches Android's `unique_id`: a client value the server echoes, so an
    // optimistic card can be matched to its saved copy.
    newLocalId = { UUID.randomUUID().toString() },
    // Admins post to any unit; the unit list does not pre-apply it.
    isAdmin = { ready.projectContext?.context?.value?.isAdmin == true },
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
 * Tells the server one segment is read, then refetches the counts.
 *
 * One helper for every surface that marks itself seen — boards, chat
 * threads, and whatever comes next — because the server does not echo a
 * badge event back to the reader for their own read (verified live: an
 * opened board's count stood for good). The beat gives the server time to
 * apply the read before being asked.
 */
internal suspend fun emitSegmentRead(
    ready: AppGraph.Ready,
    segment: String,
    module: String,
    referenceId: String? = null,
) {
    val projectId = ready.projectContext?.context?.value?.project?.projectId ?: return
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
    kotlinx.coroutines.delay(READ_SETTLE_MILLIS)
    ready.badgeStore.refresh()
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
    kotlinx.coroutines.delay(READ_SETTLE_MILLIS)
    ready.badgeStore.refresh()
}


/** How long the server gets to apply a read before we ask for counts. */
private const val READ_SETTLE_MILLIS = 1_500L


/**
 * The chat composer's microphone: the board's recorder and routed uploader,
 * ending in a [ChatAttachment] ready to ride a message envelope. One capture
 * pipeline for every place the crew speaks.
 */
internal fun chatVoice(ready: AppGraph.Ready): com.zillit.desktop.feature.chat.domain.ChatVoice =
    object : com.zillit.desktop.feature.chat.domain.ChatVoice {
        private val capture = homeMediaCapture(ready)

        override suspend fun start() =
            capture.recorder?.start() ?: com.zillit.desktop.core.common.ZillitResult.Failure(
                com.zillit.desktop.core.common.ZillitError.Storage(
                    technical = "no recorder wired",
                    userMessage = "Recording is not available on this machine.",
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
 * A film tool that is a notice board — Info and Confidential Info.
 *
 * The web mounts the Home unit-chat component under a different REST
 * segment (`info`, `confidentialinfo`) with ONE unit, resolved from the
 * production's tool list by identifier — never from `home/unit`. The
 * repository is the Home one on that segment; the unit provider is the tool
 * entry itself, folded into a [HomeUnit] so the board's tab strip and
 * composer gate work unchanged.
 */
internal fun AppGraph.Ready.boardFeed(
    board: String,
    toolIdentifier: String,
    permissions: () -> ProjectPermissions,
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
    return boardFeed(board, toolIdentifier, units)
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
internal fun AppGraph.Ready.serviceUnitsFeed(
    toolIdentifier: String,
    board: String,
    service: ZillitService,
    unitsRoute: String,
    permissions: () -> ProjectPermissions,
    postAlways: Boolean = false,
    readModule: String = "${toolIdentifier.removeSuffix("_tool")}_label",
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
    return boardFeed(board, toolIdentifier, units, service, readModule)
}

/** Camera & Sound Report — `GET reports/unit/` on the script-notes host. */
internal fun AppGraph.Ready.reportsFeed(permissions: () -> ProjectPermissions): HomeFeedViewModel =
    serviceUnitsFeed(REPORTS_TOOL, "reports", ZillitService.ScriptNotes, "reports/unit/", permissions)

/**
 * Script Notes — the same board engine on the script-notes host, segment
 * `script-notes`, three system units (Script Takes, Daily Progress Report,
 * Continuity Notes) from `GET script-notes/unit`. The web's V2 page is this
 * engine verbatim; its only extras — an episode stamp on document posts for
 * television productions and a deleted-messages history view — are not here.
 */
internal fun AppGraph.Ready.scriptNotesFeed(permissions: () -> ProjectPermissions): HomeFeedViewModel =
    serviceUnitsFeed("script_notes_tool", "script-notes", ZillitService.ScriptNotes, "script-notes/unit", permissions)

/** Catering — `GET catering/unit` on the unit host; breakfast/lunch/dinner plus the production's own. */
internal fun AppGraph.Ready.cateringFeed(permissions: () -> ProjectPermissions): HomeFeedViewModel =
    serviceUnitsFeed("catering_tool", "catering", ZillitService.Units, "catering/unit", permissions)

/**
 * Message Accounts — `GET account/unit` on the unit host (General, Purchase
 * Orders, Petty Cash, and the production's own). The web hard-codes posting
 * on for this tool (ZL-17603).
 */
internal fun AppGraph.Ready.accountsFeed(permissions: () -> ProjectPermissions): HomeFeedViewModel =
    serviceUnitsFeed(
        toolIdentifier = "accounting_tool",
        board = "account",
        service = ZillitService.Units,
        unitsRoute = "account/unit",
        permissions = permissions,
        postAlways = true,
        // The badge module is `accounts_label`, not the identifier's stem.
        readModule = "accounts_label",
    )

private const val REPORTS_TOOL = "reports_tool"

/** The board engine on any segment of any host, with the tabs handed in. */
private fun AppGraph.Ready.boardFeed(
    board: String,
    toolIdentifier: String,
    units: suspend () -> ZillitResult<List<HomeUnit>>,
    service: ZillitService = ZillitService.Units,
    /** The `notification:read` module; defaults to `<tool>_label`, which Accounts breaks. */
    readModule: String = "${toolIdentifier.removeSuffix("_tool")}_label",
): HomeFeedViewModel {
    val repository = HomeFeedRepositoryImpl(
        apiClient = apiClient,
        config = config,
        decrypt = noticeDecryptor,
        isAdmin = { projectContext?.context?.value?.isAdmin == true },
        nowMillis = System::currentTimeMillis,
        board = board,
        units = units,
        service = service,
    )
    return HomeFeedViewModel(
        repository = repository,
        nowMillis = System::currentTimeMillis,
        newLocalId = { UUID.randomUUID().toString() },
        isAdmin = { projectContext?.context?.value?.isAdmin == true },
        currentUserId = { projectContext?.context?.value?.profile?.userId },
        media = homeMediaCapture(this),
        // The board's read receipt names its own module label — the web
        // sends `info_label` / `confidential_info_label` here, not `home_label`.
        onBoardViewed = { unitId -> emitSegmentRead(this, segment = unitId, module = readModule) },
        loadRecentMentions = {
            preferences.get(ZillitPreferences.RecentMentions).split('\n').filter { it.isNotBlank() }
        },
        saveRecentMentions = { names ->
            preferences.set(ZillitPreferences.RecentMentions, names.joinToString("\n"))
        },
    )
}
