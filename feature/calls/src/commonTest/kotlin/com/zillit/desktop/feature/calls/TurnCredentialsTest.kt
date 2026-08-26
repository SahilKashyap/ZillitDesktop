package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.TurnCredentials
import com.zillit.desktop.feature.calls.data.protoo.readTurnCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The relay list, whose failures are invisible until somebody is on cellular.
 *
 * Every case here degrades rather than throws, deliberately: a call with no
 * relays still works for most people on most networks, so refusing to dial
 * because the list was malformed would be the worse of the two failures.
 */
class TurnCredentialsTest {

    private fun parse(raw: String) = readTurnCredentials(Json.parseToJsonElement(raw) as JsonObject)

    @Test
    fun `a well-formed envelope yields the relays and the ttl`() {
        val creds = parse(
            """{"data":{"ttl":3600,"iceServers":[
                {"urls":["stun:stun.zillit.com:3478"]},
                {"urls":["turn:turn.zillit.com:3478?transport=udp"],
                 "username":"u1","credential":"c1"}]}}""",
        )

        assertEquals(2, creds.iceServers.size)
        assertEquals(3600, creds.ttlSeconds)
        assertEquals("u1", creds.iceServers[1].username)
        assertEquals("c1", creds.iceServers[1].credential)
    }

    @Test
    fun `an absent ttl falls back to a short one rather than a long one`() {
        val creds = parse("""{"data":{"iceServers":[{"urls":["stun:s:1"]}]}}""")
        assertEquals(TurnCredentials.DEFAULT_TTL_SECONDS, creds.ttlSeconds)
        // Over-estimating means dialling with credentials the relay has
        // already forgotten, at exactly the moment relaying was needed.
        assertTrue(creds.ttlSeconds <= 600)
    }

    @Test
    fun `a missing or malformed list degrades to no relays, not an exception`() {
        assertTrue(parse("""{"data":{}}""").isEmpty)
        assertTrue(parse("""{}""").isEmpty)
        assertTrue(readTurnCredentials(null).isEmpty)
        // A server entry with no usable url is dropped, not kept empty.
        assertTrue(parse("""{"data":{"iceServers":[{"urls":[]},{"urls":[""]}]}}""").isEmpty)
    }

    /** Some deployments answer without the envelope wrapper. */
    @Test
    fun `an unwrapped body is read too`() {
        val creds = parse("""{"iceServers":[{"urls":["stun:s:1"]}],"ttl":120}""")
        assertEquals(1, creds.iceServers.size)
        assertEquals(120, creds.ttlSeconds)
    }

    @Test
    fun `the json handed to the page is the shape RTCPeerConnection wants`() {
        val creds = parse(
            """{"data":{"iceServers":[{"urls":["turn:t:1"],"username":"u","credential":"c"}]}}""",
        )
        val server = creds.toJson().single().jsonObject

        assertEquals("turn:t:1", server["urls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("u", server["username"]?.jsonPrimitive?.content)
        assertEquals("c", server["credential"]?.jsonPrimitive?.content)
    }

    /** A relay with no credentials is a STUN server; the keys must be absent. */
    @Test
    fun `a stun entry carries no empty credential keys`() {
        val server = parse("""{"data":{"iceServers":[{"urls":["stun:s:1"]}]}}""")
            .toJson().single().jsonObject
        assertTrue("username" !in server)
        assertTrue("credential" !in server)
    }
}
