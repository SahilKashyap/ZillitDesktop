package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
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
 * Scrolling the board back. The board used to load the newest page and
 * nothing else — every post behind it was unreachable on the desktop. Pages
 * are asked from the oldest post's `updated`, as Android asks, and MERGE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardPagingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun post(id: String, created: Long, updated: Long = created) =
        Notice(id = id, body = id, authorName = "Sam", createdAtMillis = created, updatedAtMillis = updated)

    private class PagedBoard(val newest: List<Notice>, val pages: (Long) -> List<Notice>) : HomeFeedRepository {
        val olderAsked = mutableListOf<Long>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) = ZillitResult.Success(newest)

        override suspend fun loadOlderNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            olderAsked += beforeMillis
            return ZillitResult.Success(pages(beforeMillis))
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) = ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) = ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) = ZillitResult.Success(Unit)

        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
    }

    private fun viewModel(board: PagedBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 100_000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `older pages merge in from the oldest updated stamp, and stop when one adds nothing`() =
        runTest(dispatcher) {
            // An old post edited later: its `updated` is what the server windows by.
            val newest = listOf(post("n3", 5_000), post("n2", 4_000, updated = 4_500), post("n1", 3_000))
            val older = listOf(post("n1", 3_000), post("o2", 2_000), post("o1", 1_000))
            val board = PagedBoard(newest) { before -> if (before == 3_000L) older else listOf(post("o1", 1_000)) }
            val model = viewModel(board)
            advanceUntilIdle()
            assertTrue(model.state.value.hasOlder)

            model.onEvent(HomeFeedEvent.LoadOlder)
            advanceUntilIdle()

            assertEquals(3_000L, board.olderAsked.last())
            assertEquals(setOf("n1", "n2", "n3", "o1", "o2"), model.state.value.notices.map { it.id }.toSet())
            assertTrue(model.state.value.hasOlder, "that page brought new posts")
            assertFalse(model.state.value.loadingOlder)

            model.onEvent(HomeFeedEvent.LoadOlder)
            advanceUntilIdle()

            assertEquals(1_000L, board.olderAsked.last())
            assertEquals(5, model.state.value.notices.size)
            assertFalse(model.state.value.hasOlder, "only the boundary post came back: the start of the board")

            model.onEvent(HomeFeedEvent.LoadOlder)
            advanceUntilIdle()
            assertEquals(2, board.olderAsked.size, "no more asking once the start is reached")
        }

    @Test
    fun `a board of one post offers nothing older`() = runTest(dispatcher) {
        val model = viewModel(PagedBoard(listOf(post("only", 1_000))) { emptyList() })
        advanceUntilIdle()

        assertFalse(model.state.value.hasOlder)
    }

    // -- a refresh over a scrolled-back board --------------------------------

    /**
     * The read only ever asks for the newest window, so a refresh used to
     * collapse every page the reader had scrolled back to — and a comment
     * arriving on the board was enough to trigger one.
     */
    @Test
    fun `a refresh keeps the pages already scrolled back to`() = runTest(dispatcher) {
        val newest = listOf(post("n2", 5_000), post("n1", 4_000))
        val older = listOf(post("n1", 4_000), post("o2", 2_000), post("o1", 1_000))
        val board = PagedBoard(newest) { older }
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.LoadOlder)
        advanceUntilIdle()
        assertEquals(setOf("n1", "n2", "o1", "o2"), model.state.value.notices.map { it.id }.toSet())

        // A comment lands on the open board, which re-reads it.
        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u1")))
        advanceUntilIdle()

        assertEquals(
            setOf("n1", "n2", "o1", "o2"),
            model.state.value.notices.map { it.id }.toSet(),
            "the refresh collapsed the board back to its newest page",
        )
        assertTrue(model.state.value.hasOlder, "and took the reader's place in it away")
    }

    /**
     * "There is nothing older" is knowledge the reader paid a fetch for, and
     * only `loadOlder` is entitled to retire it. A refresh that handed the cue
     * back would offer a button that fetches the same empty page for ever.
     */
    @Test
    fun `a refresh does not offer older posts again once the start is reached`() = runTest(dispatcher) {
        val newest = listOf(post("n2", 5_000), post("n1", 4_000))
        // Only the boundary post comes back: the start of the board.
        val board = PagedBoard(newest) { listOf(post("n1", 4_000)) }
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.LoadOlder)
        advanceUntilIdle()
        assertFalse(model.state.value.hasOlder)

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u1")))
        advanceUntilIdle()

        assertFalse(model.state.value.hasOlder, "the refresh offered a page that does not exist")
    }

    /** Arriving at a tab is not a refresh: its cue is computed afresh. */
    @Test
    fun `opening a tab still works out whether it has older posts`() = runTest(dispatcher) {
        val board = PagedBoard(listOf(post("n2", 5_000), post("n1", 4_000))) { emptyList() }
        val model = viewModel(board)
        advanceUntilIdle()

        assertTrue(model.state.value.hasOlder)
    }

    /**
     * The other half of the same rule. A post missing from the answer but
     * INSIDE its window is one the server no longer has, and keeping it would
     * be a deleted post that will not go away.
     */
    @Test
    fun `a refresh still drops a post deleted inside the newest window`() = runTest(dispatcher) {
        val first = listOf(post("n2", 5_000), post("n1", 4_000))
        val older = listOf(post("n1", 4_000), post("o1", 1_000))
        var newest = first
        val board = object : HomeFeedRepository {
            override suspend fun loadUnits() = ZillitResult.Success(
                listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
            )

            override suspend fun loadNotices(unitId: String, beforeMillis: Long) = ZillitResult.Success(newest)

            override suspend fun loadOlderNotices(unitId: String, beforeMillis: Long) = ZillitResult.Success(older)

            override suspend fun postNotice(
                unitId: String,
                text: String,
                localId: String,
                attachment: UploadedNoticeMedia?,
                location: GeoPoint?,
            ) = ZillitResult.Success(Notice(id = "server", body = text, authorName = "You"))

            override suspend fun postComment(noticeId: String, unitId: String, text: String) =
                ZillitResult.Success(emptyList<NoticeComment>())

            override suspend fun editComment(noticeId: String, commentId: String, text: String) =
                ZillitResult.Success(null as NoticeComment?)

            override suspend fun deleteComment(noticeId: String, commentId: String) = ZillitResult.Success(Unit)

            override suspend fun editNotice(noticeId: String, text: String) = ZillitResult.Success(null as Notice?)

            override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

            override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
                ZillitResult.Success(Unit)

            override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

            override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
                ZillitResult.Success(Unit)

            override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
        }
        val model = HomeFeedViewModel(
            repository = board,
            nowMillis = { 100_000 },
            newLocalId = { "local-1" },
            isAdmin = { false },
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.LoadOlder)
        advanceUntilIdle()
        assertEquals(setOf("n1", "n2", "o1"), model.state.value.notices.map { it.id }.toSet())

        // Somebody deletes n2 — it is gone from the newest window.
        newest = listOf(post("n1", 4_000))
        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u1")))
        advanceUntilIdle()

        val ids = model.state.value.notices.map { it.id }.toSet()
        assertFalse(ids.contains("n2"), "a deleted post came back from the kept pages")
        assertEquals(setOf("n1", "o1"), ids, "and the older page was kept")
    }
}
