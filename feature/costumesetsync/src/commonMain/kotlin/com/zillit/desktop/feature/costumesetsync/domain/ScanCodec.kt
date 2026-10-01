package com.zillit.desktop.feature.costumesetsync.domain

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A picture's pixels, turned the right way up (its EXIF orientation applied) and, when [maxSide] is above zero,
 * shrunk so neither side exceeds it. Null when [bytes] is not a picture. Transparent areas come out white.
 */
internal expect fun decodeRaster(bytes: ByteArray, maxSide: Int): ScanRaster?

/** [raster] as a JPEG at [quality] (0-100), or null when it cannot be encoded. */
internal expect fun encodeRasterJpeg(raster: ScanRaster, quality: Int): ByteArray?

/** [raster] as something Compose can draw. */
internal expect fun ScanRaster.toImageBitmap(): ImageBitmap

/** The text of the first QR code found in the picture [bytes], or null when there is none (or it will not read). */
internal expect fun decodeQrText(bytes: ByteArray): String?
