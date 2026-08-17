package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the mailbox reacts to the socket.
 *
 * The interesting cases are about timing, not mapping: mail that arrives *while*
 * a sync is running, and a burst of events that must not start a burst of syncs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxRealtimeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(server: FakeMailServer) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { 1_000L },
    )

    @Test
    fun `new mail in the open folder is fetched without the user asking`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        server.uids = listOf(1, 2, 3, 4)
        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("INBOX")))
        advanceUntilIdle()

        assertEquals("m4", mailbox.state.value.messages.first().id, "the new mail should be on top")
    }

    @Test
    fun `a change in another folder does not refetch the open one`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        val before = server.uidCalls

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("Archive")))
        advanceUntilIdle()

        assertEquals(before, server.uidCalls, "Archive is not open; it syncs when opened")
    }

    @Test
    fun `an unnamed folder refreshes whatever is open`() = runTest {
        // A move names at most one of the two folders it touches.
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        val before = server.uidCalls

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged(null)))
        advanceUntilIdle()

        assertTrue(server.uidCalls > before)
    }

    @Test
    fun `any mail event refreshes the sidebar counts`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        val before = server.folderCalls

        // Even for a folder that is not open: the unread badge is what tells
        // the user mail arrived somewhere they are not looking.
        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("Archive")))
        advanceUntilIdle()

        assertTrue(server.folderCalls > before)
    }

    @Test
    fun `a burst of events does not start a burst of syncs`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        // Hold the in-flight sync open, then pile events on top of it.
        val gate = CompletableDeferred<Unit>()
        server.indexGate = gate
        server.uids = listOf(1, 2, 3, 4)
        repeat(10) { mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("INBOX"))) }
        advanceUntilIdle()
        val duringBurst = server.uidCalls

        server.indexGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, duringBurst - 1, "ten events should have started one sync, not ten")
        // And exactly one more afterwards, for whatever landed mid-flight.
        assertEquals(3, server.uidCalls, "one initial, one for the burst, one to catch up")
    }

    @Test
    fun `mail arriving mid-sync is not lost`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        // A sync is running and has already read the uid list.
        val gate = CompletableDeferred<Unit>()
        server.indexGate = gate
        server.uids = listOf(1, 2, 3, 4)
        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("INBOX")))
        advanceUntilIdle()

        // Mail lands while that sync is still in flight.
        server.uids = listOf(1, 2, 3, 4, 5)
        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged("INBOX")))
        server.indexGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "m5",
            mailbox.state.value.messages.first().id,
            "the mail that arrived mid-sync should have been picked up",
        )
    }

    @Test
    fun `a read elsewhere updates the row without a refetch`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        val before = server.uidCalls

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.ReadChanged(uid = 2)))
        advanceUntilIdle()

        assertTrue(mailbox.state.value.messages.first { it.uid == 2 }.isRead)
        assertFalse(mailbox.state.value.messages.first { it.uid == 1 }.isRead)
        assertEquals(before, server.uidCalls, "the uid is in the payload; nothing to fetch")
    }

    @Test
    fun `a folder event reloads the sidebar`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        val before = server.folderCalls

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FoldersChanged))
        advanceUntilIdle()

        assertTrue(server.folderCalls > before)
    }

    @Test
    fun `a background refresh failure does not put an error over readable mail`() = runTest {
        val server = FakeMailServer()
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FoldersChanged))
        advanceUntilIdle()

        assertEquals(null, mailbox.state.value.error)
        assertTrue(mailbox.state.value.messages.isNotEmpty())
    }
}
