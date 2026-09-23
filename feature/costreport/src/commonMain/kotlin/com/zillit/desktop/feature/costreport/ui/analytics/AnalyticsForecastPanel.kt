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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.ForecastView
import com.zillit.desktop.feature.costreport.domain.analytics.ModuleForecastRow
import kotlin.math.abs
import kotlin.math.max

/**
 * `forecast` / `projection` — the web's `ForecastPanel`: EFC and its
 * variance, the four figures behind it, a budget rail (actual, committed and
 * estimate-to-complete stacked against a budget marker) and the cumulative
 * projection to wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ForecastPanel(block: AnalyticsBlock.Forecast, context: BlockContext) {
    val colors = analyticsColors
    val view = remember(block.data, context.currency) { ForecastView.of(block.data, context.currency) }
    val hex = colors.toneHex(view.tone)
    val title = block.title ?: if (block.projection) {
        str(S.desktop_cr_cost_to_final)
    } else {
        str(S.desktop_cr_forecast_to_final)
    }
    val wrap = view.wrap.takeIf { it.isNotBlank() }?.let { str(S.desktop_cr_wrap_suffix, it) }.orEmpty()
    Panel(border = colors.tone(view.tone).ring) {
        SectionLabel(hex, title, str(S.desktop_cr_shoot_week_of, view.currentWeek, view.totalWeeks, wrap))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EfcHeadline(view)
            Box(Modifier.weight(1f))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ForecastStat(str(S.desktop_cr_actual_to_date), view.actualText)
                ForecastStat(str(S.desktop_cr_committed), view.committedText)
                ForecastStat(str(S.desktop_cr_est_to_complete), view.etcText, hex)
                ForecastStat(str(S.budget_text), view.budgetText)
            }
        }
        BudgetRail(view, hex, Modifier.padding(top = 18.dp))
        Column(Modifier.padding(top = 20.dp).fillMaxWidth().topRule(colors.line).padding(top = 18.dp)) {
            ZillitText(
                str(S.desktop_cr_cumulative_spend),
                style = AnalyticsType.mono(10f, FontWeight.Bold, 0.09f),
                color = colors.ink3,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            ProjectionChart(view, hex, AnalyticsFormat.prefix(context.currency))
        }
        view.driver?.let { driver ->
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitIcon(ZillitIcons.Info, tint = colors.ink4, size = 14.dp)
                ZillitText(driver, style = AnalyticsType.text(11.5f), color = colors.ink3)
            }
        }
    }
}

@Composable
private fun EfcHeadline(view: ForecastView) {
    val colors = analyticsColors
    val tone = colors.tone(view.varianceTone)
    Column {
        ZillitText(
            str(S.desktop_cr_estimated_final_cost_caps),
            style = AnalyticsType.mono(10f, FontWeight.Bold, 0.1f),
            color = colors.ink3,
        )
        Row(
            Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitText(view.efcText, style = AnalyticsType.mono(34f, FontWeight.Bold, -0.03f), color = colors.ink)
            Row(
                modifier = Modifier
                    .background(tone.soft, CircleShape)
                    .border(1.dp, tone.ring, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ZillitIcon(
                    if (view.over) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                    tint = tone.ink,
                    size = 13.dp,
                )
                ZillitText(view.varianceText, style = AnalyticsType.mono(13f, FontWeight.Bold), color = tone.ink)
            }
            ZillitText(
                listOf(view.variancePercentText, view.varianceLabel).filter { it.isNotBlank() }.joinToString(" · "),
                style = AnalyticsType.text(11.5f),
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun ForecastStat(label: String, value: String, color: Color? = null) {
    val colors = analyticsColors
    Column {
        ZillitText(label.uppercase(), style = AnalyticsType.mono(9.5f, FontWeight.Bold, 0.09f), color = colors.ink3)
        ZillitText(
            value,
            style = AnalyticsType.mono(17f, FontWeight.Bold, -0.02f),
            color = color ?: colors.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Actual, committed and estimate-to-complete stacked against the budget, the overrun washed red. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetRail(view: ForecastView, hex: Color, modifier: Modifier = Modifier) {
    val colors = analyticsColors
    val scale = max(view.efc, view.budget) * RAIL_HEADROOM
    fun share(value: Double) = if (scale <= 0) 0f else (value / scale).toFloat().coerceIn(0f, 1f)
    val budgetAt = share(view.budget)
    Column(modifier.fillMaxWidth()) {
        StackedRail(
            parts = listOf(
                share(view.actual) to 1f,
                share(view.committed) to COMMITTED_ALPHA,
                share(view.etc) to ETC_ALPHA,
            ),
            color = hex,
            budgetAt = budgetAt.takeIf { view.budget > 0 },
            over = view.efc > view.budget,
            height = 32.dp,
            radius = 8.dp,
        )
        AtFraction(budgetAt, Modifier.padding(top = 3.dp).height(14.dp)) {
            ZillitText(
                "▲ Budget ${view.budgetText}",
                style = AnalyticsType.mono(9.5f, FontWeight.Bold),
                color = colors.ink2,
                maxLines = 1,
            )
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RailKey(hex, 1f, str(S.desktop_cr_actual), view.actualText)
            RailKey(hex, COMMITTED_ALPHA, str(S.desktop_cr_committed), view.committedText)
            RailKey(hex, ETC_ALPHA, str(S.desktop_cr_est_to_complete), view.etcText)
        }
    }
}

/**
 * A rail of stacked shares from the left, the part past [budgetAt] washed red
 * when [over], and a budget marker.
 */
@Composable
internal fun StackedRail(
    parts: List<Pair<Float, Float>>,
    color: Color,
    budgetAt: Float?,
    over: Boolean,
    height: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = analyticsColors
    val redSoft = colors.tone("red").soft
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(colors.track)
            .drawBehind {
                if (over && budgetAt != null) {
                    drawRect(redSoft, Offset(size.width * budgetAt, 0f), Size(size.width * (1 - budgetAt), size.height))
                }
                var x = 0f
                parts.forEach { (share, alpha) ->
                    val w = size.width * share
                    drawRect(color.copy(alpha = color.alpha * alpha), Offset(x, 0f), Size(w, size.height))
                    x += w
                }
                budgetAt?.let {
                    val marker = 2.dp.toPx()
                    drawRect(colors.ink, Offset(size.width * it - marker / 2, 0f), Size(marker, size.height))
                }
            },
    )
}

/** [content] centred on a fraction of the width, kept inside it. */
@Composable
private fun AtFraction(fraction: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints())
        val width = constraints.maxWidth
        layout(width, placeable.height) {
            val x = (width * fraction - placeable.width / 2f).toInt().coerceIn(0, max(0, width - placeable.width))
            placeable.place(x, 0)
        }
    }
}

@Composable
private fun RailKey(color: Color, alpha: Float, label: String, value: String) {
    val colors = analyticsColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(11.dp).background(color.copy(alpha = alpha), RoundedCornerShape(3.dp)))
        ZillitText(label, style = AnalyticsType.text(11.5f, FontWeight.SemiBold), color = colors.ink2)
        ZillitText(value, style = AnalyticsType.mono(11.5f, FontWeight.Bold), color = colors.ink)
    }
}

/**
 * `module-forecast` — "Spend & Forecast by Module": a budget rail per module
 * with its EFC and variance. Label, icon and year-to-date come from the strip;
 * a row opens its module.
 */
@Composable
internal fun ModuleForecastBlock(block: AnalyticsBlock.ModuleForecast, context: BlockContext) {
    if (block.rows.isEmpty()) return
    val colors = analyticsColors
    Panel {
        SectionLabel(
            colors.amber,
            block.title ?: str(S.desktop_cr_spend_forecast_by_module),
            block.sub ?: str(S.desktop_cr_actual_efc_vs_budget),
        )
        ForecastGridRow(Modifier.bottomRule(true, colors.line).padding(bottom = 10.dp)) {
            listOf(
                str(S.desktop_cr_module),
                str(S.desktop_cr_actual_committed_etc),
                str(S.desktop_cr_efc),
                str(S.desktop_variance),
            ).forEachIndexed { i, header ->
                ZillitText(
                    header.uppercase(),
                    style = AnalyticsType.mono(10f, FontWeight.Bold, 0.08f),
                    color = colors.ink3,
                    textAlign = if (i >= 2) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
            }
        }
        block.rows.forEachIndexed { i, row -> ModuleForecastLine(row, context, last = i == block.rows.lastIndex) }
    }
}

/** A module's forecast figures, worked out once for its row. */
private class ModuleFigures(row: ModuleForecastRow, currency: String?) {
    val efc = row.efc ?: (row.actual + row.committed + row.etc)
    val over = efc > row.budget
    private val scale = (max(efc, row.budget) * RAIL_HEADROOM).takeIf { it != 0.0 } ?: 1.0
    val parts = listOf(share(row.actual) to 1f, share(row.committed) to COMMITTED_ALPHA, share(row.etc) to ETC_ALPHA)
    val budgetAt = share(row.budget).takeIf { row.budget > 0 }

    /** Against a budget, the variance as a percentage of it; without one, the amount. */
    val varianceText: String = run {
        val variance = row.variance ?: (efc - row.budget)
        val sign = if (over) "+" else "\u2212"
        if (row.budget > 0) {
            "$sign${fixedOne(abs(variance / row.budget * PERCENT))}%"
        } else {
            sign + AnalyticsFormat.money(abs(variance), currency)
        }
    }

    private fun share(value: Double) = (value / scale).toFloat().coerceIn(0f, 1f)
}

@Suppress("LongMethod") // One block, in one place; the sweep's wrapped calls added the lines.
@Composable
private fun ModuleForecastLine(row: ModuleForecastRow, context: BlockContext, last: Boolean) {
    val colors = analyticsColors
    val meta = context.meta(row.module)
    val toneId = row.tone ?: meta?.tone ?: "grey"
    val figures = remember(row, context.currency) { ModuleFigures(row, context.currency) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    ForecastGridRow(
        Modifier
            .background(if (hovered) colors.rowHover else Color.Transparent)
            .hoverable(hover)
            .clickable { context.onSelect(row.module) }
            .bottomRule(!last, colors.line)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToneIcon(moduleIcon(row.module), colors.tone(toneId), box = 26.dp, glyph = 14.dp, radius = 7.dp)
            Column {
                ZillitText(
                    meta?.label ?: AnalyticsTitles.titleFor(row.module) ?: row.module,
                    style = AnalyticsType.text(13f, FontWeight.Bold),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ytd = AnalyticsFormat.money(meta?.total ?: row.actual, context.currency)
                ZillitText(
                    str(S.desktop_cr_ytd, ytd),
                    style = AnalyticsType.mono(11f),
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
        }
        Column {
            StackedRail(
                parts = figures.parts,
                color = colors.toneHex(toneId),
                budgetAt = figures.budgetAt,
                over = figures.over,
                height = 16.dp,
                radius = 5.dp,
            )
            ZillitText(
                str(S.desktop_br_budget_in_currency, AnalyticsFormat.money(row.budget, context.currency)),
                style = AnalyticsType.mono(10f),
                color = colors.ink4,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        ZillitText(
            AnalyticsFormat.money(figures.efc, context.currency),
            style = AnalyticsType.mono(13f, FontWeight.Bold),
            color = colors.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            VarianceChip(figures.over, figures.varianceText)
        }
    }
}

/** Red and pointing up when over budget, green and pointing down when under. */
@Composable
private fun VarianceChip(over: Boolean, text: String) {
    val chip = analyticsColors.tone(if (over) "red" else "green")
    Row(
        modifier = Modifier
            .background(chip.soft, CircleShape)
            .border(1.dp, chip.ring, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitIcon(if (over) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown, tint = chip.ink, size = 10.dp)
        ZillitText(text, style = AnalyticsType.mono(11f, FontWeight.Bold), color = chip.ink, maxLines = 1)
    }
}

/** The forecast table's grid: `1.5fr 1.6fr 96px 96px`, fourteen apart, vertically centred. */
@Composable
private fun ForecastGridRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 14.dp.roundToPx()
        val fixed = 96.dp.roundToPx()
        val free = max(0, constraints.maxWidth - fixed * 2 - gap * 3)
        val fr = MODULE_FR + RAIL_FR
        val widths = listOf((free * MODULE_FR / fr).toInt(), (free * RAIL_FR / fr).toInt(), fixed, fixed)
        val placeables = measurables.take(widths.size).mapIndexed { i, m ->
            m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i]))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                p.place(x, (height - p.height) / 2)
                x += widths[i] + gap
            }
        }
    }
}

private fun Modifier.topRule(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, HAIRLINE / 2), Offset(size.width, HAIRLINE / 2), HAIRLINE)
}

/** `toFixed(1)`. */
private fun fixedOne(value: Double): String {
    val tenths = kotlin.math.floor(value * TENTHS + HALF).toLong()
    return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()}"
}

private const val RAIL_HEADROOM = 1.02
private const val HAIRLINE = 1f
private const val MODULE_FR = 1.5f
private const val RAIL_FR = 1.6f
private const val COMMITTED_ALPHA = 0.55f
private const val ETC_ALPHA = 0.26f
private const val PERCENT = 100.0
private const val TENTHS = 10.0
private const val HALF = 0.5
