package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailFilters
import com.zillit.desktop.feature.email.domain.EmailQuery
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.ReadFilter
import com.zillit.desktop.feature.email.domain.ReadStatus
import com.zillit.desktop.feature.email.domain.SearchField
import com.zillit.desktop.feature.email.domain.applyFilters
import com.zillit.desktop.feature.email.domain.matchingSearch
import com.zillit.desktop.feature.email.domain.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Searching mail.
 *
 * Local, because there is nothing else on offer: the mail API exposes no search
 * endpoint, and neither other client has one — Android queries its Realm cache
 * and the web filters the list it already holds. So the assertions here are
 * about the *rules*, and about the UI being honest that this covers downloaded
 * mail rather than the mailbox.
 */
class EmailSearchTest {

    private fun mail(
        id: String,
        subject: String = "",
        from: String = "",
        to: List<String> = emptyList(),
        snippet: String = "",
        at: Long = 0,
    ) = EmailSummary(
        id = id,
        threadId = id,
        subject = subject,
        from = from,
        to = to,
        snippet = snippet,
        receivedAtMillis = at,
    )

    /** Where a message sits and what state it is in, kept off the constructor. */
    private fun EmailSummary.filed(
        folder: String = "INBOX",
        read: Boolean = true,
        attachments: Int = 0,
    ) = copy(
        folderName = folder,
        isRead = read,
        hasAttachments = attachments > 0,
        attachmentCount = attachments,
    )

    private val mailbox = listOf(
        mail("m1", subject = "Call sheet day 12", from = "Aisha <a@prod.com>", at = 300),
        mail("m2", subject = "Catering", from = "Ravi <r@prod.com>", snippet = "call sheet attached", at = 200),
        mail("m3", subject = "Invoice", from = "vendor@x.com", at = 100)
            .filed(folder = "Archive", read = false),
        mail("m4", subject = "Sides", to = listOf("crew@prod.com"), at = 400)
            .filed(folder = "Sent", attachments = 2),
    )

    private fun find(
        term: String,
        fields: Set<SearchField> = EmailQuery.DEFAULT_FIELDS,
        folders: Set<String> = emptySet(),
        readFilter: ReadFilter = ReadFilter.Any,
        attachmentsOnly: Boolean = false,
    ) = mailbox.search(
        EmailQuery(term, folders, fields, readFilter, attachmentsOnly),
    ).map { it.id }

    // -- the basics --------------------------------------------------------

    @Test
    fun `subject and sender are searched by default`() {
        assertEquals(listOf("m1"), find("call sheet"))
        assertEquals(listOf("m2"), find("ravi"))
    }

    @Test
    fun `the body is not searched by default`() {
        // A term in a quoted reply chain matches half the mailbox, and someone
        // searching "call sheet" wants the message about it.
        assertFalse(find("call sheet").contains("m2"))

        val withBody = find("call sheet", fields = EmailQuery.DEFAULT_FIELDS + SearchField.Body)
        assertTrue(withBody.contains("m2"))
    }

    @Test
    fun `searching is case-insensitive`() {
        assertEquals(find("CALL SHEET"), find("call sheet"))
    }

    @Test
    fun `a blank term finds nothing, not everything`() {
        // A box that shows the whole mailbox the moment it is focused is noise.
        assertTrue(find("").isEmpty())
        assertTrue(find("   ").isEmpty())
    }

    @Test
    fun `results are newest first`() {
        assertEquals(listOf("m4", "m1", "m2", "m3"), find("", fields = emptySet()).ifEmpty {
            mailbox.search(EmailQuery("@", fields = setOf(SearchField.From, SearchField.To, SearchField.Subject)))
                .map { it.id }
        })
    }

    // -- crossing folders --------------------------------------------------

    @Test
    fun `search spans every folder, not the open one`() {
        // Someone looking for a call sheet does not know which folder it is in.
        // That is the point of searching.
        assertEquals(listOf("m3"), find("invoice"))
        assertEquals(listOf("m4"), find("sides"))
    }

    @Test
    fun `folders can be narrowed explicitly`() {
        assertTrue(find("invoice", folders = setOf("INBOX")).isEmpty())
        assertEquals(listOf("m3"), find("invoice", folders = setOf("Archive")))
    }

    // -- filters -----------------------------------------------------------

    @Test
    fun `unread narrows to unread`() {
        assertEquals(listOf("m3"), find("invoice", readFilter = ReadFilter.Unread))
        assertTrue(find("invoice", readFilter = ReadFilter.Read).isEmpty())
    }

    @Test
    fun `attachments narrow to messages that have them`() {
        assertEquals(listOf("m4"), find("sides", attachmentsOnly = true))
        assertTrue(find("call sheet", attachmentsOnly = true).isEmpty())
    }

    @Test
    fun `recipients are searched when the To field is on`() {
        assertEquals(listOf("m4"), find("crew@", fields = setOf(SearchField.To)))
        assertTrue(find("crew@").isEmpty(), "To is off by default")
    }

    @Test
    fun `filters combine`() {
        val hits = mailbox.search(
            EmailQuery(
                term = "@prod.com",
                fields = setOf(SearchField.To),
                withAttachmentsOnly = true,
            ),
        )

        assertEquals(listOf("m4"), hits.map { it.id })
    }

    // -- the folder's own search box and filters ---------------------------

    @Test
    fun `the folder search box matches subject, sender and recipients`() {
        // The web's `useEmailListData` filter over the open folder's rows.
        assertEquals(listOf("m1"), mailbox.matchingSearch("day 12").map { it.id })
        assertEquals(listOf("m2"), mailbox.matchingSearch("ravi").map { it.id })
        assertEquals(listOf("m4"), mailbox.matchingSearch("crew@prod").map { it.id })
    }

    @Test
    fun `a blank folder search leaves the rows alone`() {
        assertEquals(mailbox, mailbox.matchingSearch("   "))
    }

    @Test
    fun `filters narrow by read status, attachments and address`() {
        assertEquals(listOf("m4"), mailbox.applyFilters(EmailFilters(readStatus = ReadStatus.Read)).map { it.id })
        assertEquals(
            listOf("m1", "m2", "m3"),
            mailbox.applyFilters(EmailFilters(readStatus = ReadStatus.Unread)).map { it.id },
        )
        assertEquals(listOf("m4"), mailbox.applyFilters(EmailFilters(hasAttachments = true)).map { it.id })
        assertEquals(listOf("m1"), mailbox.applyFilters(EmailFilters(from = "aisha")).map { it.id })
        assertEquals(listOf("m4"), mailbox.applyFilters(EmailFilters(to = "CREW@")).map { it.id })
        assertTrue(mailbox.applyFilters(EmailFilters(cc = "nobody")).isEmpty())
    }

    @Test
    fun `no filter set means every row, and the button's dot stays off`() {
        assertEquals(mailbox, mailbox.applyFilters(EmailFilters.None))
        assertFalse(EmailFilters.None.isActive)
        assertTrue(EmailFilters(bcc = "x").isActive)
    }

    @Test
    fun `filters are reported as narrowing`() {
        assertFalse(EmailQuery("x").hasFilters)
        assertTrue(EmailQuery("x", readFilter = ReadFilter.Unread).hasFilters)
        assertTrue(EmailQuery("x", withAttachmentsOnly = true).hasFilters)
        assertTrue(EmailQuery("x", folders = setOf("INBOX")).hasFilters)
    }
}
