package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.NominalCode
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.QuickEntryType
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.TaxOption
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter

/**
 * The module's tabs, in the order the web lists them.
 *
 * [badgeKey] is the notification ledger's `level_1` for the tab. Open Banking
 * has none: it is a placeholder on the web too, and nothing is ever filed there.
 */
enum class BankTab(val slug: String, private val labelKey: String, val badgeKey: String?) {
    Overview("overview", S.ah_overview, "bank_overview"),
    Workspace("workspace", S.desktop_workspace, "bank_workspace"),
    Exceptions("exceptions", S.desktop_exceptions, "bank_exceptions"),
    FraudAlerts("fraud-alerts", S.desktop_fraud_alerts, "bank_fraud_alerts"),
    FxVariances("fx-variances", S.desktop_fx_variances, "bank_fx_variances"),
    History("history", S.history, "bank_period_history"),
    OpenBanking("open-banking", S.desktop_open_banking, null),
    GuarantorPortal("guarantor-portal", S.desktop_guarantor_portal, "bank_guarantor_portal"),
    Settings("settings", S.desktop_accounts_and_rules, "bank_settings"),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(slug: String?): BankTab = entries.firstOrNull { it.slug == slug } ?: Overview
    }
}

/** "All open periods" in a period filter. */
const val ALL_PERIODS = "all"

/**
 * Which period a list tab shows.
 *
 * Null is "follow the newest open period", which is what the tab opens on. A
 * choice that is no longer open — signed off or deleted elsewhere — falls back
 * the same way rather than leaving the list empty over a period that has gone.
 */
fun resolvePeriodChoice(choice: String?, openPeriods: List<BankPeriod>): String = when {
    choice == ALL_PERIODS -> ALL_PERIODS
    choice != null && openPeriods.any { it.id == choice } -> choice
    else -> openPeriods.firstOrNull()?.id ?: ALL_PERIODS
}

/** Which of the two period tables a selection belongs to — each keeps its own. */
enum class PeriodScope { Overview, History }

/** A delete waiting for its confirmation, with the months it names. */
data class DeleteRequest(val ids: List<String>, val label: String, val deleting: Boolean = false)

/** The read-only detail of a period, opened from View. */
data class PeriodDetailState(
    val periodId: String,
    val loading: Boolean = true,
    val preview: PortalPreview? = null,
    val transactions: List<BankTransaction> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
)

/** The signed-off periods being chosen for a PDF. */
data class ExportPdfState(val selected: Set<String> = emptySet(), val exporting: Boolean = false)

/** A statement being imported — the web's two screens: choose, then watch it process. */
data class ImportState(
    val open: Boolean = false,
    val bankAccountId: String = "",
    val file: PickedStatement? = null,
    val dragOver: Boolean = false,
    val picking: Boolean = false,
    val processing: Boolean = false,
    /** 0 upload, 1 parse, 2 match, 3 checks, 4 done — see [ImportStep]. */
    val step: Int = 0,
    val result: ImportResult? = null,
    val error: String? = null,
)

/** The five steps the import dialog walks through. */
enum class ImportStep(private val labelKey: String) {
    Upload(S.desktop_br_import_step_upload),
    Parse(S.desktop_br_import_step_parse),
    Match(S.desktop_br_import_step_match),
    Validate(S.desktop_br_import_step_validate),
    Done(S.desktop_br_audit_import_complete),
    ;

    val label: String get() = str(labelKey)
}

/** A suggested match waiting for its confirmation. */
data class MatchProposal(val transactionId: String, val invoiceId: String)

/** The sign-off dialog. */
data class SignOffState(val note: String = "", val submitting: Boolean = false)

/**
 * The quick-entry drawer beside the workspace.
 *
 * Two forms share one drawer: the general form that posts an exception and
 * matches it, and the FX form that posts a variance. The nominal code and cost
 * centre are shared between them, as on the web.
 */
data class QuickEntryState(
    val type: QuickEntryType = QuickEntryType.BankCharge,
    /** The bank line being quick-added, when the drawer was opened from one. */
    val transactionId: String? = null,
    val form: QuickAddForm = QuickAddForm(),
    val fraudReason: String = "",
    val fraudPriority: String = "High",
    val adding: Boolean = false,
    val fx: FxDetail? = null,
    val fxCurrency: String = "EUR",
    val fxForeignAmount: String = "",
    val fxBudgetRate: String = "",
    val fxBankRate: String = "",
    val fxPosting: Boolean = false,
    val fxPosted: Boolean = false,
)

/** The reconciliation workspace for one period. */
data class WorkspaceState(
    val periodId: String = "",
    val loading: Boolean = false,
    val transactions: List<BankTransaction> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    /** The ledger balance from the latest workspace read, which is fresher than the period's. */
    val closingZillit: Double? = null,
    val filter: WorkspaceFilter = WorkspaceFilter.All,
    val selectedId: String? = null,
    /** A ledger row lit for a moment after "View" — scrolled to and highlighted. */
    val flashId: String? = null,
    /** The full view — the module's header and tabs given back to the panels. */
    val expanded: Boolean = false,
    val showQuickEntry: Boolean = true,
    val rerunning: Boolean = false,
    val quickEntry: QuickEntryState = QuickEntryState(),
    val proposal: MatchProposal? = null,
    val accepting: Boolean = false,
    val manualMatchId: String? = null,
    val manualMatchEntryId: String? = null,
    val manualMatching: Boolean = false,
    val signOff: SignOffState? = null,
)

/** Which exception is being acted on, and with what. */
data class ExceptionAction(val id: String, val status: ExceptionStatus)

/** The exceptions dialog that posts one to the ledger. */
data class ExceptionQuickAddState(
    val exceptionId: String,
    val form: QuickAddForm,
    val saving: Boolean = false,
)

data class ExceptionsPageState(
    val periodChoice: String? = null,
    val acting: ExceptionAction? = null,
    val quickAdd: ExceptionQuickAddState? = null,
    val exporting: Boolean = false,
)

/** The two things done to an alert. */
enum class AlertAction { Dismiss, Escalate }

/** A column the audit trail can be sorted by. */
enum class AuditSort { CreatedAt, Action, Risk, Amount, Vendor }

data class AuditLogState(
    val loading: Boolean = true,
    val entries: List<FraudAuditEntry> = emptyList(),
    val filters: AuditFilters = AuditFilters(),
    val sort: AuditSort = AuditSort.CreatedAt,
    val ascending: Boolean = false,
    val exportMenu: Boolean = false,
    val exporting: AuditExportFormat? = null,
)

data class FraudPageState(
    val periodChoice: String? = null,
    val acting: Pair<String, AlertAction>? = null,
    val audit: AuditLogState? = null,
)

/** The dialog that posts one FX variance. */
data class FxPostState(
    val varianceId: String,
    val nominalCode: String,
    val costCentre: String,
    val budgetRate: String,
    val bankRate: String,
    val posting: Boolean = false,
    val posted: Boolean = false,
) {
    val budgetRateValue: Double get() = budgetRate.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    val bankRateValue: Double get() = bankRate.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    /** Both rates, or the variance is the budget figure minus nothing. */
    val ready: Boolean get() = budgetRateValue > 0 && bankRateValue > 0
}

data class FxPageState(
    val periodChoice: String? = null,
    val post: FxPostState? = null,
    val postingAll: Boolean = false,
    val postAllMessage: String? = null,
)

/** The shared-links tab. */
data class PortalState(
    val links: List<PortalLink> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val selectedPeriodId: String? = null,
    val preview: PortalPreview? = null,
    val previewLoading: Boolean = false,
    val draft: PortalLinkDraft? = null,
    val copiedToken: String? = null,
    val revokingId: String? = null,
)

/** The accounts and rules tab. */
data class RulesState(
    val settings: RulesSettings = RulesSettings(),
    val saved: RulesSettings = RulesSettings(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val savingMatch: Boolean = false,
    val savingFraud: Boolean = false,
) {
    val matchDirty: Boolean get() = settings.autoMatch != saved.autoMatch

    val fraudDirty: Boolean get() = settings.fraud != saved.fraud
}

/** What the quick forms read from other services. */
data class LookupState(
    val taxTypes: List<TaxOption> = emptyList(),
    val nominalCodes: List<NominalCode> = emptyList(),
    val lockedThrough: String? = null,
    val departments: Map<String, String> = emptyMap(),
    /** The open production, for the portal summary's header and the exports. */
    val company: CompanyDetails = CompanyDetails(),
)

/** Everything the module renders. */
data class BankRecUiState(
    val tab: BankTab = BankTab.Overview,
    val periods: List<BankPeriod> = emptyList(),
    /** True until the first period list lands — the skeleton, not an empty page. */
    val periodsLoading: Boolean = true,
    val bankAccounts: List<BankAccountRef> = emptyList(),
    /** The project's own currency and its rates — see [ProjectRates]. */
    val rates: ProjectRates = ProjectRates(),
    val exceptions: List<BankException> = emptyList(),
    val exceptionsLoading: Boolean = true,
    val fraudAlerts: List<FraudAlert> = emptyList(),
    val fraudLoading: Boolean = true,
    val fxVariances: List<FxVariance> = emptyList(),
    val fxLoading: Boolean = true,
    /** Unread per tab, keyed by [BankTab.badgeKey]. */
    val badges: Map<String, Int> = emptyMap(),
    val overviewSelection: Set<String> = emptySet(),
    val historySelection: Set<String> = emptySet(),
    val historyAccountId: String = "",
    val deleting: DeleteRequest? = null,
    val periodDetail: PeriodDetailState? = null,
    val exportPdf: ExportPdfState? = null,
    val import: ImportState = ImportState(),
    val workspace: WorkspaceState = WorkspaceState(),
    val exceptionsPage: ExceptionsPageState = ExceptionsPageState(),
    val fraudPage: FraudPageState = FraudPageState(),
    val fxPage: FxPageState = FxPageState(),
    val portal: PortalState = PortalState(),
    val rules: RulesState = RulesState(),
    val lookups: LookupState = LookupState(),
    val notice: String? = null,
    val canImport: Boolean = true,
) {
    /** The periods still being worked on, newest month first. */
    val openPeriods: List<BankPeriod>
        get() = periods.filter { it.isOpen }.sortedByDescending { it.periodMillis ?: 0 }

    /**
     * The period the KPI row describes: the first one in progress.
     *
     * Not the newest: a signed-off month is done, and showing its figures as
     * the current state of the reconciliation is how a finished period comes
     * to look like an outstanding one.
     */
    val currentPeriod: BankPeriod? get() = periods.firstOrNull { it.status == PeriodStatus.InProgress }

    val workspacePeriod: BankPeriod? get() = period(workspace.periodId)

    /** The History table's rows: one account's periods, as the web filters them. */
    val historyPeriods: List<BankPeriod>
        get() = if (historyAccountId.isBlank()) periods else periods.filter { it.bankAccountId == historyAccountId }

    /** The signed-off periods — the only ones the PDF export offers. */
    val completedPeriods: List<BankPeriod> get() = periods.filter { !it.isOpen }

    fun period(id: String?): BankPeriod? = id?.let { wanted -> periods.firstOrNull { it.id == wanted } }

    fun account(id: String?): BankAccountRef? = id?.let { wanted -> bankAccounts.firstOrNull { it.id == wanted } }

    val projectCurrency: String get() = rates.defaultCode

    /** A period's own currency is its bank account's; the period carries none. */
    fun currencyOf(period: BankPeriod?): String =
        account(period?.bankAccountId)?.currencyCode?.takeIf { it.isNotBlank() } ?: projectCurrency

    /**
     * A period's label, disambiguated by account only when two open periods
     * share a month — the common single-account case stays clean.
     */
    fun periodOptionLabel(period: BankPeriod): String {
        val month = BankRecFormat.periodLabel(period)
        val sharesMonth = openPeriods.count { it.periodMillis == period.periodMillis } > 1
        val name = account(period.bankAccountId)?.displayName.orEmpty()
        return if (sharesMonth && name.isNotBlank()) "$month · $name" else month
    }

    /**
     * The KPI row for [period], in one comparable currency.
     *
     * A period's balances are in its bank account's currency; the ledger's are
     * in the project's. When they differ the bank side is converted — and when
     * no rate is set it is *not*, and the row says so rather than showing a
     * difference computed across two currencies.
     */
    fun kpiFor(period: BankPeriod?): BankKpi {
        val row = period ?: return BankKpi(currency = projectCurrency)
        val bankCode = currencyOf(row)
        val needsConversion = !bankCode.equals(projectCurrency, ignoreCase = true)
        val converted = if (needsConversion) rates.toDefault(row.closingBank, bankCode) else row.closingBank
        return BankKpi(
            currency = projectCurrency,
            bankBalance = converted,
            zillitBalance = row.closingZillit,
            difference = converted?.let { row.differenceIn(it, needsConversion) },
            nativeBalance = row.closingBank.takeIf { needsConversion },
            nativeCurrency = bankCode.takeIf { needsConversion },
            hasRate = !needsConversion || converted != null,
            matched = row.matchedCount,
            total = row.totalTxns,
        )
    }

    /** Whether any dialog is up — keyboard shortcuts stand down while one is. */
    val dialogOpen: Boolean
        get() = deleting != null || periodDetail != null || exportPdf != null || import.open ||
            workspace.proposal != null || workspace.manualMatchId != null || workspace.signOff != null ||
            exceptionsPage.quickAdd != null || fraudPage.audit != null || fxPage.post != null ||
            portal.draft != null
}

/**
 * The five figures at the top of Overview.
 *
 * [bankBalance] and [difference] are null when the account is in a currency
 * the project has no rate for: a difference computed across two currencies is
 * a number that means nothing, and printing it invites somebody to chase it.
 */
data class BankKpi(
    val currency: String = "GBP",
    val bankBalance: Double? = null,
    val zillitBalance: Double = 0.0,
    val difference: Double? = null,
    /** The statement's own figure, when it was converted to get [bankBalance]. */
    val nativeBalance: Double? = null,
    val nativeCurrency: String? = null,
    val hasRate: Boolean = true,
    val matched: Int = 0,
    val total: Int = 0,
) {
    val isReconciled: Boolean get() = difference != null && kotlin.math.abs(difference) < HALF_PENNY

    private companion object {
        /** Sums of converted decimals: exact equality calls a balanced ledger broken. */
        const val HALF_PENNY = 0.005
    }
}
