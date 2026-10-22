package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Drafts are the one folder paged from the server.
 *
 * Every other folder holds all its rows in the cache; `email-draft` hands over
 * a page at a time, so the list asks for the one before it as it nears the
 * bottom, and the sidebar's number comes from the server's own total rather
 * than from however many rows happen to be loaded (web ZL, PR #6515).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftPagingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** [count] drafts, newest first, each a minute apart and all before "now". */
    private fun savedDrafts(count: Int) = (1..count).map { n ->
        EmailDraft(id = "d$n", subject = "Draft $n", updatedAtMillis = NOW - n * 60_000L)
    }

    private fun mailbox(server: FakeMailServer) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { NOW },
    )

    /** Opens the mailbox, waits for it to settle, then opens Drafts. */
    private fun TestScope.openDrafts(server: FakeMailServer): EmailViewModel {
        val mailbox = mailbox(server)
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.DRAFTS))
        return mailbox
    }

    @Test
    fun `the first page is one page, not the whole folder`() = runTest {
        val server = FakeMailServer().apply {
            storedDrafts = savedDrafts(12)
            draftPageSize = PAGE
        }
        val mailbox = openDrafts(server)
        advanceUntilIdle()

        val state = mailbox.state.value
        assertEquals(PAGE, state.messages.size, "the list should hold one page")
        assertTrue(state.draftsHaveMore, "the server said more were pending")
    }

    @Test
    fun `the sidebar counts every draft, not the loaded page`() = runTest {
        val server = FakeMailServer().apply {
            storedDrafts = savedDrafts(12)
            draftPageSize = PAGE
        }
        val mailbox = openDrafts(server)
        advanceUntilIdle()

        // The number beside Drafts is the server's total_drafts: counting the
        // loaded rows would say 5 for a folder of 12.
        assertEquals(12, mailbox.state.value.draftsTotal)
    }

    @Test
    fun `nearing the bottom appends the page before it`() = runTest {
        val server = FakeMailServer().apply {
            storedDrafts = savedDrafts(12)
            draftPageSize = PAGE
        }
        val mailbox = openDrafts(server)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.LoadMoreDrafts)
        advanceUntilIdle()

        val state = mailbox.state.value
        assertEquals((1..10).map { "d$it" }, state.drafts.map { it.id }, "the pages should stack in order")
        assertEquals(state.drafts.map { it.id }, state.messages.map { it.id }, "the list follows the drafts")
        // The second page was asked for from the oldest loaded draft's stamp.
        assertEquals(NOW - PAGE * 60_000L, server.draftCursors.last())
    }

    @Test
    fun `the last page stops the paging`() = runTest {
        val server = FakeMailServer().apply {
            storedDrafts = savedDrafts(7)
            draftPageSize = PAGE
        }
        val mailbox = openDrafts(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.LoadMoreDrafts)
        advanceUntilIdle()

        assertEquals(7, mailbox.state.value.drafts.size)
        assertFalse(mailbox.state.value.draftsHaveMore, "nothing is pending after the last page")

        // Scrolling to the bottom of an exhausted list must not keep asking:
        // the server would answer the same empty page every time.
        val asked = server.draftCursors.size
        mailbox.onEvent(EmailEvent.LoadMoreDrafts)
        advanceUntilIdle()
        assertEquals(asked, server.draftCursors.size, "the exhausted cursor was asked again")
    }

    @Test
    fun `a reload starts over at page one`() = runTest {
        val server = FakeMailServer().apply {
            storedDrafts = savedDrafts(12)
            draftPageSize = PAGE
        }
        val mailbox = openDrafts(server)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.LoadMoreDrafts)
        advanceUntilIdle()
        assertEquals(10, mailbox.state.value.drafts.size)

        // A draft saved in another window re-reads the list; the web's socket
        // refetch replaces it with page one and scrolling pages it back in.
        mailbox.onEvent(EmailEvent.Realtime(com.zillit.desktop.feature.email.domain.EmailRealtimeEvent.DraftsChanged))
        advanceUntilIdle()

        assertEquals(PAGE, mailbox.state.value.drafts.size)
        assertTrue(mailbox.state.value.draftsHaveMore)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PAGE = 5
    }
}
