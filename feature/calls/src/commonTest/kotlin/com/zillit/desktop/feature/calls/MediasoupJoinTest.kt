package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.MediasoupJoin
import com.zillit.desktop.feature.calls.data.protoo.toJoin
import com.zillit.desktop.feature.calls.data.protoo.mediasoupIdentityOf
import com.zillit.desktop.feature.calls.data.protoo.mediasoupPeerId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The join requests, whose field names are the protocol.
 *
 * These fail silently when wrong — a transport pair with the direction flags
 * crossed connects, reports itself healthy, and carries no media — so the
 * shapes are pinned rather than trusted.
 */
class MediasoupJoinTest {

    private val caps = buildJsonObject { put("codecs", "…") }

    @Test
    fun `the send transport produces and does not consume`() {
        val body = MediasoupJoin.createTransport(producing = true, sctpCapabilities = null)

        assertEquals(true, body["producing"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["consuming"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `the receive transport consumes and does not produce`() {
        val body = MediasoupJoin.createTransport(producing = false, sctpCapabilities = null)

        assertEquals(false, body["producing"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, body["consuming"]?.jsonPrimitive?.content?.toBoolean())
    }

    /**
     * Forcing TCP would give up UDP for every user, including the majority for
     * whom it works. The SFU offers TCP candidates itself where they are needed.
     */
    @Test
    fun `transports do not force TCP`() {
        assertEquals(
            false,
            MediasoupJoin.createTransport(true, null)["forceTcp"]?.jsonPrimitive?.content?.toBoolean(),
        )
    }

    @Test
    fun `sctp capabilities are sent only when the device has them`() {
        val without = MediasoupJoin.createTransport(true, null)
        assertFalse("sctpCapabilities" in without, "an absent capability must be absent, not null")

        val with = MediasoupJoin.createTransport(true, caps)
        assertTrue("sctpCapabilities" in with)
    }

    @Test
    fun `join announces the device honestly rather than impersonating a phone`() {
        val body = MediasoupJoin.join("Vivek Mishra", caps, null)

        assertEquals("Vivek Mishra", body["displayName"]?.jsonPrimitive?.content)
        assertEquals("desktop", body["device"]?.jsonObject?.get("flag")?.jsonPrimitive?.content)
        assertTrue("rtpCapabilities" in body, "the SFU cannot route without them")
    }

    /**
     * Both halves of the peer id do work: the user half is what remote clients
     * bind a tile to and must survive a rejoin, the device half is what keeps
     * one person's two devices from being the same peer.
     */
    @Test
    fun `the peer id carries user and device, and the identity is the user half`() {
        val peerId = mediasoupPeerId("user-9", "device-3")
        assertEquals("user-9:device-3", peerId)
        assertEquals("user-9", mediasoupIdentityOf(peerId))
    }

    /** A rejoin appends a suffix to the device half; the identity must not move. */
    @Test
    fun `a rejoin suffix does not change who the peer is`() {
        assertEquals("user-9", mediasoupIdentityOf("user-9:device-3-r-a1b2c3d4"))
    }

    @Test
    fun `a peer id with no colon is still an identity, not an empty string`() {
        assertEquals("legacy-peer", mediasoupIdentityOf("legacy-peer"))
    }
}

/** What a session becomes when the engine is asked to join it. */
class SessionToJoinTest {

    private fun session(body: String) = com.zillit.desktop.feature.calls.data.readCallSession(
        kotlinx.serialization.json.Json.parseToJsonElement(body),
        selfUserId = "user-9",
        selfDeviceId = "device-3",
    )!!

    @Test
    fun `a mediasoup session carries the host stripped, the room elected, and a composite peer id`() {
        val join = session(
            """{"call_uuid":"u","line":"mediasoup","room_id":"r-7",
                "mediasoup_server_url":"wss://sfu.zillit.com/","sfu_token":"tok"}""",
        ).toJoin(selfDeviceId = "device-3", displayName = "Vivek")

        assertEquals(com.zillit.desktop.feature.calls.domain.CallProvider.Mediasoup, join.provider)
        assertEquals("sfu.zillit.com", join.sfuHost, "the scheme must not survive into the dial host")
        assertEquals("r-7", join.roomId)
        assertEquals("user-9:device-3", join.peerId)
        assertEquals("tok", join.sfuToken)
        assertEquals("Vivek", join.displayName)
    }

    @Test
    fun `an agora session still carries channel, token and uid`() {
        val join = session(
            """{"call_uuid":"u","line":"agora","agora_channel_name":"chan","agora_token":"tok"}""",
        ).toJoin("device-3", "Vivek")

        assertEquals(com.zillit.desktop.feature.calls.domain.CallProvider.Agora, join.provider)
        assertEquals("chan", join.channel)
        assertEquals("tok", join.token)
    }

    /**
     * The fields for the other line are populated regardless. That is
     * deliberate — the provider decides which half an engine reads, and a
     * half-blank object is easier to reason about than one whose contents
     * depend on a branch.
     */
    @Test
    fun `the room falls back through invite code and call uuid`() {
        assertEquals(
            "inv-1",
            session("""{"call_uuid":"u","line":"mediasoup","room_id":"r","invite_code":"inv-1"}""")
                .toJoin("d", "").roomId,
        )
        assertEquals(
            "u",
            session("""{"call_uuid":"u","line":"mediasoup"}""").toJoin("d", "").roomId,
        )
    }
}

/**
 * The peer id used when the socket comes back.
 *
 * The rule looks arbitrary and is not: reusing the previous id makes the SFU
 * evict the old peer and announce `peerClosed`, and the REMOTE — which never
 * lost anything — reads that as everyone having left and ends the call. The
 * side that reconnected cannot stop it.
 */
class MediasoupRejoinPeerIdTest {

    private val fixed = kotlin.random.Random(1)

    @Test
    fun `a rejoin id differs from the original`() {
        val original = "user-9:device-3"
        val rejoin = com.zillit.desktop.feature.calls.data.protoo.mediasoupRejoinPeerId(original, fixed)

        assertTrue(rejoin != original, "reusing the id evicts the old peer and ends the remote's call")
        assertTrue(rejoin.startsWith("user-9:device-3-r-"), rejoin)
    }

    /** Remotes bind tiles to the user half; it must survive so nobody looks new. */
    @Test
    fun `the identity survives a rejoin`() {
        val rejoin = com.zillit.desktop.feature.calls.data.protoo.mediasoupRejoinPeerId(
            "user-9:device-3", fixed,
        )
        assertEquals("user-9", com.zillit.desktop.feature.calls.data.protoo.mediasoupIdentityOf(rejoin))
    }

    /** A flapping connection must not grow an unbounded id. */
    @Test
    fun `rejoining twice replaces the suffix rather than stacking it`() {
        var id = "user-9:device-3"
        repeat(5) { id = com.zillit.desktop.feature.calls.data.protoo.mediasoupRejoinPeerId(id, fixed) }

        assertEquals(1, id.split("-r-").size - 1, "suffixes must not accumulate: $id")
        assertTrue(id.startsWith("user-9:device-3-r-"), id)
    }

    @Test
    fun `two rejoins in a row do not collide`() {
        val a = com.zillit.desktop.feature.calls.data.protoo.mediasoupRejoinPeerId("u:d", kotlin.random.Random(1))
        val b = com.zillit.desktop.feature.calls.data.protoo.mediasoupRejoinPeerId("u:d", kotlin.random.Random(2))
        assertTrue(a != b, "a colliding id is the same eviction bug")
    }
}
