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
 * QA #7/#8: "Show older" pages the thread backwards. The fetch asks `history`
 * from the oldest loaded stamp (`/messages/{peer}/{ts}/previous`) and pages
 * MERGE rather than replace.
 *
 * ## When the button shows
 *
 * Whenever there may be more — which is any first window past a lone row, and
 * any older page that still brought rows the thread had not seen. It used to
 * be "a full page of 50", an assumption about the server's window size that
 * nothing on the wire confirms: with a smaller window the button never
 * appeared, and everything older than the first page was unreachable. That
 * is the "old chat of a few users is not loading" report — the few being the
 * ones with more than a window's worth. The web assumes no size either; it
 * offers older until a page comes back with nothing new.
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
    fun `older pages merge in, and the button retires only when a page adds nothing`() =
        runTest(dispatcher) {
            val repository = PagingRepository()
            // Newest window: 50 rows ending NOW. The page before: 10 older
            // rows, one of them the boundary row the newest window already
            // holds. The page before THAT: only the boundary row — the
            // server's window includes it, so a page of nothing new is what
            // the start of the thread looks like.
            val newest = (0 until 50).map { message(100 + it, 10_000L + it) }
            val older = (0 until 10).map { message(if (it == 0) 100 else it, 9_000L + it) }
            val boundaryOnly = listOf(older.last())
            repository.answer = { before ->
                when (before) {
                    NOW_MS -> newest
                    10_000L -> older
                    else -> boundaryOnly
                }
            }

            val model = viewModel(repository)
            model.onEvent(ChatEvent.OpenThread(aisha))
            advanceUntilIdle()

            assertTrue(model.currentState.hasOlder, "a first window past one row may have more")
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
            assertTrue(model.currentState.hasOlder, "a page that brought new rows may have more")
            assertFalse(model.currentState.loadingOlder)

            model.onEvent(ChatEvent.ShowOlder)
            advanceUntilIdle()

            assertEquals(9_000L, repository.asked.last(), "asked from the new oldest stamp")
            assertEquals(59, model.currentState.messages.size, "nothing new to merge")
            assertFalse(model.currentState.hasOlder, "a page that adds nothing retires the button")
        }

    /**
     * The regression this rule exists for: a window smaller than the old
     * assumed 50 still has history behind it, and must still offer it.
     */
    @Test
    fun `a first window smaller than the old page size still offers Show older`() =
        runTest(dispatcher) {
            val repository = PagingRepository()
            repository.answer = { (0 until 20).map { message(it, 10_000L + it) } }

            val model = viewModel(repository)
            model.onEvent(ChatEvent.OpenThread(aisha))
            advanceUntilIdle()

            assertTrue(model.currentState.hasOlder, "20 rows is not proof the thread starts here")
        }

    @Test
    fun `a lone first row never offers the button`() = runTest(dispatcher) {
        val repository = PagingRepository()
        repository.answer = { listOf(message(1, 10_000L)) }

        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        advanceUntilIdle()

        assertFalse(model.currentState.hasOlder)
    }
}

private const val NOW_MS = 20_000L
