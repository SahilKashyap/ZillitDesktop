package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.continuity.data.ContinuityRepositoryImpl
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityBadges
import com.zillit.desktop.feature.continuity.domain.ContinuityCrewMember
import com.zillit.desktop.feature.continuity.domain.ContinuityForwarder
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityUnread
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.ui.ContinuityToolProvider
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import com.zillit.desktop.feature.continuity.ui.PickKind
import com.zillit.desktop.feature.continuity.ui.decodeImageBitmap
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.data.videoThumbnailJpeg
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.UUID

/**
 * Continuity's host seams: files up through the app's S3 machinery under the
 * web's own key prefix (`/film-tools/continuity`), a poster frame beside a
 * video the way the web's `captureThumbnail` does, down through the
 * signed-storage path, thumbnails decoded for the cards.
 */
internal fun AppGraph.Ready.continuityTransfer(): ContinuityTransfer = object : ContinuityTransfer {

    private val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }

    private fun uploader(folder: String) = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { name ->
            val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
            val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9. ]"), "").replace(" ", "")
            val ext = name.substringAfterLast('.', "bin")
            "$projectId/film-tools/continuity/$folder/${stem}_${System.currentTimeMillis()}.$ext"
        },
    )

    override suspend fun upload(file: PickedContinuityFile): ZillitResult<ContinuityAttachment> {
        val stored = when (val up = uploader("actual").upload(file.name, file.contentType, file.bytes)) {
            is ZillitResult.Failure -> return up
            is ZillitResult.Success -> up.data
        }
        // A video's poster frame goes up beside it, under `/thumb/` as the web
        // files it (`uploadedFilesOnAWS.js:227-233`); a clip jcodec cannot
        // read posts without one, as before.
        val poster = if (file.isVideo) withContext(Dispatchers.Default) { videoThumbnailJpeg(file.bytes) } else null
        val thumbnail = poster?.let { frame ->
            val name = file.name.substringBeforeLast('.') + "_videoThumbnail.jpeg"
            (uploader("thumb").upload(name, "image/jpeg", frame.jpegBytes) as? ZillitResult.Success)?.data?.media
        }
        return ZillitResult.Success(
            ContinuityAttachment(
                media = stored.media,
                // Images are their own thumbnail; the web renders documents from a placeholder.
                thumbnail = when {
                    file.isImage -> stored.media
                    file.isVideo -> thumbnail.orEmpty()
                    else -> ""
                },
                contentType = when {
                    file.isImage -> "image"
                    file.isVideo -> "video"
                    else -> "document"
                },
                contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                name = file.name.replace(Regex("\\s"), ""),
                bucket = stored.bucket,
                region = stored.region,
                fileSize = file.bytes.size.toString(),
                height = poster?.heightPx ?: 0,
                width = poster?.widthPx ?: 0,
            ),
        )
    }

    override suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean): ZillitResult<ByteArray> =
        noticeMedia.fetch(attachment.toNotice(), preview = preview)

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }

    /** A temp copy for the system player or viewer — nothing lands in Downloads for a look. */
    override suspend fun open(fileName: String, bytes: ByteArray): ZillitResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "zillit-continuity").apply { mkdirs() }
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
            val target = File(dir, "${System.currentTimeMillis()}_$safe")
            target.writeBytes(bytes)
            target.deleteOnExit()
            openSavedFile(target.absolutePath)
        }.fold(
            onSuccess = { ZillitResult.Success(Unit) },
            onFailure = {
                ZillitResult.Failure(
                    com.zillit.desktop.core.common.ZillitError.Storage(
                        technical = it.message,
                        userMessage = str(S.desktop_could_not_open_file),
                    ),
                )
            },
        )
    }
}

private fun ContinuityAttachment.toNotice() = NoticeAttachment(
    media = media,
    fileName = name,
    // A video's poster is its thumbnail key; a card's preview is what the notice cache answers with.
    thumbnail = thumbnail.ifBlank { media },
    bucket = bucket,
    region = region,
)

/**
 * The unread the boards wear, off the ledger — the web's `continuity_label`
 * subtree (`TabsComponents.jsx:753-797`): rows filed under
 * `unit=continuity_intra_label|continuity_all_label`, `level_1=<scene>`,
 * `level_2=<department>`. A read names the folder as the web does
 * (`IntraDepartment.jsx:494-508`: `segment`, `level_1`, `module`) and flips
 * the same rows locally, so the chip does not wait for the echo.
 */
private fun AppGraph.Ready.continuityBadges(): ContinuityBadges = object : ContinuityBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val unread: Flow<ContinuityUnread> = badgeStore.counts
        .map {
            val tabs = badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = CONTINUITY_TOOL))
            val folders = ContinuityTab.entries.associate { tab ->
                tab.readSegment to
                    badgeStore.split(
                        BadgeDrilldownQuery(groupBy = "level_1", tool = CONTINUITY_TOOL, unit = tab.readSegment),
                    )
            }
            val allFolders = folders[ContinuityTab.AllDepartments.readSegment].orEmpty().keys
            val departments = allFolders.associateWith { folder ->
                badgeStore.split(
                    BadgeDrilldownQuery(
                        groupBy = "level_2",
                        tool = CONTINUITY_TOOL,
                        unit = ContinuityTab.AllDepartments.readSegment,
                        level1 = folder,
                    ),
                )
            }
            ContinuityUnread(tabs = tabs, folders = folders, departments = departments)
        }
        .distinctUntilChanged()

    override fun markRead(tab: ContinuityTab, sceneFolder: String, departmentId: String?) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(
                        projectId = projectId,
                        segment = tab.readSegment,
                        module = CONTINUITY_TOOL,
                        level1 = sceneFolder,
                        level2 = departmentId,
                        timestamp = if (departmentId == null) System.currentTimeMillis() else 0L,
                    ),
                    NotificationReadDto.serializer(),
                )
            }.onFailure { ZillitLog.w(CONTINUITY_TAG) { "folder read not sent: ${it::class.simpleName}" } }
            badgeStore.markRead(
                LedgerRead.Levels(
                    tool = CONTINUITY_TOOL,
                    unit = tab.readSegment,
                    level1 = sceneFolder,
                    level2 = departmentId,
                ),
            )
        }
    }
}

/**
 * "Forward → Select Users" — one private chat message per card per person,
 * as the web's `sendPrivateChat` sends it (`ContinuityDrawer.jsx:85-133`):
 * the card's file as the attachment at the web's fixed 300×225, the scene
 * details as the body.
 */
private fun AppGraph.Ready.continuityForwarder() = ContinuityForwarder { scene, toUserId, caption ->
    val a = scene.attachment
        ?: return@ContinuityForwarder ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Validation("this card has no file to forward"),
        )
    chatRepository.send(
        receiverId = toUserId,
        body = caption,
        uniqueId = UUID.randomUUID().toString(),
        nowMillis = System.currentTimeMillis(),
        attachment = ChatAttachment(
            media = a.media,
            name = a.name.ifBlank { a.media.substringAfterLast('/') },
            contentType = a.contentType,
            bucket = a.bucket,
            region = a.region,
            thumbnail = a.thumbnail,
            widthPx = FORWARD_WIDTH_PX,
            heightPx = FORWARD_HEIGHT_PX,
            durationMillis = a.duration.toLong(),
        ),
    )
}

internal fun AppGraph.Ready.buildContinuity(permissions: () -> ProjectPermissions) = ContinuityViewModel(
    repository = ContinuityRepositoryImpl(
        apiClient,
        config,
        localise = { it.localised() },
        bus = socketEvents,
    ),
    transfer = continuityTransfer(),
    resolveViewer = {
        val context = projectContext?.context?.value
        ContinuityViewer.from(
            permissions = permissions(),
            userId = context?.profile?.userId.orEmpty(),
            departmentId = context?.profile?.departmentId.orEmpty(),
            isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
        )
    },
    // The server sends the department's label key; the dictionary turns it into its display name.
    departmentName = { id ->
        val profile = projectContext?.context?.value?.profile
        profile?.departmentName?.takeIf { profile.departmentId == id }?.localised()
    },
    newUniqueId = { UUID.randomUUID().toString() },
    nowMillis = System::currentTimeMillis,
    // The web's `ShowUsers`: crew who have accepted, never oneself (the ViewModel drops me).
    crew = {
        projectContext?.context?.value?.users.orEmpty()
            .filter { it.hasJoined() }
            .map { ContinuityCrewMember(it.userId, it.fullName, it.designationText().orEmpty()) }
            .sortedBy { it.name.lowercase() }
    },
    badges = continuityBadges(),
    forwarder = continuityForwarder(),
    rights = rightsRequests,
)

internal fun AppGraph.Ready.continuityProvider(viewModel: ContinuityViewModel, scope: CoroutineScope) =
    ContinuityToolProvider(
        viewModel = viewModel,
        onPickFiles = { kind, onPicked ->
            scope.launch {
                val preview = when (kind) {
                    PickKind.Photos -> PreviewKind.Image
                    PickKind.Videos -> PreviewKind.Video
                    PickKind.Documents -> PreviewKind.Document
                }
                onPicked(
                    attachmentPicker.pick(preview, maxBytes = CONTINUITY_MAX_BYTES)
                        .map { PickedContinuityFile(it.name, it.contentType, it.bytes) },
                )
            }
        },
        // Thumbnails ride the notice-media cache; the viewer asks for the full file.
        loadImage = { attachment, preview ->
            (noticeMedia.fetch(attachment.toNotice(), preview = preview) as? ZillitResult.Success)
                ?.data?.let(::decodeImageBitmap)
        },
        loadPdfPages = { attachment ->
            (noticeMedia.fetch(attachment.toNotice(), preview = false) as? ZillitResult.Success)?.data?.let { bytes ->
                withContext(Dispatchers.IO) {
                    (PdfBoxWork().renderPages(bytes, PDF_PAGE_WIDTH_PX) as? ZillitResult.Success)
                        ?.data?.mapNotNull { decodeImageBitmap(it.imageBytes) }
                }
            }
        },
        resolveUser = { userId ->
            projectContext?.context?.value?.user(userId)?.authorLine()
        },
        formatDate = ::continuityDate,
    )

/** "Aug 20, 2026" from epoch ms; "—" for none. */
internal fun continuityDate(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(MONTH_ABBREV)
    return "$month ${d.dayOfMonth.toString().padStart(2, '0')}, ${d.year}"
}

private const val CONTINUITY_TAG = "ContinuityWiring"
private const val CONTINUITY_TOOL = "continuity_label"
private const val MONTH_ABBREV = 3
private const val FORWARD_WIDTH_PX = 225L
private const val FORWARD_HEIGHT_PX = 300L
private const val PDF_PAGE_WIDTH_PX = 1000
/** Videos are the point of the tool; the picker's mail-sized default would refuse most of them. */
private const val CONTINUITY_MAX_BYTES: Long = 500L * 1024 * 1024
