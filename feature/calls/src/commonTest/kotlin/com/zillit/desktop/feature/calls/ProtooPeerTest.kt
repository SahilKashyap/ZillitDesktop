package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.ProtooError
import com.zillit.desktop.feature.calls.data.protoo.ProtooMessage
import com.zillit.desktop.feature.calls.data.protoo.ProtooPeer
import com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame
import com.zillit.desktop.feature.calls.data.protoo.protooNotificationFrame
import com.zillit.desktop.feature.calls.data.protoo.protooRejectFrame
import com.zillit.desktop.feature.calls.data.protoo.protooRequestFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Request correlation, timeouts, and the arbitration between them.
 *
 * The interesting cases are all races: an answer and a timeout arriving
 * together, a socket dying under a suspended caller, a late answer to a
 * request nobody is waiting on any more. Each of those, done wrong, produces
 * a call that hangs with nothing in the log.
 */
class ProtooPeerTest {

    /** Records what went out, and lets a test post what comes back. */
    private class Wire {
        val sent = mutableListOf<String>()
        var failNext = false
        suspend fun send(frame: String) {
            if (failNext) {
                failNext = false
                error("socket is gone")
            }
            sent += frame
        }
    }

    private fun ids(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: 999L }
    }

    @Test
    fun `a request resolves with the response payload`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids(1))

        val answer = async { peer.request("createWebRtcTransport", buildJsonObject { put("producing", true) }) }
        runCurrent()

        assertEquals(1, wire.sent.size)
        assertTrue(wire.sent.single().contains("createWebRtcTransport"))

        peer.onFrame(protooAcceptFrame(1, buildJsonObject { put("id", "transport-1") }))
        runCurrent()

        assertEquals("transport-1", answer.await()["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a rejected request throws with the server's code and reason`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids(2))

        val answer = async { runCatching { peer.request("join") } }
        runCurrent()
        peer.onFrame(protooRejectFrame(2, 409, "room is full"))
        runCurrent()

        val error = answer.await().exceptionOrNull() as? ProtooError
        assertTrue(error != null, "expected a ProtooError")
        assertEquals(409, error.code)
        assertEquals("room is full", error.reason)
    }

    /** Ten seconds flat, and it must actually fire. */
    @Test
    fun `a request that is never answered times out at ten seconds`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids(3))

        val answer = async { runCatching { peer.request("getRouterRtpCapabilities") } }
        runCurrent()

        advanceTimeBy(ProtooPeer.REQUEST_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertTrue(!answer.isCompleted, "must still be waiting just before the deadline")

        advanceTimeBy(2)
        runCurrent()

        val error = answer.await().exceptionOrNull() as? ProtooError
        assertEquals(ProtooPeer.CODE_TIMEOUT, error?.code)
    }

    /**
     * The arbitration. A timer already dispatched cannot be called back, so an
     * answer landing in the same instant must not also complete the request —
     * exactly one path wins, and the loser finds nothing to complete.
     */
    @Test
    fun `an answer arriving after the timeout does not complete anything twice`() =
        runTest(StandardTestDispatcher()) {
            val wire = Wire()
            val peer = ProtooPeer(backgroundScope, wire::send, ids(4))

            val answer = async { runCatching { peer.request("join") } }
            runCurrent()
            advanceTimeBy(ProtooPeer.REQUEST_TIMEOUT_MILLIS + 1)
            runCurrent()
            assertEquals(ProtooPeer.CODE_TIMEOUT, (answer.await().exceptionOrNull() as ProtooError).code)

            // The late answer must be a no-op rather than a second completion.
            peer.onFrame(protooAcceptFrame(4, buildJsonObject { put("id", "late") }))
            runCurrent()
        }

    @Test
    fun `a send that fails rejects immediately rather than waiting out the timeout`() =
        runTest(StandardTestDispatcher()) {
            val wire = Wire()
            wire.failNext = true
            val peer = ProtooPeer(backgroundScope, wire::send, ids(5))

            val answer = async { runCatching { peer.request("join") } }
            runCurrent()

            assertTrue(answer.isCompleted, "a dead socket must not cost ten seconds")
            assertEquals(ProtooPeer.CODE_SEND_FAILED, (answer.await().exceptionOrNull() as ProtooError).code)
        }

    /**
     * When the socket dies, everything waiting has to be told. Otherwise a
     * join sits ten seconds behind a socket that is provably gone.
     */
    @Test
    fun `failAll rejects every outstanding request at once`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids(6, 7))

        val first = async { runCatching { peer.request("createWebRtcTransport") } }
        val second = async { runCatching { peer.request("getRouterRtpCapabilities") } }
        runCurrent()

        peer.failAll(4409, "replaced-by-other-device")
        runCurrent()

        for (answer in listOf(first, second)) {
            val error = answer.await().exceptionOrNull() as ProtooError
            assertEquals(4409, error.code)
            assertEquals("replaced-by-other-device", error.reason)
        }
    }

    @Test
    fun `a closed peer refuses new requests instead of hanging`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids(8))
        peer.close()
        runCurrent()

        val answer = async { runCatching { peer.request("join") } }
        runCurrent()

        assertEquals(ProtooPeer.CODE_CLOSED, (answer.await().exceptionOrNull() as ProtooError).code)
    }

    @Test
    fun `notifications and server requests are surfaced separately`() = runTest(StandardTestDispatcher()) {
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send, ids())

        val notes = mutableListOf<ProtooMessage.Notification>()
        val asks = mutableListOf<ProtooMessage.Request>()
        val a = launch { peer.notifications.collect { notes += it } }
        val b = launch { peer.requests.collect { asks += it } }
        runCurrent()

        peer.onFrame(protooNotificationFrame("activeSpeaker", buildJsonObject { put("peerId", "u:d") }))
        peer.onFrame(protooRequestFrame(100, "newConsumer", JsonObject(emptyMap())))
        runCurrent()
        a.cancel(); b.cancel()

        assertEquals(listOf("activeSpeaker"), notes.map { it.method })
        assertEquals(listOf("newConsumer"), asks.map { it.method })
        assertEquals(100L, asks.single().id)
    }

    /** A server request we do not implement must be refused, not ignored. */
    @Test
    fun `rejecting a server request puts a well-formed refusal on the wire`() =
        runTest(StandardTestDispatcher()) {
            val wire = Wire()
            val peer = ProtooPeer(backgroundScope, wire::send, ids())

            peer.reject(11, ProtooPeer.CODE_UNKNOWN_METHOD, "unknown method: mystery")
            runCurrent()

            val frame = wire.sent.single()
            assertTrue(frame.contains("\"ok\":false"), frame)
            assertTrue(frame.contains("403"), frame)
            assertTrue(frame.contains("unknown method: mystery"), frame)
        }
}
