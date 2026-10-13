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
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.DeletePrompt
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.SelectionAction
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
 * Selection mode on the board — the web's (`UnitChatMessage` arms it from a
 * post's menu, `UnitChatMessageBox` draws the bar and `openCommonPopup` asks
 * before `deleteUnitChatMessages` sends):
 *
 * - a post's menu Delete ticks it and opens the mode; nothing is deleted;
 * - a post this person may not delete never enters the mode, and says why;
 * - the bar's Delete asks, and the answer withdraws the whole batch in ONE
 *   call, which is what `PUT home/chat/delete/chats` takes;
 * - a reply is asked about on its own, as the web asks about a comment;
 * - a refused batch comes back onto the board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSelectionDeleteTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBoard(
        var notices: List<Notice> = emptyList(),
        var refuse: Boolean = false,
    ) : HomeFeedRepository {
        /** One entry per request — the point of the batch is that there is only one. */
        val deleteCalls = mutableListOf<List<String>>()
        val deletedComments = mutableListOf<Pair<String, String>>()

        override suspend fun loadUnits() = ZillitResult.Success(listOf(UNIT))

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(notices)

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> = ZillitResult.Failure(ZillitError.NoConnection())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
            replacePrevious: Boolean?,
            replaceChatId: String?,
        ): ZillitResult<Notice> = ZillitResult.Failure(ZillitError.NoConnection())

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String): ZillitResult<Unit> {
            deletedComments += noticeId to commentId
            return ZillitResult.Success(Unit)
        }

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String): ZillitResult<Unit> =
            deleteNotices(listOf(noticeId))

        override suspend fun deleteNotices(noticeIds: List<String>): ZillitResult<Unit> {
            deleteCalls += noticeIds
            return if (refuse) ZillitResult.Failure(ZillitError.NoConnection()) else ZillitResult.Success(Unit)
        }

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
    }

    @Test
    fun `the menu's Delete opens selection mode with that post ticked`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me"), post("n2", "me")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))

        val open = model.state.value.selection
        assertEquals(SelectionAction.Delete, open?.action)
        assertEquals(setOf("n1"), open?.ids)
        assertTrue(board.deleteCalls.isEmpty(), "nothing was sent")
        assertEquals(2, model.state.value.notices.size)
    }

    @Test
    fun `a post this person may not delete never enters the mode`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "them")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))

        assertNull(model.state.value.selection)
        assertEquals(
            "You have no permission to delete other users' messages.",
            model.state.value.error,
        )
    }

    @Test
    fun `ticking adds, un-ticking removes, and a post that is not mine stays out`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me"), post("n2", "me"), post("n3", "them")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))
        model.onEvent(HomeFeedEvent.ToggleSelected("n2"))
        assertEquals(setOf("n1", "n2"), model.state.value.selection?.ids)

        model.onEvent(HomeFeedEvent.ToggleSelected("n3"))
        assertEquals(setOf("n1", "n2"), model.state.value.selection?.ids, "theirs stayed out")

        model.onEvent(HomeFeedEvent.ToggleSelected("n1"))
        assertEquals(setOf("n2"), model.state.value.selection?.ids)
    }

    @Test
    fun `the bar asks, and the answer withdraws the batch in one call`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me"), post("n2", "me"), post("n3", "me")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))
        model.onEvent(HomeFeedEvent.ToggleSelected("n3"))
        model.onEvent(HomeFeedEvent.AskDeleteSelected)
        advanceUntilIdle()

        val prompt = model.state.value.deletePrompt
        assertEquals(listOf("n1", "n3"), (prompt as? DeletePrompt.Posts)?.ids?.sorted())
        assertTrue(board.deleteCalls.isEmpty(), "the question sends nothing")

        model.onEvent(HomeFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(1, board.deleteCalls.size, "one request, not one per post")
        assertEquals(listOf("n1", "n3"), board.deleteCalls.single().sorted())
        assertEquals(listOf("n2"), model.state.value.notices.map { it.id })
        assertNull(model.state.value.selection, "the mode closed with the delete")
        assertNull(model.state.value.deletePrompt)
    }

    @Test
    fun `No keeps the posts and the ticks`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))
        model.onEvent(HomeFeedEvent.AskDeleteSelected)
        model.onEvent(HomeFeedEvent.CancelDeletePrompt)
        advanceUntilIdle()

        assertNull(model.state.value.deletePrompt)
        assertEquals(setOf("n1"), model.state.value.selection?.ids, "still ticked")
        assertTrue(board.deleteCalls.isEmpty())
        assertEquals(1, model.state.value.notices.size)
    }

    @Test
    fun `a refused batch comes back onto the board`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me"), post("n2", "me")), refuse = true)
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))
        model.onEvent(HomeFeedEvent.ToggleSelected("n2"))
        model.onEvent(HomeFeedEvent.AskDeleteSelected)
        model.onEvent(HomeFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(setOf("n1", "n2"), model.state.value.notices.map { it.id }.toSet())
        assertTrue(model.state.value.error != null, "the refusal is said out loud")
    }

    @Test
    fun `a reply is asked about on its own`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.AskDeleteComment("n1", "n1-c"))
        assertEquals(DeletePrompt.Reply("n1", "n1-c"), model.state.value.deletePrompt)
        assertNull(model.state.value.selection, "a reply does not open selection mode")

        model.onEvent(HomeFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals("n1" to "n1-c", board.deletedComments.single())
        assertTrue(board.deleteCalls.isEmpty(), "the post itself stays")
    }

    @Test
    fun `switching unit forgets the mode`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(post("n1", "me")))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.SelectForDelete("n1"))
        model.onEvent(HomeFeedEvent.AskDeleteSelected)
        model.onEvent(HomeFeedEvent.SelectUnit(UNIT.id))
        advanceUntilIdle()

        assertNull(model.state.value.selection)
        assertNull(model.state.value.deletePrompt)
    }

    private fun viewModel(board: FakeBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { NOW },
        newLocalId = { "local-1" },
        isAdmin = { false },
        currentUserId = { "me" },
    ).also { it.onEvent(HomeFeedEvent.Load) }

    private fun post(id: String, author: String, createdAt: Long = NOW - ONE_MINUTE) = Notice(
        id = id,
        body = "post $id",
        authorName = author,
        authorId = author,
        createdAtMillis = createdAt,
        kind = NoticeKind.Text,
        attachment = null as NoticeAttachment?,
        comments = listOf(
            NoticeComment(id = "$id-c", body = "reply", authorId = author, createdAtMillis = createdAt),
        ),
    )

    private companion object {
        val UNIT = HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)
        const val NOW = 1_800_000_000_000L
        const val ONE_MINUTE = 60_000L
    }
}
