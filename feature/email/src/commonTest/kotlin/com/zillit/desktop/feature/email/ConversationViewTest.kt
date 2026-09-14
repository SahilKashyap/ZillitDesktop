package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.SELECTION_LIMIT
import com.zillit.desktop.feature.email.domain.calculateThreadId
import com.zillit.desktop.feature.email.domain.groupIntoThreads
import com.zillit.desktop.feature.email.domain.selectionMembers
import com.zillit.desktop.feature.email.domain.threadMembersByFolder
import com.zillit.desktop.feature.email.domain.threadScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Conversation view — the web's `groupEmailsIntoThreads`, `getEmailsById`
 * and `getSelectedEmails`, whose rules decide which rows stack, what a
 * stacked row shows, and what a tick on it reaches.
 */
class ConversationViewTest {

    private fun mail(
        id: String,
        thread: String,
        folder: String = EmailFolder.INBOX,
        at: Long,
        from: String = "a@prod.com",
        read: Boolean = true,
    ) = EmailSummary(
        id = id,
        threadId = thread,
        subject = "s",
        from = from,
        folderName = folder,
        receivedAtMillis = at,
        isRead = read,
    )

    // An Inbox conversation with a reply sent from here, plus an unrelated mail.
    private val inbox = listOf(
        mail("m1", thread = "t1", at = 100, from = "Aisha <a@prod.com>"),
        mail("m2", thread = "t1", at = 300, from = "Aisha <a@prod.com>", read = false),
        mail("m9", thread = "t9", at = 200),
    )
    private val sent = listOf(mail("s1", thread = "t1", folder = EmailFolder.SENT, at = 400, from = "Me <me@prod.com>"))
    private val trashed = listOf(mail("x1", thread = "t1", folder = EmailFolder.TRASH, at = 500))
    private val everything = inbox + sent + trashed

    @Test
    fun `without conversation view every message is its own row, newest first`() {
        val rows = groupIntoThreads(inbox, everything, conversationView = false, currentFolder = EmailFolder.INBOX)

        assertEquals(listOf("m2", "m9", "m1"), rows.map { it.id })
        assertTrue(rows.none { it.showsCount })
    }

    @Test
    fun `with it on, one row per conversation shows the newest message the folder holds`() {
        val rows = groupIntoThreads(inbox, everything, conversationView = true, currentFolder = EmailFolder.INBOX)

        assertEquals(listOf("m2", "m9"), rows.map { it.id }, "the older copy should have folded under the newer")
    }

    @Test
    fun `a stacked row is named by the conversation's latest message, wherever it lives`() {
        // ZL-17843: the reply sent from here is the newest; the Inbox row must
        // not keep naming the person who wrote three messages ago.
        val row = groupIntoThreads(inbox, everything, true, EmailFolder.INBOX).first { it.threadId == "t1" }

        assertEquals("s1", row.latest.id)
        assertEquals("Me", row.latest.senderName)
        assertEquals("m2", row.message.id, "a click still opens the folder's own copy")
    }

    @Test
    fun `the count and the unread dot come from every folder but Trash`() {
        val row = groupIntoThreads(inbox, everything, true, EmailFolder.INBOX).first { it.threadId == "t1" }

        assertEquals(3, row.count, "m1, m2 and the sent reply — never the trashed copy")
        assertTrue(row.showsCount)
        assertTrue(row.hasUnread)
    }

    @Test
    fun `opened from Trash, a conversation is only its trashed messages`() {
        // ZL-13209: otherwise a deleted reply keeps reappearing in the Inbox
        // thread it was deleted from — and Trash shows what is really there.
        val admits = threadScope(EmailFolder.TRASH)
        assertTrue(admits(trashed.single()))
        assertFalse(admits(inbox.first()))

        val fromTrash = threadMembersByFolder("t1", everything, currentFolder = EmailFolder.TRASH)
        assertEquals(mapOf(EmailFolder.TRASH to listOf("x1")), fromTrash.mapValues { (_, rows) -> rows.map { it.id } })

        val fromInbox = threadMembersByFolder("t1", everything, currentFolder = EmailFolder.INBOX)
        assertEquals(setOf(EmailFolder.INBOX, EmailFolder.SENT), fromInbox.keys)
        assertEquals(listOf("m1", "m2"), fromInbox.getValue(EmailFolder.INBOX).map { it.id })
    }

    @Test
    fun `a ticked row stands for its whole conversation, newest first`() {
        val members = selectionMembers(listOf(inbox[1]), everything, conversationView = true, EmailFolder.INBOX)

        assertEquals(listOf("s1", "m2", "m1"), members.map { it.id })
    }

    @Test
    fun `without conversation view a tick reaches only the ticked message`() {
        val members = selectionMembers(listOf(inbox[1]), everything, conversationView = false, EmailFolder.INBOX)

        assertEquals(listOf("m2"), members.map { it.id })
    }

    @Test
    fun `a conversation is keyed by the root of its chain`() {
        // The web's `calculateThreadId`, rerun on every sync: the first real
        // reference, else the parent, else the message itself.
        assertEquals("root", calculateThreadId("m3", listOf("<>", "root", "m2"), inReplyTo = "m2"))
        assertEquals("m2", calculateThreadId("m3", emptyList(), inReplyTo = " m2 "))
        assertEquals("m3", calculateThreadId("m3", listOf("<>"), inReplyTo = "<>"))
    }

    @Test
    fun `the bulk-action cap is the web's thirty`() {
        assertEquals(30, SELECTION_LIMIT)
    }
}
