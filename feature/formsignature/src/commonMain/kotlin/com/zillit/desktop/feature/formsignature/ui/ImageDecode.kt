package com.zillit.desktop.feature.formsignature.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encoded bytes (PNG, JPEG) as a bitmap, or null when they will not decode.
 *
 * Expect/actual because the decoder is Skia, which common code cannot name.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
