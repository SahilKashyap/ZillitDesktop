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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlin.math.max
import kotlin.math.min

// -- surfaces ----------------------------------------------------------------------------------

/** The web's `Panel`: a white card, radius 16, hairline border. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, border ?: colors.line, shape)
            .padding(padding),
        content = content,
    )
}

/** A dot, a title and its muted aside on the left; anything on the right. */
@Composable
internal fun SectionLabel(
    dot: Color,
    title: String,
    sub: String? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val colors = analyticsColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            ZillitText(title, style = AnalyticsType.text(16f, FontWeight.Bold, -0.014f), color = colors.ink, maxLines = 1)
            sub?.takeIf { it.isNotBlank() }?.let {
                ZillitText(it, style = AnalyticsType.text(12f), color = colors.ink3, maxLines = 1)
            }
        }
        right?.invoke()
    }
}

/** A heading only when there is something to say — the web skips the label for an untitled block. */
@Composable
internal fun OptionalSectionLabel(
    dot: Color,
    title: String?,
    sub: String?,
    right: (@Composable () -> Unit)? = null,
) {
    if (title.isNullOrBlank() && right == null) return
    SectionLabel(dot, title.orEmpty(), sub, right)
}

// -- chips -------------------------------------------------------------------------------------

/**
 * The web's `DeltaChip`: a value that starts with `+`/`▲` gets an up arrow and
 * one that starts with `−`/`-`/`▼` a down arrow, the sign itself dropped.
 */
@Composable
internal fun DeltaChip(value: String?, tone: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    val colors = analyticsColors
    val t = colors.tone(tone)
    val text = value.trim()
    val signed = text.firstOrNull()?.let { it in SIGNS } == true
    val up = text.firstOrNull()?.let { it == '+' || it == '▲' } == true
    Row(
        modifier = modifier
            .background(t.soft, CircleShape)
            .border(1.dp, t.ring, CircleShape)
            .padding(start = 5.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (signed) ZillitIcon(if (up) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown, tint = t.ink, size = 11.dp)
        ZillitText(
            if (signed) text.drop(1).trimStart() else text,
            style = AnalyticsType.mono(10.5f, FontWeight.Bold),
            color = t.ink,
            maxLines = 1,
        )
    }
}

private const val SIGNS = "+−-▲▼"

/** A table's status: a dot and a label on the tone's tint. */
@Composable
internal fun StatusPill(label: String, tone: String?) {
    val t = analyticsColors.tone(tone)
    Row(
        modifier = Modifier
            .background(t.soft, CircleShape)
            .border(1.dp, t.ring, CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).background(t.fg, CircleShape))
        ZillitText(label, style = AnalyticsType.text(11f, FontWeight.Bold), color = t.ink, maxLines = 1)
    }
}

/** The tab switch under a module's own blocks. */
@Composable
internal fun Segmented(options: List<Pair<String, String>>, active: String?, onChange: (String) -> Unit) {
    val colors = analyticsColors
    Row(
        modifier = Modifier
            .background(colors.donutTrack, RoundedCornerShape(8.dp))
            .padding(3.dp),
    ) {
        options.forEach { (id, label) ->
            val on = id == active
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            ZillitText(
                label,
                style = AnalyticsType.text(12f, FontWeight.Bold),
                color = if (on || hovered) colors.ink else colors.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) colors.surface else Color.Transparent)
                    .hoverable(hover)
                    .clickable { onChange(id) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** Colour swatches with their names, on a section label's right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Swatches(items: List<Pair<Color, String>>) {
    val colors = analyticsColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
                ZillitText(label, style = AnalyticsType.text(11.5f, FontWeight.SemiBold), color = colors.ink3)
            }
        }
    }
}

/** An icon on a tone's tint, as the module cards and alerts draw it. */
@Composable
internal fun ToneIcon(icon: ImageVector, tone: ToneColors, box: Dp, glyph: Dp, radius: Dp, tint: Color = tone.fg) {
    Box(
        modifier = Modifier
            .size(box)
            .background(tone.soft, RoundedCornerShape(radius))
            .border(1.dp, tone.ring, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = tint, size = glyph)
    }
}

/** A module id's icon — resolved here so a module always has one, whatever the server sends. */
internal fun moduleIcon(id: String): ImageVector = when (id) {
    "overview" -> ZillitIcons.Grid
    "payroll" -> ZillitIcons.Bank
    "po" -> ZillitIcons.Receipt
    "extras" -> ZillitIcons.Users
    "invoices" -> ZillitIcons.File
    "petty" -> ZillitIcons.Calculator
    "oop" -> ZillitIcons.Wallet
    "prodcards" -> ZillitIcons.CreditCard
    else -> ZillitIcons.BarChart
}

// -- KPI ---------------------------------------------------------------------------------------

/** The web's `KPI`: an uppercase label and delta, a large figure with its unit, a note and a sparkline. */
@Composable
internal fun KpiCard(
    label: String,
    value: String,
    unit: String?,
    sub: String?,
    delta: String?,
    deltaTone: String?,
    spark: List<Double>,
    sparkColor: Color,
    valueColor: Color?,
    modifier: Modifier = Modifier,
) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 128.dp)
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                label.uppercase(),
                style = AnalyticsType.mono(10f, FontWeight.Bold, 0.1f),
                color = colors.ink3,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            DeltaChip(delta, deltaTone)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                value,
                style = AnalyticsType.text(29f, FontWeight.Bold, -0.028f).copy(lineHeight = AnalyticsType.text(29f).fontSize),
                color = valueColor ?: colors.ink,
                maxLines = 1,
            )
            unit?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    it,
                    style = AnalyticsType.mono(13.5f, FontWeight.Bold, 0.04f),
                    color = colors.ink3,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        sub?.takeIf { it.isNotBlank() }?.let {
            ZillitText(it, style = AnalyticsType.text(11.5f), color = colors.ink3)
        }
        if (spark.size > 1) {
            Spacer(Modifier.weight(1f, fill = false))
            Sparkline(spark, sparkColor)
        }
    }
}

// -- legends and ranked bars -------------------------------------------------------------------

internal data class LegendItem(val label: String, val color: Color, val value: String, val pct: String?)

/** A donut's legend: swatch, label, value and share, in [cols] columns. */
@Composable
internal fun Legend(items: List<LegendItem>, cols: Int) {
    val colors = analyticsColors
    TemplateGrid(tracks = List(max(1, cols)) { GridTrack.Weight(1f) }, gap = 18.dp, rowGap = 10.dp) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(10.dp).background(item.color, RoundedCornerShape(3.dp)))
                ZillitText(
                    item.label,
                    style = AnalyticsType.text(12.5f, FontWeight.Medium),
                    color = colors.ink2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(item.value, style = AnalyticsType.mono(12.5f, FontWeight.Bold), color = colors.ink)
                item.pct?.let {
                    ZillitText(
                        it,
                        style = AnalyticsType.mono(11f),
                        color = colors.ink3,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
        }
    }
}

internal data class HBarItem(
    val label: String,
    val code: String?,
    val value: Double,
    val color: Color,
    val display: String,
    val pct: String,
)

/** Ranked horizontal bars: label, a bar scaled to the largest row, the amount and its share. */
@Composable
internal fun HBarList(rows: List<HBarItem>, labelWidth: Dp = 150.dp) {
    val colors = analyticsColors
    val largest = rows.maxOfOrNull { it.value } ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.width(labelWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.code?.let { ZillitText(it, style = AnalyticsType.mono(10.5f, FontWeight.SemiBold), color = colors.ink4) }
                    ZillitText(row.label, style = AnalyticsType.text(13f, FontWeight.SemiBold), color = colors.ink, maxLines = 1)
                }
                val share = if (largest > 0) (row.value / largest).toFloat().coerceIn(MIN_BAR_SHARE, 1f) else MIN_BAR_SHARE
                RailBar(share, row.color, Modifier.weight(1f), height = 8.dp)
                Row(
                    modifier = Modifier.widthIn(min = 96.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ZillitText(row.display, style = AnalyticsType.mono(13f, FontWeight.Bold), color = colors.ink)
                    if (row.pct.isNotEmpty()) {
                        ZillitText(
                            row.pct,
                            style = AnalyticsType.mono(11f),
                            color = colors.ink3,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(42.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A rounded track with a share of it filled. */
@Composable
internal fun RailBar(share: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(CircleShape).background(analyticsColors.track)) {
        if (share > 0f) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
        }
    }
}

private const val MIN_BAR_SHARE = 0.02f

// -- grids -------------------------------------------------------------------------------------

/** One column of a CSS grid template. */
internal sealed interface GridTrack {
    data class Weight(val fr: Float, val min: Dp = 0.dp) : GridTrack
    data class Fixed(val width: Dp) : GridTrack
}

/**
 * CSS `repeat(auto-fit | auto-fill, minmax(min, 1fr))`: as many columns of at
 * least [minCell] as fit. Auto-fit ([fill] false) lets a short row's items
 * share the width; auto-fill keeps the empty columns. Items in a row share
 * its height unless [stretch] is off.
 */
@Composable
internal fun FlowGrid(
    minCell: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    stretch: Boolean = true,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val fitting = max(1, (width + gapPx) / (minCell.roundToPx() + gapPx))
        val columns = if (fill) fitting else min(fitting, max(1, measurables.size))
        val cell = max(0, (width - gapPx * (columns - 1)) / columns)
        placeRows(measurables, List(columns) { cell }, gapPx, gapPx, stretch, width)
    }
}

/** A fixed CSS grid template — `1.6fr 1fr`, `300px 1fr` — with children filling tracks left to right. */
@Composable
internal fun TemplateGrid(
    tracks: List<GridTrack>,
    gap: Dp,
    modifier: Modifier = Modifier,
    rowGap: Dp = gap,
    stretch: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val list = tracks.ifEmpty { listOf(GridTrack.Weight(1f)) }
        val fixed = list.sumOf { if (it is GridTrack.Fixed) it.width.roundToPx() else 0 }
        val free = max(0, width - fixed - gapPx * (list.size - 1))
        val totalFr = list.sumOf { ((it as? GridTrack.Weight)?.fr ?: 0f).toDouble() }.toFloat()
        val widths = list.map { track ->
            when (track) {
                is GridTrack.Fixed -> track.width.roundToPx()
                is GridTrack.Weight -> max(track.min.roundToPx(), if (totalFr > 0) (free * track.fr / totalFr).toInt() else 0)
            }
        }
        placeRows(measurables, widths, gapPx, rowGap.roundToPx(), stretch, width)
    }
}

private fun androidx.compose.ui.layout.MeasureScope.placeRows(
    measurables: List<Measurable>,
    widths: List<Int>,
    gap: Int,
    rowGap: Int,
    stretch: Boolean,
    width: Int,
): androidx.compose.ui.layout.MeasureResult {
    val rows = measurables.chunked(widths.size).map { row ->
        val height = if (stretch) row.mapIndexed { i, m -> m.maxIntrinsicHeight(widths[i]) }.maxOrNull() ?: 0 else 0
        row.mapIndexed { i, m ->
            val w = widths[i]
            if (stretch) m.measure(Constraints(w, w, height, height)) else m.measure(Constraints(minWidth = w, maxWidth = w))
        }
    }
    val heights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
    val total = heights.sum() + rowGap * max(0, rows.size - 1)
    return layout(width, total) {
        var y = 0
        rows.forEachIndexed { r, row ->
            var x = 0
            row.forEachIndexed { i, placeable ->
                placeable.place(x, y)
                x += widths[i] + gap
            }
            y += heights[r] + rowGap
        }
    }
}

/**
 * A server's `cols` string as tracks: `Nfr`, `Npx`, `auto`, `minmax(Apx, Bfr)`
 * and `repeat(n, …)`. Null for `repeat(auto-fit | auto-fill, …)`, which is a
 * [FlowGrid] instead — see [autoGridMin].
 */
internal fun parseGridTemplate(cols: String?): List<GridTrack>? {
    val template = cols?.trim()?.takeIf { it.isNotEmpty() } ?: return listOf(GridTrack.Weight(1f), GridTrack.Weight(1f))
    if (autoGridMin(template) != null) return null
    return topLevelTokens(template).flatMap(::tracksOf).ifEmpty { listOf(GridTrack.Weight(1f), GridTrack.Weight(1f)) }
}

/** The minimum column width of `repeat(auto-fit, minmax(212px, 1fr))`, or null for any other template. */
internal fun autoGridMin(cols: String?): Pair<Dp, Boolean>? {
    val match = AUTO_REPEAT.find(cols?.trim().orEmpty()) ?: return null
    val min = match.groupValues[2].toFloatOrNull() ?: return null
    return min.dp to (match.groupValues[1] == "auto-fill")
}

private fun tracksOf(token: String): List<GridTrack> {
    REPEAT.matchEntire(token)?.let { match ->
        val times = match.groupValues[1].toIntOrNull() ?: return emptyList()
        val inner = topLevelTokens(match.groupValues[2]).flatMap(::tracksOf)
        return List(times) { inner }.flatten()
    }
    MINMAX.matchEntire(token)?.let { match ->
        val low = match.groupValues[1].removeSuffix("px").toFloatOrNull() ?: 0f
        val high = match.groupValues[2]
        return listOf(
            high.removeSuffix("fr").toFloatOrNull()?.takeIf { high.endsWith("fr") }?.let { GridTrack.Weight(it, low.dp) }
                ?: GridTrack.Fixed((high.removeSuffix("px").toFloatOrNull() ?: low).dp),
        )
    }
    return listOf(
        when {
            token.endsWith("fr") -> GridTrack.Weight(token.removeSuffix("fr").toFloatOrNull() ?: 1f)
            token.endsWith("px") -> GridTrack.Fixed((token.removeSuffix("px").toFloatOrNull() ?: 0f).dp)
            token.endsWith("%") -> GridTrack.Weight(token.removeSuffix("%").toFloatOrNull() ?: 1f)
            else -> GridTrack.Weight(1f)
        },
    )
}

/** Whitespace-separated tokens, keeping parenthesised groups whole. */
private fun topLevelTokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    text.forEach { char ->
        when {
            char == '(' -> depth++.also { current.append(char) }
            char == ')' -> depth--.also { current.append(char) }
            char.isWhitespace() && depth == 0 -> if (current.isNotEmpty()) tokens += current.toString().also { current.clear() }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

private val AUTO_REPEAT = Regex("^repeat\\(\\s*(auto-fit|auto-fill)\\s*,\\s*minmax\\(\\s*([\\d.]+)px\\s*,\\s*[\\d.]+fr\\s*\\)\\s*\\)$")
private val REPEAT = Regex("^repeat\\(\\s*(\\d+)\\s*,\\s*(.+)\\)$")
private val MINMAX = Regex("^minmax\\(\\s*([\\d.]+(?:px)?)\\s*,\\s*([\\d.]+(?:px|fr)?)\\s*\\)$")
