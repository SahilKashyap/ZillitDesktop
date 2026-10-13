package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPlacement
import com.zillit.desktop.feature.dealmemo.ui.documents.SignedPlacement
import com.zillit.desktop.feature.dealmemo.ui.documents.platformPdfWork
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Flattening signatures into a page: each placed mark is drawn from the image it was placed with (ZL-21732). */
class DealPdfStampTest {

    private val work = platformPdfWork()

    private fun blankPdf(): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage(PDRectangle(PAGE, PAGE)))
        ByteArrayOutputStream().also(document::save).toByteArray()
    }

    private fun solid(color: Color): ByteArray {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, 40, 20)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    // A 200-point page, a mark 40 wide and (from its image) 20 tall: left edge at fx × 200, top edge at fy × 200.
    private fun mark(png: ByteArray, fx: Double) = SignedPlacement(DealPlacement(0, fx, 0.1, 0.2), png)

    private fun stamped(vararg marks: SignedPlacement): ByteArray {
        val result = work.stamp(blankPdf(), marks.toList())
        return assertIs<ZillitResult.Success<ByteArray>>(result).data
    }

    private fun pixel(pdf: ByteArray, x: Int, y: Int): Int = Loader.loadPDF(pdf).use { document ->
        PDFRenderer(document).renderImage(0, 1f).getRGB(x, y)
    }

    @Test
    fun `two different signatures each keep their own image in the flattened page`() {
        val pdf = stamped(mark(solid(Color.RED), 0.1), mark(solid(Color.BLUE), 0.6))

        // Centres of the two marks: x = 20..60 and 120..160, y = 20..40.
        assertEquals("red", hue(pixel(pdf, 40, 30)))
        assertEquals("blue", hue(pixel(pdf, 140, 30)))
    }

    @Test
    fun `one signature stamped twice is embedded once, two different ones twice`() {
        val red = solid(Color.RED)

        assertEquals(1, imageCount(stamped(mark(red, 0.1), mark(red.copyOf(), 0.6))))
        assertEquals(2, imageCount(stamped(mark(red, 0.1), mark(solid(Color.BLUE), 0.6))))
    }

    // Rendering may shade a channel by a step or two; which channel carries the colour is what matters.
    private fun hue(rgb: Int): String {
        val color = Color(rgb)
        return when {
            color.red > STRONG && color.green < WEAK && color.blue < WEAK -> "red"
            color.blue > STRONG && color.red < WEAK && color.green < WEAK -> "blue"
            else -> "other"
        }
    }

    private fun imageCount(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { document ->
        document.getPage(0).resources.xObjectNames.count()
    }

    private companion object {
        const val PAGE = 200f
        const val STRONG = 200
        const val WEAK = 60
    }
}
