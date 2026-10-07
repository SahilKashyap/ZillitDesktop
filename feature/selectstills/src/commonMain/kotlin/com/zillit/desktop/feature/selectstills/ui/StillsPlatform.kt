package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an encoded image (a thumbnail, a preview, a face crop). Null when it cannot be read. */
expect fun decodeStillBitmap(bytes: ByteArray): ImageBitmap?

/**
 * The OS folder chooser — "or choose a folder" on the upload page, where a
 * photographer points at the card rather than selecting 500 files.
 *
 * Null when the reader cancelled or the panel could not be shown.
 */
expect suspend fun chooseStillsFolder(title: String): String?
