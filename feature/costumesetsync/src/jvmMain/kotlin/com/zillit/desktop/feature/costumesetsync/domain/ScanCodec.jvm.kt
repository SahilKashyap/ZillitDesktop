package com.zillit.desktop.feature.costumesetsync.domain

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BYTES_PER_PIXEL = 4

internal actual fun decodeRaster(bytes: ByteArray, maxSide: Int): ScanRaster? = runCatching {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val origin = codec.encodedOrigin
    val image = Image.makeFromBitmap(codec.readPixels())
    val upright = if (origin.swapsWidthHeight()) image.height to image.width else image.width to image.height
    val scale = if (maxSide > 0) min(1f, maxSide.toFloat() / max(upright.first, upright.second)) else 1f
    val width = max(1, (upright.first * scale).roundToInt())
    val height = max(1, (upright.second * scale).roundToInt())
    val target = Bitmap().apply { allocN32Pixels(width, height) }
    Canvas(target).use { canvas ->
        canvas.clear(WHITE)
        canvas.scale(width.toFloat() / upright.first, height.toFloat() / upright.second)
        canvas.concat(origin.toMatrix(image.width, image.height))
        canvas.drawImage(image, 0f, 0f)
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    val raw = checkNotNull(target.readPixels(info, width * BYTES_PER_PIXEL))
    val argb = IntArray(width * height)
    // B, G, R, A in memory read little-endian is exactly 0xAARRGGBB.
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
    ScanRaster(width, height, argb)
}.getOrNull()

private fun ScanRaster.toBitmap(): Bitmap {
    val raw = ByteArray(width * height * BYTES_PER_PIXEL)
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(argb)
    // The sizes are read here, not inside `apply`, where `width` and `height` would be the empty bitmap's own.
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(raw)
    return bitmap
}

internal actual fun encodeRasterJpeg(raster: ScanRaster, quality: Int): ByteArray? = runCatching {
    Image.makeFromBitmap(raster.toBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, quality)?.bytes
}.getOrNull()

internal actual fun ScanRaster.toImageBitmap(): ImageBitmap = Image.makeFromBitmap(toBitmap()).toComposeImageBitmap()

internal actual fun decodeQrText(bytes: ByteArray): String? {
    val raster = decodeRaster(bytes, QR_MAX_SIDE) ?: return null
    val source = RGBLuminanceSource(raster.width, raster.height, raster.argb)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    val read = { luminance: LuminanceSource ->
        runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)), hints).text }.getOrNull()
    }
    // A label printed light-on-dark reads only inverted.
    return (read(source) ?: read(source.invert()))
        ?.trim()?.takeIf { it.isNotEmpty() }
}

/** Big enough to read a label across a desk, small enough to read in a blink. */
private const val QR_MAX_SIDE = 1600
