package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes encoded image bytes (JPEG, PNG, WebP…) for display and editing.
 *
 * Null on anything undecodable — a corrupt pick must fall back to the file
 * glyph, not crash the dialog. The same seam every feature module carried
 * privately (`feature/home/ui/ImageDecode.kt` and its siblings); it lives
 * here so the preview and the chat composer share one.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

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
