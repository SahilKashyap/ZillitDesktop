package com.zillit.desktop.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rubber band the crop tool drags out, as its two corners in image
 * pixels — wherever the drag started and wherever the pointer is now. Kept
 * as corners rather than a rectangle so a drag up-and-left works as well as
 * one down-and-right; [cropSelection] normalises.
 */
data class CropBand(val start: Offset, val end: Offset)

/**
 * The band as the rectangle that will be cut, clamped to the picture and
 * rounded to whole pixels; null when it is too small to mean anything.
 *
 * Android's `CropImageView` keeps its rectangle inside the bitmap's bounds
 * the same way (`CropFragment.java:234-254` maps and clamps before
 * `Bitmap.createBitmap`). A band under [MIN_CROP_PX] on either side is a
 * slipped click, not a crop, and applying it would leave a picture nobody
 * can see.
 */
fun cropSelection(band: CropBand, image: IntSize): IntRect? {
    val left = min(band.start.x, band.end.x).roundToInt().coerceIn(0, image.width)
    val right = max(band.start.x, band.end.x).roundToInt().coerceIn(0, image.width)
    val top = min(band.start.y, band.end.y).roundToInt().coerceIn(0, image.height)
    val bottom = max(band.start.y, band.end.y).roundToInt().coerceIn(0, image.height)
    if (right - left < MIN_CROP_PX || bottom - top < MIN_CROP_PX) return null
    return IntRect(left, top, right, bottom)
}

/**
 * The scale that fits [image] inside [box] without cropping — the ratio the
 * editor divides pointer positions by to get back to image pixels.
 */
fun fitScale(image: IntSize, box: IntSize): Float =
    min(box.width.toFloat() / image.width, box.height.toFloat() / image.height)

/** [image] scaled by [scale], rounded to whole pixels. */
fun fittedSize(image: IntSize, scale: Float): IntSize =
    IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())

/** The size after [turns] quarter turns — odd counts swap the sides. */
fun rotatedSize(image: IntSize, turns: Int): IntSize =
    if (turns % 2 == 0) image else IntSize(image.height, image.width)

/** Below this on either side a band is a slipped click. */
const val MIN_CROP_PX = 8
