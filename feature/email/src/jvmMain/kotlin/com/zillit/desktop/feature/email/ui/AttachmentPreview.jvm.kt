package com.zillit.desktop.feature.email.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer

internal actual fun renderPdfPages(bytes: ByteArray): List<ImageBitmap> = try {
    Loader.loadPDF(bytes).use { document ->
        val renderer = PDFRenderer(document)
        (0 until minOf(document.numberOfPages, MAX_PAGES)).mapNotNull { page ->
            runCatching { renderer.renderImageWithDPI(page, RENDER_DPI).toComposeImageBitmap() }.getOrNull()
        }
    }
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    ZillitLog.d(TAG) { "no preview for this PDF: ${throwable::class.simpleName}" }
    emptyList()
}

private const val RENDER_DPI = 110f
private const val MAX_PAGES = 30
private const val TAG = "Email"
