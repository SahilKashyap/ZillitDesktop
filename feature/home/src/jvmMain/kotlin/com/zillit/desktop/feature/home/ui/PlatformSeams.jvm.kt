package com.zillit.desktop.feature.home.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun encodeImageJpeg(image: ImageBitmap, quality: Int): ByteArray? =
    runCatching {
        Image.makeFromBitmap(image.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.JPEG, quality)
            ?.bytes
    }.getOrNull()
