package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forwarding a post to another unit.
 *
 * The behaviours pinned are the web's rules: rights belong to the *target*
 * unit and are checked when one is chosen, a call sheet takes only text and
 * documents, and a copy — never the original — is what travels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardNoticeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val photoPost = Notice(
        id = "n1",
        body = "set photo",
        authorName = "Sam",
        kind = NoticeKind.Image,
        attachment = NoticeAttachment(media = "chat/set.jpg", fileName = "set.jpg"),
    )

    private class FakeBoard(
        var failForwards: Boolean = false,
        val units: List<HomeUnit> = listOf(
            HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true),
            HomeUnit("u2", "camera_tool", "camera_label", canView = true, canPost = true),
            HomeUnit("u3", "sound_tool", "sound_label", canView = true, canPost = false),
            HomeUnit("u4", "call_sheet", "call_sheet_label", canView = true, canPost = true),
        ),
        var notices: List<Notice> = emptyList(),
    ) : HomeFeedRepository {
        val forwarded = mutableListOf<Pair<String, Notice>>()

        override suspend fun loadUnits() = ZillitResult.Success(units)

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(notices)

        override suspend fun forwardNotice(
            unitId: String,
            notice: Notice,
            localId: String,
        ): ZillitResult<Unit> {
            if (failForwards) return ZillitResult.Failure(ZillitError.NoConnection())
            forwarded += unitId to notice
            return ZillitResult.Success(Unit)
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

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) =
            ZillitResult.Success(com.zillit.desktop.feature.home.domain.ReadBy())

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

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
    fun `forwarding posts a copy to the chosen unit and says so`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(photoPost))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        assertEquals("n1", model.state.value.forwarding?.id)

        model.onEvent(HomeFeedEvent.ForwardTo("u2"))
        advanceUntilIdle()

        assertEquals("u2" to photoPost, board.forwarded.single())
        assertNull(model.state.value.forwarding, "the picker closes with the choice")
        assertNotNull(model.state.value.info, "a forward the user cannot see needs a receipt")
        assertNull(model.state.value.error)
    }

    @Test
    fun `a unit without posting rights refuses at choose time`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(photoPost))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardTo("u3"))
        advanceUntilIdle()

        assertTrue(board.forwarded.isEmpty())
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `the call sheet takes text and documents, not photos`() = runTest(dispatcher) {
        val board = FakeBoard(notices = listOf(photoPost))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardTo("u4"))
        advanceUntilIdle()

        assertTrue(board.forwarded.isEmpty())
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a document may go to the call sheet`() = runTest(dispatcher) {
        val schedule = photoPost.copy(
            id = "n2",
            kind = NoticeKind.Document,
            attachment = NoticeAttachment(media = "chat/day7.pdf", fileName = "day7.pdf"),
        )
        val board = FakeBoard(notices = listOf(schedule))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n2"))
        model.onEvent(HomeFeedEvent.ForwardTo("u4"))
        advanceUntilIdle()

        assertEquals("u4", board.forwarded.single().first)
    }

    @Test
    fun `a post still sending has no server copy to forward`() = runTest(dispatcher) {
        val pending = photoPost.copy(id = "n5", sendState = NoticeSendState.Sending)
        val board = FakeBoard(notices = listOf(pending))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n5"))

        assertNull(model.state.value.forwarding, "the picker never opens")
    }

    @Test
    fun `a failed forward reports instead of pretending`() = runTest(dispatcher) {
        val board = FakeBoard(failForwards = true, notices = listOf(photoPost))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartForward("n1"))
        model.onEvent(HomeFeedEvent.ForwardTo("u2"))
        advanceUntilIdle()

        assertNotNull(model.state.value.error)
        assertNull(model.state.value.info)
    }
}
