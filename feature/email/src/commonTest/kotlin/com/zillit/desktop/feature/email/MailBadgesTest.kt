package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import com.zillit.desktop.feature.email.ui.MailBadges
import com.zillit.desktop.feature.email.ui.MailFolderSync
import com.zillit.desktop.feature.email.ui.MailRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the mailbox tells the badge ledger, and when.
 *
 * The ledger keys an email row by folder and IMAP uid; a read handed over by
 * message id alone matched nothing and the Email badge outlived the mail.
 * These pin the handover: the open, the sync, and the read-elsewhere frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailBadgesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val reads = mutableListOf<MailRead>()
    private val syncs = mutableListOf<MailFolderSync>()
    private var folderBadgeAsks = 0

    private fun viewModel(server: FakeMailServer, now: Long = 5_000L) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { now },
        badges = MailBadges(
            onMessageRead = { reads += it },
            onFolderSynced = { syncs += it },
            folderBadges = { folderBadgeAsks++; mapOf(EmailFolder.INBOX to 1) },
        ),
    )

    @Test
    fun `opening unread mail hands the ledger the folder and uid, not just the message id`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        reads.clear()

        mailbox.onEvent(EmailEvent.SelectMessage("m2"))
        advanceUntilIdle()

        assertEquals(listOf(MailRead(folderName = EmailFolder.INBOX, uid = 2, messageId = "m2")), reads)
    }

    @Test
    fun `a folder opens onto its newest row, which is read like a click`() = runTest {
        // The web's `useEmailListData` auto-open: the pane never sits empty
        // over a folder with mail in it, and the opened row is read.
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        assertEquals("m3", mailbox.state.value.openRowId)
        assertEquals(listOf(MailRead(folderName = EmailFolder.INBOX, uid = 3, messageId = "m3")), reads)
    }

    @Test
    fun `mail already read here is not read again`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        mailbox.onEvent(EmailEvent.SelectMessage("m2"))
        advanceUntilIdle()
        reads.clear()

        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        assertTrue(reads.isEmpty(), "a second open is a click, not a read")
    }

    @Test
    fun `every folder sync hands the ledger the server's whole uid list and the mail held`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server, now = 5_000L)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        val sync = syncs.single()
        assertEquals(EmailFolder.INBOX, sync.folderName)
        assertEquals(setOf(1, 2, 3), sync.serverUids)
        assertEquals(listOf("m3", "m2", "m1"), sync.messages.map { it.id })
        assertTrue(sync.complete)
        assertEquals(5_000L, sync.listedAt, "stamped when the list was asked for")

        // Mail deleted elsewhere: the next sync reports the shorter list, which
        // is what retires its row (Android ZL-21196).
        server.uids = listOf(1, 3)
        mailbox.onEvent(EmailEvent.Refresh)
        advanceUntilIdle()

        assertEquals(setOf(1, 3), syncs.last().serverUids)
        assertEquals(listOf("m3", "m1"), syncs.last().messages.map { it.id })
    }

    @Test
    fun `a sync redraws the folder counts from the ledger afterwards`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        assertTrue(folderBadgeAsks >= 2, "asked on load and again after the sync reconciled")
        assertEquals(mapOf(EmailFolder.INBOX to 1), mailbox.state.value.folderBadges)
    }

    @Test
    fun `a read on another device clears the row and the ledger`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        // The newest row (m2) opened itself; m1 is the one still unread here.
        reads.clear()

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.ReadChanged(1)))
        advanceUntilIdle()

        assertTrue(mailbox.state.value.messages.first { it.uid == 1 }.isRead)
        assertEquals(listOf(MailRead(EmailFolder.INBOX, 1, "m1")), reads)
        // The cache learnt it too: a reopen of the folder shows it read.
        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.INBOX))
        advanceUntilIdle()
        assertTrue(mailbox.state.value.messages.first { it.uid == 1 }.isRead)
    }

    @Test
    fun `a read frame for mail already read here is not handed over again`() = runTest {
        // The ledger learnt of the click; a second handover for the same row
        // is a settle wait and three requests for nothing.
        val server = FakeMailServer(uids = listOf(1, 2))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        reads.clear()

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.ReadChanged(2)))
        advanceUntilIdle()

        assertTrue(reads.isEmpty(), "read twice: $reads")
    }

    @Test
    fun `a read frame for mail this folder does not hold is left to that folder's sync`() = runTest {
        val server = FakeMailServer(uids = listOf(1))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        reads.clear()

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.ReadChanged(99)))
        advanceUntilIdle()

        assertTrue(reads.isEmpty())
    }
}
