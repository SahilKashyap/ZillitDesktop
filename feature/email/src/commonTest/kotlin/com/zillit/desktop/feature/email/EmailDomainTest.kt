package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.defaultFolder
import com.zillit.desktop.feature.email.domain.forSidebar
import com.zillit.desktop.feature.email.domain.toSnippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folder ordering and header parsing.
 *
 * Mail headers are written by every client ever made, and the server returns
 * folders in creation order — both are places where "it mostly works" shows up
 * as a visibly wrong sidebar.
 */
class EmailDomainTest {

    private fun folder(name: String, system: Boolean = true) =
        EmailFolder(name = name, isSystem = system)

    @Test
    fun `system folders sort into their conventional order`() {
        // The server returns creation order, which has put Trash above Inbox.
        val sidebar = listOf(
            folder("Trash"), folder("Inbox"), folder("Sent"), folder("Drafts"),
        ).forSidebar()

        assertEquals(listOf("Inbox", "Sent", "Drafts", "Trash"), sidebar.map { it.name })
    }

    @Test
    fun `user folders follow the system ones, alphabetically`() {
        val sidebar = listOf(
            folder("zebra", system = false),
            folder("Inbox"),
            folder("Accounts", system = false),
        ).forSidebar()

        assertEquals(listOf("Inbox", "Accounts", "zebra"), sidebar.map { it.name })
    }

    @Test
    fun `folder matching ignores case`() {
        assertEquals("INBOX", listOf(folder("INBOX"), folder("Trash")).forSidebar().first().name)
    }

    @Test
    fun `the inbox opens first`() {
        val folders = listOf(folder("Trash"), folder("Inbox"), folder("Sent"))

        assertEquals("Inbox", folders.defaultFolder()?.name)
    }

    @Test
    fun `with no inbox, the first sidebar folder opens`() {
        val folders = listOf(folder("zebra", system = false), folder("Sent"))

        assertEquals("Sent", folders.defaultFolder()?.name)
    }

    @Test
    fun `an empty mailbox has no default folder`() {
        assertNull(emptyList<EmailFolder>().defaultFolder())
    }

    // -- header parsing ---------------------------------------------------

    private fun summary(from: String) =
        EmailSummary(id = "1", threadId = "t", subject = "s", from = from)

    @Test
    fun `a display name is taken from the header`() {
        assertEquals("Aisha Khan", summary("Aisha Khan <aisha@prod.com>").senderName)
        assertEquals("aisha@prod.com", summary("Aisha Khan <aisha@prod.com>").senderAddress)
    }

    @Test
    fun `a quoted display name loses its quotes`() {
        assertEquals("Khan, Aisha", summary("\"Khan, Aisha\" <a@b.com>").senderName)
    }

    @Test
    fun `a bare address is used as the name`() {
        assertEquals("aisha@prod.com", summary("aisha@prod.com").senderName)
        assertEquals("aisha@prod.com", summary("aisha@prod.com").senderAddress)
    }

    @Test
    fun `an angle-bracketed address with no name falls back to the address`() {
        assertEquals("a@b.com", summary("<a@b.com>").senderName)
    }

    @Test
    fun `a blank sender is named rather than left empty`() {
        // A row with no sender is unusable.
        assertEquals("Unknown sender", summary("   ").senderName)
    }

    // -- snippets ---------------------------------------------------------

    @Test
    fun `html is stripped from the preview`() {
        val snippet = "<p>Call sheet <b>attached</b> for tomorrow.</p>".toSnippet()

        assertEquals("Call sheet attached for tomorrow.", snippet)
        assertFalse(snippet.contains("<"))
    }

    @Test
    fun `entities and runs of whitespace collapse`() {
        assertEquals("A B", "A&nbsp;&nbsp;\n\t  B".toSnippet())
    }

    @Test
    fun `a long body is elided`() {
        val snippet = "x".repeat(500).toSnippet()

        assertTrue(snippet.length < 200)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `a short body is left alone`() {
        assertEquals("Short one.", "Short one.".toSnippet())
    }

    @Test
    fun `a summary never prints the subject`() {
        // Mail is the most private thing in the app and this reaches logs.
        val text = summary("a@b.com").copy(subject = "Budget overrun", snippet = "confidential")
            .toString()

        assertFalse(text.contains("Budget overrun"))
        assertFalse(text.contains("confidential"))
    }
}
