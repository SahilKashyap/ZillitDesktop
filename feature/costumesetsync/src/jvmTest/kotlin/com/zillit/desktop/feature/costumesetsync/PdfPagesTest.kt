package com.zillit.desktop.feature.costumesetsync

import androidx.compose.ui.graphics.toAwtImage
import com.zillit.desktop.feature.costumesetsync.ui.openPdf
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfPagesTest {
    @Test
    fun rendersEveryPageOfAGeneratedPdfWithItsInkOnIt() = runBlocking {
        val pages = assertNotNull(openPdf(samplePdf(3)))
        assertEquals(3, pages.sizes.size)
        // A4 portrait, and the landscape page third (the sample turns it).
        assertEquals(PDRectangle.A4.width, pages.sizes[0].width)
        assertEquals(PDRectangle.A4.height, pages.sizes[2].width)
        val bitmap = assertNotNull(pages.render(0, 400))
        assertEquals(400, bitmap.width)
        val image = bitmap.toAwtImage()
        var dark = 0
        for (x in 0 until image.width step 2) for (y in 0 until image.height step 2) {
            if ((image.getRGB(x, y) and 0xFF) < 100) dark++
        }
        assertTrue(dark > 50, "the page text should have drawn dark pixels, found $dark")
        // Pages draw again at another width, and out of range is null rather than a throw.
        assertEquals(800, assertNotNull(pages.render(1, 800)).width)
        assertNull(pages.render(9, 400))
        pages.close()
    }

    @Test
    fun refusesBytesThatAreNotAPdf() {
        assertNull(openPdf("not a pdf".encodeToByteArray()))
        assertNull(openPdf(ByteArray(0)))
    }
}

/** A tiny PDF of [count] pages, each saying what page it is; the last is landscape. */
internal fun samplePdf(count: Int): ByteArray = PDDocument().use { doc ->
    val font = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    repeat(count) { i ->
        val landscape = i == count - 1 && count > 1
        val page = PDPage(if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(font, 22f)
            cs.newLineAtOffset(60f, 700f)
            cs.showText("SCENE ${i + 1}. INT. KITCHEN - NIGHT")
            cs.endText()
            cs.setNonStrokingColor(0.8f, 0.9f, 1f)
            cs.addRect(60f, 300f, 200f, 120f)
            cs.fill()
        }
    }
    ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
}
