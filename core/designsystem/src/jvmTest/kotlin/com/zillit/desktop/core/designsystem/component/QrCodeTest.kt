package com.zillit.desktop.core.designsystem.component

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeTest {

    /** The shape the backend generates: 64 random chars + "zillit" + 64 more. */
    private val loginCode = "a".repeat(64) + "zillit" + "b".repeat(64)

    @Test
    fun `encodes a login code`() {
        val matrix = encodeQrCode(loginCode)

        assertNotNull(matrix)
        assertTrue(matrix.size > 0)
    }

    @Test
    fun `a real scanner can read what we render`() {
        // The point of the whole feature: a phone has to be able to read it.
        // Decoding our own matrix with an independent reader is the closest
        // thing to that available without a camera.
        val matrix = assertNotNull(encodeQrCode(loginCode))

        assertEquals(loginCode, decode(matrix))
    }

    @Test
    fun `round-trips payloads of varying length`() {
        listOf("z", "short-code", loginCode, "x".repeat(400)).forEach { payload ->
            val matrix = assertNotNull(encodeQrCode(payload), "failed to encode ${payload.length} chars")
            assertEquals(payload, decode(matrix), "round trip failed for ${payload.length} chars")
        }
    }

    @Test
    fun `an empty payload returns null rather than an empty code`() {
        // A blank QR that scans to nothing would look like it worked.
        assertNull(encodeQrCode(""))
    }

    @Test
    fun `an oversized payload fails cleanly instead of throwing`() {
        // QR tops out around 4k characters. Failing to a null lets the UI
        // explain itself; an exception would crash the sign-in screen.
        assertNull(encodeQrCode("x".repeat(10_000)))
    }

    @Test
    fun `the matrix is square and self-consistent`() {
        val matrix = assertNotNull(encodeQrCode(loginCode))

        // Corner finder patterns are always dark — a cheap sanity check that
        // the row/column indexing is not transposed.
        assertTrue(matrix[0, 0], "top-left finder pattern missing")
        assertTrue(matrix[matrix.size - 1, 0], "top-right finder pattern missing")
        assertTrue(matrix[0, matrix.size - 1], "bottom-left finder pattern missing")
    }

    /**
     * Decodes a matrix by rendering it to pixels and reading it back.
     *
     * Each module is drawn [SCALE]×[SCALE] pixels. At one pixel per module the
     * detector cannot lock onto the finder patterns and throws NotFoundException
     * — which is also true of a real screen, and is why the composable renders
     * the code large rather than at its natural module count.
     */
    private fun decode(matrix: QrMatrix): String {
        // The spec requires a quiet zone; without one the detector finds nothing.
        val quiet = QUIET_MODULES * SCALE
        val side = matrix.size * SCALE + quiet * 2
        val pixels = IntArray(side * side) { WHITE }

        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) fillModule(pixels, side, quiet + x * SCALE, quiet + y * SCALE)
            }
        }

        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    /** Paints one SCALE×SCALE module block. */
    private fun fillModule(pixels: IntArray, side: Int, left: Int, top: Int) {
        for (dy in 0 until SCALE) {
            for (dx in 0 until SCALE) {
                pixels[(top + dy) * side + (left + dx)] = BLACK
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val SCALE = 4
        const val QUIET_MODULES = 4
    }
}
