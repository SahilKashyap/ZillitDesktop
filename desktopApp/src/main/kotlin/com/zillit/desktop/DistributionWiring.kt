package com.zillit.desktop

import androidx.compose.ui.graphics.vector.ImageVector
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
import com.zillit.desktop.feature.pagedistribution.ui.DistributionToolProvider
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import kotlinx.coroutines.CoroutineScope
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
)

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
)
