package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.BibleFilters
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.Vendor

/**
 * The closeout bible.
 *
 * Unlike the trial balance beside it, the bible does not run on open. It opens
 * on a card that says "Set filters and run the report", as the web does,
 * because this is the heaviest read the cost-report service has.
 */
data class BibleReportState(
    val filters: BibleFilters = BibleFilters(),
    /** Whether the Date Range has been seeded and the filter sources asked for, once per console. */
    val prepared: Boolean = false,
    /** Today as `YYYY-MM-DD`, from the host clock — what "Current Period" runs up to. */
    val today: String = "",
    /**
     * The close boundary is being read.
     *
     * Current Period starts on it, so Run waits: a run a moment early would ask
     * for everything since 2000 and label it as this period.
     */
    val lockLoading: Boolean = false,
    val loading: Boolean = false,
    /** Null until the first run. */
    val report: BibleReport? = null,
    /** What the report on screen was run with — its banner's fallback and its export's filters. */
    val run: BibleRun? = null,
    /**
     * Why the last run failed.
     *
     * Shown above whatever the previous run left on screen rather than
     * replacing it, as the web does: a failed refresh does not unmake the
     * figures an accountant was reading.
     */
    val error: String? = null,
    /** Accounts the reader has folded away, by code. */
    val collapsed: Set<String> = emptySet(),
    val exporting: ExportFormat? = null,
    val exportOpen: Boolean = false,
    /**
     * Every vendor, for the Vendor filter.
     *
     * The bible's own list rather than the Vendors page's rows: that page's
     * rows are whatever its search last matched, and a filter drawn from them
     * would offer only the vendors somebody searched for.
     */
    val vendors: List<Vendor> = emptyList(),
) {
    val hasRun: Boolean get() = report != null
}

/**
 * One run of the report.
 *
 * The export sends these rather than the filter bar, so the file matches the
 * table on screen even when a filter has been touched since. [periodLabel] and
 * [companyName] are the words the server prints in the file's header.
 */
data class BibleRun(val query: BibleQuery, val periodLabel: String, val companyName: String = "")
