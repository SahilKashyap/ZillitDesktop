package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.costreport.data.AnalyticsRepositoryImpl
import com.zillit.desktop.feature.costreport.data.CostReportRepositoryImpl
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFilterSource
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsOption
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsViewModel
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.ui.CostReportToolProvider
import com.zillit.desktop.feature.costreport.ui.CostReportViewModel
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetToolProvider
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetViewModel
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

    override suspend fun exportReport(format: ExportFormat, body: JsonObject): ZillitResult<ByteArray> =
        postForBytes("$base/export/${format.wire}", body)
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

/**
 * "Name · Designation" for the posted-by lines and the export's `generated_by`.
 * A designation arrives as a label key (`production_office_label`), so it is
 * translated before it is shown.
 */
internal fun AppGraph.Ready.costReportUser(userId: String): String? =
    projectContext?.context?.value?.user(userId)?.let { user ->
        user.designation?.takeIf { it.isNotBlank() }?.let { "${user.fullName} · ${it.localised()}" } ?: user.fullName
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

/**
 * The accountant's worksheet — the Account Hub's REPORTS → Cost Report, at
 * `/film-tools/account-hub/cost-report`. Same service and seams as the crew
 * tool, plus the writes: weekly versions, posts, the lock and the live export.
 */
internal fun AppGraph.Ready.buildCostReportWorksheet(permissions: () -> ProjectPermissions) = WorksheetViewModel(
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
    companyName = { projectContext?.context?.value?.project?.companyName },
    signedInUser = {
        projectContext?.context?.value?.let { context -> context.profile?.userId?.let(::costReportUser) }
    },
    resolveUser = ::costReportUser,
    nowMillis = System::currentTimeMillis,
)

/**
 * The Analytics page (`/film-tools/cost-report/analytics`): the three block
 * reads, and the filter choices from the services that own them — currencies
 * and companies from the Account Hub, departments from the crew list, units
 * from the units service (matched on the unit's own id, as the web sends it).
 */
internal fun AppGraph.Ready.buildCostReportAnalytics(): AnalyticsViewModel {
    val reference = CostReportRepositoryImpl(apiClient, config)
    return AnalyticsViewModel(
        repository = AnalyticsRepositoryImpl(apiClient, config),
        filterSource = object : AnalyticsFilterSource {
            override suspend fun currencies(): CurrencyOptions? = reference.currencies().getOrNull()

            override suspend fun companies(): List<CrCompany> = reference.companies().getOrNull().orEmpty()

            override suspend fun departments(): List<AnalyticsOption> =
                hubDepartments().map { AnalyticsOption(it.id, it.name) }

            override suspend fun units(): List<AnalyticsOption> =
                unitRepository.joinUnits().getOrNull().orEmpty().map { unit ->
                    AnalyticsOption(unit.unitId ?: unit.id, unit.name.localised())
                }
        },
    )
}

internal fun AppGraph.Ready.costReportWorksheetProvider(viewModel: WorksheetViewModel) =
    WorksheetToolProvider(viewModel = viewModel, resolveUser = ::costReportUser)
