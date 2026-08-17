package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.domain.AudioRecorder
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.RecordedAudio
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Voice messages: the microphone half.
 *
 * The recording lands in the draft as an ordinary file, so everything after
 * stop — upload, post, retry — is the attachment pipeline already under test.
 * What is pinned here is the recording lifecycle itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMessageTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeMic(
        var failStart: Boolean = false,
    ) : AudioRecorder {
        var started = 0
            private set
        var cancelled = 0
            private set

        override suspend fun start(): ZillitResult<Unit> {
            if (failStart) {
                return ZillitResult.Failure(
                    ZillitError.Storage("no mic", "The microphone could not be opened."),
                )
            }
            started++
            return ZillitResult.Success(Unit)
        }

        override suspend fun stop(): ZillitResult<RecordedAudio> =
            ZillitResult.Success(RecordedAudio(ByteArray(64), durationMillis = 7_000))

        override fun cancel() {
            cancelled++
        }
    }

    private class Board(val canPost: Boolean = true) : HomeFeedRepository {
        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(
                HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = canPost),
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

    private fun viewModel(
        mic: FakeMic = FakeMic(),
        board: Board = Board(),
        admin: Boolean = false,
    ) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { admin },
        media = com.zillit.desktop.feature.home.ui.MediaCapture(
            recorder = mic,
            newRecordingName = { "voice-message.wav" },
        ),
    ).also { it.onEvent(HomeFeedEvent.Load) }

    @Test
    fun `recording runs a clock and stop parks a wav in the draft`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartRecording)
        runCurrent()
        assertEquals(0, model.state.value.recordingSeconds)

        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(3, model.state.value.recordingSeconds)

        model.onEvent(HomeFeedEvent.StopRecording)
        advanceUntilIdle()

        assertNull(model.state.value.recordingSeconds, "the clock stops")
        val media = model.state.value.draft.media
        assertEquals("voice-message.wav", media?.name)
        assertEquals("audio/wav", media?.contentType)
        assertEquals(7_000L, media?.durationMillis)
        assertEquals(NoticeKind.Audio, media?.kind)
    }

    @Test
    fun `cancel discards the recording and attaches nothing`() = runTest(dispatcher) {
        val mic = FakeMic()
        val model = viewModel(mic)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartRecording)
        runCurrent()
        model.onEvent(HomeFeedEvent.CancelRecording)
        advanceUntilIdle()

        assertNull(model.state.value.recordingSeconds)
        assertNull(model.state.value.draft.media)
        assertEquals(1, mic.cancelled)
    }

    @Test
    fun `a microphone that will not open explains itself`() = runTest(dispatcher) {
        val model = viewModel(FakeMic(failStart = true))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartRecording)
        advanceUntilIdle()

        assertNull(model.state.value.recordingSeconds)
        assertTrue(model.state.value.error.orEmpty().contains("microphone"))
    }

    @Test
    fun `no rights, no recording`() = runTest(dispatcher) {
        // The same action-time gate as the paperclip; iOS asks at the tap.
        val mic = FakeMic()
        val model = viewModel(mic, Board(canPost = false))
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartRecording)
        advanceUntilIdle()

        assertEquals(0, mic.started)
        assertTrue(model.state.value.error.orEmpty().contains("posting rights"))
    }

    @Test
    fun `a second start while recording is ignored`() = runTest(dispatcher) {
        val mic = FakeMic()
        val model = viewModel(mic)
        advanceUntilIdle()

        model.onEvent(HomeFeedEvent.StartRecording)
        runCurrent()
        model.onEvent(HomeFeedEvent.StartRecording)
        runCurrent()

        assertEquals(1, mic.started)

        // The ticker must not outlive the test.
        model.onEvent(HomeFeedEvent.CancelRecording)
        advanceUntilIdle()
    }
}
