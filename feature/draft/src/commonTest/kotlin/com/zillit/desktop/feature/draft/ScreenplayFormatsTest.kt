@file:Suppress("MaxLineLength") // Fixtures read best as the documents they are.

package com.zillit.desktop.feature.draft

import com.zillit.desktop.feature.draft.data.ScreenplayCodec
import com.zillit.desktop.feature.draft.domain.ElementFlow
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.FinalDraftXml
import com.zillit.desktop.feature.draft.domain.Fountain
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScreenplayLayout
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.SmartType
import com.zillit.desktop.feature.draft.domain.TitlePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenplayFormatsTest {

    private var counter = 0
    private fun id() = "e${counter++}"

    private val script = Screenplay(
        id = "s1",
        projectId = "p1",
        title = "The Weekend",
        titlePage = TitlePage(title = "The Weekend", author = "Sam Writer", contact = "sam@example.com\n+44 1"),
        elements = listOf(
            ScriptElement("1", ElementType.SceneHeading, "INT. KITCHEN - DAY"),
            ScriptElement("2", ElementType.Action, "SAM stares at the kettle. It refuses to boil."),
            ScriptElement("3", ElementType.Character, "SAM"),
            ScriptElement("4", ElementType.Parenthetical, "(quietly)"),
            ScriptElement("5", ElementType.Dialogue, "Come on."),
            ScriptElement("6", ElementType.Character, "KETTLE (V.O.)"),
            ScriptElement("7", ElementType.Dialogue, "No."),
            ScriptElement("8", ElementType.Transition, "CUT TO:"),
            ScriptElement("9", ElementType.SceneHeading, "EXT. GARDEN - NIGHT"),
            ScriptElement("10", ElementType.Action, "Rain."),
        ),
    )

    @Test
    fun `fountain round-trips the elements and the title page`() {
        val text = Fountain.write(script)
        assertTrue(text.startsWith("Title: The Weekend\n"))
        assertTrue(text.contains("\nINT. KITCHEN - DAY\n"))
        assertTrue(text.contains("\nSAM\n(quietly)\nCome on.\n"))
        val parsed = Fountain.parse(text, ::id)
        assertEquals(script.elements.map { it.type }, parsed.elements.map { it.type })
        assertEquals(script.elements.map { it.text }, parsed.elements.map { it.text })
        assertEquals("Sam Writer", parsed.titlePage.author)
        assertEquals("sam@example.com\n+44 1", parsed.titlePage.contact)
    }

    @Test
    fun `fountain reads forced elements and multi-line action`() {
        val parsed = Fountain.parse(
            """
            .OPENING SHOT
            @McCLANE
            Yippee ki-yay.

            !INT. NOT A HEADING
            More action here
            still the same paragraph.

            >SMASH CUT TO:
            """.trimIndent(),
            ::id,
        )
        assertEquals(
            listOf(ElementType.SceneHeading, ElementType.Character, ElementType.Dialogue, ElementType.Action,
                ElementType.Transition),
            parsed.elements.map { it.type },
        )
        assertEquals("INT. NOT A HEADING\nMore action here\nstill the same paragraph.", parsed.elements[3].text)
        assertEquals("SMASH CUT TO:", parsed.elements[4].text)
    }

    @Test
    fun `fdx round-trips and escapes`() {
        val withAmp = script.copy(elements = script.elements + ScriptElement("11", ElementType.Action,
            "Tom & Jerry <3"))
        val xml = FinalDraftXml.write(withAmp)
        assertTrue(xml.contains("<Paragraph Type=\"Scene Heading\">"))
        assertTrue(xml.contains("Tom &amp; Jerry &lt;3"))
        val parsed = FinalDraftXml.parse(xml, ::id)
        assertEquals(withAmp.elements.map { it.type }, parsed.elements.map { it.type })
        assertEquals(withAmp.elements.map { it.text }, parsed.elements.map { it.text })
        assertEquals("The Weekend", parsed.titlePage.title)
        assertEquals("Sam Writer", parsed.titlePage.author)
        assertEquals("sam@example.com\n+44 1", parsed.titlePage.contact)
    }

    @Test
    fun `fdx reads a Final Draft export with styled runs`() {
        val fdx = """
            <?xml version="1.0"?><FinalDraft DocumentType="Script"><Content>
            <Paragraph Type="Scene Heading"><Text>INT. </Text><Text Style="Bold">OFFICE</Text><Text> - DAY</Text></Paragraph>
            <Paragraph Type="Character"><Text>JO</Text></Paragraph>
            <Paragraph Type="Dialogue"><Text>It&apos;s late.</Text></Paragraph>
            </Content></FinalDraft>
        """.trimIndent()
        val parsed = FinalDraftXml.parse(fdx, ::id)
        assertEquals(listOf("INT. OFFICE - DAY", "JO", "It's late."), parsed.elements.map { it.text })
    }

    @Test
    fun `codec round-trips a screenplay through the store body`() {
        val back = ScreenplayCodec.decode(ScreenplayCodec.encode(script))
        assertEquals(script, back)
    }

    @Test
    fun `layout wraps and paginates like Courier 12 on Letter`() {
        assertEquals(listOf("one two", "three"), ScreenplayLayout.wrap("one two three", 8))
        assertEquals(listOf("abcdefgh", "ij"), ScreenplayLayout.wrap("abcdefghij", 8))
        val long = List(40) { ScriptElement("a$it", ElementType.Action, "Line of action number $it.") }
        val pages = ScreenplayLayout.paginate(long)
        // 40 actions with a blank line between: 79 lines → two pages.
        assertEquals(2, pages.pageCount)
        assertEquals(1, pages.pageOfElement.first())
        assertEquals(2, pages.pageOfElement.last())
    }

    @Test
    fun `enter and tab move the way Final Draft does`() {
        assertEquals(ElementType.Action, ElementFlow.onEnter(ElementType.SceneHeading))
        assertEquals(ElementType.Dialogue, ElementFlow.onEnter(ElementType.Character))
        assertEquals(ElementType.Character, ElementFlow.onEnter(ElementType.Dialogue))
        assertEquals(ElementType.Character, ElementFlow.onTab(ElementType.Action))
        assertEquals(ElementType.Parenthetical, ElementFlow.onTab(ElementType.Dialogue))
        assertEquals(ElementType.Transition, ElementFlow.onEmptyTab(ElementType.Character))
        assertEquals(ElementType.SceneHeading, ElementFlow.onEmptyTab(ElementType.Transition))
        assertEquals(ElementType.General, ElementFlow.previous(ElementType.SceneHeading))
    }

    @Test
    fun `smart type offers known names and places, most recent first`() {
        assertEquals(listOf("KETTLE", "SAM"), SmartType.characters(script.elements))
        assertEquals(listOf("GARDEN", "KITCHEN"), SmartType.locations(script.elements))
        assertEquals(listOf("SAM"), SmartType.suggestions(ElementType.Character, "s", script.elements, "new"))
        assertEquals(
            listOf("INT. KITCHEN - DAY", "INT. KITCHEN"),
            SmartType.suggestions(ElementType.SceneHeading, "INT. K", script.elements, "new"),
        )
        assertTrue(SmartType.suggestions(ElementType.Action, "s", script.elements, "new").isEmpty())
    }
}
