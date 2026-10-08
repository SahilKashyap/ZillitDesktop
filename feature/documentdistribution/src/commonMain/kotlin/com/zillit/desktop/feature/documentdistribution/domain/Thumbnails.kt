package com.zillit.desktop.feature.documentdistribution.domain

import kotlin.math.min

/**
 * Thumbnails for Document Distribution (the web's `utils/thumbnail.js`).
 *
 * The backend stores whatever `thumbnail` key a document is created with and
 * returns it on the row (`attachment.thumbnail`), but never MAKES one: an image
 * is its own thumbnail, and a PDF — most of this module — gets nothing unless
 * the client renders page 1 at upload time, while it already holds the bytes.
 * Word and Excel have no renderer here, so they keep their file-type icon.
 */
object Thumbnails {
    /** Rendered width in pixels: twice the card's preview area, for a sharp HiDPI card. */
    const val WIDTH = 480

    /** A ceiling on height, so a scroll or a long receipt does not become a huge canvas. */
    const val MAX_HEIGHT = 960

    /** JPEG quality: visually clean at this size, a few tens of kilobytes. */
    const val JPEG_QUALITY = 0.8f

    /**
     * How long an upload waits for its thumbnail. Rendering page 1 takes a second
     * or two; a damaged PDF can stall with no error at all, and must not hold the
     * document hostage — past this the document goes without one.
     */
    const val DEADLINE_MILLIS = 15_000L
}

/**
 * Whether the client has to make this file's thumbnail itself: only PDFs.
 * Judged by name as well as type, because uploads routinely arrive typed
 * `application/octet-stream` whatever they really are.
 */
fun needsGeneratedThumbnail(contentType: String?, name: String?): Boolean =
    contentType.orEmpty().contains("pdf", ignoreCase = true) || name.orEmpty().endsWith(".pdf", ignoreCase = true)

/** An image is used as its own thumbnail — the storage helper's rule on the web. */
fun isItsOwnThumbnail(contentType: String?, name: String?): Boolean =
    fileKindOf(contentType, name) == FileKind.Image

/** The page-1 scale that fits the target width without exceeding the height ceiling. */
fun thumbnailScale(
    pageWidth: Double,
    pageHeight: Double,
    width: Int = Thumbnails.WIDTH,
    maxHeight: Int = Thumbnails.MAX_HEIGHT,
): Double {
    if (pageWidth <= 0.0 || pageHeight <= 0.0) return 1.0
    return min(width / pageWidth, maxHeight / pageHeight)
}

/** `Call Sheet.pdf` → `Call Sheet_thumb.jpg`. */
fun thumbnailFileName(name: String): String =
    "${name.substringBeforeLast('.', name).ifBlank { "document" }}_thumb.jpg"
