package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.MediaSendTracker
import com.zillit.desktop.core.media.SendStatus
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.BoardPin
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

/**
 * A board post with a file stays with the production it was sent in. Before
 * the pin, a post whose upload outlived a project switch was written to the
 * NEW production — the old production's unit, under the new one's headers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinnedBoardPostTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Board : HomeFeedRepository {
        val plain = mutableListOf<String>()
        val pinned = mutableListOf<Pair<CallOptions, String>>()

        private fun saved(text: String, localId: String) = ZillitResult.Success(
            Notice(id = "s-$localId", body = text, authorName = "You", kind = NoticeKind.Video, localId = localId),
        )

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> {
            plain += unitId
            return saved(text, localId)
        }

        override suspend fun postNoticeAs(
            options: CallOptions,
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
            replacePrevious: Boolean?,
            replaceChatId: String?,
        ): ZillitResult<Notice> {
            pinned += options to unitId
            return saved(text, localId)
        }

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) = ZillitResult.Success(Unit)
        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "General", canView = true, canPost = true)),
        )
        override suspend fun loadNotices(unitId: String, beforeMillis: Long) = ZillitResult.Success(emptyList<Notice>())
        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())
        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)
        override suspend fun deleteComment(noticeId: String, commentId: String) = ZillitResult.Success(Unit)
        override suspend fun editNotice(noticeId: String, text: String) = ZillitResult.Success(null as Notice?)
        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)
        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) = ZillitResult.Success(Unit)
        override suspend fun readBy(noticeId: String) =
            ZillitResult.Success(com.zillit.desktop.feature.home.domain.ReadBy())
        override suspend fun notifyUnread(unitId: String, noticeId: String) = ZillitResult.Success(Unit)
    }

    @Test
    fun `a post that outlives a project switch is written to the production it was sent in`() = runTest(dispatcher) {
        val board = Board()
        val tracker = MediaSendTracker()
        val bytesArrive = CompletableDeferred<Unit>()
        var open = "A"
        val video = PickedMedia("rushes.mov", "video/quicktime", ByteArray(8))
        val pinA = BoardPin(
            projectId = "A",
            projectName = "Production A",
            options = CallOptions(projectId = "A", userId = "me-in-A"),
            upload = { picked, onProgress ->
                onProgress(50)
                bytesArrive.await()
                ZillitResult.Success(
                    UploadedNoticeMedia(
                        kind = picked.kind, media = "s3/${picked.name}", bucket = "b", region = "r",
                        fileName = picked.name, contentType = picked.contentType, sizeBytes = 8,
                    ),
                )
            },
        )
        val model = HomeFeedViewModel(
            repository = board,
            nowMillis = { 1000 },
            newLocalId = { "local-1" },
            isAdmin = { false },
            media = MediaCapture(
                pick = { listOf(video) },
                pin = { pinA.takeIf { open == "A" } },
                currentProjectId = { open },
                tracker = tracker,
            ),
        ).also { it.onEvent(HomeFeedEvent.Load) }
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.PreviewSent(listOf(video), caption = "day 12"))
        advanceUntilIdle()
        assertEquals(50, tracker.sends.value.single().progress)
        assertEquals("General", tracker.sends.value.single().destination)

        open = "B"
        model.onEvent(HomeFeedEvent.ProjectChanged)
        advanceUntilIdle()
        bytesArrive.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(CallOptions(projectId = "A", userId = "me-in-A") to "u1"), board.pinned)
        assertTrue(board.plain.isEmpty(), "never posted through the headers that now name B")
        assertEquals(SendStatus.Done, tracker.sends.value.single().status)
        assertTrue(model.state.value.notices.isEmpty(), "B's board never shows A's post")
    }
}
