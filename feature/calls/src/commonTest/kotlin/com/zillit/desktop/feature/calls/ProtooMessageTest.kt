package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.ProtooMessage
import com.zillit.desktop.feature.calls.data.protoo.parseProtoo
import com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame
import com.zillit.desktop.feature.calls.data.protoo.protooNotificationFrame
import com.zillit.desktop.feature.calls.data.protoo.protooRejectFrame
import com.zillit.desktop.feature.calls.data.protoo.protooRequestFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The protoo envelope — Line 1's entire signalling grammar.
 *
 * Worth pinning tightly because every mediasoup exchange rides inside it, and
 * a frame this client fails to recognise is not an error anybody sees: it is a
 * consumer that never resumes, or a transport that never connects, on somebody
 * else's call.
 */
class ProtooMessageTest {

    private val payload = buildJsonObject { put("transportId", "t-1") }

    // ── the flagged forms, which are what protoo-client speaks ──────────

    @Test
    fun `a flagged request round-trips`() {
        val parsed = parseProtoo(protooRequestFrame(42, "createWebRtcTransport", payload))

        val request = parsed as? ProtooMessage.Request
        assertTrue(request != null, "expected a request, got $parsed")
        assertEquals(42L, request.id)
        assertEquals("createWebRtcTransport", request.method)
        assertEquals("t-1", request.data["transportId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `accept and reject are both responses, distinguished by ok`() {
        val accepted = parseProtoo(protooAcceptFrame(7)) as ProtooMessage.Response
        assertEquals(7L, accepted.id)
        assertTrue(accepted.ok)

        val rejected = parseProtoo(protooRejectFrame(8, 403, "unknown method: nope")) as ProtooMessage.Response
        assertEquals(false, rejected.ok)
        assertEquals(403, rejected.errorCode)
        assertEquals("unknown method: nope", rejected.errorReason)
    }

    @Test
    fun `a notification has no id and expects no answer`() {
        val parsed = parseProtoo(protooNotificationFrame("peerClosed", buildJsonObject { put("peerId", "u:d") }))

        val note = parsed as? ProtooMessage.Notification
        assertTrue(note != null, "expected a notification, got $parsed")
        assertEquals("peerClosed", note.method)
        assertEquals("u:d", note.data["peerId"]?.jsonPrimitive?.content)
    }

    /** A response with `ok:false` and no flags on the payload must not be read as success. */
    @Test
    fun `a flagged response defaults to not-ok rather than assuming success`() {
        val parsed = parseProtoo("""{"response":true,"id":9}""") as ProtooMessage.Response
        assertEquals(false, parsed.ok, "an unstated ok on a flagged response is not success")
    }

    // ── the lenient fallback, which is why this parser is not stock ─────

    /**
     * This deployment has emitted frames with no type flag. protoo-client
     * drops them silently; the phones infer the shape from which keys are
     * present, and so must we — otherwise the desktop loses exactly the frames
     * the phones handle, in production only.
     */
    @Test
    fun `an unflagged frame with an id and a method is a request`() {
        val parsed = parseProtoo("""{"id":5,"method":"newConsumer","data":{"consumerId":"c1"}}""")

        val request = parsed as? ProtooMessage.Request
        assertTrue(request != null, "expected a request, got $parsed")
        assertEquals("newConsumer", request.method)
        assertEquals(5L, request.id)
    }

    @Test
    fun `an unflagged frame with only an id is a response, and data stands in for ok`() {
        val withData = parseProtoo("""{"id":6,"data":{"id":"t-9"}}""") as ProtooMessage.Response
        assertTrue(withData.ok, "a payload is the only success signal these frames carry")

        val withoutData = parseProtoo("""{"id":6}""") as ProtooMessage.Response
        assertEquals(false, withoutData.ok, "no payload and no ok is not a success")

        val explicit = parseProtoo("""{"id":6,"ok":false,"errorCode":408,"errorReason":"timeout"}""")
                as ProtooMessage.Response
        assertEquals(false, explicit.ok, "an explicit ok always wins over the inference")
        assertEquals(408, explicit.errorCode)
    }

    @Test
    fun `an unflagged frame with only a method is a notification`() {
        val parsed = parseProtoo("""{"method":"activeSpeaker","data":{"peerId":"u:d"}}""")
        assertTrue(parsed is ProtooMessage.Notification, "expected a notification, got $parsed")
    }

    // ── things that are not protoo ──────────────────────────────────────

    @Test
    fun `garbage is dropped rather than thrown`() {
        assertNull(parseProtoo("not json at all"))
        assertNull(parseProtoo("[1,2,3]"), "an array is not a protoo frame")
        assertNull(parseProtoo("""{"hello":"world"}"""), "neither id nor method")
    }

    @Test
    fun `a missing data object reads as empty, never null`() {
        val note = parseProtoo("""{"notification":true,"method":"endCall"}""") as ProtooMessage.Notification
        assertEquals(JsonObject(emptyMap()), note.data, "endCall carries no payload and must still parse")
    }

    /** Some servers quote the id. Both forms address the same pending request. */
    @Test
    fun `a quoted id is still an id`() {
        val parsed = parseProtoo("""{"response":true,"id":"1234","ok":true,"data":{}}""") as ProtooMessage.Response
        assertEquals(1234L, parsed.id)
    }
}
