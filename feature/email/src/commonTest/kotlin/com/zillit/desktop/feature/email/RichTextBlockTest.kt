package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.BlockKind
import com.zillit.desktop.feature.email.domain.LineStyle
import com.zillit.desktop.feature.email.domain.MAX_INDENT
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.indentBy
import com.zillit.desktop.feature.email.domain.lineStyles
import com.zillit.desktop.feature.email.domain.orderedNumbers
import com.zillit.desktop.feature.email.domain.selectedLineStyle
import com.zillit.desktop.feature.email.domain.toHtml
import com.zillit.desktop.feature.email.domain.toggleList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Paragraph attributes: the half of the composer that is not a span.
 *
 * The cases that matter are the ones where a list survives an *edit* — a list
 * that looks right until the user types in it is the defining bug of a
 * home-grown editor, and the reason attributes are ranges carried by
 * `withText` rather than a line-number map.
 */
class RichTextBlockTest {

    private fun doc(text: String) = RichText.plain(text)

    @Test
    fun `a bullet covers only the selected lines`() {
        val listed = doc("one\ntwo\nthree").toggleList(BlockKind.Bullet, 0, 7)

        assertEquals(
            listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet), LineStyle()),
            listed.lineStyles(),
        )
    }

    @Test
    fun `toggling a fully listed selection clears it`() {
        val listed = doc("one\ntwo").toggleList(BlockKind.Bullet, 0, 7)
        val cleared = listed.toggleList(BlockKind.Bullet, 0, 7)

        assertEquals(listOf(LineStyle(), LineStyle()), cleared.lineStyles())
    }

    @Test
    fun `a half-listed selection becomes fully listed on the first press`() {
        val half = doc("one\ntwo").toggleList(BlockKind.Bullet, 0, 2)
        val whole = half.toggleList(BlockKind.Bullet, 0, 7)

        assertEquals(listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet)), whole.lineStyles())
    }

    @Test
    fun `the other marker replaces the first`() {
        val bulleted = doc("one").toggleList(BlockKind.Bullet, 0, 3)
        val numbered = bulleted.toggleList(BlockKind.Ordered, 0, 3)

        assertEquals(listOf(LineStyle(BlockKind.Ordered)), numbered.lineStyles())
    }

    @Test
    fun `typing at a line's start keeps that line's bullet`() {
        val listed = doc("one\ntwo").toggleList(BlockKind.Bullet, 0, 7)
        // An insertion at offset 0 pushes the block's start off the line; the
        // realignment after every edit is what puts it back.
        val typed = listed.withText("Xone\ntwo")

        assertEquals(listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet)), typed.lineStyles())
    }

    @Test
    fun `enter at the end of an item starts a new item`() {
        val listed = doc("one").toggleList(BlockKind.Bullet, 0, 3)
        val split = listed.withText("one\n")

        assertEquals(listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet)), split.lineStyles())
    }

    @Test
    fun `enter in the middle of an item bullets both halves`() {
        val listed = doc("onetwo").toggleList(BlockKind.Bullet, 0, 6)
        val split = listed.withText("one\ntwo")

        assertEquals(listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet)), split.lineStyles())
    }

    @Test
    fun `a plain line between two listed ones keeps its own nothing`() {
        val listed = doc("one\ntwo\nthree")
            .toggleList(BlockKind.Bullet, 0, 2)
            .toggleList(BlockKind.Bullet, 9, 11)

        assertEquals(
            listOf(LineStyle(BlockKind.Bullet), LineStyle(), LineStyle(BlockKind.Bullet)),
            listed.lineStyles(),
        )
    }

    @Test
    fun `indent stops at the web's ceiling and at zero`() {
        val deep = doc("one").indentBy(MAX_INDENT + 3, 0, 3)
        assertEquals(MAX_INDENT, deep.lineStyles().single().indent)

        val back = deep.indentBy(-(MAX_INDENT + 3), 0, 3)
        assertEquals(0, back.lineStyles().single().indent)
    }

    @Test
    fun `a mixed selection reports no shared paragraph style`() {
        val mixed = doc("one\ntwo").toggleList(BlockKind.Bullet, 0, 2)

        assertNull(mixed.selectedLineStyle(0, 7))
        assertEquals(LineStyle(BlockKind.Bullet), mixed.selectedLineStyle(0, 2))
    }

    @Test
    fun `numbering restarts under a sublist and resumes after it`() {
        val styles = listOf(
            LineStyle(BlockKind.Ordered, 0),
            LineStyle(BlockKind.Ordered, 1),
            LineStyle(BlockKind.Ordered, 1),
            LineStyle(BlockKind.Ordered, 0),
        )

        assertEquals(listOf(1, 1, 2, 2), styles.orderedNumbers())
    }

    @Test
    fun `a bullet in the run does not take a number`() {
        val styles = listOf(LineStyle(BlockKind.Ordered), LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Ordered))

        assertEquals(listOf(1, 0, 1), styles.orderedNumbers())
    }

    @Test
    fun `a list is real list markup on the wire`() {
        val listed = doc("one\ntwo").toggleList(BlockKind.Bullet, 0, 7)

        assertEquals("<ul><li>one</li><li>two</li></ul>", listed.toHtml())
    }

    @Test
    fun `an indented item nests inside its parent`() {
        val listed = doc("one\ntwo")
            .toggleList(BlockKind.Ordered, 0, 7)
            .indentBy(1, 4, 7)

        assertEquals("<ol><li>one</li><ol><li>two</li></ol></ol>", listed.toHtml())
    }

    @Test
    fun `a top-level list carries its own indent as margin, in the web's unit`() {
        val listed = doc("one").toggleList(BlockKind.Bullet, 0, 3).indentBy(2, 0, 3)

        assertEquals("<ul style=\"margin-left: 6em\"><li>one</li></ul>", listed.toHtml())
    }

    @Test
    fun `an indented plain line is a padded div`() {
        val pushed = doc("one").indentBy(1, 0, 3)

        assertEquals("<div style=\"padding-left: 3em\">one</div>", pushed.toHtml())
    }

    @Test
    fun `plain lines are still separated by br and never end on one`() {
        assertEquals("one<br>two", doc("one\ntwo").toHtml())
    }

    @Test
    fun `lists round-trip through html`() {
        val listed = doc("one\ntwo\nthree")
            .toggleList(BlockKind.Ordered, 0, 11)
            .indentBy(1, 4, 7)

        val restored = htmlToRichText(listed.toHtml())

        assertEquals(listed.text, restored.text)
        assertEquals(listed.lineStyles(), restored.lineStyles())
    }

    @Test
    fun `indents round-trip through html`() {
        val pushed = doc("one\ntwo").indentBy(2, 0, 2).indentBy(1, 4, 7)
        val restored = htmlToRichText(pushed.toHtml())

        assertEquals(pushed.text, restored.text)
        assertEquals(pushed.lineStyles(), restored.lineStyles())
    }

    @Test
    fun `the web's own list markup is understood`() {
        // TipTap wraps every item's content in a paragraph; reading that as a
        // line of its own would strip the marker off every item it sends.
        val restored = htmlToRichText("<ul><li><p>one</p></li><li><p>two</p></li></ul>")

        assertEquals("one\ntwo", restored.text)
        assertEquals(listOf(LineStyle(BlockKind.Bullet), LineStyle(BlockKind.Bullet)), restored.lineStyles())
    }

    @Test
    fun `consecutive blocks do not leave a trailing blank line`() {
        assertEquals("one\ntwo", htmlToRichText("<div>one</div><div>two</div>").text)
    }

    @Test
    fun `a link is an anchor both ways`() {
        val linked = RichText.plain("call sheet").set(TextMark.Link("https://zillit.com"), 0, 4)

        assertEquals("<a href=\"https://zillit.com\" target=\"_blank\">call</a> sheet", linked.toHtml())
        assertEquals(linked.marks, htmlToRichText(linked.toHtml()).marks)
    }

    @Test
    fun `a link that is not a link is dropped rather than written`() {
        val hostile = RichText.plain("x").set(TextMark.Link("javascript:alert(1)"), 0, 1)

        assertEquals("<a href=\"\" target=\"_blank\">x</a>", hostile.toHtml())
        assertEquals(emptyList(), htmlToRichText(hostile.toHtml()).marks)
    }

    @Test
    fun `a font family is a styled span both ways`() {
        val styled = RichText.plain("call").set(TextMark.FontFamily("Times New Roman"), 0, 4)

        assertEquals("<span style=\"font-family: Times New Roman\">call</span>", styled.toHtml())
        assertEquals(styled.marks, htmlToRichText(styled.toHtml()).marks)
    }

    @Test
    fun `a quoted font stack reads as its first family`() {
        val restored = htmlToRichText("<span style=\"font-family: 'Segoe UI', sans-serif\">x</span>")

        assertEquals(TextMark.FontFamily("Segoe UI"), restored.marks.single().style)
    }
}
