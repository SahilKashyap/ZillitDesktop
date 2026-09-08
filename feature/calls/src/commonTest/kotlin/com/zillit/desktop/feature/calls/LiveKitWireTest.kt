package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.LiveKitEvent
import com.zillit.desktop.feature.calls.data.livekit.LiveKitFrame
import com.zillit.desktop.feature.calls.data.livekit.liveKitRequestFrame
import com.zillit.desktop.feature.calls.data.livekit.parseLiveKitFrame
import com.zillit.desktop.feature.calls.data.livekit.userStateStatus
import com.zillit.desktop.feature.calls.data.livekit.readLiveKitRoster
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Line 3's frames, as the calling backend sends them.
 *
 * The shapes are pinned from the phones' `ServerEvent.kt` and the web's
 * `protocol/index.ts`; a field read under the wrong name is a ring that
 * arrives and shows nobody, which is the failure that reads as "Line 3 is
 * flaky" rather than as a bug.
 */
class LiveKitWireTest {

    @Test
    fun `a reply is told from an event by its reqId`() {
        val ok = parseLiveKitFrame("""{"reqId":"7","ok":true,"data":{"calls":[]}}""")
        assertIs<LiveKitFrame.Response>(ok)
        assertEquals("7", ok.reqId)
        assertTrue(ok.ok)

        val refused = parseLiveKitFrame("""{"reqId":"8","ok":false,"error":"caller_busy"}""")
        assertIs<LiveKitFrame.Response>(refused)
        assertEquals("caller_busy", refused.error)
        assertNull(refused.data)
    }

    @Test
    fun `an incoming call carries who, where, and the room`() {
        val frame = parseLiveKitFrame(
            """
            {"type":"incomingCall","callId":"c1","callType":"video","callMode":"group",
             "from":{"userId":"u-caller","displayName":"Vivek","profilePic":{"media":"m","bucket":"b","region":"r"}},
             "toUserId":"u-me","projectId":"p1","projectName":"Sides Testing",
             "chatRoomId":"room-9","chatRoomName":"Camera dept",
             "inCallUsers":[{"userId":"u-caller","displayName":"Vivek"},{"userId":"u-2","displayName":"Sam"}],
             "expiresAt":1700000000000,
             "livekit":{"token":"tok","url":"ws://localhost:7880","preconnectToken":"warm"}}
            """.trimIndent(),
        )
        val invite = assertIs<LiveKitEvent.IncomingCall>(assertIs<LiveKitFrame.Event>(frame).event).invite

        assertEquals("c1", invite.callId)
        assertEquals(CallType.Video, invite.callType)
        assertEquals(CallMode.Group, invite.callMode)
        assertEquals("u-caller", invite.fromUserId)
        assertEquals("m|b|r", invite.fromImage, "an object picture flattens like the phones'")
        assertEquals("u-me", invite.toUserId)
        assertEquals("warm", invite.preconnectToken)
        assertTrue(invite.isExpired(1700000000001))

        val session = invite.toSession(selfUserId = "ambient-me", selfDeviceId = "d")
        assertEquals(CallProvider.LiveKit, session.provider)
        assertEquals(CallDirection.Incoming, session.direction)
        assertEquals("u-me", session.selfUserId, "the callee's id on that project, not the ambient one")
        assertEquals("Camera dept", session.title, "a group call is titled by its room")
        assertEquals(listOf("u-caller", "u-2"), session.participants.map { it.userId })
        assertEquals(CallStatus.Caller, session.participants.first().status)
        assertTrue(session.isJoinable)
    }

    @Test
    fun `ring states land on the statuses the coordinator already knows`() {
        fun status(type: String) =
            (parseLiveKitFrame("""{"type":"$type","callId":"c","userId":"u"}""") as LiveKitFrame.Event)
            .event as LiveKitEvent.RingState

        assertEquals(CallStatus.Ringing, status("callRinging").status)
        assertEquals(CallStatus.InCall, status("callAccepted").status)
        assertEquals(CallStatus.Declined, status("callDeclined").status)
        assertEquals(CallStatus.NotAnswered, status("callMissed").status)
        assertEquals(CallStatus.NotAnswered, status("callUnreachable").status)
        val busy = status("callBusy")
        assertEquals(CallStatus.Declined, busy.status)
        assertTrue(busy.busy, "busy is a decline that says why")
    }

    @Test
    fun `the callee-side dismissals are their own events`() {
        assertIs<LiveKitEvent.Cancelled>(event("""{"type":"callCancelled","callId":"c"}"""))
        assertIs<LiveKitEvent.HandledElsewhere>(event("""{"type":"callHandledElsewhere","callId":"c"}"""))
        assertEquals("hangup", assertIs<LiveKitEvent.Ended>(event("""{"type":"callEnded","reason":"hangup"}""")).reason)
    }

    @Test
    fun `an unread type is unknown, never an error`() {
        assertIs<LiveKitFrame.Unknown>(parseLiveKitFrame("""{"type":"guestKnocking","callId":"c"}"""))
        assertNull(parseLiveKitFrame("not json"))
    }

    @Test
    fun `a request frame names its type and id first and carries its fields`() {
        val frame = liveKitRequestFrame("acceptCall", "3", buildJsonObject { put("callId", JsonPrimitive("c")) })
        val obj = Json.parseToJsonElement(frame) as JsonObject
        assertEquals("acceptCall", (obj["type"] as JsonPrimitive).content)
        assertEquals("3", (obj["reqId"] as JsonPrimitive).content)
        assertEquals("c", (obj["callId"] as JsonPrimitive).content)
    }

    @Test
    fun `the roster marks the caller and reads every state word`() {
        val roster = readLiveKitRoster(
            Json.parseToJsonElement(
                """{"states":[
                    {"userId":"u-caller","displayName":"Vivek","state":"in_call"},
                    {"userId":"u-2","displayName":"Sam","state":"ringing"},
                    {"userId":"u-3","displayName":"Ana","state":"busy"},
                    {"userId":"u-4","displayName":"Lee","state":"available"}]}""",
            ),
            callerId = "u-caller",
        )
        assertEquals(
            listOf(CallStatus.Caller, CallStatus.Ringing, CallStatus.Declined),
            roster.map { it.status },
        )
    }

    private fun event(raw: String): LiveKitEvent = assertIs<LiveKitFrame.Event>(parseLiveKitFrame(raw)).event

    @Test
    fun `a state word this build does not know is dropped, not read as a departure`() {
        assertEquals(CallStatus.InCall, userStateStatus("accepted"))
        assertEquals(CallStatus.Left, userStateStatus("left"))
        assertEquals(CallStatus.Ringing, userStateStatus("calling"), "being rung reads as ringing")
        assertEquals(null, userStateStatus("available"), "not on the call is not a departure")
        assertEquals(null, userStateStatus("on_hold"))
        val frame = parseLiveKitFrame("""{"type":"callUserStateChanged","callId":"c","userId":"u","state":"on_hold"}""")
        assertTrue(frame is LiveKitFrame.Unknown, "an unknown state is an unread event: $frame")
    }
}
