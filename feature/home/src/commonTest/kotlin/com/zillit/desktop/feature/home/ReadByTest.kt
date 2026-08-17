package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.ReadReceipt
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Read receipts, and the board-viewed signal that produces them.
 *
 * The receipts panel is a plain fetch; what earns tests is the signal's
 * timing — the server hears "viewed" when a board loads and again when a post
 * lands on the open board, and must NOT hear it for boards the user is not on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadByTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBoard(
        var receipts: ZillitResult<ReadBy> = ZillitResult.Success(
            ReadBy(
                read = listOf(ReadReceipt("u-read", "Aisha", "Gaffer", 1_700_000_000_000)),
                unread = listOf(ReadReceipt("u-unread")),
            ),
        ),
        val canPost: Boolean = true,
    ) : HomeFeedRepository {
        val asked = mutableListOf<String>()
        val notified = mutableListOf<Pair<String, String>>()

        override suspend fun readBy(noticeId: String): ZillitResult<ReadBy> {
            asked += noticeId
            return receipts
        }

        override suspend fun notifyUnread(unitId: String, noticeId: String): ZillitResult<Unit> {
            notified += unitId to noticeId
            return ZillitResult.Success(Unit)
        }

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(
                HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = canPost),
            ),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(listOf(Notice(id = "n1", body = "post", authorName = "Sam")))

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

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)
    }

    private fun viewModel(
        board: FakeBoard = FakeBoard(),
        viewed: MutableList<String> = mutableListOf(),
    ) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
        onBoardViewed = { viewed += it },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `the panel opens loading and fills with both lists`() = runTest(dispatcher) {
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.ShowReadBy("n1"))
        runCurrent()
        assertEquals("n1", model.state.value.readBy?.noticeId)

        advanceUntilIdle()
        val lists = model.state.value.readBy?.lists
        assertNotNull(lists)
        assertEquals(listOf("u-read"), lists.read.map { it.userId })
        assertEquals(listOf("u-unread"), lists.unread.map { it.userId })
        assertEquals(listOf("n1"), board.asked)
    }

    @Test
    fun `a failed fetch closes the panel with the error, not a dead card`() =
        runTest(dispatcher) {
            val board = FakeBoard(receipts = ZillitResult.Failure(ZillitError.NoConnection()))
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.ShowReadBy("n1"))
            advanceUntilIdle()

            assertNull(model.state.value.readBy)
            assertNotNull(model.state.value.error)
        }

    @Test
    fun `loading a board tells the server it was viewed`() = runTest(dispatcher) {
        val viewed = mutableListOf<String>()
        viewModel(viewed = viewed)
        advanceUntilIdle()

        assertEquals(listOf("u1"), viewed)
    }

    @Test
    fun `a post landing on the open board is read as it lands`() = runTest(dispatcher) {
        val viewed = mutableListOf<String>()
        val model = viewModel(viewed = viewed)
        advanceUntilIdle()
        viewed.clear()

        model.onEvent(
            HomeFeedEvent.Realtime(
                com.zillit.desktop.feature.home.domain.HomeRealtimeEvent.NoticeAdded(
                    unitId = "u1",
                    notice = Notice(id = "n2", body = "new", authorName = "Sam"),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("u1"), viewed)
    }

    @Test
    fun `notify reaches the server with the panel's post and the open unit`() =
        runTest(dispatcher) {
            val board = FakeBoard()
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.ShowReadBy("n1"))
            advanceUntilIdle()
            model.onEvent(HomeFeedEvent.NotifyUnread)
            advanceUntilIdle()

            assertEquals(listOf("u1" to "n1"), board.notified)
            assertNotNull(model.state.value.info, "a push with no receipt looks like a dead button")
        }

    @Test
    fun `notify without posting rights is refused, not sent`() = runTest(dispatcher) {
        val board = FakeBoard(canPost = false)
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.ShowReadBy("n1"))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.NotifyUnread)
        advanceUntilIdle()

        assertEquals(emptyList(), board.notified)
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a post for another board is not marked read`() = runTest(dispatcher) {
        val viewed = mutableListOf<String>()
        val model = viewModel(viewed = viewed)
        advanceUntilIdle()
        viewed.clear()

        model.onEvent(
            HomeFeedEvent.Realtime(
                com.zillit.desktop.feature.home.domain.HomeRealtimeEvent.NoticeAdded(
                    unitId = "somewhere-else",
                    notice = Notice(id = "n3", body = "new", authorName = "Sam"),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(emptyList(), viewed)
    }
}
