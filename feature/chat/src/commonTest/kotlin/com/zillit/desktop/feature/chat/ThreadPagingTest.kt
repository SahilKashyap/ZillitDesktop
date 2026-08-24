package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatMessage
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA #7/#8: "Show older" pages the thread backwards. A full first window
 * offers the button, the fetch asks `history` from the oldest loaded stamp
 * (`/messages/{peer}/{ts}/previous`), pages MERGE rather than replace, and a
 * short page retires the button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadPagingTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** history() with pages behind it; everything else is the shared fake. */
    private class PagingRepository(
        val base: FakeChatRepository = FakeChatRepository(),
    ) : ChatRepository by base {
        val asked = mutableListOf<Long>()
        var answer: (Long) -> List<ChatMessage> = { emptyList() }

        override suspend fun history(
            otherUserId: String,
            nowMillis: Long,
            isGroup: Boolean,
        ): ZillitResult<List<ChatMessage>> {
            asked += nowMillis
            return ZillitResult.Success(answer(nowMillis))
        }
    }

    private fun message(n: Int, at: Long) = ChatMessage(
        id = "m-$n",
        uniqueId = "m-$n",
        senderId = "u-aisha",
        receiverId = "me",
        body = "line $n",
        timestampMillis = at,
        isMine = false,
    )

    private fun viewModel(repository: ChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW_MS },
        newUniqueId = { "unique-1" },
    )

    @Test
    fun `a full window offers Show older and the next page merges in`() = runTest(dispatcher) {
        val repository = PagingRepository()
        // Newest window: 50 rows ending NOW; the page before: 10 older rows,
        // one of them overlapping the newest window by unique id.
        val newest = (0 until 50).map { message(100 + it, 10_000L + it) }
        val older = (0 until 10).map { message(if (it == 0) 100 else it, 9_000L + it) }
        repository.answer = { before -> if (before == NOW_MS) newest else older }

        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        assertTrue(model.currentState.hasOlder, "a full page means more behind it")
        assertEquals(50, model.currentState.messages.size)

        model.onEvent(ChatEvent.ShowOlder)
        advanceUntilIdle()

        assertEquals(10_000L, repository.asked.last(), "asked from the oldest loaded stamp")
        // 50 + 10 - 1 duplicate: merged by unique id, not replaced.
        assertEquals(59, model.currentState.messages.size)
        assertEquals(
            model.currentState.messages.sortedBy { it.timestampMillis },
            model.currentState.messages,
            "the merge keeps time order",
        )
        assertFalse(model.currentState.hasOlder, "a short page retires the button")
        assertFalse(model.currentState.loadingOlder)
    }

    @Test
    fun `a short first window never offers the button`() = runTest(dispatcher) {
        val repository = PagingRepository()
        repository.answer = { listOf(message(1, 10_000L)) }

        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        assertFalse(model.currentState.hasOlder)
    }
}

private const val NOW_MS = 20_000L
