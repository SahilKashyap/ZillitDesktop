package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.chat.data.ConversationBacklog
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
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
 * The listing's last-message line for a conversation this computer never
 * downloaded.
 *
 * QA: "chat messages are not syncing until the user opens up the specific
 * chat — on the chat listing the latest message never showed up". The stamps
 * were right all along (the notification backlog and `sorting_activity` carry
 * them whether or not the message was fetched), so the row moved up and grew
 * its badge while the words underneath stayed whatever this computer last
 * downloaded. Opening the conversation ran a history fetch, which is what
 * finally corrected both the line and the thread's own scroll.
 *
 * The listing now asks the server for that one row itself, the way the web's
 * `CNCSyncHelper` does — and asks only about the rows whose stamp outruns
 * their cached line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListPreviewTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { 1_000L },
        newUniqueId = { "unique-${repository.sent.size}" },
    )

    private fun line(id: String, from: String, body: String, at: Long) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = from,
        receiverId = "me",
        body = body,
        timestampMillis = at,
        isMine = false,
    )

    @Test
    fun `a conversation this computer never downloaded is stamped by users-group and fetched`() =
        runTest(dispatcher) {
            // Nothing cached and nothing in the backlog — the shape of almost
            // every row on a real production (DN6: 12 rows, 3 stamped). The
            // warm pass compared `0 > 0`, asked nothing, and the line stayed
            // blank until the thread was opened. `chat/users-group` is the
            // only read that stamps a private thread, and with it the row is
            // known to be stale.
            val repository = FakeChatRepository().apply {
                recents = listOf("u-a")
                stamps = mapOf("u-a" to 300L)
                serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
            }
            val model = viewModel(repository)

            model.onEvent(ChatEvent.RefreshRecents)
            advanceUntilIdle()

            assertEquals(listOf("u-a"), repository.lastMessageAsks, "the unstamped row became askable")
            assertEquals("running late", model.currentState.previews["u-a"]?.text)
        }

    @Test
    fun `with no stamps from users-group the listing behaves exactly as before`() = runTest(dispatcher) {
        // An environment still on the old contract: nothing is claimed about
        // any row, so nothing is asked and nothing is shown that was not
        // shown before.
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertTrue(repository.lastMessageAsks.isEmpty(), "no stamp, no claim, no ask")
    }

    @Test
    fun `a stamp newer than the cache fetches the line the listing shows`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            // What this computer downloaded, and what the server has since.
            rememberArrival(line("m-old", "u-a", "see you then", at = 100L))
            serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
            backlog = ConversationBacklog(
                unread = mapOf("u-a" to 1),
                activity = mapOf("u-a" to 300L),
            )
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a"), repository.lastMessageAsks, "the stale row was asked about")
        assertEquals(
            "running late",
            model.currentState.previews["u-a"]?.text,
            "the listing shows the server's newest line without the thread being opened",
        )
    }

    @Test
    fun `a row whose cache already reaches its stamp is never asked`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            rememberArrival(line("m-a", "u-a", "see you then", at = 300L))
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertTrue(repository.lastMessageAsks.isEmpty(), "nothing to fetch, so no emit")
        assertEquals("see you then", model.currentState.previews["u-a"]?.text)
    }

    @Test
    fun `one ask per stamp, however many times the listing repaints`() = runTest(dispatcher) {
        // The server cannot answer for this conversation — the ask must still
        // not repeat on every repaint, and the answers' own repaint must not
        // start a third round.
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a"), repository.lastMessageAsks, "asked once, for the one stamp")
    }

    @Test
    fun `a newer stamp reopens exactly one ask`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        repository.backlog = ConversationBacklog(activity = mapOf("u-a" to 400L))
        repository.serverLast = mapOf("u-a" to line("m-new", "u-a", "on my way", at = 400L))
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a", "u-a"), repository.lastMessageAsks)
        assertEquals("on my way", model.currentState.previews["u-a"]?.text)
    }

    /**
     * One row's bad luck is not the pass's.
     *
     * This used to stop at the first failure, to spare the remaining rows an
     * ack timeout each. But the pass runs newest-first, so the row most
     * likely to fail is the one the user is most likely to be looking at, and
     * giving up on it gave up on everything behind it — most of the listing
     * ("latest messages do not show up"). The ask itself already retries a
     * lost ack, so by the time the pass sees a failure it is a row that
     * really is not answering, and the rest deserve their turn.
     */
    @Test
    fun `a row that does not answer does not cost the rows behind it`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L, "u-b" to 200L))
            lastMessageFailure = ZillitError.Unknown("ack failed")
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a", "u-b"), repository.lastMessageAsks, "both rows were asked about")

        repository.lastMessageFailure = null
        repository.serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(
            listOf("u-a", "u-b", "u-a", "u-b"),
            repository.lastMessageAsks,
            "neither failure was memoised",
        )
        assertEquals("running late", model.currentState.previews["u-a"]?.text)
    }

    /**
     * Except when the failure is the connection itself: then every remaining
     * ask would fail the same way, and the socket's connect edge re-runs the
     * whole pass once it is back.
     */
    @Test
    fun `no connection stops the pass at the first row`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L, "u-b" to 200L))
            lastMessageFailure = ZillitError.NoConnection("socket is not connected")
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a"), repository.lastMessageAsks)

        repository.lastMessageFailure = null
        repository.serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a", "u-a", "u-b"), repository.lastMessageAsks, "the row was asked again")
    }

    /**
     * And a pass does not spend an ack window on all thirty rows to learn
     * what the first few already said.
     */
    @Test
    fun `a pass gives up once enough rows have gone unanswered`() = runTest(dispatcher) {
        val rows = (1..8).map { "u-$it" }
        val repository = FakeChatRepository().apply {
            recents = rows
            // Descending, so the pass's newest-first order is the list's own.
            backlog = ConversationBacklog(activity = rows.withIndex().associate { (i, id) -> id to (900L - i) })
            lastMessageFailure = ZillitError.Unknown("ack failed")
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(rows.take(3), repository.lastMessageAsks, "three unanswered rows end the pass")
    }
}
