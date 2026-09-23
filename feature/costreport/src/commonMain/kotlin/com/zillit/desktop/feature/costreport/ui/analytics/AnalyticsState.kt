package com.zillit.desktop.feature.costreport.ui.analytics

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
enum class AnalyticsPeriod(private val labelKey: String) {
    All(S.desktop_all_time),
    Range(S.cs_date_range),
    ;

    val label: String get() = str(labelKey)
}

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
            isOverview -> str(S.ah_overview)
            else -> page?.title ?: AnalyticsTitles.titleFor(selected) ?: str(S.analytics)
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
    private val TITLE_KEYS = mapOf(
        "overview" to (S.ah_overview to S.desktop_cr_overview_sub),
        "payroll" to (S.dm_section_payroll to S.desktop_cr_payroll_sub),
        "po" to (S.ah_purchase_orders to S.desktop_cr_po_sub),
        "extras" to (S.desktop_cr_extras to S.desktop_cr_extras_sub),
        "invoices" to (S.ah_invoices to S.desktop_cr_invoices_sub),
        "petty" to (S.desktop_petty_cash to S.desktop_cr_petty_sub),
        "oop" to (S.desktop_ce_out_of_pocket to S.desktop_cr_oop_sub),
        "prodcards" to (S.ah_card_expenses to S.desktop_cr_prodcards_sub),
    )

    fun titleFor(id: String): String? = TITLE_KEYS[id]?.first?.let { str(it) }
    fun subtitleFor(id: String): String? = TITLE_KEYS[id]?.second?.let { str(it) }
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
