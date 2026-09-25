package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image

internal actual fun decodeReceiptPages(bytes: ByteArray): List<ImageBitmap> {
    if (bytes.isEmpty()) return emptyList()
    return if (bytes.looksLikePdf()) pdfPages(bytes) else listOfNotNull(image(bytes))
}

private fun image(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** `%PDF` — the magic number, which is more honest than the file name. */
private fun ByteArray.looksLikePdf(): Boolean =
    size > PDF_MAGIC.size && PDF_MAGIC.indices.all { this[it] == PDF_MAGIC[it] }

/** A receipt is a page or two; [MAX_PAGES] keeps a stray long document from holding the window. */
private fun pdfPages(bytes: ByteArray): List<ImageBitmap> = try {
    Loader.loadPDF(bytes).use { document ->
        val renderer = PDFRenderer(document)
        (0 until minOf(document.numberOfPages, MAX_PAGES)).mapNotNull { page ->
            runCatching { renderer.renderImageWithDPI(page, RENDER_DPI).toComposeImageBitmap() }.getOrNull()
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no preview for this receipt: ${throwable::class.simpleName}" }
    emptyList()
}

private val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
private const val RENDER_DPI = 110f
private const val MAX_PAGES = 10
private const val TAG = "CardReceiptPreview"
