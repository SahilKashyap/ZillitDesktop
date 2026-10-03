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
fun pdfThumbnailJpeg(
    bytes: ByteArray,
    /** Higher for a page shown large — the media editor's stage. */
    dpi: Float = RENDER_DPI,
    maxEdge: Int = MAX_EDGE,
): PosterFrame? = try {
    Loader.loadPDF(bytes).use { document ->
        if (document.numberOfPages < 1) {
            null
        } else {
            PDFRenderer(document)
                .renderImageWithDPI(0, dpi)
                .toPosterFrame(maxEdge)
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no thumbnail for this document: ${throwable::class.simpleName}" }
    null
}

/**
 * How many pages a PDF has — the "3 pages" under its name in the media
 * editor. Null for anything that will not open as one; never a throw.
 */
fun pdfPageCount(bytes: ByteArray): Int? = try {
    Loader.loadPDF(bytes).use { document -> document.numberOfPages.takeIf { it > 0 } }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no page count for this document: ${throwable::class.simpleName}" }
    null
}

private const val TAG = "PdfThumbnails"
private const val RENDER_DPI = 72f
private const val MAX_EDGE = 480
