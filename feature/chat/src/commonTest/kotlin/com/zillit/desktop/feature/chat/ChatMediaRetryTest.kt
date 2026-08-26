package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.PendingChatUpload
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
 * QA #9: a media bubble that could not send is not screen-state — it
 * survives closing and reopening the thread, and re-sends itself when
 * connectivity returns. (App-restart persistence is out of scope: the bytes
 * live in memory, not the outbox DB — see ChatViewModel.UnsentMedia.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaRetryTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private var now = 1_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val stored = ChatAttachment(media = "s3/key", name = "clip.mp4", contentType = "video/mp4")

    private fun pending() = PendingChatUpload(
        name = "clip.mp4",
        contentType = "video/mp4",
        bytes = byteArrayOf(4, 4),
    ) { _, _ -> stored }

    private fun TestScope.support(online: MutableStateFlow<Boolean>): OfflineSupport {
        val engine = SyncEngine(
            store = InMemoryOutboxStore(),
            handlers = SyncHandlerRegistry(emptyList()),
            online = online,
            currentScope = { SyncScope("me", "p1") },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op-${now++}" },
        )
        engine.start()
        return OfflineSupport(engine, InMemoryDraftStore(), online) { SyncScope("me", "p1") }
    }

    private fun viewModel(repository: FakeChatRepository, support: OfflineSupport?) = ChatViewModel(
        repository = repository,
        nowMillis = { now },
        newUniqueId = { "unique-${now++}" },
        pickAttachment = { pending() },
        offline = support,
    )

    /**
     * Building the view model while ALREADY online must not crash.
     *
     * `init` collects `support.online`, a StateFlow that replays its current
     * value into a `Dispatchers.Main.immediate` collector — synchronously,
     * inside the constructor. The retry it triggers reads `unsentMedia`, so
     * that map has to be initialised before `init` runs. It was declared six
     * hundred lines below, and the app died on the AWT thread before drawing
     * a frame: `NullPointerException … "$this$filterValues$iv" is null`
     * (seen live, 2026-08-25). Property initialisers run in source order —
     * this test fails if the declaration ever moves back down.
     */
    @Test
    fun `starting up already online does not crash the view model`() = runTest(dispatcher) {
        val repository = FakeChatRepository()

        // True from the first frame — the ordinary case for a desktop that
        // opens with the network up.
        val model = viewModel(repository, support(MutableStateFlow(true)))
        runCurrent()

        // It lives, and its state is the empty board rather than a corpse.
        assertTrue(model.currentState.messages.isEmpty())
    }

    /** Paperclip, then the preview's Send — with the pick settling between. */
    private fun TestScope.sendClip(model: ChatViewModel) {
        model.onEvent(ChatEvent.AttachFile)
        runCurrent()
        model.onEvent(
            ChatEvent.PreviewSend(PreviewResult("clip.mp4", "video/mp4", byteArrayOf(4, 4)), ""),
        )
        runCurrent()
    }

    @Test
    fun `a failed media send survives reopening and goes when the network is back`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository(socketDown = true)
            val online = MutableStateFlow(true)
            val model = viewModel(repository, support(online))
            model.onEvent(ChatEvent.OpenThread(aisha))
            runCurrent()
            online.value = false
            runCurrent()

            sendClip(model)
            runCurrent()

            val bubble = model.currentState.messages.single()
            assertEquals(ChatSendState.Queued, bubble.sendState, "offline wears the clock")
            assertEquals(stored, bubble.attachment, "the upload itself had succeeded")
            assertTrue(repository.sent.isEmpty())

            // Away and back: the thread rebuilds from cache + history, which
            // know nothing of the unsent file — the store must restore it.
            model.onEvent(ChatEvent.CloseThread)
            runCurrent()
            model.onEvent(ChatEvent.OpenThread(aisha))
            runCurrent()

            val restored = model.currentState.messages.single()
            assertEquals(bubble.uniqueId, restored.uniqueId, "the bubble survived the reopen")
            assertEquals(ChatSendState.Queued, restored.sendState)

            // Connectivity returns: the store retries by itself.
            repository.socketDown = false
            online.value = true
            runCurrent()

            assertEquals(listOf(bubble.uniqueId), repository.sent, "the send ran again")
            assertEquals(ChatSendState.Sent, model.currentState.messages.single().sendState)
        }

    @Test
    fun `a refused media send keeps its Failed bubble across reopens`() = runTest(dispatcher) {
        val repository = FakeChatRepository(sendFails = true)
        val model = viewModel(repository, support = null)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        sendClip(model)
        runCurrent()
        assertEquals(ChatSendState.Failed, model.currentState.messages.single().sendState)

        model.onEvent(ChatEvent.CloseThread)
        runCurrent()
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        val restored = model.currentState.messages.single()
        assertEquals(ChatSendState.Failed, restored.sendState, "still there, still marked")
        assertEquals("clip.mp4", restored.attachment?.name)
    }
}
