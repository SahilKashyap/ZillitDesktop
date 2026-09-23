package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.EngineBridge
import com.zillit.desktop.feature.calls.data.withRinging
import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.MediaPeer
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallStageKind
import com.zillit.desktop.feature.calls.ui.CallTile
import com.zillit.desktop.feature.calls.ui.CallUiState
import com.zillit.desktop.feature.calls.ui.afterWindowGesture
import com.zillit.desktop.feature.calls.ui.callUserSections
import com.zillit.desktop.feature.calls.ui.isDuo
import com.zillit.desktop.feature.calls.ui.projectCallUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 2026-09-23 calling fixes: one users panel, the two-person layout, the
 * pill's expand button, and a Line 3 presenter actually reaching the stage.
 */
class CallUsersAndLayoutTest {

    private val session = CallSession(
        callUuid = "c1",
        selfUserId = "me",
        participants = listOf(
            CallParticipant(userId = "me", name = "Me", status = CallStatus.InCall),
            CallParticipant(userId = "asha", name = "Asha", status = CallStatus.InCall),
            CallParticipant(userId = "ravi", name = "Ravi", status = CallStatus.Ringing),
            CallParticipant(userId = "noor", name = "Noor", status = CallStatus.Declined),
            CallParticipant(userId = "omar", name = "Omar", status = CallStatus.Left),
        ),
    )

    private val tiles = listOf(
        CallTile(key = "me", name = "Me", userId = "me", isSelf = true),
        CallTile(key = "asha", name = "Asha", userId = "asha", designation = "Gaffer"),
        CallTile(key = "ravi", name = "Ravi", userId = "ravi", presence = CallStatus.Ringing),
    )

    private val crew = listOf(
        CallCrewEntry(userId = "me", deviceId = "d0", name = "Me"),
        CallCrewEntry(userId = "asha", deviceId = "d1", name = "Asha"),
        CallCrewEntry(userId = "noor", deviceId = "d2", name = "Noor"),
        CallCrewEntry(userId = "zed", deviceId = "d3", name = "Zed", designation = "Grip"),
        CallCrewEntry(userId = "bea", deviceId = "d4", name = "Bea"),
    )

    private val state = CallUiState(session = session, tiles = tiles, addableCrew = crew)

    @Test
    fun `the users panel lists the room, the ringing, the dropped and everyone else, once each`() {
        val sections = callUserSections(state, query = "")

        assertEquals(listOf("me", "asha"), sections.inCall.map { it.userId })
        assertEquals(listOf("ravi"), sections.ringing.map { it.userId })
        // Left and declined sit apart from the room, and are ringable again.
        assertEquals(listOf("noor", "omar"), sections.dropped.map { it.userId })
        // Everyone else in the crew — never ourselves, never someone already
        // on the call in any state, sorted by name.
        assertEquals(listOf("bea", "zed"), sections.addable.map { it.userId })
        assertTrue(sections.canAdd)
    }

    @Test
    fun `search matches a name or a designation across every section`() {
        val byDesignation = callUserSections(state, query = "grip")
        assertEquals(listOf("zed"), byDesignation.addable.map { it.userId })
        assertTrue(byDesignation.inCall.isEmpty())

        val byName = callUserSections(state, query = "  ASHA ")
        assertEquals(listOf("asha"), byName.inCall.map { it.userId })
        assertTrue(byName.addable.isEmpty() && byName.dropped.isEmpty() && byName.ringing.isEmpty())
    }

    @Test
    fun `a support call offers nobody to add`() {
        val support = state.copy(session = session.copy(is247Call = true))
        val sections = callUserSections(support, query = "")
        assertFalse(sections.canAdd)
        assertTrue(sections.addable.isEmpty())
    }

    @Test
    fun `a re-ring flips the dropped row back to ringing instead of adding a second one`() {
        val rung = session.withRinging(userId = "noor", deviceId = "d2", name = "Noor")
        val noor = rung.participants.filter { it.userId == "noor" }
        assertEquals(1, noor.size)
        assertEquals(CallStatus.Ringing, noor.single().status)
        assertEquals("d2", noor.single().deviceId)

        val fresh = session.withRinging(userId = "zed", deviceId = "d3", name = "Zed")
        assertEquals(CallStatus.Ringing, fresh.participants.single { it.userId == "zed" }.status)
        assertEquals(session.participants.size + 1, fresh.participants.size)
    }

    @Test
    fun `two people is the two-person layout, three is the grid`() {
        assertTrue(isDuo(tiles.take(2)))
        assertFalse(isDuo(tiles))
        assertFalse(isDuo(tiles.take(1)))
        // Two tiles that are both somebody else is not us-and-them.
        assertFalse(isDuo(listOf(tiles[1], tiles[2])))
    }

    @Test
    fun `the pill's expand button raises the call's own window, full size`() {
        val inItsOwnWindow = CallUiState(pipOpen = true, pipCompact = true)
        val raised = inItsOwnWindow.afterWindowGesture(CallEvent.ToggleStage)
        assertEquals(1, raised.windowRaise)
        assertFalse(raised.pipCompact)
        assertTrue(raised.pipOpen)

        // Pressed again, it asks again — each press is its own request.
        assertEquals(2, raised.afterWindowGesture(CallEvent.ToggleStage).windowRaise)
    }

    @Test
    fun `inside the main window the same button still expands and minimises`() {
        val inline = CallUiState(pipOpen = false, expanded = false)
        val expanded = inline.afterWindowGesture(CallEvent.ToggleStage)
        assertTrue(expanded.expanded)
        assertEquals(0, expanded.windowRaise)
        assertFalse(expanded.afterWindowGesture(CallEvent.ToggleStage).expanded)
    }

    @Test
    fun `a Line 3 presenter reaches the media picture`() {
        assertEquals(
            CallEngineEvent.PeerScreenShare(4_242, true),
            EngineBridge.parse("""{"type":"peer-screen-share","uid":4242,"sharing":true}"""),
        )
    }

    @Test
    fun `somebody presenting on an audio call moves the stage to video`() {
        val media = CallMedia(selfUid = 1, peers = mapOf(7 to MediaPeer(7, sharing = true)))
        val projected = projectCallUi(CallUiState(), CallSession(callUuid = "a"), media, false, false, "Me")
        assertEquals(CallStageKind.Video, projected.stage)
    }
}
