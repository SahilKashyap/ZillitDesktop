package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.CallDiagnostics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Line 3's telemetry, against the backend's rules.
 *
 * Each of these is a rule the server states and the client has to keep:
 * silence outside a call, one transport per line, a self-imposed budget, and
 * an event set that matches the phones. Getting any of them wrong is invisible
 * here and shows up as a flooded or an empty server log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallDiagnosticsTest {

    private class Sent {
        val socket = mutableListOf<Triple<String, String, JsonObject>>()
        val rest = mutableListOf<Triple<String, String, JsonObject>>()
        var socketUp = true
        var socketCarries = true
    }

    private fun kotlinx.coroutines.test.TestScope.diagnostics(
        sent: Sent,
        now: () -> Long = { 0L },
    ) = CallDiagnostics(
        scope = backgroundScope,
        now = now,
        socketOnline = { sent.socketUp },
        sendOverSocket = { callId, event, data ->
            sent.socket += Triple(callId, event, data)
            sent.socketCarries
        },
        sendOverRest = { callId, event, data -> sent.rest += Triple(callId, event, data) },
    )

    @Test
    fun `nothing is sent outside a call`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.log("ring_shown")
        diagnostics.selfSpeaking(speaking = true, level = 0.4)
        runCurrent()
        assertTrue(sent.socket.isEmpty() && sent.rest.isEmpty(), "idle and between calls, it is silent")
        assertTrue(!diagnostics.isActive)

        diagnostics.startCall("c1")
        diagnostics.log("ring_shown", mapOf("source" to "socket"))
        runCurrent()
        assertEquals("c1" to "ring_shown", sent.socket.single().let { it.first to it.second })

        diagnostics.endCall("hangup")
        runCurrent()
        // `disconnect` goes out FIRST, while the id is still live — after the
        // close it would have no call id and be dropped, every time.
        assertEquals("disconnect", sent.socket.last().second)
        assertEquals("hangup", sent.socket.last().third.text("reason"))
        diagnostics.log("audio")
        runCurrent()
        assertEquals(2, sent.socket.size, "the window is shut")
    }

    @Test
    fun `every line says which client sent it`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.startCall("c1")
        diagnostics.log("audio", mapOf("outPkts" to 12, "micOn" to true, "level" to 0.25))
        runCurrent()
        val data = sent.socket.single().third
        assertEquals(CallDiagnostics.PLATFORM, data.text("platform"))
        // Numbers and booleans keep their JSON type: the server charts them.
        assertEquals(12, data.text("outPkts")?.toInt())
        assertEquals(true, data.flag("micOn"))
    }

    @Test
    fun `a line goes over REST only when the socket did not carry it`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.startCall("c1")

        diagnostics.log("ice")
        runCurrent()
        assertEquals(1, sent.socket.size)
        assertTrue(sent.rest.isEmpty(), "never both")

        sent.socketCarries = false
        diagnostics.log("audio")
        runCurrent()
        assertEquals("audio", sent.rest.single().second)

        sent.socketUp = false
        diagnostics.log("speak")
        runCurrent()
        assertEquals(2, sent.rest.size)
        assertEquals(2, sent.socket.size, "a socket that is down is not even tried")
    }

    @Test
    fun `over budget, lines are discarded rather than queued`() = runTest {
        val sent = Sent()
        var clock = 0L
        val diagnostics = diagnostics(sent) { clock }
        diagnostics.startCall("c1")
        repeat(CallDiagnostics.MAX_PER_MINUTE + 10) { diagnostics.log("audio") }
        runCurrent()
        assertEquals(CallDiagnostics.MAX_PER_MINUTE, sent.socket.size)

        // The window slides: the next minute is a fresh allowance, and nothing
        // from the last one was held back to fill it.
        clock += CallDiagnostics.WINDOW_MILLIS
        diagnostics.log("audio")
        runCurrent()
        assertEquals(CallDiagnostics.MAX_PER_MINUTE + 1, sent.socket.size)
    }

    @Test
    fun `logOnce is once per call, not once per process`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.startCall("c1")
        diagnostics.logOnce("warm_connect", mapOf("ms" to 120))
        diagnostics.logOnce("warm_connect", mapOf("ms" to 900))
        runCurrent()
        assertEquals(1, sent.socket.count { it.second == "warm_connect" })

        diagnostics.endCall()
        diagnostics.startCall("c2")
        diagnostics.logOnce("warm_connect", mapOf("ms" to 200))
        runCurrent()
        assertEquals(
            listOf("c1", "c2"),
            sent.socket.filter { it.second == "warm_connect" }.map { it.first },
            "one call's first-line set must not silence the next call's",
        )
    }

    @Test
    fun `speak is edge-triggered and debounced`() = runTest {
        val sent = Sent()
        var clock = 0L
        val diagnostics = diagnostics(sent) { clock }
        diagnostics.startCall("c1")

        diagnostics.selfSpeaking(speaking = true, level = 0.5)
        diagnostics.selfSpeaking(speaking = true, level = 0.9)
        runCurrent()
        assertEquals(1, sent.socket.size, "the same state again is not an edge")

        // A flip inside the debounce is dropped: a chatty room flips the active
        // speaker several times a second and would eat the whole budget.
        diagnostics.selfSpeaking(speaking = false, level = 0.0)
        runCurrent()
        assertEquals(1, sent.socket.size)

        clock += CallDiagnostics.SPEAK_DEBOUNCE_MILLIS
        diagnostics.selfSpeaking(speaking = false, level = 0.0)
        runCurrent()
        assertEquals(2, sent.socket.size)
        assertEquals(false, sent.socket.last().third.flag("speaking"))
    }

    @Test
    fun `an oversized payload is replaced by its own head rather than cut mid-key`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.startCall("c1")
        diagnostics.log("ice", mapOf("url" to "x".repeat(CallDiagnostics.MAX_DATA_CHARS * 2)))
        runCurrent()
        val data = sent.socket.single().third
        assertEquals(true, data.flag("truncated"))
        assertEquals(CallDiagnostics.PLATFORM, data.text("platform"), "the marker survives truncation")
        assertTrue(data.toString().length <= CallDiagnostics.MAX_DATA_CHARS + PAYLOAD_SLACK)
    }

    @Test
    fun `an event name is bounded, because the server bounds it`() = runTest {
        val sent = Sent()
        val diagnostics = diagnostics(sent)
        diagnostics.startCall("c1")
        diagnostics.log("e".repeat(CallDiagnostics.EVENT_MAX * 2))
        runCurrent()
        assertEquals(CallDiagnostics.EVENT_MAX, sent.socket.single().second.length)
    }

    private companion object {
        /** Room for the JSON wrapper around a truncated payload's own head. */
        const val PAYLOAD_SLACK = 96
    }
}

/** One field of a sent payload, however the writer typed it. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
