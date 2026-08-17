package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.pdfThumbnailJpeg
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Document posters — with a real fixture, because PDFBox can write the PDF it
 * then reads. The video generator never got this luxury; this one has no
 * excuse.
 */
class PdfThumbnailTest {

    /** A genuine one-page A4 PDF, built in memory. */
    private fun onePagePdf(): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage(PDRectangle.A4))
        val out = ByteArrayOutputStream()
        document.save(out)
        out.toByteArray()
    }

    @Test
    fun `a real PDF renders its first page as a JPEG poster`() {
        val poster = pdfThumbnailJpeg(onePagePdf())

        assertNotNull(poster)
        assertTrue(poster.jpegBytes.isNotEmpty())
        // A4 portrait: taller than wide, scaled within the poster edge.
        assertTrue(poster.heightPx > poster.widthPx)
        assertTrue(maxOf(poster.widthPx, poster.heightPx) <= 480)

        // The bytes are a decodable image, not merely nonempty.
        val image = ImageIO.read(ByteArrayInputStream(poster.jpegBytes))
        assertNotNull(image)
        assertEquals(poster.widthPx, image.width)
    }

    @Test
    fun `a zero-page document yields no poster`() {
        val empty = PDDocument().use { document ->
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }

        assertNull(pdfThumbnailJpeg(empty))
    }

    @Test
    fun `not a PDF means no poster, never a throw`() {
        assertNull(pdfThumbnailJpeg(ByteArray(0)))
        assertNull(pdfThumbnailJpeg("just some text".encodeToByteArray()))
        assertNull(pdfThumbnailJpeg(ByteArray(128) { (it * 5).toByte() }))
    }
}
