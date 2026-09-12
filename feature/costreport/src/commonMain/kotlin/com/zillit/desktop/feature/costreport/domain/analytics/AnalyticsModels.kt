package com.zillit.desktop.feature.costreport.domain.analytics

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions

/**
 * The Analytics page's reads — `GET /cost-reports/analytics/…`, the web's
 * `costReportApi.analyticsModules / analyticsOverview / analyticsModule`.
 *
 * The server sends raw numbers, one currency per response and semantic ids
 * (`tone`, `fmt`, `cell`); the client formats them and maps each block type to
 * a widget. A block type this client does not know is skipped, so the server
 * can add one without breaking the page.
 */
interface AnalyticsRepository {
    /** One headline per module — the strip cards. Totals follow the filters. */
    suspend fun modules(query: AnalyticsQuery): ZillitResult<List<AnalyticsModuleMeta>>

    /** The all-modules overview; null when the server has nothing for this selection. */
    suspend fun overview(query: AnalyticsQuery): ZillitResult<AnalyticsPage?>

    /** One module's blocks, or — with [sub] — one of its tabs, loaded when first opened. */
    suspend fun module(id: String, query: AnalyticsQuery, sub: String? = null): ZillitResult<AnalyticsPage?>
}

/**
 * The filter panel's choices: the production's currencies and companies (on
 * the Account Hub host) and its departments and units (on crew and units).
 * A source that fails offers nothing, and the page still loads.
 */
interface AnalyticsFilterSource {
    suspend fun currencies(): CurrencyOptions? = null
    suspend fun companies(): List<CrCompany> = emptyList()
    suspend fun departments(): List<AnalyticsOption> = emptyList()
    suspend fun units(): List<AnalyticsOption> = emptyList()
}

/** A value the server matches on, and what the chip says. */
data class AnalyticsOption(val value: String, val label: String)

/**
 * What every read is scoped to — the web's `outFilters`. They change only the
 * numbers, never the shape. [currency] is always sent (the project default
 * until another is picked); a date range is UTC midnight to 23:59:59.
 */
data class AnalyticsQuery(
    val currency: String? = null,
    val departmentIds: List<String> = emptyList(),
    val unitIds: List<String> = emptyList(),
    val entity: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
) {
    /** The query string, blanks dropped as the web's `qs()` drops them. */
    fun parameters(sub: String? = null): Map<String, String> = listOf(
        "currency" to currency,
        "depts" to departmentIds.joinToString(","),
        "units" to unitIds.joinToString(","),
        "entity" to entity,
        "from" to fromMs?.toString(),
        "to" to toMs?.toString(),
        "sub" to sub,
    ).mapNotNull { (key, value) -> value?.takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()
}

/** A strip card: one module's headline total and its movement. */
data class AnalyticsModuleMeta(
    val id: String,
    val label: String,
    val icon: String? = null,
    val tone: String? = null,
    val total: Double? = null,
    val currency: String? = null,
    val delta: Double? = null,
    val deltaFmt: String? = null,
    val deltaTone: String? = null,
    val spark: List<Double> = emptyList(),
)

/** The overview, one module, or one module tab. */
data class AnalyticsPage(
    val id: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val currency: String? = null,
    val blocks: List<AnalyticsBlock> = emptyList(),
    /** Sub-tabs; one without inline blocks is fetched with `?sub=<id>` when opened. */
    val tabs: List<AnalyticsTab> = emptyList(),
) {
    val hasContent: Boolean get() = blocks.isNotEmpty() || tabs.isNotEmpty()
}

data class AnalyticsTab(val id: String, val label: String, val blocks: List<AnalyticsBlock>? = null)

/** A section heading's parts: the dot's tone, the title and its muted aside. */
data class BlockHeading(val title: String? = null, val sub: String? = null, val dot: String? = null)

/** One server-driven widget. */
sealed interface AnalyticsBlock {

    /** `kpi-row` — a responsive row of KPI cards. */
    data class KpiRow(val items: List<KpiItem>) : AnalyticsBlock

    /** `forecast` and `projection` — EFC against budget, the rail and the cumulative chart. */
    data class Forecast(val title: String?, val data: ForecastData, val projection: Boolean) : AnalyticsBlock

    /** `bars` — one series of vertical bars. */
    data class Bars(
        val heading: BlockHeading,
        val labels: List<String>,
        val series: List<Double>,
        val tone: String?,
        val color: String?,
        val fmt: String?,
    ) : AnalyticsBlock

    /** `stacked-bars` — a stack of coloured segments per label. */
    data class StackedBars(
        val heading: BlockHeading,
        val labels: List<String>,
        val stacks: List<List<ChartSegment>>,
        val legend: List<ChartSegment>,
        val fmt: String?,
    ) : AnalyticsBlock

    /** `trend` — lines (optionally filled or dashed) over periods, with an optional budget line. */
    data class Trend(
        val heading: BlockHeading,
        val labels: List<String>,
        val series: List<TrendSeries>,
        val budget: Double?,
        val fmt: String?,
    ) : AnalyticsBlock

    /** `donut` — a ring with a centre total and a legend. */
    data class Donut(
        val heading: BlockHeading,
        val segments: List<ChartSegment>,
        val centerValue: Double?,
        val centerSub: String?,
        val legendCols: Int,
    ) : AnalyticsBlock

    /** `hbars` — ranked horizontal bars with each row's share. */
    data class HBars(
        val heading: BlockHeading,
        val rows: List<HBarRow>,
        val tone: String?,
        val color: String?,
    ) : AnalyticsBlock

    /** `table` — columns whose `cell` picks a renderer. */
    data class Table(
        val heading: BlockHeading,
        val columns: List<TableColumn>,
        val rows: List<TableRow>,
        val link: TableLink?,
    ) : AnalyticsBlock

    /** `method-cards` — a card per payment method with its share bar. */
    data class MethodCards(val heading: BlockHeading, val methods: List<MethodItem>) : AnalyticsBlock

    /** `gauge-cards` — a card per channel with a mini gauge of its share. */
    data class GaugeCards(val heading: BlockHeading, val total: Double?, val cards: List<MethodItem>) : AnalyticsBlock

    /** `alerts` — exceptions, each opening its module. */
    data class Alerts(val heading: BlockHeading, val alerts: List<AlertItem>) : AnalyticsBlock

    /** `module-forecast` — a budget rail per module; a row opens the module. */
    data class ModuleForecast(val title: String?, val sub: String?, val rows: List<ModuleForecastRow>) : AnalyticsBlock

    /** `snapshot-cards` — one card per module id, headline and sparkline from the strip. */
    data class SnapshotCards(val heading: BlockHeading, val modules: List<String>) : AnalyticsBlock

    /** `row` / `grid` — children laid across a CSS grid template. */
    data class Row(val cols: String?, val gap: Double?, val stretch: Boolean, val blocks: List<AnalyticsBlock>) :
        AnalyticsBlock
}

data class KpiItem(
    val label: String,
    /** The raw value — a number for every `fmt` but `text`. */
    val value: String?,
    val fmt: String? = null,
    val ratioOf: Double? = null,
    val suffix: String? = null,
    val sub: String? = null,
    val delta: Double? = null,
    val deltaFmt: String? = null,
    val deltaTone: String? = null,
    val spark: List<Double> = emptyList(),
    val sparkTone: String? = null,
    val valueTone: String? = null,
)

/**
 * A forecast's raw numbers. [cum] is cumulative actual spend by week and
 * [proj] the forecast from the current week to wrap; [cur] is the current
 * week, one-based, and [total] the weeks in the schedule.
 */
data class ForecastData(
    val tone: String? = null,
    val cum: List<Double> = emptyList(),
    val proj: List<Double> = emptyList(),
    val budget: Double = 0.0,
    val efc: Double? = null,
    val actual: Double = 0.0,
    val committed: Double = 0.0,
    val etc: Double = 0.0,
    val variance: Double? = null,
    val total: Int? = null,
    val cur: Int? = null,
    val wrap: String? = null,
    val driver: String? = null,
)

/** A donut segment, a stacked-bar segment or a legend swatch. */
data class ChartSegment(val label: String, val value: Double, val color: String?, val display: String? = null)

data class TrendSeries(
    val label: String,
    val color: String?,
    /** Null points are gaps — a series may cover only part of the axis. */
    val data: List<Double?>,
    val fill: Boolean,
    val dashed: Boolean,
)

data class HBarRow(val label: String, val code: String?, val value: Double, val color: String?)

data class TableColumn(
    val key: String,
    val label: String,
    val align: String? = null,
    val mono: Boolean = false,
    val bold: Boolean = false,
    val cell: String? = null,
    val fmt: String? = null,
)

/** A table row's fields as the server sent them, numbers in their JavaScript spelling. */
data class TableRow(val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
    fun number(key: String): Double? = values[key]?.toDoubleOrNull()
}

data class TableLink(val label: String, val href: String?)

/** A method card or a gauge card — the two share their fields. */
data class MethodItem(
    val id: String?,
    val label: String,
    val color: String?,
    val value: Double,
    val count: String? = null,
    val avg: Double? = null,
    val note: String? = null,
    /** A gauge's share: a number of percent, or a ready string. */
    val pct: String? = null,
    val display: String? = null,
    val turn: String? = null,
)

data class AlertItem(val tone: String?, val module: String?, val title: String, val meta: String?)

data class ModuleForecastRow(
    val module: String,
    val actual: Double,
    val committed: Double,
    val etc: Double,
    val efc: Double?,
    val budget: Double,
    val variance: Double?,
    val tone: String?,
)
