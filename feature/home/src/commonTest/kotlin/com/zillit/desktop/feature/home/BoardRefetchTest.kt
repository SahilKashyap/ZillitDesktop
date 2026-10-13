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
 * What a socket event costs the board.
 *
 * Every Home event whose payload could not patch a post used to arrive as
 * `UnitsChanged`, which refetches the tab strip *and* the board — and the
 * units spinner was unguarded, so the board was replaced by a full-screen
 * "Loading…" every time. Comment events are the common case: anybody
 * commenting on any unit of the production blanked everyone's open board.
 *
 * So the cost is graded now, and this is where that grading is pinned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardRefetchTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Counts what each event asked the server for, and for which unit. */
    private class CountingBoard : HomeFeedRepository {
        var unitLoads = 0
            private set
        val noticeLoads = mutableListOf<String>()

        override suspend fun loadUnits(): ZillitResult<List<HomeUnit>> {
            unitLoads++
            return ZillitResult.Success(
                listOf(
                    HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true),
                    HomeUnit("u2", "general_tool", "general_label", canView = true, canPost = true),
                ),
            )
        }

        override suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            noticeLoads += unitId
            return ZillitResult.Success(
                listOf(
                    Notice(id = "$unitId-a", body = "a", authorName = "Sam", createdAtMillis = 1_000),
                    Notice(id = "$unitId-b", body = "b", authorName = "Sam", createdAtMillis = 2_000),
                ),
            )
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

    private fun viewModel(board: CountingBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 100_000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `a comment on the open unit re-reads that board and nothing else`() = runTest(dispatcher) {
        val board = CountingBoard()
        val model = viewModel(board)
        advanceUntilIdle()
        assertEquals(1, board.unitLoads)
        assertEquals(listOf("u1"), board.noticeLoads)

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u1")))
        advanceUntilIdle()

        assertEquals(listOf("u1", "u1"), board.noticeLoads)
        assertEquals(1, board.unitLoads, "a comment does not move the tab strip")
    }

    @Test
    fun `a comment on a unit nobody is looking at costs nothing`() = runTest(dispatcher) {
        val board = CountingBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u2")))
        advanceUntilIdle()

        assertEquals(listOf("u1"), board.noticeLoads, "u2 is not on screen; opening its tab reads it")
        assertEquals(1, board.unitLoads)
    }

    /** The server omits `unit_id` on some payloads; that means "the open board". */
    @Test
    fun `an unscoped board change re-reads whatever is open`() = runTest(dispatcher) {
        val board = CountingBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged(null)))
        advanceUntilIdle()

        assertEquals(listOf("u1", "u1"), board.noticeLoads)
    }

    /**
     * The posts stay on screen across the refetch.
     *
     * Both spinners are guarded on having nothing to show, so a reload behind
     * a loaded board is invisible — which is the entire point of the change.
     */
    @Test
    fun `a refetch never empties the board`() = runTest(dispatcher) {
        val board = CountingBoard()
        val model = viewModel(board)
        advanceUntilIdle()
        assertEquals(2, model.state.value.notices.size)

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.BoardChanged("u1")))

        // Mid-flight: the fetch is out and the posts are still there.
        assertTrue(model.state.value.isLoadingNotices)
        assertFalse(model.state.value.notices.isEmpty(), "the board was blanked behind the spinner")

        advanceUntilIdle()
        assertEquals(2, model.state.value.notices.size)
    }

    /** A real units change still refetches both — rights may have moved. */
    @Test
    fun `a units change still re-reads the tab strip and the board`() = runTest(dispatcher) {
        val board = CountingBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.UnitsChanged))
        advanceUntilIdle()

        assertEquals(2, board.unitLoads)
        assertEquals(listOf("u1", "u1"), board.noticeLoads)
    }
}
