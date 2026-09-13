package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costreport.domain.analytics.ForecastView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The web's visx charts, drawn on a Canvas: the same paddings, bar widths,
 * marker radii and dash patterns, and a hover tooltip on each.
 */

/** A drawn series: its name, colour and points; a null point is a gap. */
internal data class ChartLine(
    val label: String,
    val color: Color,
    val data: List<Double?>,
    val fill: Boolean = false,
    val dashed: Boolean = false,
)

/** One bar: a single segment, or a stack. */
internal data class ChartBar(val segments: List<ChartPart>) {
    val total: Double get() = segments.sumOf { it.value }
}

internal data class ChartPart(val label: String, val value: Double, val color: Color)

/** What a tooltip says, and the point it hangs from. */
internal data class ChartTip(val anchor: Offset, val label: String?, val rows: List<ChartTipRow>)

internal data class ChartTipRow(val color: Color?, val name: String, val value: String)

/** d3's `scaleLinear`, including its midpoint for a collapsed domain. */
private class Linear(private val d0: Double, d1: Double, private val r0: Float, private val r1: Float) {
    private val span = d1 - d0
    operator fun invoke(value: Double): Float =
        if (span == 0.0 || span.isNaN()) (r0 + r1) / 2 else (r0 + (value - d0) / span * (r1 - r0)).toFloat()
}

/** The chart type, read in composition because a draw scope cannot read the theme. */
private class ChartText(
    val measurer: TextMeasurer,
    val axis: TextStyle,
    val axisBold: TextStyle,
    val marker: TextStyle,
    val markerBig: TextStyle,
    val today: TextStyle,
)

@Composable
private fun rememberChartText(): ChartText {
    val measurer = rememberTextMeasurer()
    val axis = AnalyticsType.mono(9.5f)
    val axisBold = AnalyticsType.mono(9.5f, FontWeight.Bold)
    val marker = AnalyticsType.mono(10f, FontWeight.Bold)
    val markerBig = AnalyticsType.mono(10.5f, FontWeight.Bold)
    val today = AnalyticsType.mono(10f, FontWeight.Bold, 0.14f)
    return remember(measurer, axis) { ChartText(measurer, axis, axisBold, marker, markerBig, today) }
}

private enum class Anchor { Start, Middle, End }

/** SVG `<text>`: [baseline] is where the letters sit, [anchor] which end [x] names. */
@Suppress("LongParameterList") // SVG's own text attributes.
private fun DrawScope.label(
    text: ChartText,
    value: String,
    style: TextStyle,
    color: Color,
    x: Float,
    baseline: Float,
    anchor: Anchor = Anchor.Start,
) {
    val layout = text.measurer.measure(value, style)
    val left = when (anchor) {
        Anchor.Start -> x
        Anchor.Middle -> x - layout.size.width / 2f
        Anchor.End -> x - layout.size.width
    }
    drawText(layout, color = color, topLeft = Offset(left, baseline - layout.firstBaseline))
}

private fun Modifier.hover(onMove: (Offset?) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            onMove(if (event.type == PointerEventType.Exit) null else event.changes.firstOrNull()?.position)
        }
    }
}

private fun Density.dashes(on: Dp, off: Dp) = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()))

// -- sparkline ---------------------------------------------------------------------------------

/** A mini line normalised to its own range — no axes, no tooltip. */
@Composable
internal fun Sparkline(data: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        if (data.size < 2) return@Canvas
        val inset = 1.dp.toPx()
        val x = Linear(0.0, (data.size - 1).toDouble(), inset, size.width - inset)
        val y = Linear(data.min(), data.max().takeIf { it != 0.0 } ?: 1.0, size.height - 2.dp.toPx(), 2.dp.toPx())
        val points = data.mapIndexed { i, v -> Offset(x(i.toDouble()), y(v)) }
        drawPath(linePath(points), color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// -- tooltip -----------------------------------------------------------------------------------

/** The web's `TooltipWithBounds`: ten pixels off the point, flipped to stay inside the chart. */
@Composable
private fun BoxScope.TipLayer(tip: ChartTip?) {
    if (tip == null) return
    Layout(content = { TipCard(tip) }, modifier = Modifier.matchParentSize()) { measurables, constraints ->
        val card = measurables.first().measure(Constraints())
        // Bounded in a real layout; an intrinsic pass hands unbounded constraints, which layout() refuses.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        layout(width, height) {
            val gap = TIP_GAP.roundToPx()
            val ax = tip.anchor.x.roundToInt()
            val ay = tip.anchor.y.roundToInt()
            val x = if (ax + gap + card.width > width) ax - gap - card.width else ax + gap
            val y = if (ay + gap + card.height > height) ay - gap - card.height else ay + gap
            card.place(x.coerceAtLeast(0), y.coerceAtLeast(0))
        }
    }
}

@Composable
private fun TipCard(tip: ChartTip) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .shadow(10.dp, shape, ambientColor = SHADOW, spotColor = SHADOW)
            .background(colors.tooltip, shape)
            .border(1.dp, colors.line, shape)
            .widthIn(min = 90.dp)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tip.label?.takeIf { it.isNotBlank() }?.let {
            ZillitText(it.uppercase(), style = AnalyticsType.mono(9.5f, FontWeight.Bold, 0.08f), color = colors.ink3)
        }
        tip.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.color?.let { Box(Modifier.size(8.dp).background(it, RoundedCornerShape(2.dp))) }
                if (row.name.isNotBlank()) {
                    ZillitText(
                        row.name,
                        style = AnalyticsType.text(11.5f),
                        color = colors.ink,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                ZillitText(row.value, style = AnalyticsType.mono(11.5f, FontWeight.Bold), color = colors.ink)
            }
        }
    }
}

// -- shared plot geometry ----------------------------------------------------------------------

/**
 * A chart's plot area inside its paddings, with the x axis spread over
 * [count] points — the same numbers for drawing and for hit-testing.
 */
private class PlotFrame(size: Size, density: Density, pad: Pads, private val count: Int, low: Double, high: Double) {
    val left = with(density) { pad.left.toPx() }
    val top = with(density) { pad.top.toPx() }
    val width = size.width - left - with(density) { pad.right.toPx() }
    val height = size.height - this.top - with(density) { pad.bottom.toPx() }
    val floor = this.top + height
    val labelBaseline = floor + with(density) { AXIS_LABEL_DROP.toPx() }
    private val xs = Linear(0.0, max(1, count - 1).toDouble(), 0f, width)
    private val ys = Linear(low, high, height, 0f)

    fun x(index: Int): Float = left + xs(index.toDouble())
    fun y(value: Double): Float = this.top + ys(value)

    /** The index whose slice — half a step either side of its point — holds [pointer]. */
    fun indexAt(pointer: Offset): Int? {
        val px = pointer.x - left
        val py = pointer.y - this.top
        if (count == 0 || py !in 0f..height) return null
        val cell = if (count > 1) width / (count - 1) else width
        if (px < -cell / 2 || px > width + cell / 2) return null
        return if (count == 1) 0 else (px / cell).roundToInt().coerceIn(0, count - 1)
    }
}

private data class Pads(val top: Dp, val right: Dp, val bottom: Dp, val left: Dp)

private fun DrawScope.drawGrid(
    text: ChartText,
    colors: AnalyticsColors,
    frame: PlotFrame,
    steps: List<Double>,
    valueAt: (Double) -> Double,
    format: (Double) -> String,
) {
    steps.forEachIndexed { i, step ->
        val value = valueAt(step)
        val gy = frame.y(value)
        val dashed = !(steps.first() == 0.0 && i == 0)
        drawLine(
            colors.grid,
            Offset(frame.left, gy),
            Offset(frame.left + frame.width, gy),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (dashed) dashes(3.dp, 4.dp) else null,
        )
        label(text, format(value), text.axis, colors.ink4, frame.left - 8.dp.toPx(), gy + 3.dp.toPx(), Anchor.End)
    }
}

private fun DrawScope.drawDashedAcross(frame: PlotFrame, y: Float, color: Color) = drawLine(
    color,
    Offset(frame.left, y),
    Offset(frame.left + frame.width, y),
    strokeWidth = 1.4.dp.toPx(),
    pathEffect = dashes(6.dp, 4.dp),
)

private fun linePath(points: List<Offset>) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

private fun areaPath(points: List<Offset>, floor: Float) = linePath(points).apply {
    lineTo(points.last().x, floor)
    lineTo(points.first().x, floor)
    close()
}

private fun DrawScope.drawArea(points: List<Offset>, highest: Float, floor: Float, color: Color) {
    val brush = Brush.verticalGradient(
        listOf(color.copy(alpha = AREA_TOP_ALPHA), color.copy(alpha = AREA_BOTTOM_ALPHA)),
        startY = highest,
        endY = floor,
    )
    drawPath(areaPath(points, floor), brush)
}

private fun DrawScope.drawMarker(center: Offset, radius: Dp, fill: Color, ring: Color) {
    drawCircle(fill, radius.toPx(), center)
    drawCircle(ring, radius.toPx(), center, style = Stroke(2.dp.toPx()))
}

/** Consecutive defined points — d3's `defined()` breaks a line at every gap. */
private fun runsOf(points: List<Offset?>): List<List<Offset>> {
    val runs = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    points.forEach { point ->
        if (point != null) {
            current += point
        } else if (current.isNotEmpty()) {
            runs += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

// -- trend -------------------------------------------------------------------------------------

/** Lines and areas over periods, gridlines and both axes' labels, an optional dashed budget line. */
@Composable
internal fun TrendChart(
    labels: List<String>,
    series: List<ChartLine>,
    budget: Double?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 250.dp,
) {
    val colors = analyticsColors
    val text = rememberChartText()
    val density = LocalDensity.current
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val values = series.flatMap { it.data }.filterNotNull()
    val top = ((values + (budget ?: 0.0)).max() * TREND_HEADROOM).takeIf { it != 0.0 } ?: 1.0
    val bottom = min(0.0, values.minOrNull() ?: 0.0)
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize().hover { pointer = it }) {
            canvas = size
            val frame = PlotFrame(size, this, TREND_PADS, labels.size, bottom, top)
            drawGrid(text, colors, frame, GRID_STEPS, { bottom + (top - bottom) * it }, format)
            budget?.let {
                val by = frame.y(it)
                drawDashedAcross(frame, by, colors.ink3)
                val caption = "Budget ${format(it)}"
                label(text, caption, text.axisBold, colors.ink2, frame.left + frame.width, by - 5.dp.toPx(), Anchor.End)
            }
            series.forEach { drawTrendSeries(it, frame, colors.surface) }
            labels.forEachIndexed { i, week ->
                if (labels.size <= SPARSE_LABELS || showsSparseLabel(i, labels.lastIndex)) {
                    label(text, week, text.axis, colors.ink3, frame.x(i), frame.labelBaseline, Anchor.Middle)
                }
            }
        }
        val frame = PlotFrame(canvas, density, TREND_PADS, labels.size, bottom, top)
        TipLayer(
            pointer?.let(frame::indexAt)?.let { i ->
                ChartTip(
                    anchor = Offset(frame.x(i), series.minOfOrNull { frame.y(it.data.getOrNull(i) ?: bottom) } ?: 0f),
                    label = labels.getOrNull(i),
                    rows = series.map { ChartTipRow(it.color, it.label, format(it.data.getOrNull(i) ?: 0.0)) },
                )
            },
        )
    }
}

private fun DrawScope.drawTrendSeries(series: ChartLine, frame: PlotFrame, ring: Color) {
    val points = series.data.mapIndexed { i, v -> v?.let { Offset(frame.x(i), frame.y(it)) } }
    val runs = runsOf(points)
    if (series.fill) {
        val highest = points.filterNotNull().minOfOrNull { it.y } ?: frame.floor
        runs.forEach { drawArea(it, highest, frame.floor, series.color) }
    }
    val stroke = Stroke(
        width = (if (series.dashed) 1.8.dp else 2.2.dp).toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (series.dashed) dashes(4.dp, 5.dp) else null,
    )
    val alpha = if (series.dashed) DASHED_ALPHA else 1f
    runs.forEach { drawPath(linePath(it), series.color, alpha = alpha, style = stroke) }
    if (!series.dashed) points.lastOrNull { it != null }?.let { drawMarker(it, 4.dp, series.color, ring) }
}

// -- bars --------------------------------------------------------------------------------------

/** Vertical bars, single or stacked, with gridlines and a label under each. */
@Composable
internal fun BarsChart(
    labels: List<String>,
    bars: List<ChartBar>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 250.dp,
) {
    val colors = analyticsColors
    val text = rememberChartText()
    val density = LocalDensity.current
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val top = ((bars.maxOfOrNull { it.total } ?: 0.0) * BAR_HEADROOM).takeIf { it != 0.0 && !it.isNaN() } ?: 1.0
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize().hover { pointer = it }) {
            canvas = size
            val frame = PlotFrame(size, this, BAR_PADS, bars.size, 0.0, top)
            val band = Band(frame.width, bars.size, this)
            drawGrid(text, colors, frame, BAR_GRID_STEPS, { top * it }, format)
            drawLine(
                colors.grid,
                Offset(frame.left, frame.floor),
                Offset(frame.left + frame.width, frame.floor),
                1.dp.toPx(),
            )
            bars.forEachIndexed { i, bar ->
                drawBar(frame, band, i, bar, top)
                labels.getOrNull(i)?.let {
                    label(
                        text,
                        it,
                        text.axis,
                        colors.ink3,
                        frame.left + band.center(i),
                        frame.labelBaseline,
                        Anchor.Middle,
                    )
                }
            }
        }
        val frame = PlotFrame(canvas, density, BAR_PADS, bars.size, 0.0, top)
        val band = Band(frame.width, bars.size, density)
        TipLayer(
            pointer?.let { band.indexAt(it, frame) }?.let { i ->
                val bar = bars[i]
                val named = bar.segments.size > 1
                ChartTip(
                    anchor = Offset(frame.left + band.center(i), frame.y(bar.total)),
                    label = labels.getOrNull(i),
                    rows = bar.segments.map { ChartTipRow(it.color, if (named) it.label else "", format(it.value)) },
                )
            },
        )
    }
}

/** d3's `scaleBand` with inner and outer padding of 0.4, centred; bars are at most 46 wide. */
private class Band(width: Float, private val count: Int, density: Density) {
    private val step = width / max(1f, count - BAND_PADDING + BAND_PADDING * 2)
    private val start = (width - step * (count - BAND_PADDING)) * HALF
    private val bandwidth = step * (1 - BAND_PADDING)
    val barWidth = min(with(density) { MAX_BAR.toPx() }, bandwidth)

    fun center(i: Int): Float = start + step * i + bandwidth / 2

    /** A bar's whole column answers, not just its painted rectangle — small bars stay hoverable. */
    fun indexAt(pointer: Offset, frame: PlotFrame): Int? {
        if (pointer.y !in frame.top..frame.floor) return null
        val px = pointer.x - frame.left
        return (0 until count).firstOrNull { abs(px - center(it)) <= step / 2 }
    }
}

private fun DrawScope.drawBar(frame: PlotFrame, band: Band, index: Int, bar: ChartBar, top: Double) {
    val x = frame.left + band.center(index) - band.barWidth / 2
    val radius = CornerRadius((if (bar.segments.size > 1) 1.5.dp else 2.5.dp).toPx())
    var cursor = frame.floor
    bar.segments.forEach { part ->
        val h = max(0f, (part.value / top * frame.height).toFloat())
        cursor -= h
        drawRoundRect(part.color, Offset(x, cursor), Size(band.barWidth, h), radius)
    }
}

// -- donut -------------------------------------------------------------------------------------

/**
 * A ring of segments over a track. As in d3's pie, the largest segment starts
 * at twelve o'clock and the rest follow clockwise by size.
 */
@Composable
internal fun DonutChart(
    parts: List<ChartPart>,
    centerLabel: String?,
    centerSub: String?,
    describe: (ChartPart) -> String,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    thickness: Dp = 26.dp,
) {
    val colors = analyticsColors
    val text = rememberChartText()
    val density = LocalDensity.current
    val centerStyle = AnalyticsType.mono(20f, FontWeight.Bold, -0.02f)
    val subStyle = AnalyticsType.mono(9.5f, spacingEm = 0.06f)
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val arcs = remember(parts) { donutArcs(parts) }
    val total = parts.sumOf { max(0.0, it.value) }
    Box(modifier.size(size)) {
        Canvas(Modifier.matchParentSize().hover { pointer = it }) {
            val stroke = thickness.toPx()
            val ring = Size(this.size.width - stroke, this.size.height - stroke)
            drawCircle(colors.donutTrack, radius = ring.width / 2, style = Stroke(stroke))
            arcs.forEach { arc ->
                drawArc(
                    arc.part.color,
                    arc.start,
                    arc.sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = ring,
                    style = Stroke(stroke),
                )
            }
            val mid = this.size.width / 2
            centerLabel?.let { label(text, it, centerStyle, colors.ink, mid, mid - 2.dp.toPx(), Anchor.Middle) }
            centerSub?.let { label(text, it, subStyle, colors.ink3, mid, mid + 15.dp.toPx(), Anchor.Middle) }
        }
        val outer = with(density) { size.toPx() } / 2
        val inner = outer - with(density) { thickness.toPx() }
        TipLayer(
            pointer?.let { p -> arcAt(arcs, p, outer, inner) }?.let { arc ->
                val share = "${(arc.part.value / total * PERCENT).roundToInt()}%"
                ChartTip(
                    anchor = Offset(outer, outer),
                    label = null,
                    rows = listOf(ChartTipRow(arc.part.color, arc.part.label, "${describe(arc.part)} · $share")),
                )
            },
        )
    }
}

private class DonutArc(val part: ChartPart, val start: Float, val sweep: Float)

/** As d3's pie: only positive values take a share of the ring; a negative one is a zero-width arc. */
private fun donutArcs(parts: List<ChartPart>): List<DonutArc> {
    val total = parts.sumOf { max(0.0, it.value) }
    if (total <= 0) return emptyList()
    var cursor = -QUARTER_TURN
    return parts.sortedByDescending { it.value }.map { part ->
        val sweep = (max(0.0, part.value) / total * FULL_TURN).toFloat()
        DonutArc(part, cursor, sweep).also { cursor += sweep }
    }
}

/**
 * Every other week, and the last — but not the one just before a last week
 * that falls between two labels, where the pair would print as "W33W34".
 */
private fun showsSparseLabel(index: Int, lastIndex: Int): Boolean = when {
    index == lastIndex -> true
    index == lastIndex - 1 -> false
    else -> index % 2 == 0
}

/** The arc under [pointer]: inside the ring, at an angle measured clockwise from twelve o'clock. */
private fun arcAt(arcs: List<DonutArc>, pointer: Offset, outer: Float, inner: Float): DonutArc? {
    val distance = hypot(pointer.x - outer, pointer.y - outer)
    if (distance !in inner..outer) return null
    val degrees = (atan2(pointer.y - outer, pointer.x - outer) * DEGREES / PI).toFloat()
    val angle = (degrees + QUARTER_TURN + FULL_TURN) % FULL_TURN
    return arcs.firstOrNull { angle >= it.start + QUARTER_TURN && angle < it.start + QUARTER_TURN + it.sweep }
}

// -- projection --------------------------------------------------------------------------------

/**
 * Cumulative actual spend (solid, filled) running into the forecast to wrap
 * (dashed) over a shaded forecast region, with the budget line, today's
 * marker and the EFC endpoint.
 */
@Composable
internal fun ProjectionChart(
    view: ForecastView,
    color: Color,
    currencyPrefix: String,
    modifier: Modifier = Modifier,
    height: Dp = 250.dp,
) {
    val colors = analyticsColors
    val text = rememberChartText()
    val density = LocalDensity.current
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val format: (Double) -> String = { v ->
        if (view.inMillions) "$currencyPrefix${oneDecimal(v)}M" else "$currencyPrefix${v.roundToInt()}k"
    }
    val top = ((view.cumulative + view.projection.drop(1) + view.budget).max() * PROJECTION_HEADROOM)
        .takeIf { it > 0 } ?: 1.0
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize().hover { pointer = it }) {
            canvas = size
            val frame = PlotFrame(size, this, PROJECTION_PADS, view.labels.size, 0.0, top)
            drawGrid(text, colors, frame, GRID_STEPS, { top * it }, format)
            val by = frame.y(view.budget)
            drawDashedAcross(frame, by, colors.ink3)
            label(
                text,
                "Budget ${view.budgetText}",
                text.marker,
                colors.ink2,
                frame.left + 6.dp.toPx(),
                by - 6.dp.toPx(),
            )
            drawProjectionLines(text, colors, frame, view, color)
            view.labels.forEachIndexed { i, week ->
                if (showsSparseLabel(i, view.labels.lastIndex)) {
                    label(text, week, text.axis, colors.ink3, frame.x(i), frame.labelBaseline, Anchor.Middle)
                }
            }
        }
        val frame = PlotFrame(canvas, density, PROJECTION_PADS, view.labels.size, 0.0, top)
        TipLayer(pointer?.let(frame::indexAt)?.let { i -> projectionTip(view, frame, i, color, format) })
    }
}

private fun projectionTip(
    view: ForecastView,
    frame: PlotFrame,
    index: Int,
    color: Color,
    format: (Double) -> String,
): ChartTip? {
    val current = view.currentWeek - 1
    val value = (if (index <= current) view.cumulative.getOrNull(index) else view.projection.getOrNull(index - current))
        ?: return null
    val forecast = index > current && index <= view.projectionEnd
    return ChartTip(
        anchor = Offset(frame.x(index), frame.y(value)),
        label = view.labels.getOrNull(index),
        rows = listOf(ChartTipRow(color, if (forecast) "Forecast" else "Actual", format(value))),
    )
}

private fun DrawScope.drawProjectionLines(
    text: ChartText,
    colors: AnalyticsColors,
    frame: PlotFrame,
    view: ForecastView,
    color: Color,
) {
    val current = view.currentWeek - 1
    val end = view.projectionEnd
    drawRect(
        colors.forecastShade,
        Offset(frame.x(current), frame.top),
        Size(max(0f, frame.x(end) - frame.x(current)), frame.height),
    )
    val actual = view.cumulative.mapIndexed { i, v -> Offset(frame.x(i), frame.y(v)) }
    if (actual.isNotEmpty()) {
        drawArea(actual, actual.minOf { it.y }, frame.floor, color)
        drawPath(linePath(actual), color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val forecast = view.projection.mapIndexed { i, v -> Offset(frame.x(current + i), frame.y(v)) }
    if (forecast.isNotEmpty()) {
        val dashed = Stroke(
            2.2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dashes(5.dp, 5.dp),
        )
        drawPath(linePath(forecast), color, alpha = FORECAST_ALPHA, style = dashed)
    }
    if (actual.isNotEmpty()) {
        val x = frame.x(current)
        drawLine(
            colors.amber,
            Offset(x, frame.top),
            Offset(x, frame.floor),
            1.4.dp.toPx(),
            pathEffect = dashes(4.dp, 4.dp),
        )
        drawMarker(Offset(x, actual.last().y), 4.5.dp, colors.amber, colors.surface)
        label(text, "TODAY · W${view.currentWeek}", text.today, colors.amber, x + 7.dp.toPx(), frame.top + 12.dp.toPx())
    }
    view.projection.lastOrNull()?.let { efc ->
        val point = Offset(frame.x(end), frame.y(efc))
        drawMarker(point, 4.5.dp, color, colors.surface)
        val baseline = if (efc >= view.budget) point.y - 8.dp.toPx() else point.y + 16.dp.toPx()
        label(text, "EFC ${view.efcText}", text.markerBig, color, point.x + 6.dp.toPx(), baseline)
    }
}

/** `toFixed(1)`. */
private fun oneDecimal(value: Double): String {
    val tenths = floor(value * TENTHS + HALF).toLong()
    val sign = if (tenths < 0) "-" else ""
    return "$sign${abs(tenths) / TENTHS.toLong()}.${abs(tenths) % TENTHS.toLong()}"
}

private val TIP_GAP = 10.dp
private val AXIS_LABEL_DROP = 20.dp
private val MAX_BAR = 46.dp
private val SHADOW = Color(0x470F1115)
private val TREND_PADS = Pads(top = 14.dp, right = 14.dp, bottom = 28.dp, left = 46.dp)
private val BAR_PADS = Pads(top = 16.dp, right = 8.dp, bottom = 28.dp, left = 46.dp)
private val PROJECTION_PADS = Pads(top = 16.dp, right = 118.dp, bottom = 28.dp, left = 52.dp)
private val GRID_STEPS = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
private val BAR_GRID_STEPS = listOf(0.25, 0.5, 0.75, 1.0)
private const val TREND_HEADROOM = 1.08
private const val BAR_HEADROOM = 1.12
private const val PROJECTION_HEADROOM = 1.1
private const val SPARSE_LABELS = 8
private const val AREA_TOP_ALPHA = 0.2f
private const val AREA_BOTTOM_ALPHA = 0.02f
private const val DASHED_ALPHA = 0.7f
private const val FORECAST_ALPHA = 0.75f
private const val BAND_PADDING = 0.4f
private const val HALF = 0.5f
private const val TENTHS = 10.0
private const val PERCENT = 100.0
private const val DEGREES = 180.0
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
