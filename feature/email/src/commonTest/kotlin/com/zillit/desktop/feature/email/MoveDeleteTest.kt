package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.DeleteIntent
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.deleteIntentFor
import com.zillit.desktop.feature.email.domain.moveTargets
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import com.zillit.desktop.feature.email.ui.PendingConfirm
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Moving and deleting mail.
 *
 * The rule that matters: deleting from an ordinary folder moves to Trash and is
 * recoverable, while deleting *from Trash* destroys. Getting that backwards
 * loses mail permanently on a click people expect to be able to undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveDeleteTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: String, folder: String) =
        EmailSummary(id = id, threadId = id, subject = "s", from = "a@b.com", folderName = folder)

    private fun mailbox(server: FakeMailServer) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { 1_000L },
    )

    private suspend fun loadedInbox(server: FakeMailServer): EmailViewModel {
        val mailbox = mailbox(server)
        mailbox.onEvent(EmailEvent.Load)
        return mailbox
    }

    // -- the rule ----------------------------------------------------------

    @Test
    fun `deleting from an ordinary folder moves to Trash`() {
        val intent = deleteIntentFor(listOf(summary("m1", "INBOX")), currentFolder = "INBOX")

        val move = assertIs<DeleteIntent.MoveToTrash>(intent)
        assertEquals(mapOf("INBOX" to listOf("m1")), move.byFolder)
    }

    @Test
    fun `deleting from Trash destroys`() {
        val intent = deleteIntentFor(listOf(summary("m1", "Trash")), currentFolder = "Trash")

        assertEquals(DeleteIntent.Destroy(listOf("m1")), intent)
    }

    @Test
    fun `the Trash check ignores case`() {
        assertIs<DeleteIntent.Destroy>(
            deleteIntentFor(listOf(summary("m1", "TRASH")), currentFolder = "TRASH"),
        )
    }

    @Test
    fun `a selection spanning folders becomes one move per source`() {
        // A move names a single source folder, and a thread's messages often sit
        // in more than one.
        val intent = deleteIntentFor(
            listOf(summary("m1", "INBOX"), summary("m2", "Archive"), summary("m3", "INBOX")),
            currentFolder = "INBOX",
        )

        val move = assertIs<DeleteIntent.MoveToTrash>(intent)
        assertEquals(setOf("INBOX", "Archive"), move.byFolder.keys)
        assertEquals(listOf("m1", "m3"), move.byFolder.getValue("INBOX"))
    }

    @Test
    fun `a message that does not know its folder falls back to the open one`() {
        // Dropping it from the batch would look like the delete half-worked.
        val intent = deleteIntentFor(listOf(summary("m1", "")), currentFolder = "INBOX")

        val move = assertIs<DeleteIntent.MoveToTrash>(intent)
        assertEquals(mapOf("INBOX" to listOf("m1")), move.byFolder)
    }

    @Test
    fun `you cannot move mail into the folder it is already in, or into Drafts`() {
        val folders = listOf(
            EmailFolder("INBOX"), EmailFolder("Sent"), EmailFolder("Drafts"), EmailFolder("Trash"),
        )

        val targets = folders.moveTargets(currentFolder = "INBOX").map { it.name }

        assertEquals(listOf("Sent", "Trash"), targets)
    }

    // -- through the mailbox -----------------------------------------------

    @Test
    fun `trashing a selection calls move, not delete`() = runTest {
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertEquals(1, server.moves.size)
        assertEquals(listOf("m3"), server.moves.single().ids)
        assertEquals("Trash", server.moves.single().to)
        assertTrue(server.destroyed.isEmpty(), "nothing should have been destroyed")
    }

    @Test
    fun `a hover trash needs no selection and calls move`() = runTest {
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.TrashMessage("m2"))
        advanceUntilIdle()

        assertEquals(listOf("m2"), server.moves.single().ids)
        assertEquals("Trash", server.moves.single().to)
        assertFalse(mailbox.state.value.messages.any { it.id == "m2" }, "the row lingered")
    }

    @Test
    fun `a hover trash in Trash asks before destroying`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Trash"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Trash"))
        advanceUntilIdle()

        val target = mailbox.state.value.messages.first()
        mailbox.onEvent(EmailEvent.TrashMessage(target.id))
        advanceUntilIdle()

        assertTrue(server.destroyed.isEmpty(), "destroyed without asking")
        assertTrue(mailbox.state.value.pendingConfirm is PendingConfirm.Destroy)
    }

    @Test
    fun `the row disappears at once rather than after the round trip`() = runTest {
        // A row that lingers reads as a click that did not register.
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)

        assertFalse(
            mailbox.state.value.messages.any { it.id == "m3" },
            "the row was still there before the server answered",
        )
    }

    @Test
    fun `a failed move puts the mail back`() = runTest {
        val server = FakeMailServer().apply { moveFails = true }
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertTrue(
            mailbox.state.value.messages.any { it.id == "m3" },
            "the optimistic removal was never undone",
        )
        assertEquals("nope", mailbox.state.value.error, "the failure was never reported")
    }

    @Test
    fun `deleting in Trash asks first rather than destroying`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Trash"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Trash"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertIs<PendingConfirm.Destroy>(mailbox.state.value.pendingConfirm)
        assertTrue(server.destroyed.isEmpty(), "it destroyed before asking")
    }

    @Test
    fun `confirming destroys`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Trash"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Trash"))
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertEquals(listOf(listOf("m3")), server.destroyed)
    }

    @Test
    fun `cancelling destroys nothing and keeps the mail`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Trash"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Trash"))
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.DismissConfirm)
        advanceUntilIdle()

        assertTrue(server.destroyed.isEmpty())
        assertTrue(mailbox.state.value.messages.any { it.id == "m3" })
        assertEquals(null, mailbox.state.value.pendingConfirm)
    }

    @Test
    fun `emptying Trash asks first`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Trash"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder("Trash"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.EmptyTrash)
        advanceUntilIdle()

        assertEquals(PendingConfirm.EmptyTrash, mailbox.state.value.pendingConfirm)
        assertFalse(server.trashEmptied)

        mailbox.onEvent(EmailEvent.ConfirmPending)
        advanceUntilIdle()

        assertTrue(server.trashEmptied)
    }

    @Test
    fun `moving to a named folder sends that folder, not Trash`() = runTest {
        val server = FakeMailServer(folderNames = listOf("INBOX", "Archive"))
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m2"))
        mailbox.onEvent(EmailEvent.MoveSelected("Archive"))
        advanceUntilIdle()

        assertEquals("Archive", server.moves.single().to)
        assertEquals("INBOX", server.moves.single().from)
    }

    @Test
    fun `the selection clears once the action has run`() = runTest {
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertTrue(mailbox.state.value.selectedIds.isEmpty())
        assertFalse(mailbox.state.value.isSelecting)
    }

    @Test
    fun `deleting the open message closes the reading pane`() = runTest {
        // Otherwise the pane keeps showing a message that is no longer there.
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m3"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("m3"))
        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertEquals(null, mailbox.state.value.selectedMessageId)
        assertTrue(mailbox.state.value.thread.isEmpty())
    }

    @Test
    fun `drafts cannot be selected for a mail action`() = runTest {
        // Their ids belong to a different store; the mail endpoints have never
        // heard of them.
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.DRAFTS))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.ToggleSelection("d1"))

        assertFalse(mailbox.state.value.isSelecting, "the selection toolbar would offer a broken move")
    }

    @Test
    fun `an empty selection does nothing`() = runTest {
        val server = FakeMailServer()
        val mailbox = loadedInbox(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.DeleteSelected)
        advanceUntilIdle()

        assertTrue(server.moves.isEmpty())
        assertTrue(server.destroyed.isEmpty())
    }
}
