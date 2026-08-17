package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.MediaPeer
import com.zillit.desktop.feature.calls.ui.CallStageKind
import com.zillit.desktop.feature.calls.ui.CallUiState
import com.zillit.desktop.feature.calls.ui.projectCallUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The projection, including the latch that decides which stage is up. */
class CallProjectionTest {

    private val audioCall = CallSession(callUuid = "u1", selfUserId = "me")
    private val videoCall = audioCall.copy(hasVideo = true)

    private fun project(
        previous: CallUiState = CallUiState(),
        session: CallSession? = audioCall,
        media: CallMedia = CallMedia(),
        micMuted: Boolean = false,
        cameraOn: Boolean = false,
    ) = projectCallUi(previous, session, media, micMuted, cameraOn, "Me")

    @Test
    fun `an audio call draws its own faces`() {
        assertEquals(CallStageKind.Avatars, project().stage)
    }

    @Test
    fun `a video call hosts the engine's surface`() {
        assertEquals(CallStageKind.Video, project(session = videoCall).stage)
    }

    @Test
    fun `somebody else turning a camera on moves the stage to video`() {
        val media = CallMedia(selfUid = 1, peers = mapOf(7 to MediaPeer(7, videoOn = true)))
        assertEquals(CallStageKind.Video, project(media = media).stage)
    }

    @Test
    fun `the video latch never releases mid-call`() {
        // Each swap between a Compose grid and a browser surface moves a native
        // window between parents; doing that on every camera toggle is how the
        // media stack ends up parented to nothing.
        val withVideo = project(cameraOn = true)
        assertTrue(withVideo.videoSeen)
        val cameraOffAgain = project(previous = withVideo, cameraOn = false)
        assertTrue(cameraOffAgain.videoSeen)
        assertEquals(CallStageKind.Video, cameraOffAgain.stage)
    }

    @Test
    fun `the video surface is mounted only while a call is up`() {
        val inCall = project(previous = CallUiState(phase = CallPhase.InCall), cameraOn = true)
        assertTrue(inCall.videoMounted)
        // Still mounted through teardown: unmounting mid-Ending would hand the
        // browser back to its parking window while the call is still leaving.
        assertTrue(inCall.copy(phase = CallPhase.Ending).videoMounted)
        assertFalse(inCall.copy(phase = CallPhase.Idle).videoMounted)
    }

    @Test
    fun `an empty stage pushes nothing to the page`() {
        assertEquals("", project(session = null).stageJson)
    }

    @Test
    fun `the page payload carries identity, never liveness`() {
        val media = CallMedia(selfUid = 1, speaking = setOf(1))
        val json = project(media = media).stageJson
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"cols\""))
        // Speaking is the page's own business — it hears it a frame earlier.
        assertFalse(json.contains("speaking"))
    }

    @Test
    fun `no audio is only reported after a grace period`() {
        // A healthy join is not instant, and an alarm that fires on every call
        // is one nobody reads.
        val joining = CallUiState(phase = CallPhase.InCall, elapsedSeconds = 1)
        assertFalse(joining.mediaDegraded)
        assertTrue(joining.copy(elapsedSeconds = 5).mediaDegraded)
        // A joined channel is never degraded, however long the call runs.
        assertFalse(
            joining.copy(elapsedSeconds = 60, media = CallMedia(channel = "chan")).mediaDegraded,
        )
    }

    @Test
    fun `connected counts the people actually in the call`() {
        val state = project(
            session = audioCall.copy(
                participants = listOf(
                    com.zillit.desktop.feature.calls.domain.CallParticipant(
                        userId = "a",
                        status = com.zillit.desktop.feature.calls.domain.CallStatus.InCall,
                    ),
                    com.zillit.desktop.feature.calls.domain.CallParticipant(
                        userId = "b",
                        status = com.zillit.desktop.feature.calls.domain.CallStatus.Ringing,
                    ),
                ),
            ),
        )
        // Us plus the one who answered; the one still ringing is on the stage
        // but is not in the call yet.
        assertEquals(2, state.connected)
    }

    @Test
    fun `the timer reads as hours only once there are hours`() {
        assertEquals("00:05", CallUiState(elapsedSeconds = 5).timerText)
        assertEquals("05:23", CallUiState(elapsedSeconds = 323).timerText)
        assertEquals("1:00:00", CallUiState(elapsedSeconds = 3_600).timerText)
        assertEquals("", CallUiState(elapsedSeconds = -1).timerText)
    }
}
