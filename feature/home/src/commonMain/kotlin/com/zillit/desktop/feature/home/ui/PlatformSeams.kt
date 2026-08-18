package com.zillit.desktop.feature.home.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encodes a bitmap as JPEG at [quality] (0..100), or null when the platform
 * cannot — the image reply's marked-up picture becomes the bytes it posts.
 * JPEG rather than PNG because a photo with a few pen strokes is still a
 * photo, and both phones' editors write JPEG (`resizeActualImage`, 0.6/0.8).
 */
expect fun encodeImageJpeg(image: ImageBitmap, quality: Int): ByteArray?
