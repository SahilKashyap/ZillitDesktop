package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.readCallSession
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallProvider
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Line 1's half of the ring payload.
 *
 * The desktop dials the SFU from these fields, and getting any of them wrong
 * does not fail loudly — it produces a call that connects, reports itself
 * healthy, and is silent, because the two ends are in different rooms. So the
 * election rules are pinned here rather than left to a reading of the phones'
 * source.
 */
class CallLine1WireTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    private fun session(body: String) =
        readCallSession(parse(body), selfUserId = "me", selfDeviceId = "d", direction = CallDirection.Incoming)

    /**
     * `invite_code || room_id || call_uuid`, in that order.
     *
     * The phones read one field that is already the first two collapsed; the
     * desktop keeps them apart, so the third candidate is easy to lose. A
     * two-way election lands this client in a different room from everyone
     * else on any payload carrying `room_id` without an `invite_code`.
     */
    @Test
    fun `the room is elected from three candidates in order`() {
        val all = session("""{"call_uuid":"u","room_id":"r","invite_code":"inv","line":"mediasoup"}""")
        assertEquals("inv", all!!.sfuRoomId, "invite_code wins when present")

        val noInvite = session("""{"call_uuid":"u","room_id":"r","line":"mediasoup"}""")
        assertEquals("r", noInvite!!.sfuRoomId, "room_id is the second candidate, not skipped")

        val neither = session("""{"call_uuid":"u","line":"mediasoup"}""")
        assertEquals("u", neither!!.sfuRoomId, "call_uuid is the last resort")

        val blankInvite = session("""{"call_uuid":"u","room_id":"r","invite_code":"","line":"mediasoup"}""")
        assertEquals("r", blankInvite!!.sfuRoomId, "an empty invite_code is absent, not a room named \"\"")
    }

    @Test
    fun `the SFU host prefers the canonical name over the alias`() {
        val both = session(
            """{"call_uuid":"u","line":"mediasoup",
                "mediasoup_server_url":"sfu-a.zillit.com","sfu_url":"sfu-b.zillit.com"}""",
        )
        assertEquals("sfu-a.zillit.com", both!!.sfuHost)

        val aliasOnly = session("""{"call_uuid":"u","line":"mediasoup","sfu_url":"sfu-b.zillit.com"}""")
        assertEquals("sfu-b.zillit.com", aliasOnly!!.sfuHost, "the older alias is still honoured")
    }

    /**
     * A host and a room are required; the SFU token is not. An empty token is
     * the backward-safe tokenless dial the deployed backend still accepts, so
     * treating it as missing would refuse calls that work.
     */
    @Test
    fun `a mediasoup call is joinable on a host and a room, with or without a token`() {
        val tokenless = session(
            """{"call_uuid":"u","line":"mediasoup","room_id":"r","mediasoup_server_url":"sfu.zillit.com"}""",
        )
        assertTrue(tokenless!!.isJoinable, "an empty sfu_token is legitimate")

        val withToken = session(
            """{"call_uuid":"u","line":"mediasoup","room_id":"r",
                "mediasoup_server_url":"sfu.zillit.com","sfu_token":"tok"}""",
        )
        assertTrue(withToken!!.isJoinable)
        assertEquals("tok", withToken.sfuToken)

        val hostless = session("""{"call_uuid":"u","line":"mediasoup","room_id":"r"}""")
        assertFalse(hostless!!.isJoinable, "no elected host means nothing to dial")
    }

    /** Agora's rule is untouched by any of this. */
    @Test
    fun `an agora call still needs a channel and a token`() {
        val ok = session("""{"call_uuid":"u","line":"agora","agora_channel_name":"c","agora_token":"t"}""")
        assertTrue(ok!!.isJoinable)

        val noToken = session("""{"call_uuid":"u","line":"agora","agora_channel_name":"c"}""")
        assertFalse(noToken!!.isJoinable)

        // An Agora payload must never be made joinable by the SFU fields.
        val crossed = session(
            """{"call_uuid":"u","line":"agora","room_id":"r","mediasoup_server_url":"sfu.zillit.com"}""",
        )
        assertFalse(crossed!!.isJoinable)
    }

    /**
     * `p2p.eligible` decides, not `connection_type`.
     *
     * The deployed backend sends eligibility and no top-level connection_type
     * at all, so a client branching on the latter treats every elected P2P
     * call as an SFU call.
     */
    @Test
    fun `peer-to-peer is decided by eligibility, not connection_type`() {
        val eligibleNoType = session("""{"call_uuid":"u","line":"mediasoup","p2p":{"eligible":true}}""")
        assertTrue(eligibleNoType!!.isPeerToPeer, "eligibility alone elects P2P")

        val eligibleButSfu = session(
            """{"call_uuid":"u","line":"mediasoup","connection_type":"sfu","p2p":{"eligible":true}}""",
        )
        assertFalse(eligibleButSfu!!.isPeerToPeer, "an explicit sfu overrides eligibility")

        val notEligible = session("""{"call_uuid":"u","line":"mediasoup","p2p":{"eligible":false}}""")
        assertFalse(notEligible!!.isPeerToPeer)

        val noEnvelope = session("""{"call_uuid":"u","line":"mediasoup"}""")
        assertFalse(noEnvelope!!.isPeerToPeer, "absent means no")
    }

    @Test
    fun `an unknown line falls back to agora rather than being dropped`() {
        val odd = session("""{"call_uuid":"u","line":"whatever","agora_channel_name":"c","agora_token":"t"}""")
        assertEquals(CallProvider.Agora, odd!!.provider)
        assertTrue(odd.isJoinable)
    }
}

/**
 * What a Line 1 initiate response has to survive.
 *
 * Each of these was a silent failure: a response naming the call by its room
 * was dropped outright; a group call read back as Private, so the first
 * decline tore it down for the caller while everyone else was still ringing;
 * and a video call read back camera-off.
 */
class CallLine1ResponseTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    /** Line 1's response does not always carry a `call_uuid`. */
    @Test
    fun `a response naming the call by its room is still a call`() {
        val session = readCallSession(
            parse("""{"room_id":"room-7","line":"mediasoup","mediasoup_server_url":"sfu.zillit.com"}"""),
            selfUserId = "me",
            selfDeviceId = "d",
            direction = CallDirection.Outgoing,
        )

        assertTrue(session != null, "dropping this response loses the call outright")
        assertEquals("room-7", session.sfuRoomId)
        assertTrue(session.isJoinable)
    }

    /** The Agora path is unaffected: it always sends a call_uuid, and that wins. */
    @Test
    fun `call_uuid still wins when both are present`() {
        val session = readCallSession(
            parse("""{"call_uuid":"u-1","room_id":"room-7","line":"agora"}"""),
            selfUserId = "me", selfDeviceId = "d", direction = CallDirection.Outgoing,
        )
        assertEquals("u-1", session!!.callUuid)
    }

    /**
     * The fallback is for RESPONSES only. An incoming ring always carries a
     * call_uuid, so one without it is malformed — adopting it under a room id
     * would join a call that may not be the one being offered.
     */
    @Test
    fun `an incoming ring with only a room id is still refused`() {
        assertEquals(
            null,
            readCallSession(
                parse("""{"room_id":"room-7","line":"mediasoup"}"""),
                "me", "d", CallDirection.Incoming,
            ),
        )
    }

    @Test
    fun `a response with neither id is still refused`() {
        assertEquals(
            null,
            readCallSession(parse("""{"line":"mediasoup"}"""), "me", "d", CallDirection.Outgoing),
            "a call with no identity at all cannot be adopted",
        )
    }
}

/**
 * The two room elections, which look alike and are not.
 *
 * The SFU dial prefers the invite code; the `mediasoup-call` REST family never
 * does. Sending the dial's answer to a REST handler asks about a room it does
 * not know — and that handler replies HTTP 200 with `success: false`, so the
 * mistake is invisible: the invitee never rings and the roster keeps a row
 * saying they are.
 */
class CallRoomElectionTest {

    private fun session(body: String) = readCallSession(
        Json.parseToJsonElement(body), "me", "d", CallDirection.Outgoing,
    )!!

    @Test
    fun `the dial prefers the invite code, the REST family never does`() {
        val both = session(
            """{"call_uuid":"u","room_id":"r","invite_code":"inv","line":"mediasoup"}""",
        )
        assertEquals("inv", both.sfuRoomId, "the dial takes the invite code")
        assertEquals("r", both.restRoomId, "REST takes the room, never the invite code")
    }

    @Test
    fun `both fall back to the call uuid when nothing else is named`() {
        val bare = session("""{"call_uuid":"u","line":"mediasoup"}""")
        assertEquals("u", bare.sfuRoomId)
        assertEquals("u", bare.restRoomId)
    }

    /** With an invite code but no room, the two genuinely differ. */
    @Test
    fun `the elections diverge exactly where the bug was`() {
        val inviteOnly = session("""{"call_uuid":"u","invite_code":"inv","line":"mediasoup"}""")
        assertEquals("inv", inviteOnly.sfuRoomId)
        assertEquals("u", inviteOnly.restRoomId)
        assertTrue(
            inviteOnly.sfuRoomId != inviteOnly.restRoomId,
            "this is the payload shape where using one for the other rings nobody",
        )
    }
}
