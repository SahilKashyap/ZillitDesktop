package com.zillit.desktop.core.media

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** A picture's thumbnail: JPEG bytes and the size they decode to. */
class ImageThumbnail(val jpegBytes: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * A small JPEG of a picture, for the message's `thumbnail` — what the other
 * clients load in a thread or on a board before anyone opens the full file.
 *
 * Shrunk to fit [maxEdge] (never enlarged), with Mitchell sampling so edges
 * stay crisp, and flattened onto white: JPEG has no transparency, and a
 * transparent PNG encoded as-is comes out black where it was clear. Skia
 * reads a phone photo's EXIF orientation, so the thumbnail stands the way
 * the photo does.
 *
 * Null for anything Skia cannot decode (HEIC, a damaged file); the upload
 * then goes without a thumbnail, as it always did.
 */
fun imageThumbnailJpeg(
    bytes: ByteArray,
    maxEdge: Int = IMAGE_THUMBNAIL_EDGE,
    quality: Int = IMAGE_THUMBNAIL_QUALITY,
): ImageThumbnail? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    val (width, height) = fittedSize(image.width, image.height, maxEdge)
    Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(Color.WHITE)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            SamplingMode.MITCHELL,
            null,
            true,
        )
        val jpeg = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
            ?: return@runCatching null
        ImageThumbnail(jpeg, width, height)
    }
}.getOrNull()

/** 720 px on the long edge: sharp in a 240 dp bubble on a 2× screen, and still tens of kilobytes. */
const val IMAGE_THUMBNAIL_EDGE = 720
private const val IMAGE_THUMBNAIL_QUALITY = 82
