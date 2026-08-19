package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.BodySpan
import com.zillit.desktop.feature.email.domain.BodyStyle
import com.zillit.desktop.feature.email.domain.contrastingTextOn
import com.zillit.desktop.feature.email.domain.htmlToPlainText
import com.zillit.desktop.feature.email.domain.htmlToSpans
import com.zillit.desktop.feature.email.domain.isWebUrl
import com.zillit.desktop.feature.email.domain.plainTextToSpans
import com.zillit.desktop.feature.email.domain.vanishesOnTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading an HTML mail body as styled text.
 *
 * Mail HTML is the worst HTML there is — nested tables, inline CSS, tracking
 * pixels. Without an embedded browser the reader honours the formatting people
 * apply in a composer and flattens the rest to text; these tests pin down both
 * halves, and the colour fallback that stops black text vanishing in dark mode.
 */
class HtmlToTextTest {

    private fun styleOf(html: String, text: String): BodyStyle =
        htmlToSpans(html).first { it.text.contains(text) }.style

    @Test
    fun `tags are stripped and text kept`() {
        assertEquals("Call sheet attached.", htmlToPlainText("<p>Call sheet <b>attached</b>.</p>"))
    }

    @Test
    fun `style and script contents are removed, not shown`() {
        // Otherwise a styled newsletter renders as a wall of CSS.
        val body = htmlToPlainText(
            "<style>.x{color:red;font-size:12px}</style><p>Hello</p><script>track()</script>",
        )

        assertEquals("Hello", body)
        assertFalse(body.contains("color"))
        assertFalse(body.contains("track"))
    }

    @Test
    fun `the head and comments are removed`() {
        val html = "<html><head><title>Ignored</title><meta charset=utf-8></head>" +
            "<body><!-- a comment --><p>Shown</p><!--[if mso]>outlook junk<![endif]--></body></html>"

        assertEquals("Shown", htmlToPlainText(html))
    }

    @Test
    fun `hidden preheaders are dropped`() {
        // Newsletters hide a "view in browser" preheader with display:none.
        val html = """<div style="display:none">Preheader text</div><p>Body</p>"""

        assertEquals("Body", htmlToPlainText(html))
    }

    @Test
    fun `paragraphs survive as blank lines`() {
        // Paragraph structure is meaning in a mail; collapsing it to one blob
        // makes a long message unreadable.
        val body = htmlToPlainText("<p>First</p><p>Second</p>")

        assertEquals("First\n\nSecond", body)
    }

    @Test
    fun `divs are single line breaks`() {
        // Gmail writes one <div> per line; a blank line between each would
        // double-space every message it sends.
        assertEquals("One\nTwo", htmlToPlainText("<div>One</div><div>Two</div>"))
    }

    @Test
    fun `line breaks are preserved`() {
        assertEquals("Line one\nLine two", htmlToPlainText("Line one<br>Line two"))
        assertEquals("Line one\nLine two", htmlToPlainText("Line one<br/>Line two"))
    }

    @Test
    fun `runs of spaces collapse but newlines do not`() {
        assertEquals("A B\nC", htmlToPlainText("A&nbsp;&nbsp;   B<br>C"))
    }

    @Test
    fun `source newlines are whitespace, not line breaks`() {
        assertEquals("one two", htmlToPlainText("one\n   two"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry — \"quoted\"", htmlToPlainText("Tom &amp; Jerry &mdash; &quot;quoted&quot;"))
        assertEquals("it's <b>", htmlToPlainText("it&#39;s &lt;b&gt;"))
    }

    @Test
    fun `numeric entities are decoded`() {
        assertEquals("caf<", htmlToPlainText("caf&#60;"))
        assertEquals("A", htmlToPlainText("&#x41;"))
        assertEquals("&unknown;", htmlToPlainText("&unknown;"))
    }

    @Test
    fun `an unescaped less-than is text`() {
        assertEquals("a < b", htmlToPlainText("a < b"))
    }

    // -- links ------------------------------------------------------------

    @Test
    fun `a link becomes its own span carrying the href`() {
        val spans = htmlToSpans("""Read the <a href="https://zillit.com/cs">call sheet</a> now.""")

        val link = spans.single { it.isLink }
        assertEquals("call sheet", link.text)
        assertEquals("https://zillit.com/cs", link.href)
    }

    @Test
    fun `text either side of a link is kept in order`() {
        val spans = htmlToSpans("""Before <a href="https://x.com">link</a> after""")

        assertEquals(listOf("Before ", "link", " after"), spans.map { it.text })
    }

    @Test
    fun `single-quoted and unquoted hrefs are read`() {
        assertEquals("https://a.com", htmlToSpans("""<a href='https://a.com'>x</a>""").single().href)
        assertEquals("https://b.com", htmlToSpans("""<a href=https://b.com>x</a>""").single().href)
    }

    @Test
    fun `markup inside a link label keeps its style and the link`() {
        val spans = htmlToSpans("""<a href="https://x.com"><b>Bold</b> label</a>""")

        assertEquals("Bold label", spans.filter { it.isLink }.joinToString("") { it.text })
        assertTrue(spans.first { it.text == "Bold" }.style.bold)
    }

    @Test
    fun `a link with no visible label is dropped rather than shown empty`() {
        // Tracking pixels wrapped in anchors are the usual source.
        val spans = htmlToSpans("""<a href="https://track.me"><img src="x.gif"></a>""")

        assertTrue(spans.none { it.isLink }, "an unlabelled link should not render")
    }

    @Test
    fun `a bare URL in the text becomes a link`() {
        // Both reference clients linkify at display time (ZL-20216).
        val spans = htmlToSpans("See https://zillit.com/cs. Thanks")

        assertEquals("https://zillit.com/cs", spans.single { it.isLink }.href)
        assertEquals(listOf("See ", "https://zillit.com/cs", ". Thanks"), spans.map { it.text })
    }

    @Test
    fun `plain text bodies get their URLs linked too`() {
        val spans = plainTextToSpans("Call sheet: https://zillit.com/cs")

        assertEquals("https://zillit.com/cs", spans.single { it.isLink }.href)
        assertTrue(plainTextToSpans("").isEmpty())
    }

    @Test
    fun `only web URLs count as openable`() {
        assertTrue("https://zillit.com".isWebUrl())
        assertTrue("HTTP://zillit.com".isWebUrl())
        assertFalse("file:///etc/passwd".isWebUrl())
        assertFalse("javascript:alert(1)".isWebUrl())
        assertFalse("mailto:a@b.com".isWebUrl())
    }

    @Test
    fun `plain text with no markup passes through`() {
        val spans = htmlToSpans("Just a plain message.")

        assertEquals("Just a plain message.", spans.single().text)
        assertNull(spans.single().href)
        assertEquals(BodyStyle(), spans.single().style)
    }

    @Test
    fun `an empty body yields no spans`() {
        assertTrue(htmlToSpans("").isEmpty())
        assertTrue(htmlToSpans("   ").isEmpty())
    }

    @Test
    fun `a table-based newsletter reduces to its text`() {
        // The shape most marketing mail actually arrives in.
        val html = """
            <table><tr><td style="padding:10px"><h1>Sale</h1></td></tr>
            <tr><td><p>Everything must go</p></td></tr></table>
        """.trimIndent()

        val body = htmlToPlainText(html)

        assertTrue(body.contains("Sale"))
        assertTrue(body.contains("Everything must go"))
        assertFalse(body.contains("padding"))
    }

    @Test
    fun `neighbouring table cells are separated`() {
        assertEquals("Name Value", htmlToPlainText("<table><tr><td>Name</td><td>Value</td></tr></table>"))
    }

    // -- formatting ---------------------------------------------------------

    @Test
    fun `bold italic underline and strike are read from tags`() {
        val html = "<b>b</b><strong>s</strong><i>i</i><em>e</em><u>u</u><s>x</s><strike>y</strike><del>z</del>"

        assertTrue(styleOf(html, "b").bold)
        assertTrue(styleOf(html, "s").bold)
        assertTrue(styleOf(html, "i").italic)
        assertTrue(styleOf(html, "e").italic)
        assertTrue(styleOf(html, "u").underline)
        assertTrue(styleOf(html, "x").strike)
        assertTrue(styleOf(html, "y").strike)
        assertTrue(styleOf(html, "z").strike)
    }

    @Test
    fun `nested marks stack and unwind`() {
        val spans = htmlToSpans("<b>bold <i>both</i> bold</b> plain")

        assertEquals(listOf("bold ", "both", " bold", " plain"), spans.map { it.text })
        assertEquals(BodyStyle(bold = true), spans[0].style)
        assertEquals(BodyStyle(bold = true, italic = true), spans[1].style)
        assertEquals(BodyStyle(bold = true), spans[2].style)
        assertEquals(BodyStyle(), spans[3].style)
    }

    @Test
    fun `mismatched closes do not leak a style over the rest of the message`() {
        // "<b><i>x</b></i>" is what naive writers emit; the stray </i> must be
        // ignored and the </b> must close everything it opened.
        val spans = htmlToSpans("<b><i>x</b></i> after")

        assertEquals(BodyStyle(bold = true, italic = true), spans[0].style)
        assertEquals(BodyStyle(), spans.last().style)
    }

    @Test
    fun `css marks are read from style attributes`() {
        val style = "font-weight:bold;font-style:italic;text-decoration:underline line-through"
        val html = """<span style="$style">x</span>"""

        assertEquals(BodyStyle(bold = true, italic = true, underline = true, strike = true), styleOf(html, "x"))
        assertTrue(styleOf("""<span style="font-weight: 700">x</span>""", "x").bold)
        assertFalse(styleOf("""<b><span style="font-weight: normal">x</span></b>""", "x").bold)
    }

    @Test
    fun `colours are read from font tags and inline styles`() {
        assertEquals("#e60000", styleOf("""<font color="#e60000">red</font>""", "red").color)
        assertEquals("#e60000", styleOf("""<span style="color: #E60000">red</span>""", "red").color)
        assertEquals("#ff0000", styleOf("""<span style="color:red">red</span>""", "red").color)
        assertEquals("#0000ff", styleOf("""<span style="color:#00f">blue</span>""", "blue").color)
        assertEquals("#102030", styleOf("""<span style="color:rgb(16, 32, 48)">c</span>""", "c").color)
        assertEquals("#102030", styleOf("""<span style="color:rgba(16,32,48,0.5)">c</span>""", "c").color)
    }

    @Test
    fun `an unreadable colour is ignored rather than guessed`() {
        assertNull(styleOf("""<span style="color:inherit">x</span>""", "x").color)
        assertNull(styleOf("""<span style="color:#12">x</span>""", "x").color)
    }

    @Test
    fun `background colours and mark are read`() {
        assertEquals("#ffff00", styleOf("""<span style="background-color: #ffff00">hi</span>""", "hi").background)
        assertEquals("#00ff00", styleOf("""<span style="background: lime">hi</span>""", "hi").background)
        assertEquals("#ffff00", styleOf("<mark>hi</mark>", "hi").background)
    }

    @Test
    fun `an inner colour overrides an outer one`() {
        val html = """<font color="red">r <span style="color:blue">b</span> r</font>"""

        assertEquals("#ff0000", styleOf(html, "r ").color)
        assertEquals("#0000ff", styleOf(html, "b").color)
    }

    @Test
    fun `font sizes are read in every unit mail carries`() {
        assertEquals(1.5f, styleOf("""<span style="font-size: 24px">x</span>""", "x").sizeScale)
        assertEquals(1f, styleOf("""<span style="font-size: 12pt">x</span>""", "x").sizeScale)
        assertEquals(2f, styleOf("""<span style="font-size: 2em">x</span>""", "x").sizeScale)
        assertEquals(0.5f, styleOf("""<span style="font-size: 50%">x</span>""", "x").sizeScale)
        assertEquals(1.5f, styleOf("""<span style="font-size: x-large">x</span>""", "x").sizeScale)
        assertEquals(1.5f, styleOf("""<font size="5">x</font>""", "x").sizeScale)
        assertEquals(1.13f, styleOf("""<font size="+1">x</font>""", "x").sizeScale)
    }

    @Test
    fun `relative sizes multiply their parent`() {
        val html = """<span style="font-size: 200%"><span style="font-size: 0.5em">x</span></span>"""

        assertEquals(1f, styleOf(html, "x").sizeScale)
    }

    @Test
    fun `absurd sizes are clamped`() {
        assertEquals(3f, styleOf("""<span style="font-size: 500px">x</span>""", "x").sizeScale)
        assertEquals(0.5f, styleOf("""<span style="font-size: 1px">x</span>""", "x").sizeScale)
    }

    @Test
    fun `headings are bold, larger and on their own line`() {
        val spans = htmlToSpans("<h1>Title</h1><p>Body</p>")

        val title = spans.first { it.text == "Title" }
        assertTrue(title.style.bold)
        assertEquals(2f, title.style.sizeScale)
        assertEquals("Title\n\nBody", htmlToPlainText("<h1>Title</h1><p>Body</p>"))
        assertEquals(1.5f, styleOf("<h2>x</h2>", "x").sizeScale)
        assertEquals(0.67f, styleOf("<h6>x</h6>", "x").sizeScale)
    }

    @Test
    fun `code and pre are monospace and pre keeps its whitespace`() {
        assertTrue(styleOf("<code>x</code>", "x").monospace)
        assertTrue(styleOf("""<span style="font-family: Courier New, monospace">x</span>""", "x").monospace)

        val pre = htmlToSpans("<p>Before</p><pre>\nline 1\n    indented\n</pre><p>After</p>")
        val block = pre.first { it.text.contains("indented") }
        assertTrue(block.style.monospace)
        assertEquals("line 1\n    indented\n", block.text)
    }

    // -- lists and quotes -----------------------------------------------------

    @Test
    fun `unordered lists become bullets on their own lines`() {
        val html = "<p>Bring:</p><ul><li>Boots</li><li>Radio</li></ul><p>Thanks</p>"

        assertEquals("Bring:\n\n• Boots\n• Radio\n\nThanks", htmlToPlainText(html))
    }

    @Test
    fun `ordered lists count`() {
        assertEquals("1. One\n2. Two\n3. Three", htmlToPlainText("<ol><li>One</li><li>Two</li><li>Three</li></ol>"))
    }

    @Test
    fun `nested lists indent and change marker`() {
        val html = "<ul><li>Camera<ul><li>Lenses</li></ul></li><li>Sound</li></ul>"

        assertEquals("• Camera\n   ◦ Lenses\n• Sound", htmlToPlainText(html))
    }

    @Test
    fun `a paragraph inside a list item stays on the bullet line`() {
        // Outlook wraps every item's text in <p>; the bullet must not sit on a
        // line of its own above it.
        assertEquals("• One\n• Two", htmlToPlainText("<ul><li><p>One</p></li><li><p>Two</p></li></ul>"))
    }

    @Test
    fun `list items carry their formatting`() {
        assertTrue(styleOf("<ul><li><b>Bold item</b></li></ul>", "Bold item").bold)
    }

    @Test
    fun `blockquotes are marked by depth`() {
        val html = "<p>Reply</p><blockquote><p>Quoted</p><blockquote>Deeper</blockquote></blockquote><p>After</p>"
        val spans = htmlToSpans(html)

        assertEquals(0, spans.first { it.text.contains("Reply") }.style.quoteDepth)
        assertEquals(1, spans.first { it.text.contains("Quoted") }.style.quoteDepth)
        assertEquals(2, spans.first { it.text.contains("Deeper") }.style.quoteDepth)
        assertEquals(0, spans.first { it.text.contains("After") }.style.quoteDepth)
        assertEquals("Reply\n\nQuoted\n\nDeeper\n\nAfter", htmlToPlainText(html))
    }

    @Test
    fun `the blank line before a quote belongs to the quote`() {
        // The renderer indents whole paragraphs; the break that opens a quoted
        // block has to sit inside it, or the indent starts a line late.
        val spans = htmlToSpans("<p>Reply</p><blockquote>Quoted</blockquote>")

        val breakSpan = spans.first { it.text.startsWith("\n") }
        assertEquals(1, breakSpan.style.quoteDepth)
        assertFalse(breakSpan.isLink)
    }

    @Test
    fun `a link never owns the blank line before it`() {
        // Otherwise the whole empty line above a link is clickable.
        val spans = htmlToSpans("""<p>Text</p><p><a href="https://x.com">Link</a></p>""")

        assertEquals("Link", spans.single { it.isLink }.text)
    }

    @Test
    fun `adjacent runs with the same style merge`() {
        val spans = htmlToSpans("<b>one</b><b> two</b>")

        assertEquals(listOf(BodySpan("one two", null, BodyStyle(bold = true))), spans)
    }

    // -- the theme fallback ------------------------------------------------------

    @Test
    fun `near-black vanishes on the dark theme, not the light one`() {
        assertTrue(vanishesOnTheme("#000000", isDark = true))
        assertTrue(vanishesOnTheme("#1a1a1a", isDark = true))
        assertTrue(vanishesOnTheme("#333333", isDark = true))
        assertFalse(vanishesOnTheme("#000000", isDark = false))
        assertFalse(vanishesOnTheme("#1a1a1a", isDark = false))
    }

    @Test
    fun `near-white vanishes on the light theme, not the dark one`() {
        assertTrue(vanishesOnTheme("#ffffff", isDark = false))
        assertTrue(vanishesOnTheme("#e6e6e6", isDark = false))
        assertFalse(vanishesOnTheme("#ffffff", isDark = true))
        assertFalse(vanishesOnTheme("#e6e6e6", isDark = true))
    }

    @Test
    fun `real colours survive on both themes`() {
        listOf("#e60000", "#ff9900", "#008a00", "#0066cc", "#9933ff").forEach { hex ->
            assertFalse(vanishesOnTheme(hex, isDark = true), "$hex should show on dark")
            assertFalse(vanishesOnTheme(hex, isDark = false), "$hex should show on light")
        }
    }

    @Test
    fun `an unreadable hex never triggers the fallback`() {
        assertFalse(vanishesOnTheme("red", isDark = true))
        assertFalse(vanishesOnTheme("", isDark = false))
    }

    @Test
    fun `text on a highlight contrasts with the highlight`() {
        assertEquals("#1a1a1a", contrastingTextOn("#ffff00"))
        assertEquals("#f2f2f2", contrastingTextOn("#000080"))
    }
}
