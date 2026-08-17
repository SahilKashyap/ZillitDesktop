package com.zillit.desktop.feature.callsheet.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Encoded page bytes → a bitmap, or null when the bytes are not an image. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
