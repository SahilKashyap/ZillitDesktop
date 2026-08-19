package com.zillit.desktop.core.media

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput

/**
 * One pen stroke in **image** pixels; a single point is a dot.
 *
 * Image pixels regardless of how the picture is scaled to fit its dialog, so
 * the composite draws a stroke where the hand put it: a mark drawn on a
 * picture shown at a third of its size lands on the right third of the real
 * thing.
 */
data class PenStroke(val points: List<Offset>, val color: Color, val width: Float)

/**
 * The pen between frames: strokes so far, the one being drawn, the colour and
 * width chosen. Shared by the image reply and the picked-media editor so the
 * desktop has one pen — the same colours, widths and undo wherever a picture
 * gets marked up (Android's `PaintFragment` is likewise the one paint tool).
 */
class PenState {
    var strokes by mutableStateOf<List<PenStroke>>(emptyList())
    var current by mutableStateOf<PenStroke?>(null)
    var color by mutableStateOf(PEN_COLORS.first())
    var widthIndex by mutableStateOf(1)

    /** Everything to draw this frame — the finished strokes and the live one. */
    val visible: List<PenStroke> get() = strokes + listOfNotNull(current)

    val isEmpty: Boolean get() = strokes.isEmpty() && current == null

    fun undo() {
        strokes = strokes.dropLast(1)
    }

    fun clear() {
        strokes = emptyList()
        current = null
    }
}

/**
 * The pen's gestures: a drag is a stroke, a tap is a dot — the quickest way
 * to mark "this one". Positions arrive in fitted pixels and are divided by
 * [scale] into image pixels as they arrive. Keyed on scale so a resized
 * dialog re-arms with the right ratio.
 */
fun Modifier.penGestures(pen: PenState, scale: Float, penWidth: Float): Modifier = this
    .pointerInput(pen, scale, penWidth) {
        detectDragGestures(
            onDragStart = { at ->
                pen.current = PenStroke(listOf(at / scale), pen.color, penWidth)
            },
            onDrag = { change, _ ->
                change.consume()
                pen.current = pen.current?.let { it.copy(points = it.points + change.position / scale) }
            },
            onDragEnd = {
                pen.current?.let { pen.strokes = pen.strokes + it }
                pen.current = null
            },
            onDragCancel = { pen.current = null },
        )
    }
    .pointerInput(pen, scale, penWidth) {
        detectTapGestures { at ->
            pen.strokes = pen.strokes + PenStroke(listOf(at / scale), pen.color, penWidth)
        }
    }

/**
 * Draws [strokes] in image pixels — a dot for a single point, a round-capped
 * path otherwise. Callers drawing a fitted picture wrap this in a `scale`
 * transform; the composite draws it at 1:1. One routine for both is what
 * makes what was seen what is sent.
 */
fun DrawScope.drawStrokes(strokes: List<PenStroke>) {
    strokes.forEach { stroke ->
        if (stroke.points.size == 1) {
            drawCircle(stroke.color, radius = stroke.width / 2, center = stroke.points[0])
        } else {
            drawPath(
                path = stroke.toPath(),
                color = stroke.color,
                style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** The stroke as a path in image pixels. */
private fun PenStroke.toPath(): Path = Path().apply {
    points.forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
}

/**
 * Pen width in image pixels — relative to the picture, so a stroke reads
 * the same on a phone snap and a 24-megapixel still.
 */
fun penWidthFor(bitmap: ImageBitmap, widthIndex: Int): Float =
    maxOf(bitmap.width, bitmap.height) / PEN_UNIT_DIVISOR * PEN_WIDTHS[widthIndex]

/** The palette both the pen and the text tool offer — Android's paint list, trimmed to what reads on a photo. */
val PEN_COLORS: List<Color> = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFDD835), // yellow
    Color(0xFF43A047), // green
    Color(0xFF1E88E5), // blue
    Color.White,
    Color.Black,
)

/** Multipliers of the picture-relative unit — thin, regular, thick. */
val PEN_WIDTHS: List<Float> = listOf(0.6f, 1.0f, 1.8f)
private const val PEN_UNIT_DIVISOR = 160f
