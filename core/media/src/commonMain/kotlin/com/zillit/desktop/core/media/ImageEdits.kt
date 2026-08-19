package com.zillit.desktop.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * The raster side of the editor: each of these draws into a fresh bitmap
 * with the same `DrawScope` routines the on-screen canvas uses, so what was
 * seen is what is sent. Density 1 and pixel sizes throughout — these are
 * image operations, and the screen's scale has no business in them.
 */

/**
 * The picture with the strokes and text burned in, at full size — the
 * editor's output. [measurer] lays the text out; null skips the text (the
 * image reply has none and need not carry a measurer).
 */
fun composite(
    source: ImageBitmap,
    strokes: List<PenStroke>,
    texts: List<PlacedText> = emptyList(),
    measurer: TextMeasurer? = null,
): ImageBitmap = rasterise(IntSize(source.width, source.height)) {
    drawImage(source)
    drawStrokes(strokes)
    if (measurer != null) drawPlacedTexts(texts, measurer)
}

/** The [rect] of [source] as its own picture — the crop tool's Apply. */
fun cropBitmap(source: ImageBitmap, rect: IntRect): ImageBitmap = rasterise(rect.size) {
    drawImage(
        image = source,
        srcOffset = IntOffset(rect.left, rect.top),
        srcSize = rect.size,
        dstOffset = IntOffset.Zero,
        dstSize = rect.size,
    )
}

/**
 * [source] turned a quarter turn clockwise — the phones' rotate button.
 * The new picture is as tall as the old one was wide; the old picture is
 * rotated about the origin and slid right by its own height so it lands
 * inside the new bounds.
 */
fun rotateQuarterTurn(source: ImageBitmap): ImageBitmap =
    rasterise(IntSize(source.height, source.width)) {
        withTransform({
            translate(left = source.height.toFloat())
            rotate(QUARTER_TURN_DEGREES, pivot = Offset.Zero)
        }) {
            drawImage(source)
        }
    }

/** A new bitmap of [size], drawn by [block] at density 1. */
private fun rasterise(size: IntSize, block: DrawScope.() -> Unit): ImageBitmap {
    val out = ImageBitmap(size.width, size.height)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(out),
        size = Size(size.width.toFloat(), size.height.toFloat()),
        block = block,
    )
    return out
}

private const val QUARTER_TURN_DEGREES = 90f
