package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.LiveKitEvent
import com.zillit.desktop.feature.calls.data.livekit.LiveKitFrame
import com.zillit.desktop.feature.calls.data.livekit.MeetAttendee
import com.zillit.desktop.feature.calls.data.livekit.MeetLobbyState
import com.zillit.desktop.feature.calls.data.livekit.MeetProblem
import com.zillit.desktop.feature.calls.data.livekit.MeetStartPolicy
import com.zillit.desktop.feature.calls.data.livekit.knockProblemOf
import com.zillit.desktop.feature.calls.data.livekit.meetLobbyOwnsSurface
import com.zillit.desktop.feature.calls.data.livekit.meetProblemOf
import com.zillit.desktop.feature.calls.data.livekit.parseLiveKitFrame
import com.zillit.desktop.feature.calls.data.livekit.readMeetAttendees
import com.zillit.desktop.feature.calls.data.livekit.readMeetInfo
import com.zillit.desktop.feature.calls.data.livekit.readMeetPresence
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.meetCountLine
import com.zillit.desktop.feature.calls.ui.meetNamesLine
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The meet's wire, and the lobby's pure rules: who owns the surface, and how the strip reads. */
class MeetWireTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `the meet answer is read whole, and absent is not cancelled`() {
        val info = readMeetInfo(
            "m1",
            json(
                """{"meetId":"m1","ownerId":"o","ownerName":"Olu","startPolicy":"owner","active":true,
                   "callType":"video","title":"Review","allowedUserIds":["a","b"],"startsAt":1791174600000}""",
            ),
        )!!
        assertEquals(MeetStartPolicy.Owner, info.startPolicy)
        assertEquals(CallType.Video, info.callType)
        assertEquals(listOf("a", "b"), info.allowedUserIds)
        assertEquals(1791174600000, info.startsAt)
        assertTrue(readMeetInfo("m1", json("{}"))!!.active)
        assertEquals(MeetStartPolicy.Anyone, readMeetInfo("m1", json("{}"))!!.startPolicy)
    }

    @Test
    fun `presence is media truth`() {
        assertEquals(true, readMeetPresence(json("""{"active":true,"ongoing":true,"inCall":true}"""))?.inCall)
        assertEquals(false, readMeetPresence(json("{}"))?.inCall)
        assertNull(readMeetPresence(json("[]")))
    }

    @Test
    fun `only in_call rows are people in the meeting, pictures with their bucket`() {
        val read = readMeetAttendees(
            json(
                """{"states":[
                {"userId":"a","displayName":"Asha","state":"in_call","profilePic":"k.jpg",
                 "profilePicBucket":"dev-bucket","profilePicRegion":"ap-south-1","designation":"Gaffer"},
                {"userId":"b","displayName":"Ben","state":"available"},
                {"userId":"guest_7","displayName":"Ravi","state":"in_call"}]}""",
            ),
        )!!
        assertEquals(listOf("a", "guest_7"), read.inCall.map { it.userId })
        assertEquals("dev-bucket", read.inCall[0].pictureBucket)
        assertTrue(read.inCall[1].isGuest)
        assertNull(readMeetAttendees(json("\"nope\"")))
    }

    @Test
    fun `server words map to the lobby's problems`() {
        assertEquals(MeetProblem.LinkInvalid, meetProblemOf("no_such_call"))
        assertEquals(MeetProblem.Cancelled, meetProblemOf("meet_inactive"))
        assertEquals(MeetProblem.NotAllowed, meetProblemOf("not_project_member"))
        assertEquals(MeetProblem.NotAllowed, meetProblemOf("libs_moduledata_not_accepted"))
        assertEquals(MeetProblem.AlreadyOnAnotherCall, meetProblemOf("caller_busy"))
        assertNull(meetProblemOf("something_new"))
        assertEquals(MeetProblem.KnockLinkDisabled, knockProblemOf("linkDisabled"))
        assertEquals(MeetProblem.KnockRefused, knockProblemOf(null))
    }

    @Test
    fun `the knock answers and the switch reason are read off the socket`() {
        val admitted = (parseLiveKitFrame(
            """{"type":"guestAdmitted","callId":"m1","url":"wss://in.livekit.zillit.com","token":"t","identity":"g"}""",
        ) as LiveKitFrame.Event).event as LiveKitEvent.GuestAdmitted
        assertEquals("t", admitted.token)
        assertEquals("wss://in.livekit.zillit.com", admitted.url)

        val declined = (parseLiveKitFrame("""{"type":"guestDeclined","callId":"m1","reason":"ended"}""")
            as LiveKitFrame.Event).event as LiveKitEvent.GuestDeclined
        assertEquals("ended", declined.reason)

        val switched = (parseLiveKitFrame("""{"type":"callHandledElsewhere","callId":"m1","reason":"switched"}""")
            as LiveKitFrame.Event).event as LiveKitEvent.HandledElsewhere
        assertEquals("switched", switched.reason)
        val plain = (parseLiveKitFrame("""{"type":"callHandledElsewhere","callId":"c1"}""")
            as LiveKitFrame.Event).event as LiveKitEvent.HandledElsewhere
        assertEquals("", plain.reason)
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    private val lobby = MeetLobbyState(meetId = "m1", title = "x")
    private val meet = CallSession(callUuid = "m1")
    private val other = CallSession(callUuid = "c9")

    @Test
    fun `before the hand-off the lobby owns an empty surface, and a live call takes it back`() {
        assertTrue(meetLobbyOwnsSurface(lobby, CallPhase.Idle, null))
        assertFalse(meetLobbyOwnsSurface(lobby, CallPhase.Incoming, other))
        assertFalse(meetLobbyOwnsSurface(null, CallPhase.Idle, null))
    }

    @Test
    fun `after the hand-off only its own connecting call keeps it, and an ended call never brings it back`() {
        val handed = lobby.copy(handedOff = true)
        assertTrue(meetLobbyOwnsSurface(handed, CallPhase.Outgoing, meet))
        assertFalse(meetLobbyOwnsSurface(handed, CallPhase.InCall, meet))
        assertFalse(meetLobbyOwnsSurface(handed, CallPhase.Outgoing, other))
        // The bug this rule exists for: the call ended, state went back to nothing.
        assertFalse(meetLobbyOwnsSurface(handed, CallPhase.Idle, null))
    }

    // ── The strip's words ───────────────────────────────────────────────────

    private fun people(vararg names: String) =
        names.mapIndexed { i, n -> MeetAttendee(userId = "u$i", displayName = n) }

    @Test
    fun `names read as a sentence, blank names as Participant`() {
        assertEquals("Asha", meetNamesLine(people("Asha")))
        assertEquals("Asha and Ben", meetNamesLine(people("Asha", "Ben")))
        assertEquals("Asha, Ben and Cy", meetNamesLine(people("Asha", "Ben", "Cy")))
        assertEquals("Asha, Ben, Cy and 1 other", meetNamesLine(people("Asha", "Ben", "Cy", "Di")))
        assertEquals("Asha, Ben, Cy and 2 others", meetNamesLine(people("Asha", "Ben", "Cy", "Di", "Ed")))
        assertEquals("Participant", meetNamesLine(people(" ")))
    }

    @Test
    fun `the count line`() {
        assertEquals("1 person is in the meeting", meetCountLine(1))
        assertEquals("4 people are in the meeting", meetCountLine(4))
    }
}
