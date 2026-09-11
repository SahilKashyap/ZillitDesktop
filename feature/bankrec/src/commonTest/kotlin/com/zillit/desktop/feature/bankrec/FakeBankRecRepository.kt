package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
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

    val matches = mutableListOf<Triple<String, String, LedgerEntryKind>>()
    val signOffs = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<List<String>>()
    val statusChanges = mutableListOf<Triple<String, ExceptionStatus, String>>()
    val quickAdds = mutableListOf<Pair<String, QuickAddForm>>()
    val escalations = mutableListOf<String>()
    val dismissals = mutableListOf<String>()
    val fxPosts = mutableListOf<Pair<String, FxPosting>>()
    val fxPostAlls = mutableListOf<String>()
    val savedMatchRules = mutableListOf<Map<String, Boolean>>()
    val savedFraudRules = mutableListOf<Map<String, FraudRule>>()
    val createdLinks = mutableListOf<PortalLinkDraft>()
    val revokedLinks = mutableListOf<String>()
    val imports = mutableListOf<StatementUpload>()
    var rerunCount = 0
    var workspaceLoads = 0

    override suspend fun periods(): ZillitResult<List<BankPeriod>> = ZillitResult.Success(periodRows)

    override suspend fun period(id: String): ZillitResult<BankPeriod> =
        ZillitResult.Success(periodRows.first { it.id == id })

    override suspend fun bankAccounts(): ZillitResult<List<BankAccountRef>> =
        ZillitResult.Success(accountRows)

    override suspend fun projectCurrencies(): ZillitResult<ProjectRates> = ZillitResult.Success(rates)

    override suspend fun updatePeriodNote(id: String, note: String): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    override suspend fun signOffPeriod(id: String, note: String): ZillitResult<Unit> {
        signOffs += id to note
        return ZillitResult.Success(Unit)
    }

    override suspend fun deletePeriods(periodIds: List<String>): ZillitResult<Unit> {
        deletes += periodIds
        return ZillitResult.Success(Unit)
    }

    override suspend fun workspace(periodId: String): ZillitResult<WorkspaceData> {
        workspaceLoads++
        return ZillitResult.Success(workspaceData)
    }

    override suspend fun matchTransaction(
        id: String,
        entityId: String,
        kind: LedgerEntryKind,
    ): ZillitResult<Unit> {
        matches += Triple(id, entityId, kind)
        return ZillitResult.Success(Unit)
    }

    override suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit> {
        rerunCount++
        return ZillitResult.Success(Unit)
    }

    override suspend fun importStatement(
        attachment: StatementUpload,
        bankAccountId: String?,
        periodId: String?,
    ): ZillitResult<Unit> {
        imports += attachment
        return ZillitResult.Success(Unit)
    }

    override suspend fun exceptions(periodId: String?): ZillitResult<List<BankException>> =
        ZillitResult.Success(exceptionRows)

    override suspend fun setExceptionStatus(
        id: String,
        status: ExceptionStatus,
        notes: String,
    ): ZillitResult<Unit> {
        statusChanges += Triple(id, status, notes)
        return ZillitResult.Success(Unit)
    }

    override suspend fun quickAddException(id: String, form: QuickAddForm): ZillitResult<Unit> {
        quickAdds += id to form
        return ZillitResult.Success(Unit)
    }

    override suspend fun fraudAlerts(periodId: String?): ZillitResult<List<FraudAlert>> =
        ZillitResult.Success(alertRows)

    override suspend fun escalateFraudAlert(id: String): ZillitResult<Unit> {
        escalations += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun dismissFraudAlert(id: String): ZillitResult<Unit> {
        dismissals += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun fraudAuditLog(periodId: String?): ZillitResult<List<FraudAuditEntry>> =
        ZillitResult.Success(auditRows)

    override suspend fun fxVariances(periodId: String?): ZillitResult<List<FxVariance>> =
        ZillitResult.Success(fxRows)

    override suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit> {
        fxPosts += id to posting
        return ZillitResult.Success(Unit)
    }

    override suspend fun postAllFxVariances(periodId: String): ZillitResult<Unit> {
        fxPostAlls += periodId
        return ZillitResult.Success(Unit)
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

    override suspend fun portalLinks(): ZillitResult<List<PortalLink>> = ZillitResult.Success(linkRows)

    override suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<PortalLink> {
        createdLinks += draft
        return ZillitResult.Success(PortalLink(id = "new", recipientName = draft.recipientName))
    }

    override suspend fun updatePortalLink(
        id: String,
        draft: PortalLinkDraft,
    ): ZillitResult<PortalLink> {
        createdLinks += draft
        return ZillitResult.Success(PortalLink(id = id, recipientName = draft.recipientName))
    }

    override suspend fun revokePortalLink(id: String): ZillitResult<Unit> {
        revokedLinks += id
        return ZillitResult.Success(Unit)
    }
}
