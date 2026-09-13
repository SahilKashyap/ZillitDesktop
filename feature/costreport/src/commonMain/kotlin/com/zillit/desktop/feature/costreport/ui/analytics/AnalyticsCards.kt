package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.analytics.AlertItem
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.MethodItem
import kotlin.math.max
import kotlin.math.roundToInt

/** `method-cards`: one card per payment method — its amount, share of the whole, count and average. */
@Composable
internal fun MethodCardsBlock(block: AnalyticsBlock.MethodCards, context: BlockContext) {
    if (block.methods.isEmpty()) return
    val colors = analyticsColors
    val total = block.methods.sumOf { it.value }
    Column {
        block.heading.title?.takeIf { it.isNotBlank() }?.let {
            SectionLabel(block.heading.dot?.let(colors::toneHex) ?: colors.toneHex("teal"), it, block.heading.sub)
        }
        FlowGrid(minCell = 220.dp, gap = 14.dp) {
            block.methods.forEach { MethodCard(it, total, context) }
        }
    }
}

@Composable
private fun MethodCard(method: MethodItem, total: Double, context: BlockContext) {
    val colors = analyticsColors
    val color = colors.css(method.color) ?: colors.ink3
    val share = AnalyticsFormat.share(method.value, total)
    Column(
        modifier = Modifier.cardSurface().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color)
            ZillitText(
                method.label,
                style = AnalyticsType.text(12.5f, FontWeight.Bold),
                color = colors.ink,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
            )
            ZillitText(share, style = AnalyticsType.mono(11f, FontWeight.Bold), color = colors.ink3)
        }
        ZillitText(
            AnalyticsFormat.money(method.value, context.currency),
            style = AnalyticsType.mono(24f, FontWeight.Bold, -0.02f),
            color = colors.ink,
        )
        RailBar(if (total == 0.0) 0f else (method.value / total).toFloat(), color, Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 2.dp)) {
            val count = method.count?.let { "$it pmts" }.orEmpty()
            val avg = method.avg?.let { " · avg ${AnalyticsFormat.money(it, context.currency)}" }.orEmpty()
            ZillitText(
                count + avg,
                style = AnalyticsType.mono(11f),
                color = colors.ink3,
                modifier = Modifier.weight(1f),
            )
            ZillitText(method.note.orEmpty(), style = AnalyticsType.mono(11f), color = colors.ink3)
        }
    }
}

/** `gauge-cards`: a mini gauge of each channel's share, its amount, a note, the claim count and turnaround. */
@Composable
internal fun GaugeCardsBlock(block: AnalyticsBlock.GaugeCards, context: BlockContext) {
    if (block.cards.isEmpty()) return
    val colors = analyticsColors
    val sum = block.total ?: block.cards.sumOf { it.value }
    Column {
        block.heading.title?.takeIf { it.isNotBlank() }?.let {
            SectionLabel(block.heading.dot?.let(colors::toneHex) ?: colors.red, it, block.heading.sub)
        }
        FlowGrid(minCell = 300.dp, gap = 12.dp) {
            block.cards.forEach { GaugeCard(it, sum, context) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GaugeCard(card: MethodItem, sum: Double, context: BlockContext) {
    val colors = analyticsColors
    val color = colors.css(card.color) ?: colors.ink3
    val share = card.pct ?: "${if (sum == 0.0) 0 else (card.value / sum * PERCENT).roundToInt()}%"
    Row(
        modifier = Modifier.cardSurface().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DonutChart(
            parts = listOf(
                ChartPart(card.label, card.value, color),
                ChartPart("", max(0.0, sum - card.value), colors.donutTrack),
            ),
            centerLabel = share,
            centerSub = null,
            describe = { AnalyticsFormat.money(it.value, context.currency) },
            size = 116.dp,
            thickness = 18.dp,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(color)
                ZillitText(
                    card.label,
                    style = AnalyticsType.text(13f, FontWeight.Bold),
                    color = colors.ink,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            ZillitText(
                card.display ?: AnalyticsFormat.money(card.value, context.currency),
                style = AnalyticsType.mono(24f, FontWeight.Bold, -0.02f),
                color = colors.ink,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            card.note?.let { ZillitText(it, style = AnalyticsType.text(11.5f), color = colors.ink3) }
            if (card.count != null || card.turn != null) {
                val meta = AnalyticsType.mono(11f)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    card.count?.let { ZillitText("$it claims", style = meta, color = colors.ink3) }
                    card.turn?.let { ZillitText("Turnaround $it", style = meta, color = colors.ink3) }
                }
            }
        }
    }
}

/** `alerts`: exceptions to look at, each opening the module it names. */
@Composable
internal fun AlertsBlock(block: AnalyticsBlock.Alerts, context: BlockContext) {
    if (block.alerts.isEmpty()) return
    val colors = analyticsColors
    val red = colors.tone("red")
    Panel {
        SectionLabel(
            dot = block.heading.dot?.let(colors::toneHex) ?: colors.red,
            title = block.heading.title ?: "Needs Attention",
            sub = block.heading.sub,
            right = {
                ZillitText(
                    block.alerts.size.toString(),
                    style = AnalyticsType.mono(11f, FontWeight.Bold),
                    color = red.ink,
                    modifier = Modifier
                        .background(red.soft, CircleShape)
                        .border(1.dp, red.ring, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            },
        )
        block.alerts.forEachIndexed { i, alert -> AlertRow(alert, last = i == block.alerts.lastIndex, context) }
    }
}

@Composable
private fun AlertRow(alert: AlertItem, last: Boolean, context: BlockContext) {
    val colors = analyticsColors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val tone = colors.tone(alert.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered && alert.module != null) colors.rowHover else Color.Transparent)
            .hoverable(hover)
            .then(alert.module?.let { Modifier.clickable { context.onSelect(it) } } ?: Modifier)
            .bottomRule(show = !last, color = colors.line)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ToneIcon(ZillitIcons.Warning, tone, box = 24.dp, glyph = 13.dp, radius = 7.dp, tint = tone.ink)
        Column(Modifier.weight(1f)) {
            ZillitText(alert.title, style = AnalyticsType.text(12.5f, FontWeight.Bold, -0.005f), color = colors.ink)
            alert.meta?.let {
                ZillitText(
                    it,
                    style = AnalyticsType.text(11f),
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (alert.module != null) {
            ZillitIcon(
                ZillitIcons.ArrowRight,
                tint = colors.ink4,
                size = 13.dp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** `snapshot-cards`: a card per module id with its headline, movement and trend; a click opens it. */
@Composable
internal fun SnapshotCardsBlock(block: AnalyticsBlock.SnapshotCards, context: BlockContext) {
    val metas = block.modules.mapNotNull(context::meta)
    if (block.modules.isEmpty()) return
    val colors = analyticsColors
    Column {
        SectionLabel(
            block.heading.dot?.let(colors::toneHex) ?: colors.amber,
            block.heading.title ?: "By Module",
            block.heading.sub,
        )
        FlowGrid(minCell = 220.dp, gap = 14.dp) {
            metas.forEach { meta ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val tone = colors.tone(meta.tone)
                val currency = meta.currency ?: context.currency
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, if (hovered) colors.line2 else colors.line, RoundedCornerShape(14.dp))
                        .hoverable(hover)
                        .clickable { context.onSelect(meta.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToneIcon(moduleIcon(meta.id), tone, box = 28.dp, glyph = 15.dp, radius = 8.dp)
                        ZillitText(
                            meta.label,
                            style = AnalyticsType.text(12.5f, FontWeight.Bold),
                            color = colors.ink,
                            modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                            maxLines = 1,
                        )
                        DeltaChip(AnalyticsFormat.delta(meta.delta, meta.deltaFmt, "money", currency), meta.deltaTone)
                    }
                    ZillitText(
                        AnalyticsFormat.money(meta.total, currency),
                        style = AnalyticsType.mono(20f, FontWeight.ExtraBold, -0.02f),
                        color = colors.ink,
                    )
                    if (meta.spark.size > 1) Sparkline(meta.spark, colors.toneHex(meta.tone))
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
}

@Composable
private fun Modifier.cardSurface(): Modifier {
    val colors = analyticsColors
    val shape = RoundedCornerShape(14.dp)
    return this.fillMaxWidth().background(colors.surface, shape).border(1.dp, colors.line, shape)
}

/** A one-pixel rule under a row, as the web's `border-bottom` draws it. */
internal fun Modifier.bottomRule(show: Boolean, color: Color): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val y = size.height - HAIRLINE / 2
            drawLine(color, Offset(0f, y), Offset(size.width, y), HAIRLINE)
        }
    }

private const val HAIRLINE = 1f

private const val PERCENT = 100.0
