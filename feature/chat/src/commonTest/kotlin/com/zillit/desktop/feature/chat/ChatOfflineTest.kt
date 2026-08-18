package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.chat.data.CHAT_SEND_KIND
import com.zillit.desktop.feature.chat.data.ChatSendHandler
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * A message written with no network: it wears the clock, waits in the outbox,
 * survives a reopen, and goes by itself when the socket is back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)
    private val outbox = InMemoryOutboxStore()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private var now = 1_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val drafts = InMemoryDraftStore()

    private fun TestScope.support(repository: FakeChatRepository): OfflineSupport {
        val engine = SyncEngine(
            store = outbox,
            handlers = SyncHandlerRegistry(listOf(ChatSendHandler(repository))),
            online = online,
            currentScope = { SyncScope("me", "p1") },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op-${now++}" },
        )
        engine.start()
        return OfflineSupport(engine, drafts, online) { SyncScope("me", "p1") }
    }

    private fun viewModel(repository: FakeChatRepository, support: OfflineSupport?) = ChatViewModel(
        repository = repository,
        nowMillis = { now },
        newUniqueId = { "unique-${now++}" },
        offline = support,
    )

    @Test
    fun `offline, a message wears the clock, waits, and is sent when the network is back`() = runTest(dispatcher) {
        val repository = FakeChatRepository(socketDown = true)
        val support = support(repository)
        val model = viewModel(repository, support)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        online.value = false
        runCurrent()

        model.onEvent(ChatEvent.DraftChanged("On the way up"))
        model.onEvent(ChatEvent.Send)
        runCurrent()

        val bubble = model.currentState.messages.single()
        assertEquals(ChatSendState.Queued, bubble.sendState, "the clock, not a failure")
        assertEquals("On the way up", bubble.body)
        assertTrue(repository.sent.isEmpty(), "nothing tried the socket")
        val op = outbox.all().single()
        assertEquals(CHAT_SEND_KIND, op.kind)
        assertEquals(bubble.uniqueId, op.id, "the bubble and the queue share one id")
        assertEquals("chat:${aisha.userId}", op.groupKey)

        // The network — and the socket — come back.
        repository.socketDown = false
        online.value = true
        runCurrent()

        assertEquals(listOf(bubble.uniqueId), repository.sent)
        assertEquals(SyncState.Done, outbox.all().single().state)
        assertEquals(ChatSendState.Sent, model.currentState.messages.single().sendState)
    }

    @Test
    fun `online, a send the socket could not carry is queued rather than failed`() = runTest(dispatcher) {
        val repository = FakeChatRepository(socketDown = true)
        val model = viewModel(repository, support(repository))
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.DraftChanged("Rolling"))
        model.onEvent(ChatEvent.Send)
        runCurrent()

        assertEquals(ChatSendState.Queued, model.currentState.messages.single().sendState)
        assertEquals(1, outbox.all().size)
    }

    @Test
    fun `a queued message comes back with the thread after a restart`() = runTest(dispatcher) {
        val repository = FakeChatRepository(socketDown = true)
        val support = support(repository)
        val first = viewModel(repository, support)
        first.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        first.onEvent(ChatEvent.DraftChanged("Still here"))
        first.onEvent(ChatEvent.Send)
        runCurrent()

        val second = viewModel(repository, support)
        second.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        val restored = second.currentState.messages.single()
        assertEquals("Still here", restored.body)
        assertEquals(ChatSendState.Queued, restored.sendState)

        // Another thread does not see it.
        second.onEvent(ChatEvent.OpenThread(CrewContact(userId = "u-ben", fullName = "Ben")))
        runCurrent()
        assertTrue(second.currentState.messages.isEmpty())
    }

    @Test
    fun `without offline support a dead socket still means a failed bubble`() = runTest(dispatcher) {
        val repository = FakeChatRepository(socketDown = true)
        val model = viewModel(repository, support = null)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.DraftChanged("Rolling"))
        model.onEvent(ChatEvent.Send)
        runCurrent()

        assertEquals(ChatSendState.Failed, model.currentState.messages.single().sendState)
        assertTrue(outbox.all().isEmpty())
    }

    /**
     * The DM list comes over the socket, so with no network it never answers.
     * The last list this production had stands in — after a restart too — and
     * a thread kept on this computer counts even if that list never named it.
     */
    @Test
    fun `offline, the DM list is the last one this production had plus the threads kept here`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository().apply { recents = listOf("u-aisha", "u-bob") }
            val support = support(repository)
            val first = viewModel(repository, support)
            first.onEvent(ChatEvent.RefreshRecents)
            runCurrent()
            assertEquals(listOf("u-aisha", "u-bob"), first.currentState.recents.sorted())

            // Restart, offline: a fresh view model, the same store.
            repository.socketDown = true
            online.value = false
            repository.rememberArrival(
                com.zillit.desktop.feature.chat.domain.ChatMessage(
                    id = "m1", uniqueId = "m1", senderId = "u-carla", receiverId = "me",
                    body = "hi", timestampMillis = 5_000L, isMine = false,
                ),
            )
            val second = viewModel(repository, support)
            second.onEvent(ChatEvent.RefreshRecents)
            runCurrent()

            assertEquals(listOf("u-aisha", "u-bob", "u-carla"), second.currentState.recents.sorted())
        }
}
