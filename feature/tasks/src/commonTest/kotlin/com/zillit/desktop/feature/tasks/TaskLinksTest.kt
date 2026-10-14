@file:Suppress("MaxLineLength") // Fixtures and expected link text read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.LinkPart
import com.zillit.desktop.feature.tasks.domain.insertAt
import com.zillit.desktop.feature.tasks.domain.isPastedLink
import com.zillit.desktop.feature.tasks.domain.linkParts
import com.zillit.desktop.feature.tasks.domain.linkText
import com.zillit.desktop.feature.tasks.domain.safeUrl
import com.zillit.desktop.feature.tasks.domain.shortUrl
import com.zillit.desktop.feature.tasks.domain.webAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's `lib/links.js` rules: only http(s) becomes a link, and the text itself is never rewritten. */
class TaskLinksTest {

    private fun hrefs(text: String) = linkParts(text).filterIsInstance<LinkPart.Link>().map { it.href }

    private fun texts(text: String) = linkParts(text).map {
        when (it) {
            is LinkPart.Text -> it.text
            is LinkPart.Link -> "<${it.text}>"
        }
    }

    @Test
    fun `a labelled link shows its words and opens its address`() {
        val parts = linkParts("See [Recce photos](https://drive.google.com/x) today")
        assertEquals(listOf("See ", "<Recce photos>", " today"), texts("See [Recce photos](https://drive.google.com/x) today"))
        assertEquals(listOf("https://drive.google.com/x"), parts.filterIsInstance<LinkPart.Link>().map { it.href })
    }

    @Test
    fun `a bare address is shortened for showing but opened in full`() {
        val link = linkParts("https://www.example.com/a/b?c=1").single() as LinkPart.Link
        assertEquals("https://www.example.com/a/b?c=1", link.href)
        assertEquals("example.com/a/b?c=1", link.text)
    }

    @Test
    fun `a www address is opened over https`() {
        assertEquals("https://www.example.com", safeUrl("www.example.com"))
        assertEquals(listOf("https://www.example.com"), hrefs("go to www.example.com"))
    }

    @Test
    fun `punctuation ending a sentence is not part of the address`() {
        assertEquals(listOf("see ", "<x.com>", "."), texts("see https://x.com."))
    }

    @Test
    fun `only http and https become links`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,x",
            "mailto:a@b.com",
            "file:///etc/passwd",
        ).forEach { assertTrue(hrefs(it).isEmpty(), "$it should stay text") }
        assertNull(safeUrl("javascript:alert(1)"))
    }

    @Test
    fun `an address inside a label is still only http or https`() {
        // The label form only ever matches an http(s) address, so this stays text.
        assertTrue(hrefs("[x](javascript:alert(1))").isEmpty())
    }

    @Test
    fun `a long address is cut for showing`() {
        val long = "https://example.com/" + "a".repeat(80)
        assertTrue(shortUrl(long).length <= 46, shortUrl(long))
        assertTrue(shortUrl(long).endsWith("…"))
    }

    @Test
    fun `what was typed into Add link becomes an address, or nothing`() {
        assertEquals("https://example.com", webAddress("example.com"))
        assertEquals("https://example.com/a", webAddress("https://example.com/a"))
        assertNull(webAddress("not a host"))
        // No dot in the host.
        assertNull(webAddress("localhost"))
        assertNull(webAddress("mailto:a@b.com"))
        assertNull(webAddress(""))
    }

    @Test
    fun `the stored text keeps brackets out of the way`() {
        assertEquals("https://x.com", linkText("https://x.com"))
        assertEquals("[Recce photos](https://x.com)", linkText("https://x.com", "Recce photos"))
        // Square brackets in the words would end the link early.
        assertEquals("[Recce photos](https://x.com)", linkText("https://x.com", "[Recce] photos"))
        // Round ones in the address would too.
        assertEquals("[a](https://x.com/%281%29)", linkText("https://x.com/(1)", "a"))
    }

    @Test
    fun `only one whole address counts as a pasted link`() {
        assertTrue(isPastedLink("https://x.com/a"))
        assertTrue(isPastedLink("  https://x.com/a  "))
        assertFalse(isPastedLink("see https://x.com"))
        assertFalse(isPastedLink("example.com"))
    }

    @Test
    fun `inserting at the caret keeps a space after a word`() {
        assertEquals("ab", insertAt("b", 0, 0, "a").text)
        assertEquals(1, insertAt("b", 0, 0, "a").caret)
        // Over a selection, the selection goes.
        assertEquals("aXc", insertAt("abc", 1, 2, "X").text)
        // `spaced` keeps the new text off the word before it.
        assertEquals("ab X", insertAt("ab", 2, 2, "X", spaced = true).text)
        assertEquals("ab X", insertAt("ab ", 3, 3, "X", spaced = true).text)
    }

    @Test
    fun `text with no address is one plain piece`() {
        assertEquals(listOf(LinkPart.Text("just words")), linkParts("just words"))
        assertEquals(emptyList(), linkParts(""))
        assertEquals(emptyList(), linkParts(null))
    }
}
