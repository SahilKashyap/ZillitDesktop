package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * The first page of a PDF as a poster frame.
 *
 * What the web's `generatePdfThumbnail` does with pdf.js and a canvas, done
 * here with PDFBox. Page one at reading resolution: a call sheet's cover page
 * is its identity, and 72 dpi scaled to the poster edge is plenty for a
 * 300 px bubble.
 *
 * Null for anything that will not open as a PDF — encrypted, truncated, or
 * simply not a PDF — and never a throw: the chip without a poster is the
 * fallback, not a failure.
 */
fun pdfThumbnailJpeg(bytes: ByteArray): PosterFrame? = try {
    Loader.loadPDF(bytes).use { document ->
        if (document.numberOfPages < 1) {
            null
        } else {
            PDFRenderer(document)
                .renderImageWithDPI(0, RENDER_DPI)
                .toPosterFrame(MAX_EDGE)
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no thumbnail for this document: ${throwable::class.simpleName}" }
    null
}

private const val TAG = "PdfThumbnails"
private const val RENDER_DPI = 72f
private const val MAX_EDGE = 480
