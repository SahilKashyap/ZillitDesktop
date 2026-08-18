package com.zillit.desktop.feature.costreport.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.TreeToggles
import com.zillit.desktop.feature.costreport.domain.WeekWindow

/** The Current CR tab: filters, the live tree, and what is open. */
data class CurrentCr(
    /** The loader caption while something is in flight; null when idle. */
    val phase: String? = null,
    val error: String? = null,
    /** COA or budgets empty: the accountant has not set the project up yet. */
    val unavailable: Boolean = false,
    val companies: List<CrCompany> = emptyList(),
    /** Null = "All companies" (omitted from the query). */
    val companyId: String? = null,
    val budgets: List<BudgetVersion> = emptyList(),
    /** The budget's `version` string; null = the project's live budget. */
    val budgetKey: String? = null,
    val currencies: List<CrCurrency> = emptyList(),
    val currencyCode: String? = null,
    val report: LiveReport? = null,
    val sections: List<CrSection> = emptyList(),
    /** The prior weekly snapshot's variance by row identity — the VTP baseline. */
    val priorVariance: Map<String, Double> = emptyMap(),
    val hasPrior: Boolean = false,
    val week: WeekWindow? = null,
    /** When the live report was asked for — the "today" in the subtitle. */
    val todayMs: Long? = null,
    val query: String = "",
    val toggles: TreeToggles = TreeToggles(),
    /** The display currency's symbol, resolved once per load. */
    val symbol: String = "",
) {
    val loaded: Boolean get() = report != null
    val selectedBudget: BudgetVersion? get() = budgets.firstOrNull { it.version == budgetKey }
    val selectedCompany: CrCompany? get() = companies.firstOrNull { it.id == companyId }
    val budgetVersionId: String? get() = selectedBudget?.id
}

/** A posted snapshot as the timeline draws it (spec §4.6). */
data class PostedRow(
    val header: SnapshotHeader,
    val variance: Double?,
    /** Against the chronologically previous post; null for the oldest. */
    val delta: Double?,
)

data class PostedCrs(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
    val rows: List<SnapshotHeader> = emptyList(),
    val filter: PostedFilter = PostedFilter.All,
) {
    /** Newest first with the delta attached, then the pill filter applied. */
    val shown: List<PostedRow>
        get() = rows.mapIndexed { index, header ->
            val older = rows.getOrNull(index + 1)?.totalVariance
            val variance = header.totalVariance
            PostedRow(header, variance, if (variance != null && older != null) variance - older else null)
        }.filter { filter.admits(it.header.kind) }

    val lastPostedMs: Long? get() = rows.firstOrNull()?.postedAtMs
}

/** One posted snapshot opened for reading. */
data class SnapshotView(
    val header: SnapshotHeader,
    val detail: SnapshotDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val sections: List<CrSection> = emptyList(),
    val query: String = "",
    val toggles: TreeToggles = TreeToggles(),
    val exporting: ExportFormat? = null,
    val symbol: String = "",
) {
    val shownHeader: SnapshotHeader get() = detail?.header ?: header
}

/** The ledger drill-down for one account. */
data class LedgerView(
    val nominal: CrNominal,
    val type: LedgerType? = null,
    val source: String? = null,
    val budget: Double = 0.0,
    val symbol: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val result: LedgerResult? = null,
) {
    val subtitle: String
        get() = when (type) {
            null -> "Line items"
            LedgerType.Actuals -> "Actuals"
            LedgerType.Commits -> "Commits · PO/Card/Cash/Payroll"
        }
}

data class CostReportUiState(
    val viewer: CostReportViewer = CostReportViewer(),
    val tab: CostReportTab = CostReportTab.Current,
    val projectName: String = "",
    val referenceLoading: Boolean = false,
    val referenceError: String? = null,
    val coa: List<CoaRow> = emptyList(),
    val currencyCatalogue: List<CrCurrency> = emptyList(),
    val current: CurrentCr = CurrentCr(),
    val posted: PostedCrs = PostedCrs(),
    val snapshot: SnapshotView? = null,
    val ledger: LedgerView? = null,
) {
    /** The project list first, the preset catalogue when the project has none, then the static table. */
    fun symbolFor(code: String?): String {
        val wanted = (code?.takeIf { it.isNotBlank() } ?: current.currencyCode)?.trim()?.uppercase().orEmpty()
        if (wanted.isEmpty()) return ""
        return current.currencies.symbolOf(wanted) ?: currencyCatalogue.symbolOf(wanted) ?: Money.symbol(wanted)
    }

    /** What the Currency select offers: the project's list, else the catalogue, else the default alone. */
    val currencyChoices: List<CrCurrency>
        get() = current.currencies.ifEmpty { currencyCatalogue }
            .ifEmpty { listOfNotNull(current.currencyCode?.let { CrCurrency(code = it) }) }
}

private fun List<CrCurrency>.symbolOf(code: String): String? =
    firstOrNull { it.code.equals(code, ignoreCase = true) }?.symbol?.takeIf { it.isNotBlank() }

sealed interface CostReportEvent {
    data class SelectTab(val tab: CostReportTab) : CostReportEvent
    data object Refresh : CostReportEvent
    data object DismissError : CostReportEvent

    data class SelectCompany(val companyId: String?) : CostReportEvent
    data class SelectBudget(val budgetKey: String?) : CostReportEvent
    data class SelectCurrency(val code: String?) : CostReportEvent
    data class SearchCurrent(val query: String) : CostReportEvent
    data class ToggleSection(val sectionId: String) : CostReportEvent
    data class ToggleHeader(val key: String) : CostReportEvent
    data class ToggleNominal(val key: String) : CostReportEvent

    /** The code cell (`column == null`) or an actuals/commits cell. */
    data class OpenLedger(val nominal: CrNominal, val column: CrColumn?) : CostReportEvent
    data object CloseLedger : CostReportEvent

    data class SelectPostedFilter(val filter: PostedFilter) : CostReportEvent
    data object RefreshPosted : CostReportEvent
    data class OpenSnapshot(val header: SnapshotHeader) : CostReportEvent
    data object CloseSnapshot : CostReportEvent
    data class SearchSnapshot(val query: String) : CostReportEvent
    data class ToggleSnapshotSection(val sectionId: String) : CostReportEvent
    data class ToggleSnapshotHeader(val key: String) : CostReportEvent
    data class ToggleSnapshotNominal(val key: String) : CostReportEvent
    data class Export(val format: ExportFormat) : CostReportEvent
}

sealed interface CostReportEffect {
    data class Notice(val text: String) : CostReportEffect
}
