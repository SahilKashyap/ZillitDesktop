package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The module's tabs, in the order the web lists them. */
enum class BankTab(val slug: String, val label: String) {
    Overview("overview", "Overview"),
    Workspace("workspace", "Workspace"),
    Exceptions("exceptions", "Exceptions"),
    FraudAlerts("fraud-alerts", "Fraud Alerts"),
    FxVariances("fx-variances", "FX Variances"),
    History("history", "History"),
    GuarantorPortal("guarantor-portal", "Guarantor Portal"),
    Settings("settings", "Accounts & Rules"),
    ;

    companion object {
        fun from(slug: String?): BankTab = entries.firstOrNull { it.slug == slug } ?: Overview
    }
}

/** Which lines the workspace is showing. */
enum class WorkspaceFilter(val slug: String, val label: String) {
    All("all", "All"),
    Unmatched("unmatched", "Unmatched"),
    Suggested("suggested", "Suggested"),
    Fraud("fraud", "Fraud"),
    Fx("fx", "FX"),
    ;

    fun accepts(txn: BankTransaction): Boolean = when (this) {
        All -> true
        Unmatched -> txn.effectiveStatus == TxnStatus.Unmatched
        Suggested -> txn.effectiveStatus == TxnStatus.Suggested
        Fraud -> txn.effectiveStatus == TxnStatus.FraudFlag
        Fx -> txn.fx != null
    }
}

/** A match waiting for a person to confirm it. */
data class PendingMatch(
    val transaction: BankTransaction,
    val entry: LedgerEntry,
    val wasSuggested: Boolean,
)

/**
 * The reconciliation workspace for one period.
 *
 * [selected] is the bank line whose ledger counterpart is being chosen. Manual
 * matching is a two-step act on purpose: reconciling the wrong invoice to a
 * payment is not visible afterwards, because both then read as matched.
 */
data class WorkspaceState(
    val periodId: String = "",
    val transactions: List<BankTransaction> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val filter: WorkspaceFilter = WorkspaceFilter.All,
    val loading: Boolean = false,
    val rerunning: Boolean = false,
    val matching: Boolean = false,
    val selected: BankTransaction? = null,
    val pending: PendingMatch? = null,
    val signingOff: Boolean = false,
    val signOffNote: String = "",
    val confirmingSignOff: Boolean = false,
) {
    val visible: List<BankTransaction> get() = transactions.filter { filter.accepts(it) }

    val unmatchedLedger: List<LedgerEntry> get() = ledger.filterNot { it.isMatched }

    val matchedCount: Int get() = transactions.count { it.effectiveStatus == TxnStatus.Matched }

    val fraudCount: Int get() = transactions.count { it.hasActiveFraud }

    /** The ledger entries that could answer [selected], nearest amount first. */
    val matchCandidates: List<LedgerEntry>
        get() {
            val target = selected?.amount ?: return emptyList()
            return unmatchedLedger.sortedBy { entry ->
                entry.amount?.let { kotlin.math.abs(it - target) } ?: Double.MAX_VALUE
            }
        }
}

/** The exceptions tab. */
data class ExceptionsState(
    val rows: List<BankException> = emptyList(),
    val loading: Boolean = false,
    val periodId: String = "",
    val acting: String = "",
    /** The exception being posted to the ledger, and the form for it. */
    val posting: BankException? = null,
    val form: QuickAddForm = QuickAddForm(),
    val saving: Boolean = false,
    val noting: BankException? = null,
    val noteText: String = "",
    val noteStatus: ExceptionStatus = ExceptionStatus.UnderInvestigation,
) {
    val outstanding: List<BankException> get() = rows.filter { it.status.isOutstanding }

    val settled: List<BankException> get() = rows.filterNot { it.status.isOutstanding }
}

/** The fraud tab, and the audit trail under it. */
data class FraudState(
    val alerts: List<FraudAlert> = emptyList(),
    val auditLog: List<FraudAuditEntry> = emptyList(),
    val loading: Boolean = false,
    val periodId: String = "",
    val acting: String = "",
    val showAuditLog: Boolean = false,
    /** The alert being escalated, held until the accountant confirms. */
    val escalating: FraudAlert? = null,
) {
    val activeCount: Int get() = alerts.count { it.isOpen }
}

/** The FX tab. */
data class FxState(
    val rows: List<FxVariance> = emptyList(),
    val loading: Boolean = false,
    val periodId: String = "",
    val posting: FxVariance? = null,
    val nominalCode: String = FxPosting.DEFAULT_NOMINAL,
    val costCentre: String = "",
    val saving: Boolean = false,
    val confirmingPostAll: Boolean = false,
) {
    val unposted: List<FxVariance> get() = rows.filterNot { it.isPosted }

    val netVariance: Double get() = rows.sumOf { it.variance }
}

/** The shared-links tab. */
data class PortalState(
    val links: List<PortalLink> = emptyList(),
    val loading: Boolean = false,
    val draft: PortalLinkDraft? = null,
    val revoking: PortalLink? = null,
    val copiedToken: String = "",
)

/** The accounts and rules tab. */
data class RulesState(
    val settings: RulesSettings = RulesSettings(),
    val saved: RulesSettings = RulesSettings(),
    val loading: Boolean = false,
    val savingMatch: Boolean = false,
    val savingFraud: Boolean = false,
) {
    val matchDirty: Boolean get() = settings.autoMatch != saved.autoMatch

    val fraudDirty: Boolean get() = settings.fraud != saved.fraud
}

/** A statement being imported. */
data class ImportState(
    val open: Boolean = false,
    val bankAccountId: String = "",
    val periodId: String = "",
    val uploading: Boolean = false,
    val fileName: String = "",
)

/** Everything the module renders. */
data class BankRecUiState(
    val tab: BankTab = BankTab.Overview,
    val periods: List<BankPeriod> = emptyList(),
    val bankAccounts: List<BankAccountRef> = emptyList(),
    val periodsLoading: Boolean = false,
    /** The project's own currency and its rates — see [ProjectRates]. */
    val rates: ProjectRates = ProjectRates(),
    val workspace: WorkspaceState = WorkspaceState(),
    val exceptions: ExceptionsState = ExceptionsState(),
    val fraud: FraudState = FraudState(),
    val fx: FxState = FxState(),
    val portal: PortalState = PortalState(),
    val rules: RulesState = RulesState(),
    val import: ImportState = ImportState(),
    val deleting: List<BankPeriod> = emptyList(),
    val notice: String? = null,
    val canImport: Boolean = true,
) {
    /** The periods still being worked on, newest month first. */
    val openPeriods: List<BankPeriod>
        get() = periods.filter { it.isOpen }.sortedByDescending { it.periodMillis ?: 0 }

    /**
     * The period the KPI row describes.
     *
     * The one in progress, not the newest: a signed-off month is done, and
     * showing its figures as the current state of the reconciliation is how a
     * finished period comes to look like an outstanding one.
     */
    val currentPeriod: BankPeriod? get() = periods.firstOrNull { it.isOpen }

    fun account(id: String): BankAccountRef? = bankAccounts.firstOrNull { it.id == id }

    /** A period's own currency is its bank account's; the period carries none. */
    fun currencyOf(period: BankPeriod?): String =
        account(period?.bankAccountId.orEmpty())?.currencyCode?.takeIf { it.isNotBlank() }
            ?: projectCurrency

    val projectCurrency: String get() = rates.defaultCode

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

    fun periodLabelFor(id: String): String =
        periods.firstOrNull { it.id == id }?.let { periodLabel(it) } ?: "—"

    /**
     * A period's label, disambiguated by account only when two open periods
     * share a month.
     */
    fun periodLabel(period: BankPeriod): String {
        val month = monthLabel(period.periodMillis)
        val sharesMonth = openPeriods.count { it.periodMillis == period.periodMillis } > 1
        if (!sharesMonth) return month
        val name = account(period.bankAccountId)?.name.orEmpty()
        return if (name.isBlank()) month else "$month · $name"
    }
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
    val isReconciled: Boolean get() = difference != null && difference == 0.0
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * `Apr 2026`, read in UTC.
 *
 * The column is a first-of-month marker in UTC, so a local calendar moves it a
 * month either side of midnight — which is how a March reconciliation comes to
 * be labelled February.
 */
fun monthLabel(periodMillis: Long?): String {
    val millis = periodMillis ?: return EM_DASH
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
    return "${MONTHS[date.month.ordinal]} ${date.year}"
}

/** `05 Apr 2026`, also in UTC and for the same reason. */
fun dayLabel(millis: Long?): String {
    val value = millis ?: return EM_DASH
    val date = Instant.fromEpochMilliseconds(value).toLocalDateTime(TimeZone.UTC).date
    return "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month.ordinal]} ${date.year}"
}

/** `05 Apr` — the workspace's own column, where the year is never in doubt. */
fun shortDayLabel(millis: Long?): String {
    val value = millis ?: return EM_DASH
    val date = Instant.fromEpochMilliseconds(value).toLocalDateTime(TimeZone.UTC).date
    return "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month.ordinal]}"
}

private const val EM_DASH = "\u2014"
