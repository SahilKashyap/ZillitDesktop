package com.zillit.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.pagedistribution.data.DistributionRepositoryImpl
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTransfer
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.PdfPageImage
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.ui.DistributionToolProvider
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The three PDF distribution tools' host seams: uploads on the app's S3
 * machinery under the web's own key prefix, fetches through the signed
 * storage path, PDFBox for the viewer, Downloads for the download.
 */
internal fun AppGraph.Ready.distributionTransfer(): DistributionTransfer = object : DistributionTransfer {

    private val work = PdfBoxWork()

    override suspend fun upload(storagePath: String, fileName: String, bytes: ByteArray): ZillitResult<StoredPdf> {
        val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
        val uploader = S3AttachmentUploader(
            httpClient = httpClient,
            credentials = {
                val remote = remoteConfigRepository.current()
                val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
                val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
                if (access != null && secret != null) AwsCredentials(access, secret) else null
            },
            storage = storageTarget,
            // `{projectId}{pathname}/actual/{name}` — the web bakes its route
            // path into the key; the same string here keeps the buckets tidy.
            newKey = { name ->
                val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9. ]"), "").replace(" ", "")
                val ext = name.substringAfterLast('.', "pdf")
                "$projectId$storagePath/actual/${stem}_${System.currentTimeMillis()}.$ext"
            },
        )
        return when (val stored = uploader.upload(fileName, "application/pdf", bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                StoredPdf(
                    media = stored.data.media,
                    name = fileName.replace(Regex("\\s"), ""),
                    bucket = stored.data.bucket.orEmpty(),
                    region = stored.data.region.orEmpty(),
                    fileSize = bytes.size.toString(),
                ),
            )
        }
    }

    override suspend fun fetch(stored: StoredPdf): ZillitResult<ByteArray> =
        noticeMedia.fetch(
            NoticeAttachment(
                media = stored.media,
                fileName = stored.name,
                bucket = stored.bucket,
                region = stored.region,
            ),
            preview = false,
        )

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<PdfPageImage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { PdfPageImage(it.page, it.imageBytes, it.widthPx, it.heightPx) },
            )
        }

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

/** One distribution tool's view model. */
internal fun AppGraph.Ready.buildDistribution(
    tool: DistributionTool,
    permissions: () -> ProjectPermissions,
): DistributionViewModel = DistributionViewModel(
    tool = tool,
    repository = DistributionRepositoryImpl(
        apiClient = apiClient,
        config = config,
        newUniqueId = { UUID.randomUUID().toString() },
        nowMillis = System::currentTimeMillis,
        bus = socketEvents,
    ),
    transfer = distributionTransfer(),
    resolveViewer = {
        val context = projectContext?.context?.value
        DistributionViewer.from(
            permissions = permissions(),
            toolIdentifier = tool.toolIdentifier,
            userId = context?.profile?.userId.orEmpty(),
            // Episode fields show on television productions only — the web's
            // `project_sub_type.includes('television_label')`.
            isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
        )
    },
    nowMillis = System::currentTimeMillis,
    // The web's `notification:read` for a list or a folder.
    onListViewed = { module, segment -> emitSegmentRead(this, segment = segment, module = module) },
    folderUnread = distributionFolderUnread(tool),
    tabUnread = distributionTabUnread(tool),
    rights = rightsRequests,
    onOversizeUpload = { fileName, sizeBytes -> tellAdminsOversize(tool, fileName, sizeBytes) },
)

/**
 * Unread per folder for the tool's folder tab — the service files those
 * rows under the tab's label with `unit=<folder key>`: `dod_label` +
 * folder name for D.O.D, `schedule_distribution_pages_tool_label` +
 * scene number for schedule pages (the web's
 * `toolsUnitBadges[label].data[].unit`). Grouped by `unit` off the ledger
 * so a read or an arrival redraws the card at once.
 */
private fun AppGraph.Ready.distributionFolderUnread(tool: DistributionTool): Flow<Map<String, Int>> {
    val label = tool.tabs.firstOrNull { it.kind is TabKind.Folders }?.badgeModule ?: return emptyFlow()
    return badgeStore.counts
        .map { badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = label)) }
        .distinctUntilChanged()
}

/**
 * Unread per tab — the web's chips on the tab strip, one `toolsUnitBadges`
 * entry per tab label. Grouped by tool off the ledger, then picked by each
 * tab's `badgeModule`.
 */
private fun AppGraph.Ready.distributionTabUnread(tool: DistributionTool): Flow<Map<String, Int>> {
    if (tool.tabs.size < 2) return emptyFlow()
    return badgeStore.counts
        .map { _ ->
            val byTool = badgeStore.split(BadgeDrilldownQuery(groupBy = "tool"))
            tool.tabs.associate { tab -> tab.key to (byTool[tab.badgeModule] ?: 0) }
        }
        .distinctUntilChanged()
}

/**
 * The web's `distributeCncMessage`: a PDF past 25 MB will not be mailed to
 * the distribution list, so every accepted admin (not oneself) is told in a
 * private chat message, in the web's words. Failures are logged — the
 * upload itself succeeded, and the uploader has nothing to redo.
 */
private suspend fun AppGraph.Ready.tellAdminsOversize(tool: DistributionTool, fileName: String, sizeBytes: Long) {
    val context = projectContext?.context?.value ?: return
    val me = context.profile?.userId
    val admins = context.users.filter { it.isAdmin && it.userId != me && it.hasJoined() }
    val size = "%.2f MB".format(sizeBytes / (1024.0 * 1024.0))
    val body = "The file '$fileName' ($size) uploaded to '${tool.title}' exceeds the 25 MB limit for " +
        "auto-distribution. Crew members in the Distribution List can access the document directly within " +
        "the module. Please note that external users in the Distribution List will not receive this file via email."
    admins.forEach { admin ->
        val sent = chatRepository.send(
            receiverId = admin.userId,
            body = body,
            uniqueId = UUID.randomUUID().toString(),
            nowMillis = System.currentTimeMillis(),
        )
        if (sent is ZillitResult.Failure) {
            ZillitLog.w("DistributionWiring") { "oversize notice to ${admin.userId} not sent: ${sent.error.userMessage}" }
        }
    }
}

/** The provider for one tool, with the OS picker and the crew name lookup. */
internal fun AppGraph.Ready.distributionProvider(
    viewModel: DistributionViewModel,
    path: String,
    title: String,
    icon: ImageVector,
    scope: CoroutineScope,
) = DistributionToolProvider(
    viewModel = viewModel,
    path = path,
    title = title,
    icon = icon,
    onPickPdf = { onPicked -> scope.launch { onPicked(pickPdf()) } },
    resolveUser = { userId ->
        projectContext?.context?.value?.user(userId)?.authorLine()
    },
    loadAvatar = crewFaceLoader(this),
)
