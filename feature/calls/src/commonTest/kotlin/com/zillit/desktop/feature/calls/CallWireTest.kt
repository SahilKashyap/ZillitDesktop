package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.readCallEnded
import com.zillit.desktop.feature.calls.data.readCallSession
import com.zillit.desktop.feature.calls.data.readParticipants
import com.zillit.desktop.feature.calls.data.readStatusChange
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire shapes here are transcriptions of live payloads Android's models
 * are annotated for — the tests pin the tolerances, because every one of them
 * exists to absorb a variation the server actually produces.
 */
class CallWireTest {

    private fun parse(text: String) = Json.parseToJsonElement(text)

    // ── The ring ────────────────────────────────────────────────────────

    @Test
    fun `reads a flat socket ring`() {
        val session = readCallSession(
            parse(
                """
                {"call_uuid":"u1","room_id":"r1","chat_room_id":"c1","project_id":"p1",
                 "call_mode":"private","call_type":"video","has_video":true,"line":"agora",
                 "agora_channel_name":"chan","agora_token":"tok","invite_code":"inv",
                 "sender_user_id":"caller","sender_device_id":"callerDev",
                 "caller_name":"Vivek","receiver_user_id":"meInThatProject",
                 "call_users":[{"user_id":"caller","device_id":"callerDev","agora_uid":7,
                                "current_status":"caller"},
                               {"user_id":"me","device_id":"myDev","agora_uid":"12",
                                "current_status":"ringing"}]}
                """.trimIndent(),
            ),
            selfUserId = "me",
            selfDeviceId = "myDev",
        )
        assertNotNull(session)
        assertEquals("u1", session.callUuid)
        assertEquals(CallProvider.Agora, session.provider)
        assertEquals(CallType.Video, session.type)
        assertEquals("chan", session.channelName)
        // receiver_user_id wins over the ambient id: a cross-project call
        // identifies us by the call's production, not the open one.
        assertEquals("meInThatProject", session.selfUserId)
        // Our uid resolved from the roster row matching our device.
        assertEquals(12, session.localUid)
        assertTrue(session.isJoinable)
    }

    @Test
    fun `reads a wrapped REST response and a legacy data envelope`() {
        val wrapped = readCallSession(
            parse("""{"data":{"call":{"call_uuid":"u2","call_mode":"group"}}}"""),
            selfUserId = "me",
            selfDeviceId = "d",
        )
        assertNotNull(wrapped)
        assertEquals("u2", wrapped.callUuid)
        assertEquals(CallMode.Group, wrapped.mode)

        val legacy = readCallSession(
            parse("""{"data":{"call_uuid":"u3"}}"""),
            selfUserId = "me",
            selfDeviceId = "d",
        )
        assertEquals("u3", legacy?.callUuid)

        // What the live create-call actually hands this reader: ApiClient has
        // already peeled `data`, leaving `{success, call:{…}}` bare.
        val peeled = readCallSession(
            parse("""{"success":true,"call":{"call_uuid":"u4"}}"""),
            selfUserId = "me",
            selfDeviceId = "d",
        )
        assertEquals("u4", peeled?.callUuid)
    }

    @Test
    fun `a ring with no uuid is dropped, not misread`() {
        assertNull(readCallSession(parse("""{"room_id":"r"}"""), "me", "d"))
    }

    @Test
    fun `random flag is honoured under either spelling`() {
        val stored = readCallSession(
            parse("""{"call_uuid":"u","is_random_call":true}"""), "me", "d",
        )
        val live = readCallSession(parse("""{"call_uuid":"u","isRandom":true}"""), "me", "d")
        assertTrue(stored!!.isRandomCall)
        assertTrue(live!!.isRandomCall)
    }

    @Test
    fun `a call without agora credentials is not joinable`() {
        val session = readCallSession(
            parse("""{"call_uuid":"u","line":"agora"}"""), "me", "d",
        )
        assertFalse(session!!.isJoinable)
    }

    @Test
    fun `a call on a line this client cannot join stays signalling-only`() {
        // Both carry credentials Agora cannot use. Answering one used to
        // reach AgoraRTC.join with an empty channel, throw, and end the call
        // as "Call failed" — the far side ringing all the while.
        // No SFU host elected, so there is nothing to dial — a mediasoup
        // session is joinable on a host and a room, not on an invite code.
        val mediasoup = readCallSession(
            parse("""{"call_uuid":"u","line":"mediasoup","invite_code":"inv-1"}"""), "me", "d",
        )
        assertEquals(CallProvider.Mediasoup, mediasoup!!.provider)
        assertFalse(mediasoup.isJoinable)

        val liveKit = readCallSession(
            parse("""{"call_uuid":"u","line":"livekit","agora_channel_name":"c","agora_token":"t"}"""),
            "me",
            "d",
        )
        assertEquals(CallProvider.LiveKit, liveKit!!.provider)
        // Even with channel-shaped fields present: they are not Agora's.
        assertFalse(liveKit.isJoinable)
    }

    // ── The roster ──────────────────────────────────────────────────────

    @Test
    fun `mixed roster rows survive - strings skipped, numbers and strings both read`() {
        val rows = readParticipants(
            parse(
                """["bare-id",{"user_id":"a","agora_uid":5},{"user_id":"b","agora_uid":"9"},
                    {"no_user_id":true}]""",
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.userId })
        assertEquals(listOf(5, 9), rows.map { it.numericUid })
    }

    // ── Statuses ────────────────────────────────────────────────────────

    @Test
    fun `both in-call spellings resolve to InCall`() {
        assertEquals(CallStatus.InCall, CallStatus.ofWire("in_call"))
        assertEquals(CallStatus.InCall, CallStatus.ofWire("incall"))
        assertEquals(CallStatus.InCall, CallStatus.ofWire(" InCall "))
        assertTrue(CallStatus.isInCall("incall"))
    }

    @Test
    fun `unknown status reads as Ringing, not a crash or a drop`() {
        assertEquals(CallStatus.Ringing, CallStatus.ofWire("something_new"))
        assertEquals(CallStatus.Ringing, CallStatus.ofWire(null))
    }

    @Test
    fun `status change reads either room key spelling`() {
        val snake = readStatusChange(parse("""{"room_id":"r","user_id":"u","status":"declined"}"""))
        val camel = readStatusChange(parse("""{"roomId":"r","userId":"u","status":"declined"}"""))
        assertEquals("r", snake?.roomId)
        assertEquals("r", camel?.roomId)
        assertEquals(CallStatus.Declined, camel?.status)
    }

    // ── Ended ───────────────────────────────────────────────────────────

    @Test
    fun `ended payload reads and tolerates a detail wrapper`() {
        val flat = readCallEnded(parse("""{"room_id":"r","message":"Call has ended"}"""))
        val wrapped = readCallEnded(parse("""{"detail":{"room_id":"r2"}}"""))
        assertEquals("r", flat?.roomId)
        assertEquals("r2", wrapped?.roomId)
    }
}
