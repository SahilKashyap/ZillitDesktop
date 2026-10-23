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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contact panel's "ask them to turn their location on": the request is a
 * chat message like any other (the web's `handleSendMessage` calls
 * `sendPrivateChat`), but it comes from a button in the info panel, so it
 * must leave the composer exactly as it found it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskToShareLocationTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private val request = "Sahil Kashyap has requested to track you."

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { 2_000L },
        newUniqueId = { "unique-1" },
    )

    @Test
    fun `the request goes out and shows as our own bubble`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.AskToShareLocation(request))
        advanceUntilIdle()

        assertEquals(listOf("unique-1"), repository.sent, "one message left this machine")
        val bubble = model.currentState.messages.last()
        assertEquals(request, bubble.body)
        assertTrue(bubble.isMine)
        assertEquals(ChatSendState.Sent, bubble.sendState)
    }

    @Test
    fun `a half-typed line and a pending reply survive it`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(
            ChatEvent.Arrived(
                ChatMessage(
                    id = "srv-9",
                    uniqueId = "w-9",
                    senderId = "u-aisha",
                    receiverId = "me",
                    body = "Where do we park the vans?",
                    timestampMillis = 1_500L,
                    isMine = false,
                ),
            ),
        )
        model.onEvent(ChatEvent.StartReply("srv-9"))
        model.onEvent(ChatEvent.DraftChanged("Behind the church"))

        model.onEvent(ChatEvent.AskToShareLocation(request))
        advanceUntilIdle()

        assertEquals("Behind the church", model.currentState.draft, "the draft is not the panel's to clear")
        assertEquals("srv-9", model.currentState.replyTo?.id, "nor is the quote")
        // And the request itself went out plain — it is not a reply to anything.
        assertNull(model.currentState.messages.last().replyTo)
    }

    @Test
    fun `a room is never asked — a group has no location to share`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(
            ChatEvent.OpenGroup(
                com.zillit.desktop.feature.chat.domain.GroupRoom(id = "r1", name = "Camera"),
            ),
        )
        advanceUntilIdle()

        model.onEvent(ChatEvent.AskToShareLocation(request))
        advanceUntilIdle()

        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `a refusal decided on screen reaches the thread's own toast`() = runTest(dispatcher) {
        val model = viewModel(FakeChatRepository())
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        model.onEvent(ChatEvent.Refuse("Individual tracking is disabled for the current time."))
        assertEquals("Individual tracking is disabled for the current time.", model.currentState.error)
        model.onEvent(ChatEvent.DismissError)
        assertNull(model.currentState.error)
    }
}
