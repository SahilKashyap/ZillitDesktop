package com.zillit.desktop.core.media

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * A line of text laid over the picture: what it says, its colour, its size in
 * image pixels, and where its top-left corner sits — image pixels again, for
 * the same reason [PenStroke] uses them.
 */
data class PlacedText(val text: String, val color: Color, val sizePx: Float, val position: Offset)

/**
 * The text tool between frames — the line being typed, the colour and size
 * chosen, and the lines already placed. Android's `AddTextFragment` types
 * into a field, picks a colour, and drops a draggable sticker on the
 * picture; this is that, minus the sticker's rotate handle.
 */
class TextToolState {
    var texts by mutableStateOf<List<PlacedText>>(emptyList())
    var input by mutableStateOf("")
    var color by mutableStateOf(PEN_COLORS[TEXT_DEFAULT_COLOR])
    var sizeIndex by mutableStateOf(1)

    val isEmpty: Boolean get() = texts.isEmpty()

    /** Places the typed line at [at] and clears the field for the next one. */
    fun place(at: Offset, sizePx: Float) {
        val line = input.trim()
        if (line.isEmpty()) return
        texts = texts + PlacedText(line, color, sizePx, at)
        input = ""
    }

    fun undo() {
        texts = texts.dropLast(1)
    }

    fun clear() {
        texts = emptyList()
    }

    /** Moves the [index]th line by [delta] image pixels — the drag. */
    fun move(index: Int, delta: Offset) {
        texts = texts.mapIndexed { i, placed ->
            if (i == index) placed.copy(position = placed.position + delta) else placed
        }
    }
}

/**
 * The tool's gestures: press on a placed line and drag it. Positions arrive
 * in fitted pixels and are divided by [scale] into image pixels; the line
 * under the press is found with [bounds], which the canvas measures.
 */
fun Modifier.textDragGestures(
    tool: TextToolState,
    scale: Float,
    bounds: (PlacedText) -> Rect,
): Modifier = pointerInput(tool, scale) {
    var dragging = -1
    detectDragGestures(
        onDragStart = { at ->
            val point = at / scale
            // Topmost first: the last placed line is drawn last, so it wins a
            // press where two overlap.
            dragging = tool.texts.indexOfLast { bounds(it).contains(point) }
        },
        onDrag = { change, delta ->
            if (dragging >= 0) {
                change.consume()
                tool.move(dragging, delta / scale)
            }
        },
        onDragEnd = { dragging = -1 },
        onDragCancel = { dragging = -1 },
    )
}

/**
 * Lays out one line at its image-pixel size. Measured at density 1 so a
 * size in pixels is a size in pixels wherever the dialog is shown — the
 * screen's density would otherwise scale the composite's text but not the
 * picture under it.
 */
fun TextMeasurer.layoutPlacedText(placed: PlacedText): TextLayoutResult = measure(
    text = AnnotatedString(placed.text),
    style = TextStyle(
        color = placed.color,
        fontSize = placed.sizePx.sp,
        fontWeight = FontWeight.Bold,
        // A soft shadow keeps white text legible over a bright sky and black
        // over a dark set — the phones' text sticker relies on the same.
        shadow = Shadow(
            color = Color.Black.copy(alpha = TEXT_SHADOW_ALPHA),
            blurRadius = placed.sizePx / TEXT_SHADOW_DIVISOR,
        ),
    ),
    density = Density(1f),
)

/** The line's box on the picture, in image pixels. */
fun TextMeasurer.boundsOf(placed: PlacedText): Rect =
    Rect(placed.position, layoutPlacedText(placed).size.let { Size(it.width.toFloat(), it.height.toFloat()) })

/** Draws every placed line in image pixels; wrap in a `scale` for the fitted canvas. */
fun DrawScope.drawPlacedTexts(texts: List<PlacedText>, measurer: TextMeasurer) {
    texts.forEach { placed ->
        drawText(measurer.layoutPlacedText(placed), topLeft = placed.position)
    }
}

/**
 * Text size in image pixels — relative to the picture, as the pen width is,
 * so a caption reads the same on a phone snap and a large still.
 */
fun textSizeFor(bitmap: ImageBitmap, sizeIndex: Int): Float =
    maxOf(bitmap.width, bitmap.height) / TEXT_UNIT_DIVISOR * TEXT_SIZES[sizeIndex]

/** Multipliers of the picture-relative unit — small, regular, large. */
val TEXT_SIZES: List<Float> = listOf(0.7f, 1.0f, 1.5f)
private const val TEXT_UNIT_DIVISOR = 24f
private const val TEXT_DEFAULT_COLOR = 4 // white, Android's `mTextColor = Color.WHITE`
private const val TEXT_SHADOW_ALPHA = 0.6f
private const val TEXT_SHADOW_DIVISOR = 10f
