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
 * What a video told us about itself — read from its own header, so it costs
 * nothing beyond the bytes already in hand.
 *
 * The preview needs all of it to say how a clip can go: its length and shape
 * to work out what each quality would weigh, its format to warn that nobody
 * will be able to draw it, and whether this machine can re-encode at all.
 */
data class VideoInfo(
    /** How long the clip runs; null where the container would not say. */
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    /**
     * A format most players cannot draw ("AV1", "VP9") — the app's own
     * player, Safari, many phones. Null for everything that plays.
     */
    val unplayableFormat: String? = null,
    /** Whether this machine has a video encoder; without one, only the original can go. */
    val compressible: Boolean = true,
) {
    /** The longer of the two sides, whichever way the camera was held. */
    val longEdge: Int?
        get() = listOfNotNull(width, height).maxOrNull()
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

    /**
     * What a video says about itself — kept apart from [poster] because it is
     * a read of the file's own header and answers at once, where cutting a
     * poster frame from a 2 GB clip copies the file to disk and decodes it.
     * The quality options are priced from this, and a sender should not watch
     * an empty bar while a frame is found.
     */
    suspend fun videoInfo(item: PreviewItem): VideoInfo? = null
}

/** The app's poster maker; null where none is installed (tests, previews). */
val LocalPreviewPosterMaker = staticCompositionLocalOf<PreviewPosterMaker?> { null }
