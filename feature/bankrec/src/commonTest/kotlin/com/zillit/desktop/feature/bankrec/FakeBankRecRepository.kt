package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData

/** A repository that answers from fields the tests set, and records what it was asked. */
@Suppress("TooManyFunctions") // Mirrors the interface, one for one.
internal open class FakeBankRecRepository : BankRecRepository {

    var periodRows: List<BankPeriod> = emptyList()
    var accountRows: List<BankAccountRef> = emptyList()
    var rates: ProjectRates = ProjectRates()
    var workspaceData: WorkspaceData = WorkspaceData()
    var exceptionRows: List<BankException> = emptyList()
    var alertRows: List<FraudAlert> = emptyList()
    var auditRows: List<FraudAuditEntry> = emptyList()
    var fxRows: List<FxVariance> = emptyList()
    var linkRows: List<PortalLink> = emptyList()
    var settings: RulesSettings = RulesSettings()
    var preview: PortalPreview? = null
    var importResult: ImportResult = ImportResult()
    var exportBytes: ByteArray = byteArrayOf(1, 2, 3)

    /** Ids whose FX post answers a failure — for Post All's partial report. */
    var failingFxPosts: Set<String> = emptySet()

    val matches = mutableListOf<Triple<String, String, LedgerEntryKind>>()
    val signOffs = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<List<String>>()
    val statusChanges = mutableListOf<Pair<String, ExceptionStatus>>()
    val quickAdds = mutableListOf<Triple<String, QuickAddForm, Boolean>>()
    val escalations = mutableListOf<String>()
    val dismissals = mutableListOf<String>()
    val fxPosts = mutableListOf<Pair<String, FxPosting>>()
    val savedMatchRules = mutableListOf<Map<String, Boolean>>()
    val savedFraudRules = mutableListOf<Map<String, FraudRule>>()
    val createdLinks = mutableListOf<PortalLinkDraft>()
    val updatedLinks = mutableListOf<Pair<String, PortalLinkDraft>>()
    val revokedLinks = mutableListOf<String>()
    val imports = mutableListOf<Pair<StatementUpload, String>>()
    val periodExports = mutableListOf<List<String>>()
    val exceptionExports = mutableListOf<String>()
    val auditExports = mutableListOf<Pair<AuditExportFormat, AuditFilters>>()
    var rerunCount = 0
    var workspaceLoads = 0

    override suspend fun periods(): ZillitResult<List<BankPeriod>> = ZillitResult.Success(periodRows)

    override suspend fun bankAccounts(): ZillitResult<List<BankAccountRef>> = ZillitResult.Success(accountRows)

    override suspend fun projectCurrencies(): ZillitResult<ProjectRates> = ZillitResult.Success(rates)

    override suspend fun signOffPeriod(id: String, note: String): ZillitResult<Unit> {
        signOffs += id to note
        return ZillitResult.Success(Unit)
    }

    override suspend fun deletePeriods(periodIds: List<String>): ZillitResult<Unit> {
        deletes += periodIds
        return ZillitResult.Success(Unit)
    }

    override suspend fun exportPeriodsPdf(periodIds: List<String>, company: CompanyDetails): ZillitResult<ByteArray> {
        periodExports += periodIds
        return ZillitResult.Success(exportBytes)
    }

    override suspend fun workspace(periodId: String): ZillitResult<WorkspaceData> {
        workspaceLoads++
        return ZillitResult.Success(workspaceData)
    }

    override suspend fun matchTransaction(id: String, entityId: String, kind: LedgerEntryKind): ZillitResult<Unit> {
        matches += Triple(id, entityId, kind)
        return ZillitResult.Success(Unit)
    }

    override suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit> {
        rerunCount++
        return ZillitResult.Success(Unit)
    }

    override suspend fun importStatement(
        attachment: StatementUpload,
        bankAccountId: String,
    ): ZillitResult<ImportResult> {
        imports += attachment to bankAccountId
        return ZillitResult.Success(importResult)
    }

    override suspend fun exceptions(): ZillitResult<List<BankException>> = ZillitResult.Success(exceptionRows)

    override suspend fun setExceptionStatus(id: String, status: ExceptionStatus): ZillitResult<Unit> {
        statusChanges += id to status
        // As the service does: the re-read that follows sees the new status.
        exceptionRows = exceptionRows.map { if (it.id == id) it.copy(status = status) else it }
        return ZillitResult.Success(Unit)
    }

    override suspend fun quickAddException(id: String, form: QuickAddForm, fromWorkspace: Boolean): ZillitResult<Unit> {
        quickAdds += Triple(id, form, fromWorkspace)
        return ZillitResult.Success(Unit)
    }

    override suspend fun exportExceptionsPdf(periodId: String, company: CompanyDetails): ZillitResult<ByteArray> {
        exceptionExports += periodId
        return ZillitResult.Success(exportBytes)
    }

    override suspend fun fraudAlerts(): ZillitResult<List<FraudAlert>> = ZillitResult.Success(alertRows)

    override suspend fun escalateFraudAlert(id: String): ZillitResult<Unit> {
        escalations += id
        alertRows = alertRows.map { if (it.id == id) it.copy(status = FraudStatus.Escalated) else it }
        return ZillitResult.Success(Unit)
    }

    override suspend fun dismissFraudAlert(id: String): ZillitResult<Unit> {
        dismissals += id
        alertRows = alertRows.map { if (it.id == id) it.copy(status = FraudStatus.Dismissed) else it }
        return ZillitResult.Success(Unit)
    }

    override suspend fun fraudAuditLog(): ZillitResult<List<FraudAuditEntry>> = ZillitResult.Success(auditRows)

    override suspend fun exportAuditLog(
        format: AuditExportFormat,
        filters: AuditFilters,
        company: CompanyDetails,
    ): ZillitResult<ByteArray> {
        auditExports += format to filters
        return ZillitResult.Success(exportBytes)
    }

    override suspend fun fxVariances(): ZillitResult<List<FxVariance>> = ZillitResult.Success(fxRows)

    override suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit> {
        fxPosts += id to posting
        return if (id in failingFxPosts) {
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Validation("refused"))
        } else {
            ZillitResult.Success(Unit)
        }
    }

    override suspend fun rulesSettings(): ZillitResult<RulesSettings> = ZillitResult.Success(settings)

    override suspend fun saveAutoMatchRules(rules: Map<String, Boolean>): ZillitResult<Unit> {
        savedMatchRules += rules
        return ZillitResult.Success(Unit)
    }

    override suspend fun saveFraudRules(rules: Map<String, FraudRule>): ZillitResult<Unit> {
        savedFraudRules += rules
        return ZillitResult.Success(Unit)
    }

    override suspend fun portalPreview(periodId: String, bankAccountId: String): ZillitResult<PortalPreview?> =
        ZillitResult.Success(preview)

    override suspend fun portalLinks(): ZillitResult<List<PortalLink>> = ZillitResult.Success(linkRows)

    override suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<Unit> {
        createdLinks += draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun updatePortalLink(id: String, draft: PortalLinkDraft): ZillitResult<Unit> {
        updatedLinks += id to draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun revokePortalLink(id: String): ZillitResult<Unit> {
        revokedLinks += id
        return ZillitResult.Success(Unit)
    }
}
