package com.zillit.desktop.feature.chat

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

    @Test
    fun `a refused ask stops the pass and is retried on the next repaint`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            backlog = ConversationBacklog(activity = mapOf("u-a" to 300L, "u-b" to 200L))
            lastMessageFails = true
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        // The newest row first, then nothing: a refusal means the remaining
        // rows are not worth an ack timeout each.
        assertEquals(listOf("u-a"), repository.lastMessageAsks)

        repository.lastMessageFails = false
        repository.serverLast = mapOf("u-a" to line("m-new", "u-a", "running late", at = 300L))
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a", "u-a", "u-b"), repository.lastMessageAsks, "the refusal was not memoised")
        assertEquals("running late", model.currentState.previews["u-a"]?.text)
    }
}
