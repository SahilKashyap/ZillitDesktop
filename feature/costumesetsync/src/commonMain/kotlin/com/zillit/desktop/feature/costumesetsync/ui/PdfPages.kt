package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.ui.graphics.ImageBitmap

/** One page's size in PDF points (1/72 inch), rotation already applied. */
internal class PageSize(val width: Float, val height: Float)

/**
 * An opened PDF, rendered a page at a time. The pages' sizes are known up front so a scrolling list can reserve
 * room for every page; the pixels are made on demand by [render], off the caller's thread, and must be [close]d.
 */
internal interface PdfPages {
    val sizes: List<PageSize>

    /** Page [index] drawn [widthPx] wide, or null when this page cannot be drawn (the rest still can). */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?

    fun close()
}

/** Opens [bytes] as a PDF, or null when they are not one it can read (encrypted, truncated, no pages). */
internal expect fun openPdf(bytes: ByteArray): PdfPages?
