package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrForecast
import com.zillit.desktop.feature.costreport.domain.CrHeader
import com.zillit.desktop.feature.costreport.domain.CrFigures
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.CrOverrides
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrViewMode
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.CurrencyRates
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.PostCadence
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.TreeToggles
import com.zillit.desktop.feature.costreport.domain.VtpBaseline
import com.zillit.desktop.feature.costreport.domain.WeekWindow
import com.zillit.desktop.feature.costreport.ui.LedgerView
import com.zillit.desktop.feature.costreport.ui.SnapshotView

/** The worksheet's two panes — the header tabs. */
enum class WorksheetPane(val id: String, val label: String) {
    Worksheet("ws", "Cost Report Worksheet"),
    Live("live", "Live CR"),
}

/** The Live CR pane's own tabs. Hot Costs is switched off on the web, so it is not offered here. */
enum class LiveTab(val id: String, val label: String) {
    Current("current", "Current CR"),
    History("history", "CR History"),
}

/** What a pane is fetching — the loader's caption. */
enum class CrPhase { Init, Snapshot, Live }

/** Company, budget, currency and version: what is picked, or what the figures were last computed for. */
data class CrFilters(
    /** Null is "All Companies — Consolidated", which the server takes as no filter. */
    val companyId: String? = null,
    /** The budget's `version` string. */
    val budgetKey: String? = null,
    val currency: String? = null,
    /** Null is "— Latest (unsaved) —". */
    val versionId: String? = null,
)

/**
 * One pane's report.
 *
 * [pending] is what the dropdowns show and [applied] what the figures were
 * computed against; they part the moment a dropdown changes and meet again on
 * Compute, so five quick changes cost one fetch. The week is not part of that
 * split — the stepper moves both at once and the pane re-fetches immediately.
 */
data class CrPane(
    val pending: CrFilters = CrFilters(),
    val applied: CrFilters = CrFilters(),
    val week: WeekWindow? = null,
    val versions: List<EtcVersion> = emptyList(),
    val phase: CrPhase? = null,
    /** A background re-pull — the figures stay on screen without a loader over them. */
    val silent: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
    val sections: List<CrSection> = emptyList(),
    val serverCurrency: String? = null,
    val fromSnapshot: SnapshotHeader? = null,
    val overrides: CrOverrides = CrOverrides.NONE,
    val baseline: VtpBaseline = VtpBaseline.NONE,
    /** Compute was pressed and its fetch has not settled — the button reads "Computing…". */
    val computing: Boolean = false,
) {
    val dirty: Boolean get() = pending != applied
    val loading: Boolean get() = phase != null
    val forecast: CrForecast get() = CrForecast(overrides, baseline)

    /** What the grid's loader says, by phase. */
    val loaderMessage: String
        get() = when (phase) {
            CrPhase.Snapshot -> "Fetching ${week?.range.orEmpty()} report"
            CrPhase.Live -> "Computing live report"
            CrPhase.Init, null -> "Loading…"
        }
}

/** How a pane's grid is being looked at. */
data class CrTableView(
    val search: String = "",
    val toggles: TreeToggles = TreeToggles(),
    val viewMode: CrViewMode = CrViewMode.Headers,
    val filter: CrLineFilter = CrLineFilter.All,
    val sort: CrSort = CrSort(),
    /** The Figures control: whole pounds, one decimal or pennies. */
    val decimals: Int = 0,
)

/** The chart, budgets, companies and currencies — fetched once, shared by both panes. */
data class CrReference(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val coa: List<CoaRow> = emptyList(),
    val budgets: List<BudgetVersion> = emptyList(),
    val companies: List<CrCompany> = emptyList(),
    val currencies: CurrencyOptions = CurrencyOptions(),
    val catalogue: List<CrCurrency> = emptyList(),
) {
    val hasCoa: Boolean get() = coa.isNotEmpty()
    val hasBudget: Boolean get() = budgets.isNotEmpty()

    /** Loaded, and missing the chart or a budget — the report cannot run until the accountant adds them. */
    val metaMissing: Boolean get() = loaded && (!hasCoa || !hasBudget)

    /** `Please upload Chart of Accounts and Budget to continue.` */
    val metaMissingMessage: String
        get() = "Please upload ${listOfNotNull(
            "Chart of Accounts".takeIf { !hasCoa },
            "Budget".takeIf { !hasBudget },
        ).joinToString(" and ")} to continue."

    val rates: CurrencyRates get() = currencies.rates

    fun budget(key: String?): BudgetVersion? = budgets.firstOrNull { it.version == key }

    /** LIVE, then APPROVED, then the first — the budget a pane starts on. */
    val initialBudgetKey: String?
        get() = (budgets.firstOrNull { it.status == "LIVE" } ?: budgets.firstOrNull { it.status == "APPROVED" }
            ?: budgets.firstOrNull())?.version

    /** The project's list, else the preset catalogue, else the default alone. */
    val currencyChoices: List<CrCurrency>
        get() = currencies.currencies.ifEmpty { catalogue }
            .ifEmpty { listOfNotNull(currencies.defaultCode?.let { CrCurrency(code = it) }) }

    fun symbolFor(code: String?): String {
        val wanted = code?.trim()?.uppercase().orEmpty()
        if (wanted.isEmpty()) return Money.symbol(currencies.defaultCode ?: DEFAULT_CURRENCY)
        return currencies.currencies.symbolOf(wanted) ?: catalogue.symbolOf(wanted) ?: Money.symbol(wanted)
    }

    private fun List<CrCurrency>.symbolOf(code: String): String? =
        firstOrNull { it.code.equals(code, ignoreCase = true) }?.symbol?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_CURRENCY = "GBP"
    }
}

/** The posted-snapshot list, shared by the CR History tab and the History panel. */
data class SnapshotFeed(
    val rows: List<SnapshotHeader> = emptyList(),
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val filter: PostedFilter = PostedFilter.All,
)

/** The Publish dialog's fields, seeded from what the worksheet is showing. */
data class PublishForm(
    val cadence: PostCadence = PostCadence.Daily,
    val companyId: String? = null,
    /** Null is "Live (current)" — the server's live budget. */
    val budgetKey: String? = null,
    val currency: String? = null,
    /** Null is "None — no ETC overrides". */
    val etcVersionId: String? = null,
    val versions: List<EtcVersion> = emptyList(),
    /** `YYYY-MM-DD`, a custom post's inclusive bounds. */
    val startDate: String = "",
    val endDate: String = "",
    val note: String = "",
)

/** The worksheet's dialogs. One at a time, as on the web. */
sealed interface WorksheetModal {
    data class Lock(val note: String = "") : WorksheetModal
    data class Export(val format: ExportFormat = ExportFormat.Pdf) : WorksheetModal
    data class SaveVersion(val label: String = "") : WorksheetModal
    data class Publish(val form: PublishForm) : WorksheetModal
    data object Overages : WorksheetModal
    data object History : WorksheetModal
}

enum class ProgressStatus { Loading, Success, Error }

/** The top-right card that tracks a long write after its dialog has closed. */
data class CrProgress(val status: ProgressStatus, val title: String, val detail: String)

/** A header forecast to finish over budget, for the Overages dialog. */
data class CrOverage(val header: CrHeader, val figures: CrFigures)

data class WorksheetUiState(
    val viewer: CostReportViewer = CostReportViewer(),
    val projectName: String = "",
    /** "Name · Designation" of whoever is signed in — credited on an export. */
    val generatedBy: String? = null,
    val pane: WorksheetPane = WorksheetPane.Worksheet,
    val liveTab: LiveTab = LiveTab.Current,
    val reference: CrReference = CrReference(),
    val ws: CrPane = CrPane(),
    val wsView: CrTableView = CrTableView(),
    val live: CrPane = CrPane(),
    val liveView: CrTableView = CrTableView(),
    val snapshots: SnapshotFeed = SnapshotFeed(),
    val lock: CrLockState = CrLockState(),
    val modal: WorksheetModal? = null,
    /** Save (update the selected version in place) is in flight. */
    val saving: Boolean = false,
    /** Save Version is in flight — the dialog stays up with its spinner. */
    val savingVersion: Boolean = false,
    val posting: PostCadence? = null,
    val locking: Boolean = false,
    val exporting: Boolean = false,
    /** The export card: generating, then generated. */
    val exportDone: Boolean = false,
    val progress: CrProgress? = null,
    val ledger: LedgerView? = null,
    /** A posted snapshot opened from the CR History tab. */
    val snapshot: SnapshotView? = null,
    /** A feeder tool approved or posted something; the figures may be stale until refreshed. */
    val sourceStale: Boolean = false,
    /** The Overages dialog's notes for the producer, by header code. Local, as on the web. */
    val flagNotes: Map<String, String> = emptyMap(),
) {
    /** Whether the worksheet's applied week falls inside the cost-report lock. */
    val isLocked: Boolean get() = ws.week?.let(lock::isLocked) == true

    /** The worksheet's over-budget headers, held steady while a compute is in flight. */
    val overages: List<CrOverage>
        get() = ws.forecast.overBudgetHeaders(ws.sections).map { (header, figures) -> CrOverage(header, figures) }

    fun symbolFor(pane: CrPane): String = reference.symbolFor(pane.serverCurrency ?: pane.applied.currency)
}
