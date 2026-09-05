package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.LiveKitEvent
import com.zillit.desktop.feature.calls.data.livekit.LiveKitPeer
import com.zillit.desktop.feature.calls.data.livekit.LiveKitSignalError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Request correlation on the presence socket.
 *
 * As with the protoo peer, every interesting case is a race: an answer that
 * arrives after its timeout, a socket closing under a suspended caller, a
 * reply nobody waits for. Each of those, done wrong, is a call that hangs
 * with nothing in the log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveKitPeerTest {

    private fun reqIdOf(frame: String): String =
        (Json.parseToJsonElement(frame).jsonObject["reqId"] as JsonPrimitive).content

    @Test
    fun `a request is answered by its reqId and the data comes back`() = runTest {
        val sent = mutableListOf<String>()
        val peer = LiveKitPeer(backgroundScope, send = { sent += it })
        val answer = async { runCatching { peer.request("listActiveCalls") } }
        runCurrent()

        val id = reqIdOf(sent.single())
        peer.onFrame("""{"reqId":"$id","ok":true,"data":{"calls":[{"callId":"c1"}]}}""")

        val data = answer.await().getOrThrow() as JsonObject
        assertEquals("""{"calls":[{"callId":"c1"}]}""", data.toString())
    }

    @Test
    fun `a refusal surfaces the server's error word`() = runTest {
        val sent = mutableListOf<String>()
        val peer = LiveKitPeer(backgroundScope, send = { sent += it })
        val answer = async {
            runCatching { peer.request("startCall", buildJsonObject { put("callId", JsonPrimitive("c")) }) }
        }
        runCurrent()

        peer.onFrame("""{"reqId":"${reqIdOf(sent.single())}","ok":false,"error":"caller_busy"}""")

        val error = assertIs<LiveKitSignalError>(answer.await().exceptionOrNull())
        assertEquals("caller_busy", error.code, "the word the UI maps to a message")
    }

    @Test
    fun `silence times out, and a late answer is ignored`() = runTest {
        val sent = mutableListOf<String>()
        val peer = LiveKitPeer(backgroundScope, send = { sent += it }, timeoutMillis = 1_000)
        val answer = async { runCatching { peer.request("acceptCall") } }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(LiveKitPeer.CODE_TIMEOUT, assertIs<LiveKitSignalError>(answer.await().exceptionOrNull()).code)
        // Nothing waits any more; the answer must land quietly.
        peer.onFrame("""{"reqId":"${reqIdOf(sent.single())}","ok":true}""")
    }

    @Test
    fun `closing fails every waiter with the offline word`() = runTest {
        val sent = mutableListOf<String>()
        val peer = LiveKitPeer(backgroundScope, send = { sent += it })
        val one = async { runCatching { peer.request("ringingAck") } }
        val two = async { runCatching { peer.request("getCallRoster") } }
        runCurrent()

        peer.close("socket dropped")

        assertEquals(LiveKitPeer.CODE_CLOSED, assertIs<LiveKitSignalError>(one.await().exceptionOrNull()).code)
        assertEquals(LiveKitPeer.CODE_CLOSED, assertIs<LiveKitSignalError>(two.await().exceptionOrNull()).code)
        assertFailsWith<LiveKitSignalError> { peer.request("leaveCall") }
    }

    @Test
    fun `a frame without a reqId is an event`() = runTest {
        val peer = LiveKitPeer(backgroundScope, send = { })
        val seen = async { peer.events.first() }
        runCurrent()

        peer.onFrame("""{"type":"callAccepted","callId":"c","userId":"u"}""")

        assertIs<LiveKitEvent.RingState>(seen.await())
    }

    @Test
    fun `a send that throws fails only that request`() = runTest {
        val sent = mutableListOf<String>()
        var fail = true
        val peer = LiveKitPeer(backgroundScope, send = { if (fail) error("gone") else sent += it })
        val first = async { runCatching { peer.request("holdCall") } }
        runCurrent()
        assertEquals(LiveKitPeer.CODE_SEND_FAILED, assertIs<LiveKitSignalError>(first.await().exceptionOrNull()).code)

        fail = false
        val second = launch { peer.request("resumeCall") }
        runCurrent()
        assertEquals(1, sent.size, "the peer stays usable after one failed send")
        second.cancel()
    }
}
