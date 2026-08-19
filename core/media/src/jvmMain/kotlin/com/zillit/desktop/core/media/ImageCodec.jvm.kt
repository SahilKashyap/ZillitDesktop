package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun encodeImage(image: ImageBitmap, encoding: ImageEncoding, quality: Int): ByteArray? =
    runCatching {
        val format = when (encoding) {
            ImageEncoding.Jpeg -> EncodedImageFormat.JPEG
            ImageEncoding.Png -> EncodedImageFormat.PNG
        }
        Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(format, quality)?.bytes
    }.getOrNull()
