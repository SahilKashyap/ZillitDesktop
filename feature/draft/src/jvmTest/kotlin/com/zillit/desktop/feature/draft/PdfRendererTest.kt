package com.zillit.desktop.feature.draft

import com.zillit.desktop.feature.draft.data.PdfScreenplayRenderer
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.TitlePage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfRendererTest {

    @Test
    fun `renders a title page and body pages in Courier with page numbers`() {
        val elements = buildList {
            add(ScriptElement("h", ElementType.SceneHeading, "INT. KITCHEN - DAY"))
            repeat(60) { add(ScriptElement("a$it", ElementType.Action, "Beat $it of the morning.")) }
            add(ScriptElement("c", ElementType.Character, "SAM"))
            add(ScriptElement("d", ElementType.Dialogue, "Finally."))
        }
        val script = Screenplay("s", "p", "The Weekend", TitlePage(title = "The Weekend", author = "Sam Writer"),
            elements)
        val bytes = PdfScreenplayRenderer().pdf(script)
        Loader.loadPDF(bytes).use { doc ->
            // Title page + three body pages (60 double-spaced actions ≈ 120 lines).
            assertEquals(4, doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("THE WEEKEND"))
            assertTrue(text.contains("Sam Writer"))
            assertTrue(text.contains("INT. KITCHEN - DAY"))
            assertTrue(text.contains("SAM"))
            assertTrue(text.contains("3."))
        }
    }
}
