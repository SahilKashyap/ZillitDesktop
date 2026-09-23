package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

/**
 * Decodes encoded image bytes (JPEG, PNG, WebP…) for display and editing.
 *
 * Null on anything undecodable — a corrupt pick must fall back to the file
 * glyph, not crash the dialog. The same seam every feature module carried
 * privately (`feature/home/ui/ImageDecode.kt` and its siblings); it lives
 * here so the preview and the chat composer share one.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * [decodeImageBitmap], shrunk so its longer side is at most [maxSide] pixels.
 *
 * For pictures only ever shown small. A profile photo arrives as the phone's
 * original upload — 12 megapixels is ordinary — and drawing that into a 32dp
 * circle means scaling the whole photo on every frame the row is on screen:
 * a list of crew stuttered as it scrolled, fifteen full-size photos a frame.
 * A picture already small enough is returned as decoded.
 */
expect fun decodeImageBitmap(bytes: ByteArray, maxSide: Int): ImageBitmap?

/** [width] × [height] fitted inside a [maxSide] square, aspect kept; unchanged when it already fits. */
fun fittedSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxSide || longest <= 0) return width to height
    val scale = maxSide.toDouble() / longest
    // Rounded, not truncated: 10000 × (384 / 10000) is 383.99…, and the long
    // side should land on the cap, not a pixel short of it.
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** The two encodings an edited picture can leave in. */
enum class ImageEncoding { Jpeg, Png }

/**
 * Encodes a bitmap, or null when the platform cannot — the editor's output
 * becomes the bytes that upload.
 *
 * JPEG at [quality] (0..100) for photos: a picture with a few pen strokes is
 * still a photo, and both phones' editors write JPEG (`resizeActualImage`,
 * 0.6/0.8). PNG is kept for pictures that arrived as PNG — screenshots and
 * diagrams with flat colour and text, which JPEG smears.
 */
expect fun encodeImage(image: ImageBitmap, encoding: ImageEncoding, quality: Int): ByteArray?

/** JPEG at [quality]; see [encodeImage]. */
fun encodeImageJpeg(image: ImageBitmap, quality: Int): ByteArray? = encodeImage(image, ImageEncoding.Jpeg, quality)
