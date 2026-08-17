package com.zillit.desktop.feature.home.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes encoded image bytes (JPEG, PNG, WebP…) for display.
 *
 * Null on anything undecodable — a corrupt upload must render as the file chip
 * fallback, not crash the board.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
