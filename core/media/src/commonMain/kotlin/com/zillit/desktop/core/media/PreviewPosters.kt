package com.zillit.desktop.core.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the preview shows for a file that is not a picture: a poster — a
 * PDF's first page, a clip's frame — and, for a document, its page count.
 */
class PreviewPoster(
    val jpegBytes: ByteArray?,
    /** A PDF's pages; null where the file has no pages to count. */
    val pages: Int? = null,
) {
    override fun toString(): String = "PreviewPoster(pages=$pages, poster=${jpegBytes?.size ?: 0} B)"
}

/**
 * Renders [PreviewPoster]s for the preview — WhatsApp's first page of a PDF
 * under its name and page count, a clip's frame. Rendering PDFs and video
 * needs the platform (PDFBox, the video decoder), which this module does not
 * carry, so the app installs one at its root; without one, files show their
 * badge.
 */
fun interface PreviewPosterMaker {
    /** Null when there is nothing to show; never throws. Called off the UI thread. */
    suspend fun poster(item: PreviewItem): PreviewPoster?
}

/** The app's poster maker; null where none is installed (tests, previews). */
val LocalPreviewPosterMaker = staticCompositionLocalOf<PreviewPosterMaker?> { null }
