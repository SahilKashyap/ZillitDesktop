package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta

/** What every block may need beyond its own data: the page's currency, the strip's modules, and where clicks go. */
internal class BlockContext(
    val currency: String?,
    val modules: List<AnalyticsModuleMeta>,
    val onSelect: (String) -> Unit,
    val onLink: (String) -> Unit,
) {
    fun meta(id: String): AnalyticsModuleMeta? = modules.firstOrNull { it.id == id }
}

/** Blocks top to bottom, twenty apart. */
@Composable
internal fun BlockList(blocks: List<AnalyticsBlock>, context: BlockContext, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        blocks.forEach { AnalyticsBlockView(it, context) }
    }
}

/** The registry: one widget per block type. A block with nothing to show draws nothing. */
@Composable
internal fun AnalyticsBlockView(block: AnalyticsBlock, context: BlockContext) {
    when (block) {
        is AnalyticsBlock.KpiRow -> KpiRowBlock(block, context)
        is AnalyticsBlock.Forecast -> ForecastPanel(block, context)
        is AnalyticsBlock.Bars -> BarsBlock(block, context)
        is AnalyticsBlock.StackedBars -> StackedBarsBlock(block, context)
        is AnalyticsBlock.Trend -> TrendBlock(block, context)
        is AnalyticsBlock.Donut -> DonutBlock(block, context)
        is AnalyticsBlock.HBars -> HBarsBlock(block, context)
        is AnalyticsBlock.Table -> TableBlock(block, context)
        is AnalyticsBlock.MethodCards -> MethodCardsBlock(block, context)
        is AnalyticsBlock.GaugeCards -> GaugeCardsBlock(block, context)
        is AnalyticsBlock.Alerts -> AlertsBlock(block, context)
        is AnalyticsBlock.ModuleForecast -> ModuleForecastBlock(block, context)
        is AnalyticsBlock.SnapshotCards -> SnapshotCardsBlock(block, context)
        is AnalyticsBlock.Row -> RowBlock(block, context)
    }
}

/**
 * `row` / `grid`: children across a CSS grid template, left to right and
 * wrapping per track count. Top-aligned unless the server asks to stretch.
 */
@Composable
private fun RowBlock(block: AnalyticsBlock.Row, context: BlockContext) {
    if (block.blocks.isEmpty()) return
    val gap = (block.gap ?: DEFAULT_ROW_GAP).dp
    val auto = autoGridMin(block.cols)
    if (auto != null) {
        FlowGrid(minCell = auto.first, gap = gap, fill = auto.second, stretch = block.stretch) {
            block.blocks.forEach { AnalyticsBlockView(it, context) }
        }
        return
    }
    TemplateGrid(tracks = parseGridTemplate(block.cols).orEmpty(), gap = gap, stretch = block.stretch) {
        block.blocks.forEach { AnalyticsBlockView(it, context) }
    }
}

private const val DEFAULT_ROW_GAP = 20.0

@Composable
private fun KpiRowBlock(block: AnalyticsBlock.KpiRow, context: BlockContext) {
    if (block.items.isEmpty()) return
    val colors = analyticsColors
    FlowGrid(minCell = 212.dp, gap = 16.dp) {
        block.items.forEach { item ->
            val ratio = item.fmt == "ratio"
            KpiCard(
                label = item.label,
                value = if (ratio) {
                    AnalyticsFormat.int(item.value?.let(AnalyticsFormat::toNumber))
                } else {
                    AnalyticsFormat.value(item.value, item.fmt, context.currency)
                },
                unit = if (ratio) "/${AnalyticsFormat.int(item.ratioOf)}" else item.suffix,
                sub = item.sub,
                delta = AnalyticsFormat.delta(item.delta, item.deltaFmt, item.fmt, context.currency),
                deltaTone = item.deltaTone,
                spark = item.spark,
                sparkColor = item.sparkTone?.let(colors::toneHex) ?: colors.amber,
                valueColor = item.valueTone?.let { colors.tone(it).ink },
            )
        }
    }
}

@Composable
private fun BarsBlock(block: AnalyticsBlock.Bars, context: BlockContext) {
    if (block.labels.isEmpty()) return
    val colors = analyticsColors
    val color = colors.css(block.color) ?: colors.toneHex(block.tone)
    Panel {
        block.heading.title?.takeIf { it.isNotBlank() }?.let {
            SectionLabel(block.heading.dot?.let(colors::toneHex) ?: color, it, block.heading.sub)
        }
        BarsChart(
            labels = block.labels,
            bars = block.series.map { ChartBar(listOf(ChartPart("", it, color))) },
            format = { AnalyticsFormat.value(it, block.fmt ?: "money", context.currency) },
        )
    }
}

@Composable
private fun StackedBarsBlock(block: AnalyticsBlock.StackedBars, context: BlockContext) {
    if (block.labels.isEmpty()) return
    val colors = analyticsColors
    Panel {
        val legend = block.legend.map { (colors.css(it.color) ?: colors.ink3) to it.label }
        OptionalSectionLabel(
            dot = block.heading.dot?.let(colors::toneHex) ?: colors.amber,
            title = block.heading.title,
            sub = block.heading.sub,
            right = legend.takeIf { it.isNotEmpty() }?.let { { Swatches(it) } },
        )
        BarsChart(
            labels = block.labels,
            bars = block.stacks.map { stack ->
                ChartBar(stack.map { ChartPart(it.label, it.value, colors.css(it.color) ?: colors.amber) })
            },
            format = { AnalyticsFormat.value(it, block.fmt ?: "money", context.currency) },
        )
    }
}

@Composable
private fun TrendBlock(block: AnalyticsBlock.Trend, context: BlockContext) {
    if (block.labels.isEmpty() || block.series.isEmpty()) return
    val colors = analyticsColors
    val lines = block.series.map {
        ChartLine(it.label, colors.css(it.color) ?: colors.amber, it.data, fill = it.fill, dashed = it.dashed)
    }
    Panel {
        block.heading.title?.takeIf { it.isNotBlank() }?.let { title ->
            SectionLabel(
                dot = block.heading.dot?.let(colors::toneHex) ?: colors.amber,
                title = title,
                sub = block.heading.sub,
                right = { Swatches(lines.map { it.color to it.label }) },
            )
        }
        TrendChart(
            labels = block.labels,
            series = lines,
            budget = block.budget,
            format = { AnalyticsFormat.value(it, block.fmt ?: "money", context.currency) },
        )
    }
}

@Composable
private fun DonutBlock(block: AnalyticsBlock.Donut, context: BlockContext) {
    if (block.segments.isEmpty()) return
    val colors = analyticsColors
    val total = block.segments.sumOf { it.value }
    val parts = block.segments.map { ChartPart(it.label, it.value, colors.css(it.color) ?: colors.ink3) }
    Panel {
        block.heading.title?.takeIf { it.isNotBlank() }?.let {
            SectionLabel(block.heading.dot?.let(colors::toneHex) ?: colors.amber, it, block.heading.sub)
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DonutChart(
                parts = parts,
                centerLabel = block.centerValue?.let { AnalyticsFormat.money(it, context.currency) },
                centerSub = block.centerSub,
                describe = { part ->
                    block.segments.firstOrNull { it.label == part.label }?.display
                        ?: AnalyticsFormat.money(part.value, context.currency)
                },
            )
            Legend(
                items = parts.map {
                    LegendItem(
                        it.label,
                        it.color,
                        AnalyticsFormat.money(it.value, context.currency),
                        AnalyticsFormat.share(it.value, total),
                    )
                },
                cols = block.legendCols,
            )
        }
    }
}

@Composable
private fun HBarsBlock(block: AnalyticsBlock.HBars, context: BlockContext) {
    if (block.rows.isEmpty()) return
    val colors = analyticsColors
    val color = colors.css(block.color) ?: colors.toneHex(block.tone)
    val total = block.rows.sumOf { it.value }
    Panel {
        block.heading.title?.takeIf { it.isNotBlank() }?.let {
            SectionLabel(block.heading.dot?.let(colors::toneHex) ?: color, it, block.heading.sub)
        }
        HBarList(
            rows = block.rows.map {
                HBarItem(
                    label = it.label,
                    code = it.code,
                    value = it.value,
                    color = colors.css(it.color) ?: color,
                    display = AnalyticsFormat.money(it.value, context.currency),
                    pct = AnalyticsFormat.share(it.value, total),
                )
            },
        )
    }
}
