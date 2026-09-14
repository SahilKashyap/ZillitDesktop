package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.printableHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The print page — the web's `handlePrint`: the subject, then every message
 * oldest first with its header block, ready for the browser's print dialog.
 */
class PrintMailTest {

    private val original = EmailMessage(
        id = "m1",
        threadId = "t1",
        subject = "Call sheet",
        from = "Aisha <a@prod.com>",
        to = listOf("crew@prod.com"),
        cc = listOf("ad@prod.com"),
        body = "Line one\nLine two",
        receivedAtMillis = 1_700_000_000_000,
    )
    private val reply = original.copy(
        id = "m2",
        from = "Me <me@prod.com>",
        body = "<p>Noted</p><script>alert(1)</script><iframe src=\"x\"></iframe>",
        isHtml = true,
        receivedAtMillis = 1_700_000_100_000,
    )

    @Test
    fun `the page carries the subject and every message oldest first`() {
        val html = printableHtml("Call sheet", listOf(reply, original))

        assertTrue(html.contains("<title>Call sheet</title>"))
        assertTrue(html.indexOf("Aisha") < html.indexOf("me@prod.com"), "the reply printed before the original")
        assertEquals(2, Regex("class=\"email-block\"").findAll(html).count())
    }

    @Test
    fun `a header block names From, To, Cc and the date`() {
        val html = printableHtml("Call sheet", listOf(original))

        listOf("From:", "To:", "Cc:", "Date:").forEach { assertTrue(html.contains(it), it) }
        assertTrue(html.contains("crew@prod.com"))
        assertFalse(html.contains("Bcc:"), "an empty Bcc line is noise")
    }

    @Test
    fun `plain text keeps its line breaks and is not read as markup`() {
        val html = printableHtml("x", listOf(original.copy(body = "1 < 2\nsecond")))

        assertTrue(html.contains("1 &lt; 2<br/>second"), html)
    }

    @Test
    fun `scripts and frames inside a mail body never reach the page`() {
        // A mail body is untrusted markup. The policy refuses every script but
        // the print call, and the blocks are dropped as well.
        val html = printableHtml("x", listOf(reply), printScriptNonce = "abc123")

        assertFalse(html.contains("alert(1)"))
        assertFalse(html.contains("<iframe"))
        assertTrue(html.contains("<p>Noted</p>"))
        assertTrue(html.contains("script-src 'nonce-abc123'"))
        val printCall = "<script nonce=\"abc123\">window.addEventListener('load', () => window.print());</script>"
        assertTrue(html.contains(printCall))
    }

    @Test
    fun `without a nonce nothing may run at all`() {
        val html = printableHtml("x", listOf(original))

        assertTrue(html.contains("script-src 'none'"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `a blank subject prints as no subject`() {
        assertTrue(printableHtml("", listOf(original)).contains("<title>(no subject)</title>"))
    }
}
