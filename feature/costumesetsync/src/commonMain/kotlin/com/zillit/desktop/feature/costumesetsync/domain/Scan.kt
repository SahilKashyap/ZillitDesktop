package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.datetime.Instant
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scanning on the desktop: the web's `lib/features.js` flag and `lib/scan.js` clean-up, as pure Kotlin.
 */

/**
 * Whether scanning a costume's QR label is on. The web switches it off (`SCAN_ON_WEB = false`: it is a phone
 * workflow) and hides the Scan tab, the Dashboard's Scan button, the picker's Scan QR and On set's "Scan a label".
 * The screen and the route are built, so flipping this to true brings all of them back. The document scanner in the
 * media rows does NOT depend on it.
 */
const val SCAN_ENABLED: Boolean = false

/** How a scanned page is cleaned up: `DOCUMENT` is black & white, `COLOR` keeps its colour (the web's two modes). */
enum class ScanMode { Document, Color }

/** A page as pixels: [argb] holds `width * height` packed `0xAARRGGBB` ints, row by row. */
class ScanRaster(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "pixel count does not match ${width}x$height" }
    }
}

private const val LEVELS = 256
private const val MAX_LEVEL = LEVELS - 1
private const val ALPHA_SHIFT = 24
private const val CLIP_FRACTION = 0.02
private const val RED_WEIGHT = 0.299
private const val GREEN_WEIGHT = 0.587
private const val BLUE_WEIGHT = 0.114
private const val BYTE_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

private fun luma(pixel: Int): Int =
    (
        ((pixel shr RED_SHIFT) and BYTE_MASK) * RED_WEIGHT +
            ((pixel shr GREEN_SHIFT) and BYTE_MASK) * GREEN_WEIGHT +
            (pixel and BYTE_MASK) * BLUE_WEIGHT
        ).toInt()

/**
 * The web's `enhance`: stretch the page's levels so paper reads white and ink reads black (the darkest and lightest
 * 2% of the lightness histogram are clipped), and in [ScanMode.Document] drop the colour the way a flatbed scan
 * would. Changes [raster] in place and returns it.
 */
fun enhance(raster: ScanRaster, mode: ScanMode): ScanRaster {
    val pixels = raster.argb
    val hist = IntArray(LEVELS)
    for (p in pixels) hist[luma(p)] += 1
    val total = pixels.size
    var lo = 0
    var hi = MAX_LEVEL
    var acc = 0
    // As the web: the bucket is counted before it is compared, so the first bucket that reaches 2% is the bound.
    while (lo < MAX_LEVEL) {
        acc += hist[lo]
        if (acc >= total * CLIP_FRACTION) break
        lo += 1
    }
    acc = 0
    while (hi > 0) {
        acc += hist[hi]
        if (acc >= total * CLIP_FRACTION) break
        hi -= 1
    }
    val range = max(1, hi - lo)
    val lut = IntArray(LEVELS) { v -> (((v - lo) * MAX_LEVEL.toDouble()) / range).roundToInt().coerceIn(0, MAX_LEVEL) }
    for (i in pixels.indices) {
        val p = pixels[i]
        val alpha = p ushr ALPHA_SHIFT
        pixels[i] = if (mode == ScanMode.Document) {
            val g = lut[luma(p)]
            (alpha shl ALPHA_SHIFT) or (g shl RED_SHIFT) or (g shl GREEN_SHIFT) or g
        } else {
            val r = lut[(p shr RED_SHIFT) and BYTE_MASK]
            val g = lut[(p shr GREEN_SHIFT) and BYTE_MASK]
            val b = lut[p and BYTE_MASK]
            (alpha shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }
    return raster
}

/** Epoch ms → the stamp in a scan's file name: the web's `toISOString().slice(0, 16)` with the colon dropped. */
fun scanStamp(epochMs: Long): String =
    Instant.fromEpochMilliseconds(epochMs).toString().take(ISO_MINUTES).replace('T', ' ').replace(":", "")

private const val ISO_MINUTES = 16

/** `Scan 2026-10-01 1423.jpg`, or `… p2.jpg` when more than one page goes up together. */
fun scanFileName(stamp: String, index: Int, count: Int): String =
    "Scan $stamp${if (count > 1) " p${index + 1}" else ""}.jpg"

/** The JPEG quality scanned pages are saved at (the web's 0.9). */
const val SCAN_JPEG_QUALITY = 90

/** One page of a scan in progress: its picture as it was taken, and a number that is its identity in the strip. */
class ScanPage(val id: Int, val bytes: ByteArray)

/**
 * A page → the cleaned-up JPEG that is attached (the web's `toScanFile`), or null when the picture will not decode
 * or will not encode — the dialog says the scan could not be saved and keeps its pages.
 */
fun cleanScan(bytes: ByteArray, mode: ScanMode, name: String): PickedFile? {
    val raster = decodeRaster(bytes, 0) ?: return null
    val jpeg = encodeRasterJpeg(enhance(raster, mode), SCAN_JPEG_QUALITY) ?: return null
    return PickedFile(name, jpeg, "image/jpeg")
}
