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

/**
 * The shelf's order when the server knows more than the cache.
 *
 * QA: "sorting not working as per badge" — a conversation whose message
 * arrived while the app was closed had a badge (the server counted it) but
 * sat low, because the order came from the local cache alone and the cache
 * had never seen the message. Android sorts by `sorting_activity`, the newest
 * message's `created` regardless of whether the thread was ever opened
 * (`MembersVM.kt:150`, `:372-374`); here the notification backlog's stamps
 * stand in for that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRecentsSortingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
    )

    private fun line(id: String, from: String, at: Long) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = from,
        receiverId = "me",
        body = "…",
        timestampMillis = at,
        isMine = false,
    )

    @Test
    fun `the server's newer stamp outranks the cache's newer line`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            // The cache holds A's thread and nothing of B's — B wrote while
            // the app was closed, so only the server has that message.
            rememberArrival(line("m-a", "u-a", at = 100L))
            backlog = ConversationBacklog(
                unread = mapOf("u-b" to 1),
                activity = mapOf("u-a" to 100L, "u-b" to 200L),
            )
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-b", "u-a"), model.currentState.recents)
        assertEquals(1, model.currentState.unread["u-b"], "the badge that came with the stamp")
        assertEquals(200L, model.currentState.activity["u-b"], "the row's clock reads the server's stamp")
    }

    @Test
    fun `the cache's newer line still outranks an older server stamp`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            rememberArrival(line("m-a", "u-a", at = 300L))
            backlog = ConversationBacklog(activity = mapOf("u-a" to 100L, "u-b" to 200L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-a", "u-b"), model.currentState.recents)
        assertEquals(300L, model.currentState.activity["u-a"])
    }

    @Test
    fun `an arrival for a closed thread lifts its row and badges it`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a", "u-b")
            rememberArrival(line("m-a", "u-a", at = 200L))
            rememberArrival(line("m-b", "u-b", at = 100L))
        }
        val model = viewModel(repository)
        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        assertEquals(listOf("u-a", "u-b"), model.currentState.recents, "the starting order")

        // No thread open, and — deliberately — the fake is not told to cache
        // this line: the socket path remembers before the view model hears,
        // but the row must lift on the arrival itself, not on the cache.
        model.onEvent(ChatEvent.Arrived(line("m-b2", "u-b", at = 400L)))
        advanceUntilIdle()

        assertEquals(listOf("u-b", "u-a"), model.currentState.recents)
        assertEquals(1, model.currentState.unread["u-b"])
        assertEquals(400L, model.currentState.activity["u-b"])
    }

    @Test
    fun `a stamped conversation the socket list forgot is still a row`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf("u-a")
            backlog = ConversationBacklog(unread = mapOf("u-c" to 2), activity = mapOf("u-c" to 500L))
        }
        val model = viewModel(repository)

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(listOf("u-c", "u-a"), model.currentState.recents)
    }

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}
