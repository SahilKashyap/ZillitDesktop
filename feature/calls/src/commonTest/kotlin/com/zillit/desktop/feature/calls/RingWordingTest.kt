package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.ui.incomingRingSubtitle
import com.zillit.desktop.feature.calls.ui.outgoingRingStatus
import com.zillit.desktop.feature.calls.ui.ringContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ring card's words — the web's (`CallOverlays.tsx:34-54, 132-142`). */
class RingWordingTest {

    @Test
    fun `an outgoing ring says Calling until someone is in, then Joining`() {
        val ringing = CallSession(
            callUuid = "c", selfUserId = "me",
            participants = listOf(CallParticipant("them", status = CallStatus.Ringing)),
        )
        assertEquals("Calling…", ringing.outgoingRingStatus())
        val answered = ringing.copy(participants = listOf(CallParticipant("them", status = CallStatus.InCall)))
        assertEquals("Joining…", answered.outgoingRingStatus())
        // Our own row joining the room is not the far end answering.
        val onlyMe = ringing.copy(participants = listOf(CallParticipant("me", status = CallStatus.InCall)))
        assertEquals("Calling…", onlyMe.outgoingRingStatus())
    }

    @Test
    fun `an incoming ring names its kind and, for a room, the room`() {
        val dm = CallSession(callUuid = "c", callerName = "Aisha", hasVideo = true)
        assertEquals("Incoming video call…", dm.incomingRingSubtitle())
        assertNull(dm.ringContext(incoming = true))

        val room = CallSession(callUuid = "c", callerName = "Aisha", mode = CallMode.Group, title = "Team Leads")
        assertEquals("Incoming group audio call…", room.incomingRingSubtitle())
        assertEquals("Team Leads", room.ringContext(incoming = true))
        assertNull(room.ringContext(incoming = false))
    }
}
