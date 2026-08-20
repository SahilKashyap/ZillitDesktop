package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
 * Posting a file to the board.
 *
 * The upload is the long, failure-prone half, so the behaviours pinned here
 * are about what happens around it: a failed upload must not post, a failed
 * post must not re-upload, and an abandoned pick must not send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeAttachTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val photo = PickedMedia("set.jpg", "image/jpeg", ByteArray(8))

    private class FakeBoard(var failPosts: Boolean = false) : HomeFeedRepository {
        val posted = mutableListOf<Pair<String, UploadedNoticeMedia?>>()
        val postedLocations = mutableListOf<GeoPoint>()
        val forwarded = mutableListOf<Pair<String, Notice>>()

        override suspend fun forwardNotice(
            unitId: String,
            notice: Notice,
            localId: String,
        ): ZillitResult<Unit> {
            if (failPosts) return ZillitResult.Failure(ZillitError.NoConnection())
            forwarded += unitId to notice
            return ZillitResult.Success(Unit)
        }

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
        ): ZillitResult<Notice> {
            if (failPosts) return ZillitResult.Failure(ZillitError.NoConnection())
            posted += text to attachment
            location?.let { postedLocations += it }
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

        override suspend fun postComment(
            noticeId: String,
            unitId: String,
            text: String,
        ) = ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(
            noticeId: String,
            commentId: String,
            text: String,
        ) = ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) =
            ZillitResult.Success(com.zillit.desktop.feature.home.domain.ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

    }

    private class FakeUploads(var fail: Boolean = false) {
        var uploads = 0
            private set

        suspend fun upload(
            picked: PickedMedia,
            @Suppress("UNUSED_PARAMETER") onProgress: (Int) -> Unit,
        ): ZillitResult<UploadedNoticeMedia> {
            uploads++
            if (fail) return ZillitResult.Failure(ZillitError.NoConnection())
            return ZillitResult.Success(
                UploadedNoticeMedia(
                    kind = picked.kind,
                    media = "chat/${picked.name}",
                    bucket = "zillit-test",
                    region = "us-east-1",
                    fileName = picked.name,
                    contentType = picked.contentType,
                    sizeBytes = picked.bytes.size.toLong(),
                ),
            )
        }
    }

    private fun viewModel(
        board: FakeBoard = FakeBoard(),
        uploads: FakeUploads = FakeUploads(),
        picked: PickedMedia? = photo,
    ): HomeFeedViewModel {
        var n = 0
        return HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-${n++}" },
            isAdmin = { false },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                pick = { listOfNotNull(picked) },
                upload = uploads::upload,
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
    }

    // -- picking -----------------------------------------------------------

    @Test
    fun `a confirmed pick posts at once, leaving the composer alone`() = runTest(dispatcher) {
        // The preview dialog's Send is the send — the phones' gallery viewer.
        // Parking the file in the composer instead held every later message
        // hostage to the upload (QA #7).
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()

        assertNull(model.state.value.draft.media)
        assertEquals("chat/set.jpg", board.posted.single().second?.media)
    }

    @Test
    fun `several picked files become several posts, caption on the first`() = runTest(dispatcher) {
        // QA #6: the wire takes one attachment per message, so the phones
        // send a multi-pick as a burst of messages. The caption reads once,
        // not stuttered under every file.
        val board = FakeBoard()
        val uploads = FakeUploads()
        var n = 0
        val files = listOf(
            PickedMedia("a.jpg", "image/jpeg", ByteArray(4)),
            PickedMedia("b.jpg", "image/jpeg", ByteArray(4)),
            PickedMedia("c.jpg", "image/jpeg", ByteArray(4)),
        )
        val model = HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-${n++}" },
            isAdmin = { false },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                pick = { files },
                upload = uploads::upload,
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model, caption = "three from the set")
        advanceUntilIdle()

        assertEquals(3, uploads.uploads)
        assertEquals(listOf("three from the set", "", ""), board.posted.map { it.first })
        assertEquals(
            listOf("chat/a.jpg", "chat/b.jpg", "chat/c.jpg"),
            board.posted.map { it.second?.media },
        )
    }

    @Test
    fun `a cancelled picker changes nothing`() = runTest(dispatcher) {
        val model = viewModel(picked = null)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()

        assertNull(model.state.value.draft.media)
    }

    @Test
    fun `the composer's text stays its own when a file posts with a caption`() = runTest(dispatcher) {
        // QA #12: words typed under the composer must not ride along with a
        // file sent from the preview dialog — the dialog's caption is that
        // post's whole message.
        val board = FakeBoard()
        val model = viewModel(board)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.DraftChanged("crew call moved to 6"))
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model, caption = "unit photo")
        advanceUntilIdle()

        assertEquals("crew call moved to 6", model.state.value.draft.text)
        assertEquals("unit photo", board.posted.single().first)
    }

    @Test
    fun `a file alone is sendable, text alone is sendable, neither is not`() {
        val bare = com.zillit.desktop.feature.home.domain.NoticeDraft()
        assertTrue(!bare.canSend)
        assertTrue(bare.copy(text = "words").canSend)
        assertTrue(bare.copy(media = photo).canSend)
    }

    // -- sending -----------------------------------------------------------

    @Test
    fun `send uploads first and posts what storage returned`() = runTest(dispatcher) {
        val board = FakeBoard()
        val uploads = FakeUploads()
        val model = viewModel(board, uploads)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model, caption = "day 12 call sheet")
        advanceUntilIdle()

        assertEquals(1, uploads.uploads)
        val (caption, attachment) = board.posted.single()
        assertEquals("day 12 call sheet", caption)
        assertEquals("chat/set.jpg", attachment?.media)
        assertEquals(NoticeKind.Image, attachment?.kind)
    }

    @Test
    fun `a failed upload leaves the post retryable, not sent bare`() = runTest(dispatcher) {
        // Posting the caption without its file would look sent while the file
        // the caption describes never went anywhere.
        val board = FakeBoard()
        val uploads = FakeUploads(fail = true)
        val model = viewModel(board, uploads)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertTrue(board.posted.isEmpty())
        assertEquals(
            NoticeSendState.Failed,
            model.state.value.notices.single().sendState,
        )
    }

    @Test
    fun `retry after a failed UPLOAD uploads the file again`() = runTest(dispatcher) {
        // The tester's blank post: with nothing cached from the failed
        // upload, a retry that forgets the file posts the empty caption —
        // a blank card on everyone's board.
        val board = FakeBoard()
        val uploads = FakeUploads(fail = true)
        val model = viewModel(board, uploads)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        uploads.fail = false
        val localId = model.state.value.notices.single().localId!!
        model.onEvent(HomeFeedEvent.Retry(localId))
        advanceUntilIdle()

        assertEquals(2, uploads.uploads, "the retry must re-upload the file")
        val (_, attachment) = board.posted.single()
        assertEquals("chat/set.jpg", attachment?.media, "the post must carry its file")
    }

    @Test
    fun `a media post with no file to upload fails rather than posting blank`() = runTest(dispatcher) {
        // Belt to the braces above: even if the picked file is somehow gone,
        // a media post must never degrade into an empty text post.
        val board = FakeBoard()
        val uploads = FakeUploads(fail = true)
        val model = viewModel(board, uploads)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        // Simulate the pre-fix state: no remembered file for the retry.
        uploads.fail = false
        val localId = model.state.value.notices.single().localId!!
        model.forgetPickedFor(localId)
        model.onEvent(HomeFeedEvent.Retry(localId))
        advanceUntilIdle()

        assertTrue(board.posted.isEmpty(), "a blank post reached the board")
        assertEquals(NoticeSendState.Failed, model.state.value.notices.single().sendState)
    }

    @Test
    fun `retry after a failed post does not upload again`() = runTest(dispatcher) {
        // The bytes are already in storage. A second copy per retry fills the
        // production's bucket with orphans nobody can see or delete.
        val board = FakeBoard(failPosts = true)
        val uploads = FakeUploads()
        val model = viewModel(board, uploads)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()
        assertEquals(1, uploads.uploads)

        board.failPosts = false
        val localId = model.state.value.notices.single().localId!!
        model.onEvent(HomeFeedEvent.Retry(localId))
        advanceUntilIdle()

        assertEquals(1, uploads.uploads, "retry must reuse the stored upload")
        assertEquals("chat/set.jpg", board.posted.single().second?.media)
    }

    @Test
    fun `the composer clears once the send is underway`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        model.onEvent(HomeFeedEvent.DraftChanged("caption"))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertNull(model.state.value.draft.media)
        assertEquals("", model.state.value.draft.text)
    }

    // -- the bar, and who may use it ---------------------------------------
    //
    // iOS shows the bar unconditionally and asks about rights when the user
    // acts; hiding it for rights also raced the profile fetch, which is how
    // the desktop bar went missing entirely.

    @Test
    fun `the bar shows even without posting rights`() = runTest(dispatcher) {
        val model = readOnlyViewModel(admin = false)
        advanceUntilIdle()

        assertTrue(model.state.value.showsComposer, "the bar must not hide for rights")
        assertTrue(!model.state.value.canCompose, "but a post would be refused")
    }

    @Test
    fun `sending without rights explains instead of posting`() = runTest(dispatcher) {
        val board = ReadOnlyBoard()
        val model = readOnlyViewModel(board = board, admin = false)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.DraftChanged("let me in"))
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertTrue(board.posted.isEmpty())
        assertTrue(model.state.value.notices.isEmpty(), "no optimistic card either")
        assertTrue(model.state.value.error.orEmpty().contains("posting rights"))
    }

    @Test
    fun `an admin whose flag arrives late can still send`() = runTest(dispatcher) {
        // The profile loads in parallel with the units. Rights are read at
        // send time, live — not from a value captured before the answer came.
        var isAdmin = false
        val board = ReadOnlyBoard()
        val model = readOnlyViewModel(board = board, adminNow = { isAdmin })
        advanceUntilIdle()

        isAdmin = true
        model.onEvent(HomeFeedEvent.DraftChanged("go"))
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals(1, board.posted.size)
    }

    @Test
    fun `the call sheet unit takes documents only`() = runTest(dispatcher) {
        // iOS's attach sheet offers nothing but documents there.
        val board = CallSheetBoard()
        var n = 0
        val model = HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-" + n++ },
            isAdmin = { true },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                pick = { listOf(photo) },
                upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()

        assertNull(model.state.value.draft.media)
        assertTrue(model.state.value.error.orEmpty().contains("documents"))
    }

    private class ReadOnlyBoard : HomeFeedRepository {
        val posted = mutableListOf<String>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = false)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> {
            posted += text
            return ZillitResult.Success(Notice(id = "s", body = text, authorName = "You"))
        }

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

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)
    }

    private class CallSheetBoard : HomeFeedRepository {
        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(
                HomeUnit(
                    "u1", "call_sheet_tool", "call_sheet_label",
                    canView = true, canPost = true,
                ),
            ),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ) = ZillitResult.Success(Notice(id = "s", body = text, authorName = "You"))

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

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)
    }

    private fun readOnlyViewModel(
        board: ReadOnlyBoard = ReadOnlyBoard(),
        admin: Boolean = false,
        adminNow: (() -> Boolean)? = null,
    ): HomeFeedViewModel {
        var n = 0
        return HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-" + n++ },
            isAdmin = adminNow ?: { admin },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                pick = { listOf(photo) },
                upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
    }

    // -- video posters ------------------------------------------------------

    @Test
    fun `a picked video gains its poster frame when extraction lands`() =
        runTest(dispatcher) {
            val video = PickedMedia("take.mp4", "video/mp4", ByteArray(16))
            var n = 0
            val model = HomeFeedViewModel(
                repository = FakeBoard(),
                nowMillis = { 1000 },
                newLocalId = { "local-" + n++ },
                isAdmin = { true },
                media = com.zillit.desktop.feature.home.ui.MediaCapture(
                    pick = { listOf(video) },
                    upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
                    videoThumbnail = { picked ->
                        picked.copy(
                            thumbnailBytes = ByteArray(4),
                            thumbnailWidth = 480,
                            thumbnailHeight = 270,
                        )
                    },
                ),
            ).also { it.onEvent(HomeFeedEvent.Load) }
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.Attach)
            confirmPreview(model)
            advanceUntilIdle()

            // The poster travels on the posted card, ready before the upload.
            val card = model.state.value.notices.single().attachment
            assertEquals(480, card?.widthPx)
            assertEquals(4, card?.localBytes?.size)
        }

    @Test
    fun `an attached location sends as one, map image or not`() = runTest(dispatcher) {
        val board = FakeBoard()
        var n = 0
        val model = HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-" + n++ },
            isAdmin = { true },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                upload = { picked, _ ->
                    ZillitResult.Success(
                        UploadedNoticeMedia(
                            kind = picked.kind,
                            media = "chat/map.png",
                            bucket = "b", region = "r",
                            fileName = picked.name,
                            contentType = picked.contentType,
                            sizeBytes = picked.bytes.size.toLong(),
                        ),
                    )
                },
                staticMap = { PickedMedia("location-map.png", "image/png", ByteArray(9)) },
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()

        val point = com.zillit.desktop.feature.home.domain.GeoPoint(34.05, -118.24)
        model.onEvent(HomeFeedEvent.AttachLocation(point))
        advanceUntilIdle()

        // The map image joined the draft.
        assertEquals("location-map.png", model.state.value.draft.media?.name)
        assertEquals(point, model.state.value.draft.location)

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        // The post left with its point; the optimistic card was a Location.
        assertEquals(point, board.postedLocations.single())
    }

    @Test
    fun `a picked pdf gains a poster too`() = runTest(dispatcher) {
        // The web's pair: videos get a frame, PDFs their first page.
        val pdf = PickedMedia("callsheet.pdf", "application/pdf", ByteArray(16))
        var n = 0
        val model = HomeFeedViewModel(
            repository = FakeBoard(),
            nowMillis = { 1000 },
            newLocalId = { "local-" + n++ },
            isAdmin = { true },
            media = com.zillit.desktop.feature.home.ui.MediaCapture(
                pick = { listOf(pdf) },
                upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
                videoThumbnail = { picked -> picked.copy(thumbnailBytes = ByteArray(8)) },
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.Attach)
        confirmPreview(model)
        advanceUntilIdle()

        assertEquals(8, model.state.value.notices.single().attachment?.localBytes?.size)
    }

    @Test
    fun `a poster arriving after the file was removed does not resurrect it`() =
        runTest(dispatcher) {
            // The user removed the chip while frames were still decoding; the
            // late poster must not reattach a file they discarded.
            val video = PickedMedia("take.mp4", "video/mp4", ByteArray(16))
            var n = 0
            var releasePoster: (() -> Unit)? = null
            val model = HomeFeedViewModel(
                repository = FakeBoard(),
                nowMillis = { 1000 },
                newLocalId = { "local-" + n++ },
                isAdmin = { true },
                media = com.zillit.desktop.feature.home.ui.MediaCapture(
                    pick = { listOf(video) },
                    upload = { _, _ -> ZillitResult.Failure(ZillitError.NoConnection()) },
                    videoThumbnail = { picked ->
                        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                            releasePoster = {
                                cont.resume(picked.copy(thumbnailBytes = ByteArray(4))) { _, _, _ -> }
                            }
                        }
                    },
                ),
            ).also { it.onEvent(HomeFeedEvent.Load) }
            advanceUntilIdle()

            model.onEvent(HomeFeedEvent.Attach)
            confirmPreview(model)
            runCurrent()
            model.onEvent(HomeFeedEvent.RemoveAttachment)
            runCurrent()

            releasePoster?.invoke()
            advanceUntilIdle()

            assertNull(model.state.value.draft.media)
        }

    /**
     * Drains the picker, then answers the preview the way a person does:
     * Send, with the given caption. The dialog's Send IS the send — the post
     * leaves immediately with the caption as its message, and the composer
     * below is never touched.
     */
    private fun TestScope.confirmPreview(model: HomeFeedViewModel, caption: String = "") {
        advanceUntilIdle()
        model.currentState.pendingPreview?.let { pending ->
            model.onEvent(HomeFeedEvent.PreviewSent(pending.files, caption))
        }
        advanceUntilIdle()
    }
}
