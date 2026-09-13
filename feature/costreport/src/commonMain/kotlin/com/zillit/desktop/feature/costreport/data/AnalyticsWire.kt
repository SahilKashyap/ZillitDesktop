package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsTab
import com.zillit.desktop.feature.costreport.domain.analytics.BlockHeading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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

// -- readers -----------------------------------------------------------------------------------

/** `data`, or the `value` some services of this family wrap it in. */
internal fun unwrapValue(data: JsonElement?): JsonElement? {
    val value = (data as? JsonObject)?.get("value")
    return if (value is JsonObject || value is JsonArray) value else data
}

internal fun JsonArray?.objects(): List<JsonObject> = orEmpty().mapNotNull { it as? JsonObject }

internal fun numbers(element: JsonElement?): List<Double> = (element as? JsonArray).orEmpty().mapNotNull(::numberOf)

internal fun strings(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content.orEmpty() }

/** Module ids, as strings or as objects carrying an `id`. */
internal fun moduleIds(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull {
    when (it) {
        is JsonObject -> it.str("id", "module")
        is JsonPrimitive -> it.takeIf { p -> p !is JsonNull }?.content?.takeIf(String::isNotBlank)
        else -> null
    }
}

/** A field as the web would print it: strings as sent, numbers in JavaScript's spelling, nothing else. */
internal fun raw(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    return when {
        primitive is JsonNull -> null
        primitive.isString -> primitive.content
        primitive.booleanOrNull != null -> null
        else -> primitive.doubleOrNull?.let(AnalyticsFormat::jsNumber)
    }
}

internal fun flag(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: (primitive.content == "1" || primitive.content == "true")
}

private const val LEGACY_PAIR_COLS = "1.6fr 1fr"
private val MODULE_LIST_KEYS = listOf("modules", "items", "data")
