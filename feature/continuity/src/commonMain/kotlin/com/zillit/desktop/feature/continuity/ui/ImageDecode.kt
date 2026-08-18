package com.zillit.desktop.feature.continuity.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Encoded image bytes → a bitmap, or null when the bytes are not an image. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
