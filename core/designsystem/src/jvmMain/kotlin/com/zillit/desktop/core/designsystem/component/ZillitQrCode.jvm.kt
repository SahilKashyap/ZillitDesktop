package com.zillit.desktop.core.designsystem.component

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * ZXing-backed QR encoding.
 *
 * Returns null rather than throwing: a QR that cannot be encoded is a UI state
 * the sign-in screen can explain, not a crash. In practice it only fails if the
 * payload is too large for the format.
 */
actual fun encodeQrCode(content: String): QrMatrix? {
    if (content.isEmpty()) return null

    return try {
        val hints = mapOf(
            // Medium recovers ~15% of a damaged or partly-obscured code. High
            // would tolerate more but makes the modules smaller for the same
            // physical size, which is worse on a screen being photographed from
            // a metre away — the actual use case here.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // We draw our own quiet zone, so ask ZXing not to add one.
            EncodeHintType.MARGIN to 0,
        )

        // Size 0,0 lets ZXing pick the natural module count for the payload;
        // the composable scales it.
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)

        val size = bitMatrix.width
        if (size <= 0 || bitMatrix.height != size) return null

        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = bitMatrix.get(x, y)
            }
        }
        QrMatrix(size, modules)
    } catch (@Suppress("SwallowedException") writer: WriterException) {
        null
    } catch (@Suppress("SwallowedException") illegalArgument: IllegalArgumentException) {
        null
    }
}
