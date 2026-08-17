package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The PDF work, against real files — a generated two-page A4 document, a
 * drawn PNG, and PDFBox's own reader as the witness.
 *
 * The geometry test matters most: a signature stamped at the wrong corner of
 * the page looks fine in every unit that doesn't open the PDF.
 */
class PdfBoxWorkTest {

    private val work = PdfBoxWork()

    private fun twoPagePdf(): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage(PDRectangle.A4))
        document.addPage(PDPage(PDRectangle.A4))
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    private fun drawnPng(): ByteArray {
        val result = work.rasterizeStrokes(
            strokes = listOf(
                listOf(StrokePoint(10f, 150f), StrokePoint(400f, 120f), StrokePoint(790f, 160f)),
            ),
            canvasWidth = 800,
            canvasHeight = 300,
        )
        return assertIs<ZillitResult.Success<ByteArray>>(result).data
    }

    @Test
    fun `pages render at the asked width with true point sizes`() {
        val result = work.renderPages(twoPagePdf(), targetWidthPx = 400)

        val pages = assertIs<ZillitResult.Success<*>>(result).data
            as List<com.zillit.desktop.feature.formsignature.domain.PdfPageImage>
        assertEquals(2, pages.size)
        assertEquals(1, pages.first().page)
        assertEquals(400, pages.first().widthPx)
        // A4 in points, from the page itself rather than an assumption here.
        assertEquals(PDRectangle.A4.width.toDouble(), pages.first().widthPt)
        assertTrue(pages.first().imageBytes.isNotEmpty())
    }

    @Test
    fun `a stamp lands on its page as an image object`() {
        val spot = SignSpot(
            kind = SignSpotKind.Signature,
            page = 2,
            x = 100.0,
            y = 80.0,
            width = 160.0,
            height = 56.0,
        )

        val result = work.stamp(twoPagePdf(), listOf(PlacedStamp(drawnPng(), spot)))

        val stamped = assertIs<ZillitResult.Success<ByteArray>>(result).data
        Loader.loadPDF(stamped).use { document ->
            assertEquals(2, document.numberOfPages)
            // A page nothing touched keeps a null resources dictionary — the
            // stamp must not have invented one there.
            val first = document.getPage(0).resources
            assertEquals(0, first?.xObjectNames?.count() ?: 0)
            // The image is on page two and only page two.
            assertEquals(1, document.getPage(1).resources.xObjectNames.count())
        }
    }

    @Test
    fun `a stamp on a page that does not exist refuses rather than corrupts`() {
        val spot = SignSpot(SignSpotKind.Signature, page = 9, x = 0.0, y = 0.0, width = 10.0, height = 10.0)

        val result = work.stamp(twoPagePdf(), listOf(PlacedStamp(drawnPng(), spot)))

        assertIs<ZillitResult.Failure>(result)
    }

    @Test
    fun `an empty drawing refuses to rasterize`() {
        val result = work.rasterizeStrokes(emptyList(), 800, 300)

        assertIs<ZillitResult.Failure>(result)
    }

    @Test
    fun `bytes that are not a pdf are refused as such`() {
        val result = work.renderPages("just words".encodeToByteArray(), 400)

        assertIs<ZillitResult.Failure>(result)
    }
}
