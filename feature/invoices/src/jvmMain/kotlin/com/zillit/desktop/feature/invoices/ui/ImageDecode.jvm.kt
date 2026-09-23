package com.zillit.desktop.feature.invoices.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun decodePreviewPages(bytes: ByteArray): List<ImageBitmap> {
    if (bytes.isEmpty()) return emptyList()
    return if (bytes.looksLikePdf()) pdfPages(bytes) else listOfNotNull(decodeImageBitmap(bytes))
}

actual fun pdfPageCount(bytes: ByteArray): Int? = try {
    Loader.loadPDF(bytes).use { it.numberOfPages }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "unreadable PDF: ${throwable::class.simpleName}" }
    null
}

/** `%PDF` — the magic number, which is more honest than the file name. */
private fun ByteArray.looksLikePdf(): Boolean =
    size > PDF_MAGIC.size && PDF_MAGIC.indices.all { this[it] == PDF_MAGIC[it] }

/**
 * Pages at reading resolution.
 *
 * [MAX_PAGES] is a guard, not a preference: an invoice with a 300-page
 * appendix would otherwise hold the window while it rasterised the lot, and
 * nobody reads page 40 of a supplier invoice inside a modal.
 */
private fun pdfPages(bytes: ByteArray): List<ImageBitmap> = try {
    Loader.loadPDF(bytes).use { document ->
        val renderer = PDFRenderer(document)
        (0 until minOf(document.numberOfPages, MAX_PAGES)).mapNotNull { page ->
            runCatching { renderer.renderImageWithDPI(page, RENDER_DPI).toComposeImageBitmap() }.getOrNull()
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no preview for this document: ${throwable::class.simpleName}" }
    emptyList()
}

private val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
private const val RENDER_DPI = 110f
private const val MAX_PAGES = 30
private const val TAG = "InvoicePreview"
