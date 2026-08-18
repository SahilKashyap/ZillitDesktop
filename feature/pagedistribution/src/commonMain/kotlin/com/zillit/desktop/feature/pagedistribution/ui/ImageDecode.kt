package com.zillit.desktop.feature.pagedistribution.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Encoded page bytes → a bitmap, or null when the bytes are not an image. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
