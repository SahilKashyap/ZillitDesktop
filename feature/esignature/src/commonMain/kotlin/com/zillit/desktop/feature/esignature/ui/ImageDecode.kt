package com.zillit.desktop.feature.esignature.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Encoded bytes as a bitmap; Skia lives behind the actual. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
