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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The send/echo race that crashed the board.
 *
 * A media post uploads before it POSTs, so the socket echo of our own post
 * routinely lands while the POST is still in flight — and a media echo
 * carries no `unique_id`, so it cannot match the optimistic card and appends
 * the server's copy. When the POST response then swapped the optimistic card
 * for that same server notice, the list held two rows with one id, and the
 * board's LazyColumn threw `Key "…" was already used`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeEchoRaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A board whose POST hangs until the test releases it. */
    private class SlowBoard : HomeFeedRepository {
        val gate = CompletableDeferred<Notice>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(gate.await())

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)
    }

    @Test
    fun `the echo of a slow post never doubles the row`() = runTest(dispatcher) {
        val board = SlowBoard()
        val viewModel = HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-1" },
            isAdmin = { false },
        )
        viewModel.onEvent(HomeFeedEvent.Load)
        advanceUntilIdle()

        viewModel.onEvent(HomeFeedEvent.DraftChanged("call sheet photo"))
        viewModel.onEvent(HomeFeedEvent.Send)
        runCurrent()

        // The POST is still in flight when the socket echoes our post back —
        // without the unique_id a media echo does not carry.
        val server = Notice(id = "srv-1", body = "call sheet photo", authorName = "You")
        viewModel.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.NoticeAdded("u1", server)))
        runCurrent()

        // Now the POST lands with the same server notice.
        board.gate.complete(server)
        advanceUntilIdle()

        assertEquals(
            1,
            viewModel.currentState.notices.count { it.id == "srv-1" },
            "one post, one row — a duplicate id here is the LazyColumn crash",
        )
    }
}
