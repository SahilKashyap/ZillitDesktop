package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSelectionAction
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
 * Selection mode in a thread — the web's (`MyMessage.jsx` arms it from the
 * menu, `ChatFooterCnc` draws the bar and asks before it sends):
 *
 * - the menu's Delete ticks the line and opens the mode; nothing is sent;
 * - ticking adds and un-ticking removes, and someone else's line cannot join;
 * - the bar's Delete asks, and only the answer withdraws — as ONE emit, which
 *   is what `private-chat:delete-messages` takes;
 * - switching conversation forgets the mode and its question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSelectionDeleteTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private val vivek = CrewContact(userId = "u-vivek", fullName = "Vivek Mishra")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Records what each delete asked the wire for, batch and all. */
    private class DeleteSpy(
        val base: FakeChatRepository = FakeChatRepository(),
    ) : ChatRepository by base {
        val batches = mutableListOf<List<String>>()

        override suspend fun deleteMessages(
            messageIds: List<String>,
            isGroup: Boolean,
        ): ZillitResult<List<String>> {
            batches += messageIds
            return base.deleteMessages(messageIds, isGroup)
        }
    }

    @Test
    fun `the menu's Delete opens selection mode with that line ticked`() = runTest(dispatcher) {
        val repository = DeleteSpy()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "One")))

        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        advanceUntilIdle()

        val open = model.currentState.selection
        assertEquals(ChatSelectionAction.Delete, open?.action)
        assertEquals(setOf("srv-1"), open?.ids)
        assertTrue(repository.batches.isEmpty(), "nothing was sent")
        assertEquals(1, model.currentState.messages.size)
    }

    @Test
    fun `ticking adds and un-ticking removes`() = runTest(dispatcher) {
        val model = viewModel(DeleteSpy())
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "One")))
        model.onEvent(ChatEvent.Arrived(mine("srv-2", "Two")))

        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        model.onEvent(ChatEvent.ToggleSelected("srv-2"))
        assertEquals(setOf("srv-1", "srv-2"), model.currentState.selection?.ids)

        model.onEvent(ChatEvent.ToggleSelected("srv-1"))
        assertEquals(setOf("srv-2"), model.currentState.selection?.ids)
    }

    @Test
    fun `someone else's line and a line past the clock cannot be ticked`() = runTest(dispatcher) {
        val model = viewModel(DeleteSpy())
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Mine")))
        model.onEvent(ChatEvent.Arrived(theirs("srv-2", "Theirs")))
        model.onEvent(ChatEvent.Arrived(mine("srv-old", "Yesterday", createdAt = NOW - 3 * HOUR)))

        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        model.onEvent(ChatEvent.ToggleSelected("srv-2"))
        assertEquals(setOf("srv-1"), model.currentState.selection?.ids, "theirs stayed out")
        assertEquals(ChatComposerRules.REWRITE_WINDOW_CLOSED, model.currentState.error)

        model.onEvent(ChatEvent.DismissError)
        model.onEvent(ChatEvent.ToggleSelected("srv-old"))
        assertEquals(setOf("srv-1"), model.currentState.selection?.ids, "past the window stayed out")
        assertEquals(ChatComposerRules.REWRITE_WINDOW_CLOSED, model.currentState.error)
    }

    @Test
    fun `the bar asks, and the answer withdraws the batch in one emit`() = runTest(dispatcher) {
        val repository = DeleteSpy()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "One")))
        model.onEvent(ChatEvent.Arrived(mine("srv-2", "Two")))

        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        model.onEvent(ChatEvent.ToggleSelected("srv-2"))
        model.onEvent(ChatEvent.AskDeleteSelected)
        advanceUntilIdle()

        assertEquals(listOf("srv-1", "srv-2"), model.currentState.confirmingBatch.sorted())
        assertTrue(repository.batches.isEmpty(), "the question sends nothing")
        assertEquals(2, model.currentState.messages.size)

        model.onEvent(ChatEvent.DeleteSelected)
        advanceUntilIdle()

        assertEquals(1, repository.batches.size, "one request, not one per line")
        assertEquals(listOf("srv-1", "srv-2"), repository.batches.single().sorted())
        assertTrue(model.currentState.messages.isEmpty())
        assertNull(model.currentState.selection, "the mode closed with the delete")
        assertTrue(model.currentState.confirmingBatch.isEmpty())
    }

    @Test
    fun `cancelling the question keeps the lines and the mode`() = runTest(dispatcher) {
        val repository = DeleteSpy()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "One")))

        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        model.onEvent(ChatEvent.AskDeleteSelected)
        model.onEvent(ChatEvent.CancelDelete)
        advanceUntilIdle()

        assertTrue(model.currentState.confirmingBatch.isEmpty())
        assertEquals(setOf("srv-1"), model.currentState.selection?.ids, "still ticked")
        assertTrue(repository.batches.isEmpty())
        assertEquals(1, model.currentState.messages.size)
    }

    @Test
    fun `switching conversation forgets the mode`() = runTest(dispatcher) {
        val model = viewModel(DeleteSpy())
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "One")))
        model.onEvent(ChatEvent.SelectForDelete("srv-1"))
        model.onEvent(ChatEvent.AskDeleteSelected)

        model.onEvent(ChatEvent.OpenThread(vivek))
        advanceUntilIdle()

        assertNull(model.currentState.selection)
        assertTrue(model.currentState.confirmingBatch.isEmpty())
    }

    private fun viewModel(repository: ChatRepository, admin: Boolean = false) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${counter++}" },
        isAdmin = { admin },
    )

    private var counter = 1

    private fun mine(id: String, body: String, createdAt: Long = NOW - MINUTE) = ChatMessage(
        id = id, uniqueId = "w-$id", senderId = "me", receiverId = "u-aisha",
        body = body, timestampMillis = createdAt, isMine = true,
    )

    private fun theirs(id: String, body: String) = ChatMessage(
        id = id, uniqueId = "w-$id", senderId = "u-aisha", receiverId = "me",
        body = body, timestampMillis = NOW - MINUTE, isMine = false,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NOW = 1_700_000_000_000L
    }
}
