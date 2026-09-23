package com.zillit.desktop.feature.invoices.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Encoded image bytes → a bitmap, or null when the bytes are not an image. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * An attachment as pages to look at.
 *
 * An image is one page; a PDF is all of its pages, rendered. The web puts the
 * document in an `<iframe>` and lets the browser's viewer scroll it — a
 * Compose window has no such viewer, so the pages are rendered here and
 * stacked, which is what the reader sees either way.
 *
 * Empty for anything that will not open: encrypted, truncated, or a file type
 * with no picture in it. The pane says so rather than showing nothing.
 */
expect fun decodePreviewPages(bytes: ByteArray): List<ImageBitmap>

/**
 * How many pages a PDF has, or null when it will not open (corrupted,
 * password-protected). The bulk upload refuses a PDF past the extractor's
 * page cap before anything is sent — the web's `validateInvoiceFile`.
 */
expect fun pdfPageCount(bytes: ByteArray): Int?
