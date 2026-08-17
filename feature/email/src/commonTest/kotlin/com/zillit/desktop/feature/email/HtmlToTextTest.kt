package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.htmlToPlainText
import com.zillit.desktop.feature.email.domain.htmlToSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading an HTML mail body as text.
 *
 * Mail HTML is the worst HTML there is — nested tables, inline CSS, tracking
 * pixels. Until an embedded browser lands (plan M6/M8) a faithful text
 * conversion beats a broken visual one, which is what the web's trail item does
 * too.
 */
class HtmlToTextTest {

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
    fun `paragraphs survive as blank lines`() {
        // Paragraph structure is meaning in a mail; collapsing it to one blob
        // makes a long message unreadable.
        val body = htmlToPlainText("<p>First</p><p>Second</p>")

        assertTrue(body.contains("\n"), "expected a break between paragraphs: $body")
        assertTrue(body.startsWith("First"))
        assertTrue(body.endsWith("Second"))
    }

    @Test
    fun `line breaks are preserved`() {
        assertEquals("Line one\nLine two", htmlToPlainText("Line one<br>Line two"))
    }

    @Test
    fun `runs of spaces collapse but newlines do not`() {
        assertEquals("A B\nC", htmlToPlainText("A&nbsp;&nbsp;   B<br>C"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry — \"quoted\"", htmlToPlainText("Tom &amp; Jerry &mdash; &quot;quoted&quot;"))
    }

    @Test
    fun `numeric entities are decoded`() {
        assertEquals("caf<", htmlToPlainText("caf&#60;"))
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
    fun `markup inside a link label is stripped`() {
        val spans = htmlToSpans("""<a href="https://x.com"><b>Bold</b> label</a>""")

        assertEquals("Bold label", spans.single { it.isLink }.text)
    }

    @Test
    fun `a link with no visible label is dropped rather than shown empty`() {
        // Tracking pixels wrapped in anchors are the usual source.
        val spans = htmlToSpans("""<a href="https://track.me"><img src="x.gif"></a>""")

        assertTrue(spans.none { it.isLink }, "an unlabelled link should not render")
    }

    @Test
    fun `plain text with no markup passes through`() {
        val spans = htmlToSpans("Just a plain message.")

        assertEquals("Just a plain message.", spans.single().text)
        assertNull(spans.single().href)
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
}
