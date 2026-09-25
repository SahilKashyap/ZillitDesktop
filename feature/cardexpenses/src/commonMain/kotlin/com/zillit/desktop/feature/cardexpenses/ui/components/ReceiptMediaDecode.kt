package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A receipt's document as pages to look at: an image is one page, a PDF its
 * rendered pages. Empty when the bytes will not open — the preview says so
 * rather than showing nothing. The invoices module's `decodePreviewPages`,
 * for the same reason: the web's `<iframe>` has no Compose equivalent.
 */
internal expect fun decodeReceiptPages(bytes: ByteArray): List<ImageBitmap>
