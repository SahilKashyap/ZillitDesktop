package com.zillit.desktop.feature.productionreport.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

/** Encoded page bytes → a bitmap, or null when the bytes are not an image. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/** A drawn signature — strokes in px within [width] × [height] — as PNG bytes on a transparent ground. */
expect fun renderSignaturePng(strokes: List<List<Offset>>, width: Int, height: Int, strokeWidth: Float): ByteArray?
