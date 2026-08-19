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
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
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
 * The phones' rules on the board, enforced at the click: the thirty-minute
 * edit and delete window with the admin's exception for delete only, the
 * call sheet's "continuation or new?" and its `replacePreviousChats`, the
 * image reply as an ordinary media post, and the watermarked open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeBoardRulesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBoard(
        val units: List<HomeUnit>,
        var notices: List<Notice> = emptyList(),
        var stamped: NoticeAttachment? = null,
    ) : HomeFeedRepository {
        val posted = mutableListOf<Triple<String, UploadedNoticeMedia?, Boolean?>>()
        val deletedNotices = mutableListOf<String>()
        val deletedComments = mutableListOf<String>()
        var loads = 0
        var watermarkAsked = 0

        override suspend fun loadUnits() = ZillitResult.Success(units)

        override suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>> {
            loads++
            return ZillitResult.Success(notices)
        }

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> = postNotice(unitId, text, localId, attachment, location, null)

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
            replacePrevious: Boolean?,
        ): ZillitResult<Notice> {
            posted += Triple(text, attachment, replacePrevious)
            return ZillitResult.Success(
                Notice(
                    id = "server-${posted.size}",
                    body = text,
                    authorName = "You",
                    kind = attachment?.kind ?: NoticeKind.Text,
                    localId = localId,
                ),
            )
        }

        override suspend fun watermarkedAttachment(noticeId: String): ZillitResult<NoticeAttachment> {
            watermarkAsked++
            return stamped?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Http(500, "no stamp"))
        }

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String): ZillitResult<Unit> {
            deletedComments += commentId
            return ZillitResult.Success(Unit)
        }

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String): ZillitResult<Unit> {
            deletedNotices += noticeId
            return ZillitResult.Success(Unit)
        }

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
    }

    private val notices = HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = true)
    private val callSheet = HomeUnit(
        "cs",
        "call_sheet_tool",
        "call_sheet_label",
        canView = true,
        canPost = true,
        canDownload = true,
    )
    private val pdf = NoticeAttachment(
        media = "home/sheet.pdf",
        fileName = "sheet.pdf",
        contentSubtype = "pdf",
        bucket = "b",
        region = "r",
    )
    private val photo = PickedMedia("set.jpg", "image/jpeg", ByteArray(8))

    private fun post(
        id: String,
        author: String,
        createdAt: Long,
        kind: NoticeKind = NoticeKind.Text,
        attachment: NoticeAttachment? = null,
    ) = Notice(
        id = id,
        body = "post $id",
        authorName = author,
        authorId = author,
        createdAtMillis = createdAt,
        kind = kind,
        attachment = attachment,
        comments = listOf(NoticeComment(id = "$id-c", body = "reply", authorId = author, createdAtMillis = createdAt)),
    )

    private suspend fun upload(picked: PickedMedia, @Suppress("UNUSED_PARAMETER") p: (Int) -> Unit) =
        ZillitResult.Success(
            UploadedNoticeMedia(
                kind = picked.kind,
                media = "home/${picked.name}",
                bucket = "b",
                region = "r",
                fileName = picked.name,
                contentType = picked.contentType,
                sizeBytes = picked.bytes.size.toLong(),
            ),
        )

    private fun viewModel(
        board: FakeBoard,
        me: String = "me",
        admin: Boolean = false,
        now: Long = NOW,
        picked: PickedMedia? = null,
        defaultUnit: String? = null,
    ): HomeFeedViewModel {
        var n = 0
        return HomeFeedViewModel(
            repository = board,
            nowMillis = { now },
            newLocalId = { "local-${n++}" },
            isAdmin = { admin },
            currentUserId = { me },
            media = MediaCapture(pick = { picked }, upload = ::upload),
            defaultUnitId = { defaultUnit },
        ).also { it.onEvent(HomeFeedEvent.Load) }
    }

    // -- the landing tab -----------------------------------------------------

    @Test
    fun `the profile's default unit is the landing tab, else the first, and a switch is kept`() =
        runTest(dispatcher) {
            val units = listOf(notices, callSheet)
            val chosen = viewModel(FakeBoard(units), defaultUnit = "cs")
            advanceUntilIdle()
            assertEquals("cs", chosen.state.value.selectedUnit?.id)

            val unknown = viewModel(FakeBoard(units), defaultUnit = "not-mine")
            advanceUntilIdle()
            assertEquals("u1", unknown.state.value.selectedUnit?.id)

            // A tab the person picked since is theirs; a reload does not move them.
            chosen.onEvent(HomeFeedEvent.SelectUnit("u1"))
            chosen.onEvent(HomeFeedEvent.Load)
            advanceUntilIdle()
            assertEquals("u1", chosen.state.value.selectedUnit?.id)

            // A new production forgets it — and lands on the default again.
            chosen.onEvent(HomeFeedEvent.ProjectChanged)
            assertNull(chosen.state.value.selectedUnitId)
            assertEquals("", chosen.state.value.draft.text)
            chosen.onEvent(HomeFeedEvent.Load)
            advanceUntilIdle()
            assertEquals("cs", chosen.state.value.selectedUnit?.id)
        }

    // -- edit and delete, at the click ---------------------------------------

    @Test
    fun `an old post of mine cannot be edited, and the refusal says why`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "me", NOW - THIRTY_ONE_MINUTES)))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartEditNotice("n1"))

        assertNull(model.state.value.editingPost)
        assertEquals("You can't edit a message after 30 minutes.", model.state.value.error)
    }

    @Test
    /**
     * The server's rule for the edit route: the author alone — an admin on
     * someone else's post is refused there ("You do not have access to
     * this", found live), so the desktop refuses first. Same for a reply
     * (Android `canEditMessage`).
     */
    fun `an admin still cannot edit someone else's post`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "them", NOW)))
        val model = viewModel(board, me = "admin", admin = true)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartEditNotice("n1"))
        assertNull(model.state.value.editingPost)
        assertEquals("You can't edit other users' messages.", model.state.value.error)

        model.onEvent(HomeFeedEvent.StartEditComment("n1", "n1-c"))
        assertNull(model.state.value.editing)
        assertEquals("You can't edit other users' replies.", model.state.value.error)
    }

    @Test
    fun `an admin deletes anyone's post at any age`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "them", NOW - THIRTY_ONE_MINUTES)))
        val model = viewModel(board, me = "admin", admin = true)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DeleteNotice("n1"))
        advanceUntilIdle()

        assertEquals(listOf("n1"), board.deletedNotices)
        assertTrue(model.state.value.notices.isEmpty())
    }

    @Test
    fun `my own old post cannot be deleted, my own fresh one can`() = runTest(dispatcher) {
        val board = FakeBoard(
            listOf(notices),
            listOf(post("old", "me", NOW - THIRTY_ONE_MINUTES), post("fresh", "me", NOW - ONE_MINUTE)),
        )
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DeleteNotice("old"))
        advanceUntilIdle()
        assertTrue(board.deletedNotices.isEmpty())
        assertTrue(model.state.value.error.orEmpty().startsWith("You are allowed to edit or delete within 30 mins"))
        // The card stays: nothing was removed optimistically only to come back.
        assertEquals(setOf("old", "fresh"), model.state.value.notices.map { it.id }.toSet())

        model.onEvent(HomeFeedEvent.DeleteNotice("fresh"))
        advanceUntilIdle()
        assertEquals(listOf("fresh"), board.deletedNotices)

        // The same clock on a reply.
        model.onEvent(HomeFeedEvent.DeleteComment("old", "old-c"))
        advanceUntilIdle()
        assertTrue(board.deletedComments.isEmpty())
    }

    @Test
    fun `someone else's post is not mine to delete`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "them", NOW)))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DeleteNotice("n1"))
        advanceUntilIdle()

        assertTrue(board.deletedNotices.isEmpty())
        assertEquals("You have no permission to delete other users' messages.", model.state.value.error)
    }

    // -- the call sheet's question ---------------------------------------------

    @Test
    fun `attaching to a call sheet with posts asks first, and Continuation appends`() =
        runTest(dispatcher) {
            val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
            val board = FakeBoard(listOf(callSheet), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
            val model = viewModel(board, picked = doc)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.Attach)
           confirmPreview(model)
            confirmPreview(model)
            advanceUntilIdle()
            // The picker did not open; the question did.
            assertNull(model.state.value.draft.media)
            val prompt = assertNotNull(model.state.value.callSheetPrompt)
            assertTrue(!prompt.confirmingReplace)

            model.onEvent(HomeFeedEvent.CallSheetContinuation)
           confirmPreview(model)
            confirmPreview(model)
            advanceUntilIdle()
            assertNull(model.state.value.callSheetPrompt)
            assertEquals(doc, model.state.value.draft.media)
            assertEquals(false, model.state.value.draft.replacePrevious)

            model.onEvent(HomeFeedEvent.Send)
            advanceUntilIdle()
            assertEquals(false, board.posted.single().third)
        }

    @Test
    fun `New asks again, and only Yes replaces — then the board is read afresh`() = runTest(dispatcher) {
        val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
        val board = FakeBoard(listOf(callSheet), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board, picked = doc)
        advanceUntilIdle()
        val loadsBefore = board.loads

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        model.onEvent(HomeFeedEvent.CallSheetNew)
        assertTrue(model.state.value.callSheetPrompt?.confirmingReplace == true)
        // Nothing attached yet — "No" here must leave the board untouched.
        assertNull(model.state.value.draft.media)

        model.onEvent(HomeFeedEvent.CallSheetReplaceConfirmed)
        confirmPreview(model)
        assertEquals(true, model.state.value.draft.replacePrevious)

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()
        assertEquals(true, board.posted.single().third)
        // The server moved the rest to History; the board asks for it again.
        assertEquals(loadsBefore + 1, board.loads)
    }

    @Test
    fun `dismissing the question attaches nothing`() = runTest(dispatcher) {
        val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
        val board = FakeBoard(listOf(callSheet), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board, picked = doc)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        model.onEvent(HomeFeedEvent.CallSheetDismiss)
        advanceUntilIdle()

        assertNull(model.state.value.callSheetPrompt)
        assertNull(model.state.value.draft.media)
    }

    @Test
    fun `an empty call sheet, and any other unit, do not ask`() = runTest(dispatcher) {
        val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
        val empty = viewModel(FakeBoard(listOf(callSheet)), picked = doc)
        advanceUntilIdle()
        empty.onEvent(HomeFeedEvent.Attach)
        confirmPreview(empty)
        advanceUntilIdle()
        assertNull(empty.state.value.callSheetPrompt)
        assertEquals(doc, empty.state.value.draft.media)
        assertNull(empty.state.value.draft.replacePrevious)

        val plain = viewModel(FakeBoard(listOf(notices), listOf(post("n1", "them", NOW))), picked = photo)
        advanceUntilIdle()
        plain.onEvent(HomeFeedEvent.Attach)
        confirmPreview(plain)
        advanceUntilIdle()
        assertNull(plain.state.value.callSheetPrompt)
        assertEquals(photo, plain.state.value.draft.media)
    }

    @Test
    fun `a dropped file waits in the question and attaches with the answer`() = runTest(dispatcher) {
        val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
        val board = FakeBoard(listOf(callSheet), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.AttachDropped(doc))
        confirmPreview(model)
        assertEquals(doc, model.state.value.callSheetPrompt?.dropped)

        model.onEvent(HomeFeedEvent.CallSheetContinuation)
        confirmPreview(model)
        advanceUntilIdle()
        assertEquals(doc, model.state.value.draft.media)
    }

    @Test
    fun `removing the file forgets the answer`() = runTest(dispatcher) {
        val doc = PickedMedia("sheet.pdf", "application/pdf", ByteArray(4))
        val board = FakeBoard(listOf(callSheet), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board, picked = doc)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        model.onEvent(HomeFeedEvent.CallSheetNew)
        model.onEvent(HomeFeedEvent.CallSheetReplaceConfirmed)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.RemoveAttachment)

        assertNull(model.state.value.draft.replacePrevious)
    }

    // -- image reply -----------------------------------------------------------

    @Test
    fun `an image reply posts as a new picture and leaves the composer alone`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "them", NOW)))
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.DraftChanged("half a thought"))

        model.onEvent(HomeFeedEvent.PostImageReply(photo, "look here"))
        advanceUntilIdle()

        val (caption, uploaded, replace) = board.posted.single()
        assertEquals("look here", caption)
        assertEquals(NoticeKind.Image, uploaded?.kind)
        assertNull(replace)
        assertEquals("half a thought", model.state.value.draft.text)
        assertEquals(NoticeSendState.Sent, model.state.value.notices.last().sendState)
    }

    // -- opening files ---------------------------------------------------------

    @Test
    fun `a call sheet's PDF opens as its watermarked copy, or the original when there is none`() =
        runTest(dispatcher) {
            val stamped = pdf.copy(media = "home/sheet-stamped.pdf")
            val board = FakeBoard(
                listOf(callSheet),
                listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)),
                stamped = stamped,
            )
            val model = viewModel(board)
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.OpenAttachment("n1", pdf))
            advanceUntilIdle()
            assertEquals(1, board.watermarkAsked)
            assertEquals(stamped, model.state.value.pendingOpen?.attachment)

            model.onEvent(HomeFeedEvent.OpenHandled)
            assertNull(model.state.value.pendingOpen)

            board.stamped = null
            model.onEvent(HomeFeedEvent.OpenAttachment("n1", pdf))
            advanceUntilIdle()
            assertEquals(pdf, model.state.value.pendingOpen?.attachment)
        }

    @Test
    fun `other files open as themselves without asking the server`() = runTest(dispatcher) {
        val board = FakeBoard(listOf(notices), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.OpenAttachment("n1", pdf))
        advanceUntilIdle()

        assertEquals(0, board.watermarkAsked)
        assertEquals(pdf, model.state.value.pendingOpen?.attachment)
    }

    @Test
    fun `Download needs the unit's download right, reading a file does not`() = runTest(dispatcher) {
        val noRights = notices.copy(canDownload = false)
        val board = FakeBoard(listOf(noRights), listOf(post("n1", "them", NOW, NoticeKind.Document, pdf)))
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.OpenAttachment("n1", pdf, download = true))
        advanceUntilIdle()
        assertNull(model.state.value.pendingOpen)
        assertTrue(model.state.value.error.orEmpty().contains("downloading rights"))

        model.onEvent(HomeFeedEvent.OpenAttachment("n1", pdf))
        advanceUntilIdle()
        assertEquals(pdf, model.state.value.pendingOpen?.attachment)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ONE_MINUTE = 60_000L
        const val THIRTY_ONE_MINUTES = 31 * ONE_MINUTE
    }

    /**
     * Drains the picker, then answers the preview the way a person does:
     * Send with no caption. Attaching now lands in the preview dialog first
     * (the phones' gallery viewer), so a test that wants the file *in the
     * draft* has to walk both steps.
     */
    private fun TestScope.confirmPreview(model: HomeFeedViewModel) {
        advanceUntilIdle()
        model.currentState.pendingPreview?.let { pending ->
            model.onEvent(HomeFeedEvent.PreviewSent(pending.picked, ""))
        }
        advanceUntilIdle()
    }
}
