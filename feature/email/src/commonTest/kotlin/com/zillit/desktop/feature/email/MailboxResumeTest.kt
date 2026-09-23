package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxIdentity
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.MailboxPreferences
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
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

/**
 * Coming back to the mailbox.
 *
 * The workspace composes only the tab in front, so switching to Chat and back
 * disposes the mailbox's window and asks for [EmailEvent.Load] again when it
 * returns — while the view model, built once for the app, still holds what
 * the user had open. That second Load used to re-show the folder as if it had
 * just been clicked, closing the message; these pin that it resumes instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxResumeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        server: FakeMailServer,
        directory: MailboxDirectory? = null,
        preferences: MailboxPreferences = MailboxPreferences.None,
    ) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { 1_000L },
        directory = directory,
        preferences = preferences,
    )

    @Test
    fun `coming back from another tab keeps the message that was open`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        // What the window asks for as it comes back to the front.
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        assertEquals("m1", mailbox.state.value.openRowId, "the open message closed behind a tab switch")
        assertEquals(EmailFolder.INBOX, mailbox.state.value.selectedFolderName)
    }

    @Test
    fun `coming back keeps the ticks and the search too`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.ToggleSelection("m2"))
        mailbox.onEvent(EmailEvent.QueryChanged("subject"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        val state = mailbox.state.value
        assertEquals(setOf("m2"), state.selectedIds)
        assertEquals("subject", state.searchTerm)
        assertEquals("m3", state.openRowId, "the folder's newest row, opened on the first visit, stays open")
    }

    @Test
    fun `mail that landed while the user was away is listed on the way back, the open message kept`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m2"))
        advanceUntilIdle()

        server.uids = listOf(1, 2, 3, 4)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        assertEquals("m4", mailbox.state.value.messages.first().id, "coming back should still sync")
        assertEquals("m2", mailbox.state.value.openRowId, "not the newest row: the user's own message")
    }

    @Test
    fun `a sync that re-lists the open folder keeps the open message`() = runTest {
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        server.uids = listOf(1, 2, 3, 4)
        mailbox.onEvent(EmailEvent.Realtime(EmailRealtimeEvent.FolderChanged(EmailFolder.INBOX)))
        advanceUntilIdle()

        assertEquals("m4", mailbox.state.value.messages.first().id)
        assertEquals("m1", mailbox.state.value.openRowId)
    }

    @Test
    fun `choosing a folder is still a fresh start, unlike coming back`() = runTest {
        // The web clears the pane on a folder click and opens that folder's
        // newest row; resuming must not leak into that.
        val server = FakeMailServer(uids = listOf(1, 2, 3), folderNames = listOf(EmailFolder.INBOX, "Archive"))
        val mailbox = viewModel(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.SelectFolder("Archive"))
        advanceUntilIdle()

        assertEquals("Archive", mailbox.state.value.selectedFolderName)
        assertEquals("m3", mailbox.state.value.openRowId, "a chosen folder opens onto its newest row")
    }

    @Test
    fun `coming back to a mailbox that is no longer open starts that mailbox afresh`() = runTest {
        // Accounts membership lost while the user was in another tool: the
        // Accounts message on screen is not the personal mailbox's to keep.
        val server = FakeMailServer(uids = listOf(1, 2, 3))
        val directory = Directory(accounts = MailboxIdentity(MailboxKind.Accounts, "accounts@zillit.test"))
        val mailbox = viewModel(server, directory, preferences = RemembersAccounts)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        assertEquals(MailboxKind.Accounts, mailbox.state.value.mailboxes.active)
        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        directory.accounts = null
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        val state = mailbox.state.value
        assertEquals(MailboxKind.Personal, state.mailboxes.active)
        assertEquals("m3", state.openRowId, "the mailbox that opened should open onto its newest row")
    }

    /** Both mailboxes, the Accounts one withdrawn by setting [accounts] to null. */
    private class Directory(var accounts: MailboxIdentity?) : MailboxDirectory {
        override suspend fun personal(): ZillitResult<MailboxIdentity?> =
            ZillitResult.Success(MailboxIdentity(MailboxKind.Personal, "me@zillit.test"))

        override suspend fun accounts(): ZillitResult<MailboxIdentity?> = ZillitResult.Success(accounts)

        override suspend fun setAccountsConversationView(enabled: Boolean): ZillitResult<Unit> =
            ZillitResult.Success(Unit)

        override suspend fun setAccountsBccPresets(addresses: List<String>): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    /** A production where the user last left the Accounts mailbox open. */
    private object RemembersAccounts : MailboxPreferences {
        override suspend fun activeMailbox(): MailboxKind = MailboxKind.Accounts
        override suspend fun setActiveMailbox(kind: MailboxKind) = Unit
        override suspend fun hasSeenMailboxTour(): Boolean = true
        override suspend fun markMailboxTourSeen() = Unit
    }
}
