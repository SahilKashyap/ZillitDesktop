package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The second tick.
 *
 * Every other client tells the sender when a message reaches the device — the
 * web on each arrival, Android likewise. This one never did, so anyone
 * messaging a desktop watched a single tick until the desktop user happened to
 * open the thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDeliveryAckTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private val ravi = CrewContact(userId = "u-ravi", fullName = "Ravi Menon")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A line for a thread nobody is looking at is still acked as delivered. */
    @Test
    fun `a message for a closed thread is acked delivered`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.Arrived(incoming(from = ravi.userId, id = "m-1")))
        advanceUntilIdle()

        assertEquals(listOf(ravi.userId to "m-1"), repository.delivered2)
    }

    /**
     * The open thread is read, not merely delivered — and only the read goes,
     * since a 2 landing behind a 3 would walk the sender's ticks backwards.
     */
    @Test
    fun `a message in the open thread is read, not acked delivered`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.Arrived(incoming(from = aisha.userId, id = "m-2")))
        advanceUntilIdle()

        assertTrue(repository.delivered2.isEmpty(), "the read emit is the stronger claim")
        assertTrue(repository.reads.any { it.second == "m-2" }, "and it did go")
    }

    /**
     * Already delivered or read: the web gates its emit on `status === 1` to
     * stop a flood of acks on every sync, and so does this.
     */
    @Test
    fun `a message already past rung one is not acked again`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.Arrived(incoming(ravi.userId, "m-3", ChatSendState.Delivered)))
        model.onEvent(ChatEvent.Arrived(incoming(ravi.userId, "m-4", ChatSendState.Read)))
        advanceUntilIdle()

        assertTrue(repository.delivered2.isEmpty())
    }

    /** Our own echo is not something to acknowledge to ourselves. */
    @Test
    fun `our own message is never acked`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(
            ChatEvent.Arrived(
                incoming(ravi.userId, "m-5").copy(isMine = true, senderId = "me", receiverId = ravi.userId),
            ),
        )
        advanceUntilIdle()

        assertTrue(repository.delivered2.isEmpty())
    }

    /** A room's ack names the room, not whoever spoke in it. */
    @Test
    fun `a group message is acked against its room`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(
            ChatEvent.Arrived(
                incoming(from = ravi.userId, id = "m-6").copy(isGroup = true, receiverId = "room-7"),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("room-7" to "m-6"), repository.delivered2)
    }

    private fun incoming(
        from: String,
        id: String,
        state: ChatSendState = ChatSendState.Sent,
    ) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = from,
        receiverId = "me",
        body = "on my way",
        timestampMillis = NOW,
        isMine = false,
        sendState = state,
    )

    private fun viewModel(repository: FakeChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
    )

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}
