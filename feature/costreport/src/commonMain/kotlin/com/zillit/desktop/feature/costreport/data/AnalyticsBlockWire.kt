package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.feature.costreport.domain.analytics.AlertItem
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.BlockHeading
import com.zillit.desktop.feature.costreport.domain.analytics.ChartSegment
import com.zillit.desktop.feature.costreport.domain.analytics.ForecastData
import com.zillit.desktop.feature.costreport.domain.analytics.HBarRow
import com.zillit.desktop.feature.costreport.domain.analytics.KpiItem
import com.zillit.desktop.feature.costreport.domain.analytics.MethodItem
import com.zillit.desktop.feature.costreport.domain.analytics.ModuleForecastRow
import com.zillit.desktop.feature.costreport.domain.analytics.TableColumn
import com.zillit.desktop.feature.costreport.domain.analytics.TableLink
import com.zillit.desktop.feature.costreport.domain.analytics.TableRow
import com.zillit.desktop.feature.costreport.domain.analytics.TrendSeries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/* The analytics blocks, one reader per `type` — see [parseAnalyticsPage]. */

internal fun parseBlocks(element: JsonElement?): List<AnalyticsBlock> =
    (element as? JsonArray).objects().mapNotNull(::parseBlock)

/** One block by its `type`; null for a type this client does not draw. */
internal fun parseBlock(block: JsonObject): AnalyticsBlock? {
    val type = block.str("type") ?: return null
    val heading = BlockHeading(title = block.str("title"), sub = block.str("sub"), dot = block.str("dot"))
    return parseChartBlock(type, block, heading) ?: parseListBlock(type, block, heading)
}

/** The figure and chart blocks. */
@Suppress("CyclomaticComplexMethod") // One branch per block type.
private fun parseChartBlock(type: String, block: JsonObject, heading: BlockHeading): AnalyticsBlock? = when (type) {
    "kpi-row" -> AnalyticsBlock.KpiRow((block["items"] as? JsonArray).objects().map(::parseKpi))
    "forecast", "projection" -> (block["data"] as? JsonObject)?.let {
        AnalyticsBlock.Forecast(block.str("title"), parseForecast(it), projection = type == "projection")
    }
    "bars" -> AnalyticsBlock.Bars(
        heading = heading,
        labels = strings(block["labels"]),
        series = numbers(block["series"]),
        tone = block.str("tone"),
        color = block.str("color"),
        fmt = block.str("fmt"),
    )
    "stacked-bars" -> AnalyticsBlock.StackedBars(
        heading = heading,
        labels = strings(block["labels"]),
        stacks = (block["stacks"] as? JsonArray).objects().map { stack ->
            (stack["segments"] as? JsonArray).objects().map(::parseSegment)
        },
        legend = (block["legend"] as? JsonArray).objects().map(::parseSegment),
        fmt = block.str("fmt"),
    )
    "trend" -> AnalyticsBlock.Trend(
        heading = heading,
        labels = strings(block["labels"]),
        series = (block["series"] as? JsonArray).objects().map(::parseTrendSeries),
        budget = block.num("budget"),
        fmt = block.str("fmt"),
    )
    "donut" -> AnalyticsBlock.Donut(
        heading = heading,
        segments = (block["segments"] as? JsonArray).objects().map(::parseSegment),
        centerValue = block.num("centerValue"),
        centerSub = block.str("centerSub"),
        legendCols = block.num("legendCols")?.toInt()?.takeIf { it > 0 } ?: 1,
    )
    "hbars" -> AnalyticsBlock.HBars(
        heading = heading,
        rows = (block["rows"] as? JsonArray).objects().map {
            HBarRow(it.str("label").orEmpty(), it.str("code"), it.num("value") ?: 0.0, it.str("color"))
        },
        tone = block.str("tone"),
        color = block.str("color"),
    )
    else -> null
}

/** The table, card, alert and layout blocks. */
private fun parseListBlock(type: String, block: JsonObject, heading: BlockHeading): AnalyticsBlock? = when (type) {
    "table" -> parseTable(block, heading)
    "method-cards" -> AnalyticsBlock.MethodCards(heading, (block["methods"] as? JsonArray).objects().map(::parseMethod))
    "gauge-cards" -> AnalyticsBlock.GaugeCards(
        heading = heading,
        total = block.num("total"),
        cards = ((block["cards"] ?: block["methods"]) as? JsonArray).objects().map(::parseMethod),
    )
    "alerts" -> AnalyticsBlock.Alerts(heading, (block["alerts"] as? JsonArray).objects().mapNotNull(::parseAlert))
    "module-forecast" -> AnalyticsBlock.ModuleForecast(
        title = block.str("title"),
        sub = block.str("sub"),
        rows = (block["rows"] as? JsonArray).objects().mapNotNull(::parseModuleForecastRow),
    )
    "snapshot-cards" -> AnalyticsBlock.SnapshotCards(heading, moduleIds(block["modules"]))
    "row", "grid" -> AnalyticsBlock.Row(
        cols = block.str("cols"),
        gap = block.num("gap"),
        stretch = block.str("align") == "stretch",
        blocks = parseBlocks(block["blocks"]),
    )
    else -> null
}

internal fun parseKpi(item: JsonObject) = KpiItem(
    label = item.str("label").orEmpty(),
    value = raw(item["value"]),
    fmt = item.str("fmt"),
    ratioOf = item.num("ratioOf"),
    suffix = item.str("suffix"),
    sub = item.str("sub"),
    delta = item.num("delta"),
    deltaFmt = item.str("deltaFmt"),
    deltaTone = item.str("deltaTone"),
    spark = numbers(item["spark"]),
    sparkTone = item.str("sparkTone"),
    valueTone = item.str("valueTone"),
)

internal fun parseForecast(data: JsonObject) = ForecastData(
    tone = data.str("tone"),
    cum = numbers(data["cum"]),
    proj = numbers(data["proj"]),
    budget = data.num("budget") ?: 0.0,
    efc = data.num("efc"),
    actual = data.num("actual") ?: 0.0,
    committed = data.num("committed") ?: 0.0,
    etc = data.num("etc") ?: 0.0,
    variance = data.num("variance"),
    total = data.num("total")?.toInt(),
    cur = data.num("cur")?.toInt(),
    wrap = data.str("wrap"),
    driver = data.str("driver"),
)

internal fun parseSegment(item: JsonObject) = ChartSegment(
    label = item.str("label", "l").orEmpty(),
    value = item.num("value") ?: 0.0,
    color = item.str("color", "c"),
    display = item.str("display"),
)

private fun parseTrendSeries(item: JsonObject) = TrendSeries(
    label = item.str("label").orEmpty(),
    color = item.str("color"),
    data = (item["data"] as? JsonArray).orEmpty().map(::numberOf),
    fill = flag(item["fill"]),
    dashed = flag(item["dashed"]),
)

private fun parseTable(block: JsonObject, heading: BlockHeading): AnalyticsBlock.Table {
    val columns = (block["columns"] as? JsonArray).objects().mapNotNull { column ->
        val key = column.str("key") ?: return@mapNotNull null
        TableColumn(
            key = key,
            label = column.str("label").orEmpty(),
            align = column.str("align"),
            mono = flag(column["mono"]),
            bold = flag(column["bold"]),
            cell = column.str("cell"),
            fmt = column.str("fmt"),
        )
    }
    val rows = (block["rows"] as? JsonArray).objects().map { row ->
        TableRow(row.entries.mapNotNull { (key, value) -> raw(value)?.let { key to it } }.toMap())
    }
    val link = (block["link"] as? JsonObject)?.let { TableLink(it.str("label").orEmpty(), it.str("href")) }
    return AnalyticsBlock.Table(heading, columns, rows, link)
}

private fun parseMethod(item: JsonObject): MethodItem {
    val pct = item["pct"] as? JsonPrimitive
    return MethodItem(
        id = item.str("id"),
        label = item.str("label").orEmpty(),
        color = item.str("color"),
        value = item.num("value") ?: 0.0,
        count = raw(item["count"]),
        avg = item.num("avg"),
        note = item.str("note"),
        // A number is a percent to round; a string is already written.
        pct = when {
            pct == null || pct is JsonNull -> null
            pct.isString -> pct.content
            else -> pct.doubleOrNull?.let { "${floor(it + HALF).toLong()}%" }
        },
        display = item.str("display"),
        turn = item.str("turn"),
    )
}

internal fun parseAlert(item: JsonObject): AlertItem? {
    val title = item.str("title") ?: return null
    return AlertItem(item.str("tone"), item.str("module", "mod"), title, item.str("meta"))
}

internal fun parseModuleForecastRow(item: JsonObject): ModuleForecastRow? {
    val module = item.str("module") ?: return null
    return ModuleForecastRow(
        module = module,
        actual = item.num("actual") ?: 0.0,
        committed = item.num("committed") ?: 0.0,
        etc = item.num("etc") ?: 0.0,
        efc = item.num("efc"),
        budget = item.num("budget") ?: 0.0,
        variance = item.num("variance"),
        tone = item.str("tone"),
    )
}

private const val HALF = 0.5
