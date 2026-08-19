package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.readCallLog
import com.zillit.desktop.feature.calls.data.readCallLogs
import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call-history rows, as years of clients have written them. */
class CallLogWireTest {

    private fun read(raw: String, self: String = "me") =
        readCallLog(Json.parseToJsonElement(raw), self)

    @Test
    fun `the explicit direction flags win`() {
        val out = read("""{"call_uuid":"u1","outgoingCall":true,"from_user_id":"other"}""")
        assertEquals(CallLogDirection.Outgoing, out?.direction)
        val incoming = read("""{"call_uuid":"u1","incomingCall":true,"from_user_id":"me"}""")
        assertEquals(CallLogDirection.Incoming, incoming?.direction)
    }

    @Test
    fun `an old row with no flags falls back to who placed it`() {
        assertEquals(
            CallLogDirection.Outgoing,
            read("""{"call_uuid":"u1","from_user_id":"me"}""")?.direction,
        )
        assertEquals(
            CallLogDirection.Incoming,
            read("""{"call_uuid":"u1","from_user_id":"vivek"}""")?.direction,
        )
    }

    @Test
    fun `the peer is whichever end is not us`() {
        val out = read("""{"call_uuid":"u1","outgoingCall":true,"to_user_id":"vivek","from_user_id":"me"}""")
        assertEquals("vivek", out?.peerUserId)
        val incoming = read("""{"call_uuid":"u1","incomingCall":true,"to_user_id":"me","from_user_id":"vivek"}""")
        assertEquals("vivek", incoming?.peerUserId)
    }

    @Test
    fun `booleans are read in every spelling the wire uses`() {
        assertTrue(read("""{"call_uuid":"u1","missedCall":true}""")?.missed == true)
        assertTrue(read("""{"call_uuid":"u1","missedCall":"true"}""")?.missed == true)
        assertTrue(read("""{"call_uuid":"u1","missed":1}""")?.missed == true)
        assertFalse(read("""{"call_uuid":"u1"}""")?.missed == true)
    }

    @Test
    fun `numbers survive arriving as strings`() {
        val row = read("""{"call_uuid":"u1","call_duration":"134","start_time":"1785954297848"}""")
        assertEquals(134L, row?.durationMillis)
        assertEquals(1_785_954_297_848L, row?.startedAtMillis)
    }

    @Test
    fun `a missing duration is zero, not a crash`() {
        assertEquals(0L, read("""{"call_uuid":"u1"}""")?.durationMillis)
    }

    @Test
    fun `a row with no id at all is dropped`() {
        assertNull(read("""{"call_duration":12}"""))
        // …but `_id` alone is enough to keep it.
        assertEquals("abc", read("""{"_id":"abc"}""")?.callUuid)
    }

    @Test
    fun `mode and type read from either spelling`() {
        val row = read("""{"call_uuid":"u1","call_mode":"group","call_type":"video"}""")
        assertEquals(CallMode.Group, row?.mode)
        assertEquals(CallType.Video, row?.type)
        val camel = read("""{"call_uuid":"u1","callMode":"group","callType":"video"}""")
        assertEquals(CallMode.Group, camel?.mode)
    }

    @Test
    fun `a page reads at either depth`() {
        val peeled = """{"calls":[{"call_uuid":"a"},{"call_uuid":"b"}]}"""
        assertEquals(2, readCallLogs(Json.parseToJsonElement(peeled), "me").size)
        val wrapped = """{"data":{"calls":[{"call_uuid":"a"}]}}"""
        assertEquals(1, readCallLogs(Json.parseToJsonElement(wrapped), "me").size)
    }

    @Test
    fun `one unreadable row does not lose the page`() {
        val mixed = """{"calls":[{"call_uuid":"a"},"junk",{"no_id":true},{"call_uuid":"b"}]}"""
        assertEquals(
            listOf("a", "b"),
            readCallLogs(Json.parseToJsonElement(mixed), "me").map { it.callUuid },
        )
    }

    @Test
    fun `redialable only when there is something to ring`() {
        val group = read("""{"call_uuid":"u1","call_mode":"group","chat_room_id":"r1"}""")
        assertTrue(group?.isRedialable == true)
        val groupNoRoom = read("""{"call_uuid":"u1","call_mode":"group"}""")
        assertFalse(groupNoRoom?.isRedialable == true)
        val direct = read("""{"call_uuid":"u1","incomingCall":true,"caller_device_id":"d1"}""")
        assertTrue(direct?.isRedialable == true)
        val directNoDevice = read("""{"call_uuid":"u1","incomingCall":true}""")
        assertFalse(directNoDevice?.isRedialable == true)
    }

    @Test
    fun `a log row never prints who it was with`() {
        val row = read("""{"call_uuid":"u1","chat_room_name":"Camera Unit","to_user_id":"vivek"}""")
        val rendered = row.toString()
        assertFalse(rendered.contains("Camera Unit"))
        assertFalse(rendered.contains("vivek"))
    }

    /** `line` per Android's sheet: agora is Line 2, livekit Line 3, anything else Line 1. */
    @Test
    fun `the line reads as the sheet labels it`() {
        assertEquals(CallLine.Two, read("""{"call_uuid":"u1","line":"agora"}""")?.line)
        assertEquals(CallLine.Three, read("""{"call_uuid":"u1","line":"LiveKit"}""")?.line)
        assertEquals(CallLine.One, read("""{"call_uuid":"u1","line":"mediasoup"}""")?.line)
        assertEquals(CallLine.One, read("""{"call_uuid":"u1"}""")?.line)
    }

    /** `call_users` arrives as bare ids on older rows and objects on newer ones — mixed, even. */
    @Test
    fun `the legacy roster reads both of its shapes`() {
        val row = read(
            """{"call_uuid":"u1","from_user_id":"me","to_user_id":"vivek",
                "call_users":["me",{"user_id":"vivek","current_status":"declined"},"", null, 7]}""",
        )
        assertEquals(listOf("me", "vivek", "7"), row?.callUsers?.map { it.userId })
        assertEquals("declined", row?.callUsers?.get(1)?.status)
        assertEquals("me", row?.callerUserId)
        assertEquals("vivek", row?.calleeUserId)
    }

    @Test
    fun `the rich roster keeps attendance, and drops a row with no user`() {
        val row = read(
            """{"call_uuid":"u1","line":"livekit","participants":[
                 {"user_id":"me","status":"caller","answered_at":1000,"join_count":1,"total_ms":60000},
                 {"user_id":"g1","status":"left","display_name":"Guest Sam","is_guest":true,
                  "invited_by":"me","join_count":"3","leave_count":2,"total_ms":"45000","missed":false},
                 {"status":"missed"}
               ]}""",
        )
        val roster = row?.participants.orEmpty()
        assertEquals(listOf("me", "g1"), roster.map { it.userId })
        assertTrue(roster[0].isCaller)
        assertEquals(1_000L, roster[0].answeredAtMillis)
        val guest = roster[1]
        assertEquals("Guest Sam", guest.displayName)
        assertTrue(guest.isGuest)
        assertEquals("me", guest.invitedBy)
        assertEquals(3, guest.joinCount)
        assertEquals(2, guest.leaveCount)
        assertEquals(45_000L, guest.totalMillis)
    }
}
