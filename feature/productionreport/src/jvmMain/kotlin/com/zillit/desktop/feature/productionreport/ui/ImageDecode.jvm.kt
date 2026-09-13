package com.zillit.desktop.feature.productionreport.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Surface

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun renderSignaturePng(strokes: List<List<Offset>>, width: Int, height: Int, strokeWidth: Float): ByteArray? =
    runCatching {
        val surface = Surface.makeRasterN32Premul(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val canvas: Canvas = surface.canvas
        val paint = Paint().apply {
            color = INK
            mode = PaintMode.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            isAntiAlias = true
        }
        strokes.filter { it.isNotEmpty() }.forEach { points ->
            // Round-capped segments join into one smooth stroke; a tap still leaves a dot.
            val segments = if (points.size == 1) listOf(
                points.first(),
                points.first().copy(x = points.first().x + 0.1f),
            ) else points
            segments.zipWithNext { from, to -> canvas.drawLine(from.x, from.y, to.x, to.y, paint) }
        }
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
    }.getOrNull()

/** The web's signature ink: near-black on a transparent ground. */
private const val INK = 0xFF1D2939.toInt()
