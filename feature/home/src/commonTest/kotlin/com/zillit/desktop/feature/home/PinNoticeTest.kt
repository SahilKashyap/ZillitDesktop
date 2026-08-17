package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.withPinnedSection
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pinning: the board's curation act.
 *
 * The section maths is pure; the toggle is optimistic with a revert — a pin
 * that silently failed would leave two clients believing different boards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinNoticeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the section -------------------------------------------------------

    @Test
    fun `pinned posts float first under their own header`() {
        val rows = listOf(
            Notice(id = "a", body = "plain", authorName = "S", createdAtMillis = 3),
            Notice(id = "b", body = "pinned", authorName = "S", createdAtMillis = 2, isPinned = true),
            Notice(id = "c", body = "plain", authorName = "S", createdAtMillis = 1),
        ).withPinnedSection(nowMillis = 10)

        assertEquals("Pinned", (rows.first() as BoardRow.Separator).label)
        assertEquals("b", (rows[1] as BoardRow.Post).notice.id)
        // The rest keep their chronology, date separators intact.
        assertTrue(rows.drop(2).filterIsInstance<BoardRow.Post>().map { it.notice.id } == listOf("a", "c"))
    }

    @Test
    fun `no pins, no header`() {
        val rows = listOf(
            Notice(id = "a", body = "plain", authorName = "S", createdAtMillis = 1),
        ).withPinnedSection(nowMillis = 10)

        assertTrue(rows.filterIsInstance<BoardRow.Separator>().none { it.label == "Pinned" })
    }

    // -- the toggle --------------------------------------------------------

    private class FakeBoard(var failPins: Boolean = false) : HomeFeedRepository {
        val pinned = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun setPinned(
            notice: Notice,
            unitId: String,
            pinned: Boolean,
        ): ZillitResult<Unit> {
            if (failPins) return ZillitResult.Failure(ZillitError.NoConnection())
            this.pinned += Triple(notice.id, unitId, pinned)
            return ZillitResult.Success(Unit)
        }

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)),
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

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)
    }

    private fun viewModel(board: FakeBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `pinning flips at once and reaches the server`() = runTest(dispatcher) {
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.TogglePin("n1"))
        runCurrent()
        assertTrue(model.state.value.notices.single().isPinned, "optimistic, not round-trip")

        advanceUntilIdle()
        assertEquals(Triple("n1", "u1", true), board.pinned.single())
    }

    @Test
    fun `unpinning a pinned post sends false`() = runTest(dispatcher) {
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.TogglePin("n1"))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.TogglePin("n1"))
        advanceUntilIdle()

        assertEquals(listOf(true, false), board.pinned.map { it.third })
        assertTrue(!model.state.value.notices.single().isPinned)
    }

    @Test
    fun `a refused pin flips back with the error`() = runTest(dispatcher) {
        val board = FakeBoard(failPins = true)
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.TogglePin("n1"))
        advanceUntilIdle()

        assertTrue(!model.state.value.notices.single().isPinned, "reverted")
        assertNotNull(model.state.value.error)
    }
}
