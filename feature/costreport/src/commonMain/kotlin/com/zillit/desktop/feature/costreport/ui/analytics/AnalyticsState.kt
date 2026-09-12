package com.zillit.desktop.feature.costreport.ui.analytics

import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsOption
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsQuery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** The page's content: the overview or one module. */
enum class AnalyticsStatus { Loading, Ready, Empty, Error }

/** A module tab fetched on first open. */
enum class TabStatus { Idle, Loading, Error }

/** The filter panel's period choices; "current period" waits on a period window the page does not have. */
enum class AnalyticsPeriod(val label: String) { All("All time"), Range("Date range") }

/** A project currency as the currency chips show it. */
data class AnalyticsCurrency(val code: String, val symbol: String) {
    val chipLabel: String get() = if (symbol.isNotBlank() && symbol != code) "$code · $symbol" else code
}

/** What the filter panel offers. */
data class AnalyticsFilterOptions(
    val currencies: List<AnalyticsCurrency> = emptyList(),
    /** The project default — what is sent until another currency is picked. */
    val defaultCurrency: String = FALLBACK_CURRENCY,
    val entities: List<AnalyticsOption> = emptyList(),
    val departments: List<AnalyticsOption> = emptyList(),
    val units: List<AnalyticsOption> = emptyList(),
) {
    companion object {
        const val FALLBACK_CURRENCY = "GBP"
    }
}

/**
 * The panel's selection. Picking chips changes only this; nothing is fetched
 * until Done, which turns it into the [AnalyticsQuery] every read carries.
 */
data class AnalyticsFilterDraft(
    val period: AnalyticsPeriod = AnalyticsPeriod.All,
    /** `YYYY-MM-DD`, or blank. */
    val dateFrom: String = "",
    val dateTo: String = "",
    /** The company id, or blank for all companies. */
    val entity: String = "",
    val departmentIds: List<String> = emptyList(),
    val unitIds: List<String> = emptyList(),
    /** Blank means the project default. */
    val currency: String = "",
) {
    fun effectiveCurrency(options: AnalyticsFilterOptions): String =
        currency.ifBlank { options.defaultCurrency }

    /** The count on the Filters button: each department and unit, plus period, company and a non-default currency. */
    fun activeCount(options: AnalyticsFilterOptions): Int =
        departmentIds.size + unitIds.size +
            (if (period != AnalyticsPeriod.All) 1 else 0) +
            (if (entity.isNotBlank()) 1 else 0) +
            (if (currency.isNotBlank() && currency != options.defaultCurrency) 1 else 0)

    fun toQuery(options: AnalyticsFilterOptions): AnalyticsQuery {
        val range = period == AnalyticsPeriod.Range
        return AnalyticsQuery(
            currency = effectiveCurrency(options).ifBlank { null },
            departmentIds = departmentIds,
            unitIds = unitIds,
            entity = entity.ifBlank { null },
            fromMs = if (range) utcStartOf(dateFrom) else null,
            toMs = if (range) utcStartOf(dateTo)?.plus(LAST_SECOND_OF_DAY_MS) else null,
        )
    }

    private fun utcStartOf(date: String): Long? =
        runCatching { LocalDate.parse(date.trim()).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()

    private companion object {
        /** `T23:59:59Z` — the web's range end, whole seconds. */
        const val LAST_SECOND_OF_DAY_MS = 86_399_000L
    }
}

data class AnalyticsUiState(
    /** [OVERVIEW] or a module id. */
    val selected: String = OVERVIEW,
    val modules: List<AnalyticsModuleMeta> = emptyList(),
    val status: AnalyticsStatus = AnalyticsStatus.Loading,
    val error: String? = null,
    val page: AnalyticsPage? = null,
    val activeTab: String? = null,
    val tabBlocks: Map<String, List<AnalyticsBlock>> = emptyMap(),
    val tabStatus: TabStatus = TabStatus.Idle,
    val options: AnalyticsFilterOptions = AnalyticsFilterOptions(),
    val draft: AnalyticsFilterDraft = AnalyticsFilterDraft(),
    /** What the page on screen was fetched with; null until the first fetch. */
    val applied: AnalyticsQuery? = null,
    val filtersOpen: Boolean = false,
) {
    val isOverview: Boolean get() = selected == OVERVIEW

    /** The page's currency — the response's own, else the one asked for. */
    val currency: String? get() = page?.currency ?: applied?.currency

    /** The heading word before "Analytics". */
    val titleWord: String
        get() = when {
            isOverview -> "Overview"
            else -> page?.title ?: AnalyticsTitles.titleFor(selected) ?: "Analytics"
        }

    val subtitle: String
        get() = page?.subtitle ?: AnalyticsTitles.subtitleFor(selected).orEmpty()

    /** The open tab's blocks: inline in the module, or fetched and cached. */
    val activeTabBlocks: List<AnalyticsBlock>?
        get() {
            val tab = page?.tabs?.firstOrNull { it.id == activeTab } ?: return null
            return tab.blocks ?: tabBlocks[tab.id]
        }

    companion object {
        const val OVERVIEW = "overview"
    }
}

/** The web's fallback titles, for a response that omits its own. */
object AnalyticsTitles {
    private val TITLES = mapOf(
        "overview" to ("Overview" to "Spend, forecast and exceptions across every module"),
        "payroll" to ("Payroll" to "Gross, overtime, penalties, premiums and turnaround analytics"),
        "po" to ("Purchase Orders" to "Commitments, pipeline and vendor spend"),
        "extras" to (
            "Extras / Daily Crew" to
                "Bookings, spend, overtime, penalties, premiums and turnarounds for daily crew"
            ),
        "invoices" to ("Invoices" to "Settlement by BACS, Faster Payments, wires and cheques"),
        "petty" to ("Petty Cash" to "Floats, reconciliation and category spend"),
        "oop" to ("Out of Pocket" to "Claims reimbursed via BACS and payroll"),
        "prodcards" to (
            "Production Expense Cards" to
                "Company card spend by holder, department and merchant category"
            ),
    )

    fun titleFor(id: String): String? = TITLES[id]?.first
    fun subtitleFor(id: String): String? = TITLES[id]?.second
}

sealed interface AnalyticsEvent {
    data object Back : AnalyticsEvent
    data object Retry : AnalyticsEvent

    /** The strip, an alert, a module card or a forecast row: [id] is a module id or the overview. */
    data class Select(val id: String) : AnalyticsEvent
    data class SelectTab(val id: String) : AnalyticsEvent

    /** A table link or a `link` cell. */
    data class OpenLink(val href: String) : AnalyticsEvent

    data object ToggleFilters : AnalyticsEvent

    /** The backdrop: closes the panel and keeps the picks, unapplied. */
    data object CloseFilters : AnalyticsEvent
    data class SetPeriod(val period: AnalyticsPeriod) : AnalyticsEvent
    data class SetDateFrom(val date: String) : AnalyticsEvent
    data class SetDateTo(val date: String) : AnalyticsEvent
    data class SetCurrency(val code: String) : AnalyticsEvent
    data class SetEntity(val value: String) : AnalyticsEvent
    data class SetDepartments(val ids: List<String>) : AnalyticsEvent
    data class ToggleUnit(val id: String) : AnalyticsEvent
    data object AllUnits : AnalyticsEvent
    data object ResetFilters : AnalyticsEvent

    /** Done: applies the picks and closes the panel. */
    data object ApplyFilters : AnalyticsEvent
}

sealed interface AnalyticsEffect {
    data object Back : AnalyticsEffect
    data class OpenLink(val href: String) : AnalyticsEffect
}
