package com.zillit.desktop.feature.dealmemo.ui.documents

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitResult

/** A page rendered for reading or signing, with its size in PDF points. */
class DealPdfPage(val image: ImageBitmap, val widthPt: Float, val heightPt: Float) {
    /** Height over width — what a page of any rendered width keeps. */
    val aspect: Float get() = if (widthPt > 0f) heightPt / widthPt else A4_ASPECT

    private companion object {
        const val A4_ASPECT = 1.414f
    }
}

/**
 * Where one signature lands (`DMSignDocumentModal.jsx` `measurePlacement`): a
 * page, and its left, top and width as fractions of that page.
 */
data class DealPlacement(val pageIndex: Int, val fx: Double, val fy: Double, val fw: Double)

/**
 * The typed-signature styles (`signatureStyles.js`). The web draws Google
 * script faces; the desktop draws whichever of each style's faces the
 * machine has, falling back to an italic serif, so what the signer picks is
 * what is stamped.
 */
@Suppress("MagicNumber") // Each style's size against the others, so every face signs at one visual weight.
enum class SignatureFont(val label: String, val families: List<String>, val scale: Float) {
    Formal("Formal", listOf("Snell Roundhand", "Great Vibes", "Segoe Script"), 1.00f),
    Flowing("Flowing", listOf("Apple Chancery", "Dancing Script", "Lucida Handwriting"), 0.92f),
    Casual("Casual", listOf("Bradley Hand", "Caveat", "Ink Free"), 1.05f),
    Slim("Slim", listOf("Savoye LET", "Sacramento", "Gabriola"), 1.10f),
    Bold("Bold", listOf("Brush Script MT", "Yellowtail", "Mistral"), 0.90f),
    Natural("Natural", listOf("Zapfino", "Homemade Apple", "Segoe Print"), 0.62f),
}

/**
 * The PDF and image work the deal page does itself — the web's pdf.js and
 * pdf-lib: render pages, flatten a signature into them, and make signature
 * images from strokes or typed text.
 */
interface DealPdfWork {

    /** Every page at [widthPx] wide, up to [maxPages]. */
    fun pages(pdf: ByteArray, widthPx: Int, maxPages: Int = MAX_PAGES): ZillitResult<List<DealPdfPage>>

    /**
     * Draws [png] at each placement and answers the new document — the act of
     * signing: the mark becomes part of the page, and only the finished file
     * travels. Width `fw × page width`, height from the image's own aspect.
     */
    fun stamp(pdf: ByteArray, png: ByteArray, placements: List<DealPlacement>): ZillitResult<ByteArray>

    fun image(bytes: ByteArray): ImageBitmap?

    /** A drawn signature on a transparent PNG, [strokes] in a [width]×[height] canvas. */
    fun ink(strokes: List<List<Offset>>, width: Int, height: Int): ByteArray?

    /** A typed name as a transparent signature PNG, fitted to the web's 600×200 box at 2×. */
    fun typed(text: String, font: SignatureFont): ByteArray?

    companion object {
        const val MAX_PAGES = 60
    }
}

/** The platform's PDF and image work. */
expect fun platformPdfWork(): DealPdfWork
