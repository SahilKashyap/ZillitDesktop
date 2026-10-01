package com.zillit.desktop.feature.costumesetsync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zillit.desktop.feature.costumesetsync.domain.ScanMode
import com.zillit.desktop.feature.costumesetsync.domain.ScanRaster
import com.zillit.desktop.feature.costumesetsync.domain.cleanScan
import com.zillit.desktop.feature.costumesetsync.domain.decodeQrText
import com.zillit.desktop.feature.costumesetsync.domain.decodeRaster
import com.zillit.desktop.feature.costumesetsync.domain.encodeRasterJpeg
import com.zillit.desktop.feature.costumesetsync.domain.enhance
import com.zillit.desktop.feature.costumesetsync.domain.scanFileName
import com.zillit.desktop.feature.costumesetsync.domain.scanStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The document scanner's clean-up (`lib/scan.js`), its picture codec and the QR reader, on synthetic pictures. */
class ScanTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun channels(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    /**
     * A dim, tinted page: warm grey paper (about 190/180/160) with a dark ink bar down the middle — what a phone
     * photo of a receipt in a dull room looks like. 12% of the pixels are ink.
     */
    private fun dimPage(width: Int = 100, height: Int = 100): ScanRaster {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            if (x in 44 until 56) rgb(40, 40, 60) else rgb(190, 180, 160)
        }
        return ScanRaster(width, height, pixels)
    }

    @Test
    fun blackAndWhiteMakesPaperWhiteAndInkBlackAndDropsColour() {
        val page = enhance(dimPage(), ScanMode.Document)
        val paper = channels(page.argb[0])
        val ink = channels(page.argb[50])
        assertEquals(Triple(255, 255, 255), paper)
        assertEquals(Triple(0, 0, 0), ink)
        // Every pixel is grey: the three channels agree.
        assertTrue(page.argb.all { val (r, g, b) = channels(it); r == g && g == b })
    }

    @Test
    fun colourKeepsTheHueButStretchesTheLevels() {
        val before = dimPage()
        val page = enhance(dimPage(), ScanMode.Color)
        val (r, g, b) = channels(page.argb[0])
        // Paper was warm (red above blue) and still is; each channel is stretched on the lightness range.
        assertTrue(r >= g && g >= b, "paper hue kept: $r $g $b")
        assertTrue(r > channels(before.argb[0]).first, "paper brightened")
        val (ir, ig, _) = channels(page.argb[50])
        assertTrue(ir == 0 && ig == 0, "ink is dark in colour mode too")
    }

    @Test
    fun aFlatPictureDoesNotDivideByZero() {
        val flat = ScanRaster(4, 4, IntArray(16) { rgb(128, 128, 128) })
        val out = enhance(flat, ScanMode.Document)
        assertEquals(16, out.argb.size)
    }

    @Test
    fun fileNamesAndStampsAreTheWebs() {
        assertEquals("2026-03-06 0000", scanStamp(1_772_755_200_000L))
        assertEquals("Scan 2026-03-06 0000.jpg", scanFileName("2026-03-06 0000", 0, 1))
        assertEquals("Scan 2026-03-06 0000 p2.jpg", scanFileName("2026-03-06 0000", 1, 3))
    }

    @Test
    fun aRasterSurvivesJpegAndComesBackTheSameSize() {
        val jpeg = assertNotNull(encodeRasterJpeg(dimPage(120, 80), 90))
        val back = assertNotNull(decodeRaster(jpeg, 0))
        assertEquals(120, back.width)
        assertEquals(80, back.height)
        // Paper stays paper-coloured after the round trip.
        val (r, _, b) = channels(back.argb[0])
        assertTrue(r in 180..200 && b in 150..170, "paper $r/$b")
    }

    @Test
    fun maxSideShrinksALargePictureKeepingItsShape() {
        val jpeg = assertNotNull(encodeRasterJpeg(dimPage(400, 200), 90))
        val small = assertNotNull(decodeRaster(jpeg, 100))
        assertEquals(100, small.width)
        assertEquals(50, small.height)
    }

    @Test
    fun cleanScanTurnsAPhotoIntoACleanedJpegFile() {
        val photo = assertNotNull(encodeRasterJpeg(dimPage(), 95))
        val file = assertNotNull(cleanScan(photo, ScanMode.Document, "Scan x.jpg"))
        assertEquals("Scan x.jpg", file.name)
        assertEquals("image/jpeg", file.mime)
        assertTrue(file.isImage)
        val back = assertNotNull(decodeRaster(file.bytes, 0))
        val (r, g, b) = channels(back.argb[0])
        assertTrue(r > 240 && g > 240 && b > 240, "paper reads white: $r $g $b")
    }

    @Test
    fun aFileThatIsNotAPictureCannotBeScanned() {
        assertNull(cleanScan("not a picture".toByteArray(), ScanMode.Document, "x.jpg"))
        assertNull(decodeRaster(ByteArray(0), 0))
    }

    private fun qrPicture(text: String, invert: Boolean = false): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val scale = 6
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { i ->
            val dark = matrix.get(i % side / scale, i / side / scale) != invert
            if (dark) rgb(0, 0, 0) else rgb(255, 255, 255)
        }
        return assertNotNull(encodeRasterJpeg(ScanRaster(side, side, pixels), 95))
    }

    @Test
    fun aCostumeLabelReadsBackItsAssetNumber() {
        assertEquals("CST-000245", decodeQrText(qrPicture("CST-000245")))
    }

    @Test
    fun aLightOnDarkLabelReadsToo() {
        assertEquals("CST-000245", decodeQrText(qrPicture("CST-000245", invert = true)))
    }

    @Test
    fun aPictureWithNoCodeReadsAsNothing() {
        val page = assertNotNull(encodeRasterJpeg(dimPage(), 90))
        assertNull(decodeQrText(page))
        assertNull(decodeQrText("junk".toByteArray()))
    }
}
