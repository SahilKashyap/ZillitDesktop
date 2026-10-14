package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.ConversationBacklog
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
     * The open thread SOMEBODY IS LOOKING AT is read, not merely delivered —
     * and only the read goes, since a 2 landing behind a 3 would walk the
     * sender's ticks backwards.
     */
    @Test
    fun `a message in the watched open thread is read, not acked delivered`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        model.onEvent(ChatEvent.Watching(true))
        runCurrent()

        model.onEvent(ChatEvent.Arrived(incoming(from = aisha.userId, id = "m-2")))
        advanceUntilIdle()

        assertTrue(repository.delivered2.isEmpty(), "the read emit is the stronger claim")
        assertTrue(repository.reads.any { it.second == "m-2" }, "and it did go")
    }

    /**
     * An open thread behind another tab, or in an unfocused window, is not
     * being read by anybody.
     *
     * Found live on 2026-10-14: two messages arrived while the window showed
     * the Transportation Hub and the desktop answered each with
     * `-> private_chat_message_read_untill`, telling the sender they had been
     * seen and clearing them on the user's phone. "Open" meant only that a
     * thread object existed on this model, which outlives looking at it.
     */
    @Test
    fun `an open thread nobody is watching is delivered, never read`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.Arrived(incoming(from = aisha.userId, id = "m-8")))
        advanceUntilIdle()

        assertTrue(repository.reads.none { it.second == "m-8" }, "nobody saw it, so nobody read it")
        assertEquals(listOf(aisha.userId to "m-8"), repository.delivered2, "but it did reach this computer")
        assertEquals(1, model.currentState.unread[aisha.userId], "and the row says so")
    }

    /**
     * Looking is itself the read: what arrived unwatched is on screen the
     * moment the window comes back, so the receipt it was denied goes then.
     * Without this the gate would lose those reads rather than defer them.
     */
    @Test
    fun `coming back to the thread sends the read that was withheld`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        model.onEvent(ChatEvent.Arrived(incoming(from = aisha.userId, id = "m-9")))
        advanceUntilIdle()
        assertTrue(repository.reads.none { it.second == "m-9" })

        model.onEvent(ChatEvent.Watching(true))
        advanceUntilIdle()

        assertTrue(repository.reads.any { it.second == "m-9" }, "the read goes when a person looks")
        assertTrue(model.currentState.unread[aisha.userId] == null, "and the row clears")
    }

    /**
     * The rail keeps its badge for a thread that is open but unlooked-at.
     *
     * The backlog reload drops the open thread's count on the grounds that
     * "a thread open right now was just read". Once a read needs somebody
     * watching, that is no longer true of a merely-open thread — and the
     * subtraction took the whole conversation out of `unread`, so the
     * sidebar's C&C pill went blank while messages piled up (user,
     * 2026-10-14: "badges stop appearing when the chat tab is closed").
     */
    @Test
    fun `an open but unwatched thread keeps its backlog count`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf(aisha.userId)
            backlog = ConversationBacklog(unread = mapOf(aisha.userId to 2))
        }
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        model.onEvent(ChatEvent.Watching(false))
        advanceUntilIdle()

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(2, model.currentState.unread[aisha.userId], "nobody looked, so it is still unread")
        assertEquals(2, model.currentState.chatsBadge, "and the rail says so")
    }

    /** Looking at it is what drops the backlog's older count. */
    @Test
    fun `a watched thread drops its backlog count`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            recents = listOf(aisha.userId)
            backlog = ConversationBacklog(unread = mapOf(aisha.userId to 2))
        }
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        model.onEvent(ChatEvent.Watching(true))
        advanceUntilIdle()

        model.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()

        assertEquals(null, model.currentState.unread[aisha.userId])
        assertEquals(0, model.currentState.chatsBadge)
    }

    /**
     * Opening a thread somebody is watching claims the read ONCE.
     *
     * Two paths reach the same watermark on an open — the open itself and the
     * surface reporting that it is being watched — and both named the same
     * newest message, so the socket carried the identical claim twice, 170 ms
     * apart (live, 2026-10-14).
     */
    @Test
    fun `opening a watched thread sends one read, not two`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            history = listOf(incoming(from = aisha.userId, id = "m-10"))
        }
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        // History lands before the surface reports itself, as it does live —
        // firing both in one tick leaves `messages` empty and `onWatching`
        // returns early, which hides the very duplication this pins.
        advanceUntilIdle()
        model.onEvent(ChatEvent.Watching(true))
        advanceUntilIdle()

        assertEquals(listOf(aisha.userId to "m-10"), repository.reads, "one claim, once")
    }

    /** A newer message is a new claim, so it is sent. */
    @Test
    fun `a later message earns its own read`() = runTest(dispatcher) {
        val repository = FakeChatRepository().apply {
            history = listOf(incoming(from = aisha.userId, id = "m-11"))
        }
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Watching(true))
        advanceUntilIdle()

        model.onEvent(ChatEvent.Arrived(incoming(from = aisha.userId, id = "m-12")))
        advanceUntilIdle()

        assertEquals(
            listOf(aisha.userId to "m-11", aisha.userId to "m-12"),
            repository.reads,
            "the watermark moved, so the claim is new",
        )
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
