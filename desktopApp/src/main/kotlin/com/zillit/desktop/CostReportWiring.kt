package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.costreport.data.CostReportRepositoryImpl
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.ui.CostReportToolProvider
import com.zillit.desktop.feature.costreport.ui.CostReportViewModel
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import kotlinx.serialization.json.JsonObject

/**
 * The cost report's host seams: the binary export POST (`ApiClient` only
 * speaks envelopes) and the Downloads folder.
 */
internal fun AppGraph.Ready.costReportExporter(): CostReportExporter = object : CostReportExporter {
    private val base = config.apiV2(ZillitService.CostReport).trimEnd('/') + "/cost-reports"

    override suspend fun export(snapshotId: String, format: ExportFormat, body: JsonObject): ZillitResult<ByteArray> =
        postForBytes("$base/snapshots/$snapshotId/export/${format.wire}", body)
}

internal fun costReportFiles(): CostReportFiles = object : CostReportFiles {
    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

/** "Name · Designation" for the posted-by lines and the export's `generated_by`. */
internal fun AppGraph.Ready.costReportUser(userId: String): String? =
    projectContext?.context?.value?.user(userId)?.let { user ->
        user.designation?.takeIf { it.isNotBlank() }?.let { "${user.fullName} · $it" } ?: user.fullName
    }

internal fun AppGraph.Ready.buildCostReport(permissions: () -> ProjectPermissions) = CostReportViewModel(
    repository = CostReportRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    exporter = costReportExporter(),
    files = costReportFiles(),
    resolveViewer = {
        CostReportViewer.from(permissions(), projectContext?.context?.value?.profile?.userId.orEmpty())
    },
    projectName = { projectContext?.context?.value?.project?.name.orEmpty() },
    resolveUser = ::costReportUser,
    nowMillis = System::currentTimeMillis,
)

internal fun AppGraph.Ready.costReportProvider(viewModel: CostReportViewModel) =
    CostReportToolProvider(viewModel = viewModel, resolveUser = ::costReportUser)
