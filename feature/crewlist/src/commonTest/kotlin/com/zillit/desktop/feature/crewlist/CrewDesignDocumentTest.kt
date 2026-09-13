package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.feature.crewlist.data.CrewCanvasMessage
import com.zillit.desktop.feature.crewlist.data.CrewDesignDocument
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.HeaderSection
import com.zillit.desktop.feature.crewlist.domain.SectionOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Design canvas's document and the two messages the page may send back. */
class CrewDesignDocumentTest {

    @Test
    fun `the arranger goes in before the body closes, seeded with rows and nudges`() {
        val layout = HeaderLayout(logoSize = 220).let {
            it.copy(offsets = it.offsets + (HeaderSection.Company to SectionOffset(8, 0)))
        }
        val html = CrewDesignDocument.compose("<html><body><p>crew</p></body></html>", layout, hideInternalLines = true)

        val seeds = html.indexOf("window.__CL_LAYOUT__=[[\"logo\",\"company\"],[\"title\"]]")
        assertTrue(seeds > html.indexOf("<p>crew</p>"), "after the document's own content")
        assertTrue(html.trimEnd().endsWith("</body></html>"), "and still inside the body")
        assertTrue(html.contains("\"company\":{\"x\":8,\"y\":0}"))
        assertTrue(html.contains("width:220px"), "the logo is resized client-side")
        assertTrue(html.contains("border-color:transparent"), "the table lines are hidden client-side")
        assertTrue(html.contains("window.cefQuery"), "the page reports through the embedded browser")
    }

    @Test
    fun `a document without a body gets the arranger appended`() {
        val html = CrewDesignDocument.compose("<div class=\"doc\"></div>", HeaderLayout(), hideInternalLines = false)
        assertTrue(html.startsWith("<div class=\"doc\"></div><script>"))
        assertTrue(!html.contains("border-color:transparent"))
    }

    @Test
    fun `only the two known messages are read, sanitised`() {
        val layout = CrewCanvasMessage.parse("""{"type":"crewlist-layout","order":[["logo","logo"],"title"]}""")
        val stacked = listOf(listOf(HeaderSection.Logo), listOf(HeaderSection.Title), listOf(HeaderSection.Company))
        assertEquals(CrewCanvasMessage.Layout(stacked), layout)
        assertEquals(
            CrewCanvasMessage.Offset(HeaderSection.Title, SectionOffset(-3, 14)),
            CrewCanvasMessage.parse("""{"type":"crewlist-offset","id":"title","offset":{"x":-3,"y":14.4}}"""),
        )
        assertNull(CrewCanvasMessage.parse("""{"type":"navigate","url":"https://example.com"}"""))
        assertNull(CrewCanvasMessage.parse("""{"type":"crewlist-offset","id":"header","offset":{"x":1,"y":1}}"""))
        assertNull(CrewCanvasMessage.parse("not json"))
    }
}
