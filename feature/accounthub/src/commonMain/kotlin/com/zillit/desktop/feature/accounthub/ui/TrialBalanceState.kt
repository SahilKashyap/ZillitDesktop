package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceFilters
import com.zillit.desktop.feature.accounthub.domain.TrialBalancePeriod

/**
 * The trial balance page — the web's `TrialBalanceModule`.
 *
 * The filter bar holds what was typed, [draft] is what that amounts to, and
 * [applied] is what the rows on screen were asked for. The two differ while
 * the reader is choosing, which is what puts Refresh in the header: the report
 * is only asked for again on request, because it is the service's most
 * expensive read and a period nobody meant is not worth its time.
 */
data class TrialBalanceState(
    val periodMode: PeriodMode = PeriodMode.Current,
    /** The Date Range pickers, as typed — kept while Current Period is picked, as the web keeps them. */
    val fromText: String = "",
    val toText: String = "",
    /** The account range, as typed. Trimmed only into [draft], so typing is never rewritten under the cursor. */
    val accountFromText: String = "",
    val accountToText: String = "",
    /** One legal entity; blank is "All companies". */
    val companyId: String = "",
    val currency: String = "",
    val includeZeroAccounts: Boolean = false,
    /**
     * Whether the reader has picked a currency. Until they do, the
     * production's default is filled in whenever it becomes known — the web's
     * `currencyTouched`.
     */
    val currencyTouched: Boolean = false,
    /** Today in the machine's zone, `YYYY-MM-DD` — Current Period's last day. Read at open and at each press. */
    val today: String = "",
    /** What the rows on screen were asked for; null before the first run. */
    val applied: TrialBalanceFilters? = null,
    val loading: Boolean = false,
    /** The last run failed: the table says so, rather than show rows that answer other filters. */
    val failed: Boolean = false,
    val errorMessage: String? = null,
    val report: TrialBalance = TrialBalance(),
    val exporting: ExportFormat? = null,
    val exportOpen: Boolean = false,
) {
    /**
     * What the filter bar amounts to.
     *
     * Current Period runs from [lockedThrough] — the last closed day, which is
     * the period close's to own — to today, and from the web's floor while
     * nothing has been closed.
     */
    fun draft(lockedThrough: String): TrialBalanceFilters = TrialBalanceFilters(
        mode = periodMode,
        from = if (periodMode == PeriodMode.Current) currentFrom(lockedThrough) else fromText.trim(),
        to = if (periodMode == PeriodMode.Current) today else toText.trim(),
        accountStart = accountFromText.trim(),
        accountEnd = accountToText.trim(),
        companyId = companyId,
        currency = currency,
        includeZeroAccounts = includeZeroAccounts,
    )

    /** Rows are on screen and whole — the count, the pinned total and the export all wait for this. */
    val showsRows: Boolean get() = !loading && !failed && report.rows.isNotEmpty()

    companion object {
        fun currentFrom(lockedThrough: String): String =
            lockedThrough.takeIf { TrialBalancePeriod.parse(it) != null } ?: TrialBalancePeriod.FLOOR
    }
}

/** The trial balance's filter bar as it stands, against the production's own close boundary. */
val AccountHubUiState.trialBalanceDraft: TrialBalanceFilters
    get() = trialBalance.draft(periodClose.lock.lockedThrough)

/** A filter differs from what the rows on screen answer — the web's `dirty`, which shows Refresh. */
val AccountHubUiState.trialBalanceDirty: Boolean
    get() = trialBalance.applied != null && trialBalance.applied != trialBalanceDraft

/**
 * The currencies the filter offers — the web's `useCurrencyOptions`.
 *
 * The production's own, default first; the whole catalogue while it has picked
 * none, so the selector is never empty.
 */
val AccountHubUiState.trialBalanceCurrencies: List<ProjectCurrency>
    get() {
        val settings = setup.currencies.saved
        val defaultCode = settings.defaultCode.orEmpty()
        val offered = settings.currencies.ifEmpty { setup.currencyCatalogue }
        val default = offered.firstOrNull { it.code == defaultCode }
            ?: defaultCode.takeIf { it.isNotBlank() }?.let { ProjectCurrency(it) }
        return listOfNotNull(default) + offered.filterNot { it.code == default?.code }
    }
