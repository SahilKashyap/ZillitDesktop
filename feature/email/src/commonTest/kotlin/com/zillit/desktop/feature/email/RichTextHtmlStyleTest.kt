package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.Mark
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.toHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire shapes of the new styles.
 *
 * The receiving client is somebody's phone mail app: strikethrough must be a
 * plain `<s>`, the valued styles inline-styled `<span>`s — the exact markup
 * Quill emits on the web, so a body composed here and one composed there are
 * indistinguishable to the reader.
 */
class RichTextHtmlStyleTest {

    @Test
    fun `strike is an s tag`() {
        val doc = RichText.plain("call sheet").toggle(TextMark.Strike, 5, 10)

        assertEquals("call <s>sheet</s>", doc.toHtml())
    }

    @Test
    fun `colour highlight and size are styled spans`() {
        assertEquals(
            "<span style=\"color: #e60000\">call</span>",
            RichText.plain("call").set(TextMark.TextColor("#e60000"), 0, 4).toHtml(),
        )
        assertEquals(
            "<span style=\"background-color: #ffff00\">call</span>",
            RichText.plain("call").set(TextMark.Highlight("#ffff00"), 0, 4).toHtml(),
        )
        assertEquals(
            "<span style=\"font-size: 24px\">call</span>",
            RichText.plain("call").set(TextMark.FontSize(24), 0, 4).toHtml(),
        )
    }

    @Test
    fun `a hostile colour value cannot break out of the attribute`() {
        val doc = RichText.plain("x").set(TextMark.TextColor("\"><script>"), 0, 1)

        assertEquals("<span style=\"color: #000000\">x</span>", doc.toHtml())
    }

    @Test
    fun `every style round-trips through html`() {
        val doc = RichText.plain("The call sheet moved")
            .toggle(TextMark.Bold, 0, 3)
            .toggle(TextMark.Strike, 4, 8)
            .set(TextMark.TextColor("#0066cc"), 9, 14)
            .set(TextMark.Highlight("#ffff00"), 9, 14)
            .set(TextMark.FontSize(18), 15, 20)

        val restored = htmlToRichText(doc.toHtml())

        assertEquals(doc.text, restored.text)
        assertTrue(restored.isApplied(TextMark.Bold, 0, 3))
        assertTrue(restored.isApplied(TextMark.Strike, 4, 8))
        assertTrue(restored.isApplied(TextMark.TextColor("#0066cc"), 9, 14))
        assertTrue(restored.isApplied(TextMark.Highlight("#ffff00"), 9, 14))
        assertTrue(restored.isApplied(TextMark.FontSize(18), 15, 20))
    }

    @Test
    fun `other clients' markup is understood`() {
        val restored = htmlToRichText(
            "<del>gone</del> <span style='color: rgb(230, 0, 0); font-size: 18px !important'>red</span>",
        )

        assertEquals("gone red", restored.text)
        assertTrue(restored.isApplied(TextMark.Strike, 0, 4))
        assertTrue(restored.isApplied(TextMark.TextColor("#e60000"), 5, 8))
        assertTrue(restored.isApplied(TextMark.FontSize(18), 5, 8))
    }

    @Test
    fun `short hex and decoration styles normalise`() {
        val restored = htmlToRichText(
            "<span style=\"color: #f00\">a</span><span style=\"text-decoration: line-through\">b</span>",
        )

        assertTrue(restored.isApplied(TextMark.TextColor("#ff0000"), 0, 1))
        assertTrue(restored.isApplied(TextMark.Strike, 1, 2))
    }

    @Test
    fun `a styled span split by a line break stays off the newline`() {
        val restored = htmlToRichText("<span style=\"color: #e60000\">one<br>two</span>")

        assertEquals("one\ntwo", restored.text)
        assertTrue(restored.isApplied(TextMark.TextColor("#e60000"), 0, 3))
        assertTrue(restored.isApplied(TextMark.TextColor("#e60000"), 4, 7))
        assertTrue(
            restored.marks.none { it.start <= 3 && it.end > 3 },
            "a mark spans the newline",
        )
    }

    @Test
    fun `an unmatched span close is ignored`() {
        val restored = htmlToRichText("plain</span> text")

        assertEquals("plain text", restored.text)
        assertTrue(restored.marks.isEmpty())
    }

    @Test
    fun `a styleless span contributes nothing but stays balanced`() {
        val restored = htmlToRichText(
            "<span class=\"x\">plain</span><span style=\"color: #e60000\">red</span>",
        )

        assertEquals("plainred", restored.text)
        assertTrue(restored.isApplied(TextMark.TextColor("#e60000"), 5, 8))
        assertTrue(restored.marks.none { it.start < 5 })
    }

    @Test
    fun `size values outside sanity are clamped on the way out`() {
        val html = RichText("x", listOf(Mark(0, 1, TextMark.FontSize(500)))).toHtml()

        assertEquals("<span style=\"font-size: 72px\">x</span>", html)
    }
}
