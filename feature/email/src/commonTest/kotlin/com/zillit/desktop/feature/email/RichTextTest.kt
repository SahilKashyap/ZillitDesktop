package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.toHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Formatting that stays attached to the words it was applied to.
 *
 * This is the defining bug of home-grown rich text editors: bold that drifts a
 * character left every time you edit earlier in the message. Every test here is
 * an edit landing somewhere awkward relative to a mark.
 */
class RichTextTest {

    /** "Call sheet" with `sheet` bold. */
    private val doc = RichText.plain("Call sheet").toggle(TextMark.Bold, 5, 10)

    private fun RichText.boldRange(): Pair<Int, Int>? =
        marks.firstOrNull { it.style == TextMark.Bold }?.let { it.start to it.end }

    // -- applying ----------------------------------------------------------

    @Test
    fun `toggling on marks the selection`() {
        assertEquals(5 to 10, doc.boldRange())
        assertTrue(doc.isApplied(TextMark.Bold, 5, 10))
        assertFalse(doc.isApplied(TextMark.Bold, 0, 4))
    }

    @Test
    fun `toggling a fully marked selection turns it off`() {
        val plain = doc.toggle(TextMark.Bold, 5, 10)

        assertTrue(plain.marks.isEmpty())
    }

    @Test
    fun `a partly marked selection becomes fully marked, not inverted`() {
        // Every editor works this way: the first press makes the whole thing
        // bold rather than flipping each character.
        val all = doc.toggle(TextMark.Bold, 0, 10)

        assertTrue(all.isApplied(TextMark.Bold, 0, 10))
    }

    @Test
    fun `unmarking the middle splits the run in two`() {
        val split = RichText.plain("abcdef")
            .toggle(TextMark.Bold, 0, 6)
            .toggle(TextMark.Bold, 2, 4)

        assertEquals(2, split.marks.size)
        assertTrue(split.isApplied(TextMark.Bold, 0, 2))
        assertFalse(split.isApplied(TextMark.Bold, 2, 4))
        assertTrue(split.isApplied(TextMark.Bold, 4, 6))
    }

    @Test
    fun `styles stack independently`() {
        val both = RichText.plain("abc")
            .toggle(TextMark.Bold, 0, 3)
            .toggle(TextMark.Italic, 1, 2)

        assertTrue(both.isApplied(TextMark.Bold, 0, 3))
        assertTrue(both.isApplied(TextMark.Italic, 1, 2))
        assertFalse(both.isApplied(TextMark.Italic, 0, 1))
    }

    // -- editing -----------------------------------------------------------

    @Test
    fun `typing before a marked word does not move the mark onto it`() {
        // "Call sheet" -> "The Call sheet": bold must still be on `sheet`.
        val edited = doc.withText("The Call sheet")

        assertEquals(9 to 14, edited.boldRange())
        assertTrue(edited.isApplied(TextMark.Bold, 9, 14))
    }

    @Test
    fun `typing inside a marked word extends the mark`() {
        // "Call sheet" -> "Call shXeet"
        val edited = doc.withText("Call shXeet")

        assertEquals(5 to 11, edited.boldRange())
    }

    @Test
    fun `typing at the end of a marked word continues it`() {
        val edited = doc.withText("Call sheets")

        assertEquals(5 to 11, edited.boldRange())
    }

    @Test
    fun `typing immediately before a marked word is not marked`() {
        // "Call sheet" -> "Call Xsheet": the X should be plain.
        val edited = doc.withText("Call Xsheet")

        assertEquals(6 to 11, edited.boldRange())
        assertFalse(edited.isApplied(TextMark.Bold, 5, 6), "the new character became bold")
    }

    @Test
    fun `deleting before a mark drags it back`() {
        // "Call sheet" -> "Cal sheet"
        val edited = doc.withText("Cal sheet")

        assertEquals(4 to 9, edited.boldRange())
    }

    @Test
    fun `deleting part of a marked word shrinks the mark`() {
        val edited = doc.withText("Call she")

        assertEquals(5 to 8, edited.boldRange())
    }

    @Test
    fun `deleting a marked word entirely drops the mark`() {
        val edited = doc.withText("Call ")

        assertTrue(edited.marks.isEmpty(), "a mark outlived the text it covered")
    }

    @Test
    fun `replacing a selection that straddles a mark keeps what survives`() {
        // "Call sheet" -> "Call X" replaces `sheet`.
        val edited = doc.withText("Call X")

        assertTrue(edited.marks.all { it.end <= edited.text.length })
    }

    @Test
    fun `clearing everything clears the marks`() {
        assertTrue(doc.withText("").marks.isEmpty())
    }

    @Test
    fun `pasting a block in the middle keeps the surrounding marks straight`() {
        val edited = doc.withText("Call ---------- sheet")

        assertEquals(16 to 21, edited.boldRange())
    }

    @Test
    fun `an unchanged edit changes nothing`() {
        assertEquals(doc, doc.withText("Call sheet"))
    }

    @Test
    fun `repeated edits inside a word do not multiply the marks`() {
        // Without merging, every keystroke leaves another fragment behind and
        // the list grows without bound over a long message.
        var edited = doc
        listOf("Call sheeta", "Call sheetab", "Call sheetabc").forEach { edited = edited.withText(it) }

        assertEquals(1, edited.marks.size)
    }

    // -- the caret ---------------------------------------------------------

    @Test
    fun `the caret inside a marked word reports that style`() {
        // This is what keeps the toolbar lit as you type.
        assertTrue(doc.isApplied(TextMark.Bold, 7, 7))
        assertEquals(setOf(TextMark.Bold), doc.stylesAt(7))
    }

    @Test
    fun `the caret just before a marked word does not`() {
        assertFalse(doc.isApplied(TextMark.Bold, 5, 5))
        assertTrue(doc.stylesAt(5).isEmpty())
    }

    @Test
    fun `the caret at the end of a marked word still reports it`() {
        assertTrue(doc.isApplied(TextMark.Bold, 10, 10))
    }

    // -- HTML --------------------------------------------------------------

    @Test
    fun `marks become tags`() {
        assertEquals("Call <b>sheet</b>", doc.toHtml())
    }

    @Test
    fun `nested styles close in the right order`() {
        // `<b><i>x</b></i>` is what a naive writer emits, and mail clients then
        // render the rest of the message inside the stray tag.
        val html = RichText.plain("abc")
            .toggle(TextMark.Bold, 0, 3)
            .toggle(TextMark.Italic, 0, 3)
            .toHtml()

        assertTrue(html == "<b><i>abc</i></b>" || html == "<i><b>abc</b></i>", html)
    }

    @Test
    fun `newlines become breaks, because HTML eats whitespace`() {
        // The bug this whole file exists to fix: a plain body sent as HTML
        // arrives as one paragraph.
        assertEquals("one<br>two", RichText.plain("one\ntwo").toHtml())
    }

    @Test
    fun `angle brackets and ampersands are escaped`() {
        assertEquals(
            "a &amp; b &lt;script&gt;",
            RichText.plain("a & b <script>").toHtml(),
        )
    }

    @Test
    fun `an empty body produces empty html, not an empty tag`() {
        assertEquals("", RichText().toHtml())
    }

    // -- round trip --------------------------------------------------------

    @Test
    fun `a document survives a round trip through html`() {
        val restored = htmlToRichText(doc.toHtml())

        assertEquals(doc.text, restored.text)
        assertEquals(doc.boldRange(), restored.boldRange())
    }

    @Test
    fun `a multi-line formatted document survives`() {
        val original = RichText.plain("first line\nsecond line")
            .toggle(TextMark.Bold, 0, 5)
            .toggle(TextMark.Italic, 11, 17)

        val restored = htmlToRichText(original.toHtml())

        assertEquals(original.text, restored.text)
        assertTrue(restored.isApplied(TextMark.Bold, 0, 5))
        assertTrue(restored.isApplied(TextMark.Italic, 11, 17))
    }

    @Test
    fun `escaped characters survive the round trip`() {
        val original = RichText.plain("a & b < c > d")

        assertEquals(original.text, htmlToRichText(original.toHtml()).text)
    }

    @Test
    fun `other clients' tags are understood`() {
        val read = htmlToRichText("<strong>bold</strong> and <em>italic</em>")

        assertEquals("bold and italic", read.text)
        assertTrue(read.isApplied(TextMark.Bold, 0, 4))
        assertTrue(read.isApplied(TextMark.Italic, 9, 15))
    }

    @Test
    fun `unknown tags are stripped to their text`() {
        // Showing raw markup in an editor is worse than losing formatting
        // nobody can edit here anyway.
        val read = htmlToRichText("""<span style="color:red">hello</span>""")

        assertEquals("hello", read.text)
    }

    @Test
    fun `paragraphs and breaks both become newlines`() {
        assertEquals("one\ntwo", htmlToRichText("one<br>two").text)
        assertEquals("one\ntwo\n", htmlToRichText("<p>one</p><p>two</p>").text)
    }

    @Test
    fun `broken markup does not lose the body`() {
        // An unterminated tag must not discard everything after it.
        val read = htmlToRichText("before <b unterminated")

        assertTrue(read.text.startsWith("before "), read.text)
    }

    @Test
    fun `a tag left open runs to the end rather than being dropped`() {
        val read = htmlToRichText("plain <b>bold to the end")

        assertTrue(read.isApplied(TextMark.Bold, 6, read.text.length))
    }
}
