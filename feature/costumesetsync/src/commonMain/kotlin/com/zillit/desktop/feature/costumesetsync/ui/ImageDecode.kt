package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a JPEG/PNG/WebP/GIF/BMP into a bitmap, or null when the bytes are not a picture it understands (HEIC, a truncated file). */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap?
