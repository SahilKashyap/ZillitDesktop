package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun decodeImageBitmap(bytes: ByteArray, maxSide: Int): ImageBitmap? =
    runCatching {
        val image = Image.makeFromEncoded(bytes)
        val (width, height) = fittedSize(image.width, image.height, maxSide)
        if (width == image.width && height == image.height) return@runCatching image.toComposeImageBitmap()
        Surface.makeRasterN32Premul(width, height).use { surface ->
            // Mitchell: a cubic, so a large downscale keeps the face's edges
            // rather than shimmering the way plain linear sampling does.
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                SamplingMode.MITCHELL,
                null,
                true,
            )
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }.getOrNull()

actual fun encodeImage(image: ImageBitmap, encoding: ImageEncoding, quality: Int): ByteArray? =
    runCatching {
        val format = when (encoding) {
            ImageEncoding.Jpeg -> EncodedImageFormat.JPEG
            ImageEncoding.Png -> EncodedImageFormat.PNG
        }
        Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(format, quality)?.bytes
    }.getOrNull()
