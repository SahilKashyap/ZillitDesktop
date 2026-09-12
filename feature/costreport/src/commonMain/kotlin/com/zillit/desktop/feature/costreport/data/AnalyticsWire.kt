package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.feature.costreport.domain.analytics.AlertItem
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsTab
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/*
 * The analytics block model as the web's renderer reads it: every list is
 * optional, a malformed item is dropped rather than failing the page, and a
 * block whose `type` is unknown is skipped — forward-compatible by design.
 */

// -- responses ---------------------------------------------------------------------------------

/** `GET analytics/modules`: the strip's cards, bare or wrapped. */
internal fun parseAnalyticsModules(data: JsonElement?): List<AnalyticsModuleMeta> =
    when (val list = unwrapValue(data)) {
        is JsonArray -> list
        is JsonObject -> MODULE_LIST_KEYS.firstNotNullOfOrNull { list[it] as? JsonArray }
        else -> null
    }
        .objects()
        .mapNotNull { row ->
            val id = row.str("id") ?: return@mapNotNull null
            AnalyticsModuleMeta(
                id = id,
                label = row.str("label", "title") ?: id,
                icon = row.str("icon"),
                tone = row.str("tone"),
                total = row.num("total"),
                currency = row.str("currency"),
                delta = row.num("delta"),
                deltaFmt = row.str("deltaFmt"),
                deltaTone = row.str("deltaTone"),
                spark = numbers(row["spark"]),
            )
        }

/**
 * `GET analytics/overview` and `GET analytics/{module}`. An overview that
 * sends the older named fields (`kpis`, `projection`, `groups`,
 * `moduleForecasts`, `alerts`, `snapshots`) instead of `blocks` is turned
 * into the same block list, laid out in the design's pairs — one rendering path.
 */
internal fun parseAnalyticsPage(data: JsonElement?, overview: Boolean = false): AnalyticsPage? {
    val page = unwrapValue(data) as? JsonObject ?: return null
    val blocks = parseBlocks(page["blocks"])
    return AnalyticsPage(
        id = page.str("id"),
        title = page.str("title"),
        subtitle = page.str("subtitle"),
        currency = page.str("currency"),
        blocks = if (overview && blocks.isEmpty()) legacyOverviewBlocks(page) else blocks,
        tabs = (page["tabs"] as? JsonArray).objects().mapNotNull { tab ->
            val id = tab.str("id") ?: return@mapNotNull null
            AnalyticsTab(
                id = id,
                label = tab.str("label") ?: id,
                blocks = (tab["blocks"] as? JsonArray)?.let(::parseBlocks),
            )
        },
    )
}

private fun legacyOverviewBlocks(page: JsonObject): List<AnalyticsBlock> = buildList {
    val kpis = (page["kpis"] as? JsonArray).objects().map(::parseKpi)
    if (kpis.isNotEmpty()) add(AnalyticsBlock.KpiRow(kpis))

    val projection = (page["projection"] as? JsonObject)?.let {
        AnalyticsBlock.Forecast(title = null, data = parseForecast(it), projection = true)
    }
    val groupSegments = (page["groups"] as? JsonArray).objects().map(::parseSegment)
    val groups = groupSegments.takeIf { it.isNotEmpty() }?.let { segments ->
        AnalyticsBlock.Donut(
            heading = BlockHeading(title = "Cost to Date by Group"),
            segments = segments,
            centerValue = segments.sumOf { it.value },
            centerSub = "COST",
            legendCols = 1,
        )
    }
    addPair(projection, groups)

    val forecasts = (page["moduleForecasts"] as? JsonArray).objects().mapNotNull(::parseModuleForecastRow)
    val moduleForecast = forecasts.takeIf { it.isNotEmpty() }?.let { AnalyticsBlock.ModuleForecast(null, null, it) }
    val alertItems = (page["alerts"] as? JsonArray).objects().mapNotNull(::parseAlert)
    val alerts = alertItems.takeIf { it.isNotEmpty() }?.let { AnalyticsBlock.Alerts(BlockHeading(), it) }
    addPair(moduleForecast, alerts)

    val snapshots = moduleIds(page["snapshots"])
    if (snapshots.isNotEmpty()) {
        add(
            AnalyticsBlock.SnapshotCards(
                heading = BlockHeading(title = "By Module", sub = "12-week trend · open for full analytics"),
                modules = snapshots,
            ),
        )
    }
}

/** Two blocks side by side at 1.6 : 1 when both exist, else whichever does. */
private fun MutableList<AnalyticsBlock>.addPair(left: AnalyticsBlock?, right: AnalyticsBlock?) {
    when {
        left != null && right != null -> add(AnalyticsBlock.Row(LEGACY_PAIR_COLS, null, false, listOf(left, right)))
        left != null -> add(left)
        right != null -> add(right)
    }
}

// -- blocks ------------------------------------------------------------------------------------

internal fun parseBlocks(element: JsonElement?): List<AnalyticsBlock> =
    (element as? JsonArray).objects().mapNotNull(::parseBlock)

@Suppress("CyclomaticComplexMethod") // One branch per block type.
internal fun parseBlock(block: JsonObject): AnalyticsBlock? {
    val heading = BlockHeading(title = block.str("title"), sub = block.str("sub"), dot = block.str("dot"))
    return when (block.str("type")) {
        "kpi-row" -> AnalyticsBlock.KpiRow((block["items"] as? JsonArray).objects().map(::parseKpi))
        "forecast", "projection" -> (block["data"] as? JsonObject)?.let {
            AnalyticsBlock.Forecast(block.str("title"), parseForecast(it), projection = block.str("type") == "projection")
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
}

private fun parseKpi(item: JsonObject) = KpiItem(
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

private fun parseForecast(data: JsonObject) = ForecastData(
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

private fun parseSegment(item: JsonObject) = ChartSegment(
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

private fun parseAlert(item: JsonObject): AlertItem? {
    val title = item.str("title") ?: return null
    return AlertItem(item.str("tone"), item.str("module", "mod"), title, item.str("meta"))
}

private fun parseModuleForecastRow(item: JsonObject): ModuleForecastRow? {
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

// -- readers -----------------------------------------------------------------------------------

/** `data`, or the `value` some services of this family wrap it in. */
private fun unwrapValue(data: JsonElement?): JsonElement? {
    val value = (data as? JsonObject)?.get("value")
    return if (value is JsonObject || value is JsonArray) value else data
}

private fun JsonArray?.objects(): List<JsonObject> = orEmpty().mapNotNull { it as? JsonObject }

private fun numbers(element: JsonElement?): List<Double> = (element as? JsonArray).orEmpty().mapNotNull(::numberOf)

private fun strings(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content.orEmpty() }

/** Module ids, as strings or as objects carrying an `id`. */
private fun moduleIds(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull {
    when (it) {
        is JsonObject -> it.str("id", "module")
        is JsonPrimitive -> it.takeIf { p -> p !is JsonNull }?.content?.takeIf(String::isNotBlank)
        else -> null
    }
}

/** A field as the web would print it: strings as sent, numbers in JavaScript's spelling, nothing else. */
private fun raw(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    return when {
        primitive is JsonNull -> null
        primitive.isString -> primitive.content
        primitive.booleanOrNull != null -> null
        else -> primitive.doubleOrNull?.let(AnalyticsFormat::jsNumber)
    }
}

private fun flag(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: (primitive.content == "1" || primitive.content == "true")
}

private const val LEGACY_PAIR_COLS = "1.6fr 1fr"
private val MODULE_LIST_KEYS = listOf("modules", "items", "data")
private const val HALF = 0.5
