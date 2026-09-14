package com.zillit.desktop.core.badges

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The email rows' key, as every phone writes and reads it: section
 * `email_label`, the folder as `unit`, the IMAP uid as `reference_id`, the
 * mailbox address in `level_1`. The desktop read by message id for weeks and
 * cleared nothing — these pin the key down.
 */
class MailLedgerReadTest {

    private val project = "p1"

    @Suppress("LongParameterList") // One factory, every column a test may care about.
    private fun mail(
        id: String,
        folder: String = "Inbox",
        uid: String,
        mailbox: String = "",
        created: Long = 1_000L,
        read: Boolean = false,
    ) = NotificationRecord(
        id = id, projectId = project, section = BadgeSections.EMAIL, unit = folder, referenceId = uid,
        level1 = mailbox, created = created, messageRead = read, mongoId = "mongo-$id",
    )

    @Test
    fun `a mail read is keyed by folder and uid, not by message id`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(mail("a", uid = "12"), mail("b", folder = "Sent", uid = "12")))

        assertEquals(0, store.markRead(LedgerRead.Reference("<msg-12@zillit>")), "the old read finds nothing")

        assertEquals(1, store.markRead(LedgerRead.Mail(folder = "INBOX", uid = 12, messageId = "<msg-12@zillit>")))
        assertEquals(1, store.counts.value.section(BadgeSections.EMAIL), "Sent's uid 12 is another mail")
    }

    @Test
    fun `a row from an older writer keyed by message id still clears`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(mail("old", uid = "<msg-7@zillit>")))

        store.markRead(LedgerRead.Mail(folder = "Inbox", uid = 7, messageId = "<msg-7@zillit>"))

        assertEquals(0, store.counts.value.section(BadgeSections.EMAIL))
    }

    @Test
    fun `another mailbox's row with the same uid is not this read's`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                mail("mine", uid = "5", mailbox = "Me@Zillit.com"),
                mail("accounts", uid = "5", mailbox = "accounts@zillit.com"),
                mail("untagged", uid = "5"),
            ),
        )

        store.markRead(LedgerRead.Mail(folder = "Inbox", uid = 5, mailbox = "me@zillit.com"))

        assertEquals(1, store.counts.value.section(BadgeSections.EMAIL), "only the Accounts row stays")
        assertEquals(listOf("accounts"), store.unreadRows(BadgeSections.EMAIL).map { it.id })
    }

    @Test
    fun `an unknown own address fails open`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(mail("tagged", uid = "5", mailbox = "me@zillit.com")))

        store.markRead(LedgerRead.Mail(folder = "Inbox", uid = 5, mailbox = null))

        assertEquals(0, store.counts.value.section(BadgeSections.EMAIL))
    }

    /** Untagged rows predate the mailbox stamp and are the person's own mail. */
    @Test
    fun `a read in the shared Accounts mailbox never touches untagged rows`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                mail("untagged", uid = "5"),
                mail("accounts", uid = "5", mailbox = "invoices@zillit.net"),
            ),
        )

        val accounts = "invoices@zillit.net"
        store.markRead(LedgerRead.Mail(folder = "Inbox", uid = 5, mailbox = accounts, ownsUntagged = false))

        assertEquals(listOf("untagged"), store.unreadRows(BadgeSections.EMAIL).map { it.id })

        val sync = MailFolderState(folder = "Inbox", uids = emptySet(), mailbox = accounts, ownsUntagged = false)
        assertEquals(0, store.markRead(LedgerRead.MailFolder(sync)), "an Accounts sync retires no personal mail")
    }

    /** Android ZL-21196 — a uid the server no longer lists has left the folder, badge and all. */
    @Test
    fun `a folder sync retires rows for mail that left the folder or was read elsewhere`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                mail("gone", uid = "3"),
                mail("read-elsewhere", uid = "4"),
                mail("still-unread", uid = "5"),
                mail("other-folder", folder = "Sent", uid = "3"),
            ),
        )

        val retired = store.markRead(
            LedgerRead.MailFolder(
                MailFolderState(
                    folder = "Inbox",
                    uids = setOf(4, 5),
                    readUids = setOf(4),
                    listedAt = 10_000_000L,
                ),
            ),
        )

        assertEquals(2, retired)
        assertEquals(listOf("still-unread", "other-folder"), store.unreadRows(BadgeSections.EMAIL).map { it.id })
    }

    @Test
    fun `a row written while the uid list was in flight is not taken as gone`() = runTest {
        val listedAt = 10_000_000L
        val state = MailFolderState(folder = "Inbox", uids = emptySet(), listedAt = listedAt)

        assertTrue(state.retires(mail("settled", uid = "8", created = listedAt - 5 * 60_000L)))
        assertFalse(state.retires(mail("landing", uid = "9", created = listedAt - 30_000L)), "inside the grace")
        assertFalse(state.retires(mail("after", uid = "10", created = listedAt + 1_000L)))
        // Read is read, however fresh the row.
        assertTrue(state.copy(readUids = setOf(9)).retires(mail("landing", uid = "9", created = listedAt - 30_000L)))
    }

    @Test
    fun `a message-id key is judged departed only against a complete folder`() = runTest {
        val held = setOf("<a@z>", "<b@z>")
        val partial = MailFolderState(folder = "Inbox", uids = setOf(1, 2, 3), messageIds = held, complete = false)
        val complete = partial.copy(complete = true)
        val row = mail("old", uid = "<c@z>", created = 0L)

        assertFalse(partial.retires(row), "half a folder proves nothing")
        assertTrue(complete.retires(row))
        assertFalse(complete.retires(mail("held", uid = "<a@z>", created = 0L)))
        assertTrue(partial.copy(readMessageIds = setOf("<a@z>")).retires(mail("read", uid = "<a@z>", created = 0L)))
    }

    /** The web counts both mailboxes because it opens both; this desktop opens one. */
    @Test
    fun `mail for a mailbox this desktop cannot show is held out of every count`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(
            listOf(
                mail("mine", uid = "1", mailbox = "me@zillit.net"),
                mail("untagged", uid = "2"),
                mail("accounts", uid = "7", mailbox = "invoices@zillit.net"),
                mail("accounts-too", uid = "8", mailbox = "Invoices@zillit.net"),
                mail("elsewhere", uid = "3", mailbox = "invoices@zillit.net").copy(projectId = "p2"),
            ),
        )
        assertEquals(4, store.counts.value.section(BadgeSections.EMAIL), "mailbox unknown: every row counts")

        store.showMailboxes(project, setOf(" me@zillit.net "))

        assertEquals(2, store.counts.value.section(BadgeSections.EMAIL))
        val byFolder = store.split(BadgeDrilldownQuery(groupBy = "unit", section = BadgeSections.EMAIL))
        assertEquals(mapOf("Inbox" to 2), byFolder)
        assertEquals(mapOf(project to 2, "p2" to 1), store.projectCounts(null), "the picker agrees; p2 unscoped")
        assertEquals(listOf("mine", "untagged"), store.unreadRows(BadgeSections.EMAIL).map { it.id })
        assertEquals(4, store.unreadRows(BadgeSections.EMAIL, everyMailbox = true).size, "held, not read")
        assertTrue(storage.rows(project).none { it.messageRead }, "the rows stay unread on disk")
        assertEquals(mapOf(project to setOf("me@zillit.net")), store.mailboxes())

        // The day the desktop opens the Accounts mailbox too, its rows count — the web's total.
        store.showMailboxes(project, setOf("me@zillit.net", "invoices@zillit.net"))
        assertEquals(4, store.counts.value.section(BadgeSections.EMAIL))
        store.showMailboxes(project, setOf("me@zillit.net"))

        store.showMailboxes(project, emptySet())
        assertEquals(2, store.counts.value.section(BadgeSections.EMAIL), "a failed re-ask keeps the last answer")
        store.clear()
        store.open(project)
        assertEquals(2, store.counts.value.section(BadgeSections.EMAIL), "a production switch keeps what was learnt")
        store.forgetMailboxes()
        store.clear()
        store.open(project)
        assertEquals(4, store.counts.value.section(BadgeSections.EMAIL), "the next person starts unscoped")
    }

    @Test
    fun `a sync never touches rows of other sections or of a blank key`() = runTest {
        val state = MailFolderState(folder = "Inbox", uids = emptySet(), listedAt = 10_000_000L)
        val read = LedgerRead.MailFolder(state)

        assertFalse(read.matches(mail("blank", uid = "", created = 0L)))
        val toolRow = NotificationRecord(
            id = "t", projectId = project, section = BadgeSections.TOOLS, unit = "Inbox", referenceId = "3",
        )
        assertFalse(read.matches(toolRow))
    }
}
