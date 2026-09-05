package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.feature.calls.data.livekit.LiveKitApi
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDial
import com.zillit.desktop.feature.calls.data.livekit.LiveKitDismissal
import com.zillit.desktop.feature.calls.data.livekit.LiveKitIdentity
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLineListener
import com.zillit.desktop.feature.calls.data.livekit.LiveKitSocket
import com.zillit.desktop.feature.calls.data.livekit.LiveKitSocketFactory
import com.zillit.desktop.feature.calls.data.livekit.SignedJsonHttp
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Line 3 end to end, against a scripted backend.
 *
 * What is pinned is the sequence, because the sequence is the contract:
 * which request goes where, in what order, and with what on it. A ring
 * that never acks, an accept that mints a token when the ring already
 * carried one, or a hang-up sent to the wrong route are all invisible on
 * this side and expensive on the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveKitLineTest {

    /** The presence socket, as a script: what the line sent, and a way to answer or push events. */
    private class FakeSocket : LiveKitSocketFactory, LiveKitSocket {
        val connects = mutableListOf<Pair<String, Map<String, String>>>()
        val frames = mutableListOf<JsonObject>()
        var onFrame: (String) -> Unit = {}
        var onClosed: (String) -> Unit = {}
        var refuse = false

        override suspend fun connect(
            url: String,
            headers: Map<String, String>,
            onFrame: (String) -> Unit,
            onClosed: (reason: String) -> Unit,
        ): LiveKitSocket {
            if (refuse) error("upgrade refused")
            connects += url to headers
            this.onFrame = onFrame
            this.onClosed = onClosed
            return this
        }

        override fun send(frame: String): Boolean {
            frames += Json.parseToJsonElement(frame).jsonObject
            return true
        }

        override fun close(reason: String) = onClosed(reason)

        /** Answers the last request of [type] with [data]. */
        fun answer(type: String, data: String = "{}", ok: Boolean = true) {
            val req = frames.last { it["type"]!!.jsonPrimitive.content == type }
            val id = req["reqId"]!!.jsonPrimitive.content
            val field = if (ok) "\"data\":$data" else "\"error\":$data"
            onFrame("""{"reqId":"$id","ok":$ok,$field}""")
        }

        fun push(event: String) = onFrame(event)

        fun sentTypes() = frames.map { it["type"]!!.jsonPrimitive.content }
    }

    /** The REST side: every call recorded, answers scripted per path suffix. */
    private class FakeHttp : SignedJsonHttp {
        val calls = mutableListOf<Triple<String, JsonObject?, Pair<String?, String?>>>()
        val answers = mutableMapOf<String, String>()

        override suspend fun call(
            verb: HttpVerb,
            url: String,
            body: JsonObject?,
            projectId: String?,
            userId: String?,
        ): ZillitResult<JsonElement?> {
            calls += Triple(url, body, projectId to userId)
            val answer = answers.entries.firstOrNull { url.endsWith(it.key) }?.value ?: "{}"
            return ZillitResult.Success(Json.parseToJsonElement(answer))
        }

        fun paths() = calls.map { it.first.substringAfter("/api") }
    }

    private class Listener : LiveKitLineListener {
        val invites = mutableListOf<CallSession>()
        val rings = mutableListOf<Triple<String, CallStatus, Boolean>>()
        val dismissed = mutableListOf<Pair<String, LiveKitDismissal>>()
        val ended = mutableListOf<String>()
        val rosters = mutableListOf<List<CallParticipant>>()
        override fun onInvite(session: CallSession) { invites += session }
        override fun onRingState(
            callId: String,
            userId: String,
            displayName: String,
            status: CallStatus,
            busy: Boolean,
        ) {
            rings += Triple(userId, status, busy)
        }
        override fun onDismissed(callId: String, why: LiveKitDismissal) { dismissed += callId to why }
        override fun onEnded(reason: String) { ended += reason }
        override fun onRoster(callId: String, participants: List<CallParticipant>) { rosters += participants }
    }

    private fun kotlinx.coroutines.test.TestScope.line(
        socket: FakeSocket,
        http: FakeHttp,
        override: String? = null,
    ): Pair<LiveKitLine, Listener> {
        val listener = Listener()
        val line = LiveKitLine(
            scope = backgroundScope,
            api = LiveKitApi(http, "https://calls.test/api"),
            sockets = socket,
            socketUrl = { "wss://calls.test/ws" },
            handshake = { "BLOB" },
            identity = { LiveKitIdentity("me", "Me", "p1", "Sides Testing") },
            roomUrlOverride = { override },
            nowMillis = { 1_000L },
        )
        line.attach(listener)
        return line to listener
    }

    @Test
    fun `presence connects with the handshake blob on the query and the header, then registers`() = runTest {
        val socket = FakeSocket()
        val (line, _) = line(socket, FakeHttp())
        line.start()
        runCurrent()

        val (url, headers) = socket.connects.single()
        assertTrue(url.contains("?moduledata=BLOB"), url)
        assertEquals("BLOB", headers["moduledata"])
        assertEquals(
            listOf("listActiveCalls"),
            socket.sentTypes(),
            "the first heartbeat doubles as the registration check",
        )
        assertTrue(line.online.value)
    }

    @Test
    fun `a ring becomes an invite and is acknowledged on the socket`() = runTest {
        val socket = FakeSocket()
        val (line, listener) = line(socket, FakeHttp())
        line.start()
        runCurrent()

        socket.push(
            """{"type":"incomingCall","callId":"c1","callType":"audio","callMode":"private",
                "from":{"userId":"u-caller","displayName":"Vivek"},"toUserId":"me-there","projectId":"p2",
                "livekit":{"token":"tok","url":"wss://node.zillit.com/livekit"}}""",
        )
        runCurrent()

        val invite = listener.invites.single()
        assertEquals(CallProvider.LiveKit, invite.provider)
        assertEquals("me-there", invite.selfUserId, "the callee's id on the ringing production")
        assertTrue(socket.sentTypes().contains("ringingAck"), socket.sentTypes().toString())
    }

    @Test
    fun `placing a call creates over REST, rings over the socket, and joins with the ack's room`() = runTest {
        val socket = FakeSocket()
        val http = FakeHttp().apply { answers["/v1/calls"] = """{"callId":"c9","wsUrl":"","token":""}""" }
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()

        val placed = async { line.place(dial(listOf("u-2"), projectName = "Sides Testing")) }
        runCurrent()
        assertEquals(listOf("/v1/calls"), http.paths(), "created before it rings")
        // The mint is the web's bare create: `group`, nobody named — the backend
        // refuses a `private` call with no callees. The ring describes the call.
        val mint = http.calls.single().second!!
        assertEquals("group", mint["type"]!!.jsonPrimitive.content)
        assertEquals(0, (mint["calleeIds"] as JsonArray).size)
        assertEquals(null, mint["callType"], "the mint does not describe the call")
        assertEquals("p1" to "me", http.calls.single().third, "signed as the caller on the call's production")
        val start = socket.frames.last { it["type"]!!.jsonPrimitive.content == "startCall" }
        assertEquals("c9", start["callId"]!!.jsonPrimitive.content)
        assertEquals("video", start["callType"]!!.jsonPrimitive.content)
        assertEquals("private", start["callMode"]!!.jsonPrimitive.content)
        assertEquals("p1", start["projectId"]!!.jsonPrimitive.content)

        socket.answer("startCall", """{"livekit":{"token":"ring-tok","url":"wss://region.zillit.com/livekit"}}""")
        runCurrent()

        val join = (placed.await() as ZillitResult.Success).data
        assertEquals("c9", join.callId)
        assertEquals("ring-tok", join.token)
        assertEquals("wss://region.zillit.com/livekit", join.url)
        assertEquals(1, http.paths().count { it == "/v1/calls" }, "no token mint when the ack carried the room")
    }

    @Test
    fun `with the socket down, the REST create carries the whole call so the server rings it`() = runTest {
        val socket = FakeSocket().apply { refuse = true }
        val http = FakeHttp().apply {
            answers["/v1/calls"] = """{"callId":"c7","livekit":{"token":"t","url":"wss://node.zillit.com/livekit"}}"""
        }
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()

        val placed = async { line.place(dial(listOf("u-2"), type = CallType.Audio)) }
        runCurrent()

        val join = (placed.await() as ZillitResult.Success).data
        assertEquals("c7", join.callId)
        assertEquals(listOf("/v1/calls"), http.paths(), "one create, which also rings")
        val body = http.calls.single().second!!
        assertEquals("private", body["type"]!!.jsonPrimitive.content)
        assertEquals("private", body["callMode"]!!.jsonPrimitive.content)
        assertEquals("audio", body["callType"]!!.jsonPrimitive.content)
        assertEquals("u-2", (body["calleeIds"] as JsonArray).single().jsonPrimitive.content)
        assertEquals("p1", body["projectId"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), socket.sentTypes(), "nothing could go over a socket that is not there")
    }

    @Test
    fun `an internal room address is replaced by the configured public one`() = runTest {
        val socket = FakeSocket()
        val http = FakeHttp().apply {
            answers["/v1/calls"] = """{"callId":"c9","livekit":{"token":"t","url":"ws://localhost:7880"}}"""
        }
        val (line, _) = line(socket, http, override = "wss://calls.zillit.com/livekit")
        line.start()
        runCurrent()

        val placed = async {
            line.place(dial(emptyList(), chatRoomId = "room-1", mode = CallMode.Group, type = CallType.Audio))
        }
        runCurrent()
        socket.answer("startCall")
        runCurrent()

        assertEquals("wss://calls.zillit.com/livekit", (placed.await() as ZillitResult.Success).data.url)
    }

    @Test
    fun `without a usable room the token is minted`() = runTest {
        val socket = FakeSocket()
        val http = FakeHttp().apply {
            answers["/v1/calls"] = """{"callId":"c9","livekit":{"token":"t","url":"ws://localhost:7880"}}"""
            answers["/v1/livekit/token"] = """{"token":"minted","url":"wss://eu.zillit.com/livekit"}"""
        }
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()

        val placed = async { line.place(dial(listOf("u-2"), type = CallType.Audio)) }
        runCurrent()
        socket.answer("startCall")
        runCurrent()

        val join = (placed.await() as ZillitResult.Success).data
        assertEquals("minted" to "wss://eu.zillit.com/livekit", join.token to join.url)
        assertTrue(http.paths().contains("/v1/livekit/token"))
    }

    @Test
    fun `accepting over the socket uses the ring's own room and never mints`() = runTest {
        val socket = FakeSocket()
        val http = FakeHttp()
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()
        val ring = CallSession(
            callUuid = "c1", provider = CallProvider.LiveKit, selfUserId = "me-there", projectId = "p2",
            livekitUrl = "wss://node.zillit.com/livekit", livekitToken = "tok",
        )

        val accepted = async { line.accept(ring, "Me") }
        runCurrent()
        socket.answer("acceptCall")
        runCurrent()

        val join = (accepted.await() as ZillitResult.Success).data
        assertEquals("tok", join.token)
        assertTrue(http.paths().isEmpty(), "nothing over REST: ${http.paths()}")
    }

    @Test
    fun `with the socket down, accept and decline go over REST as the call's identity`() = runTest {
        val socket = FakeSocket().apply { refuse = true }
        val http = FakeHttp().apply {
            answers["/accept"] =
                """{"callId":"c1","livekit":{"token":"rest-tok","url":"wss://node.zillit.com/livekit"}}"""
        }
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()
        val ring = CallSession(
            callUuid = "c1",
            provider = CallProvider.LiveKit,
            selfUserId = "me-there",
            projectId = "p2",
        )

        val accepted = async { line.accept(ring, "Me") }
        runCurrent()
        assertEquals("rest-tok", (accepted.await() as ZillitResult.Success).data.token)
        val (_, _, auth) = http.calls.last()
        assertEquals("p2" to "me-there", auth, "signed as the call's production and the callee's id there")

        line.decline(ring)
        assertTrue(http.paths().last().endsWith("/v1/calls/c1/hangup"))
    }

    @Test
    fun `ring states, dismissals and endings reach the listener in the machine's words`() = runTest {
        val socket = FakeSocket()
        val (line, listener) = line(socket, FakeHttp())
        line.start()
        runCurrent()

        socket.push("""{"type":"callBusy","callId":"c","userId":"u"}""")
        socket.push("""{"type":"callAccepted","callId":"c","userId":"u"}""")
        socket.push("""{"type":"callHandledElsewhere","callId":"c"}""")
        socket.push("""{"type":"callEnded","reason":"hangup"}""")
        runCurrent()

        assertEquals(Triple("u", CallStatus.Declined, true), listener.rings[0])
        assertEquals(Triple("u", CallStatus.InCall, false), listener.rings[1])
        assertEquals("c" to LiveKitDismissal.HandledElsewhere, listener.dismissed.single())
        assertEquals(listOf("hangup"), listener.ended)
    }

    @Test
    fun `a dropped socket comes back on its own`() = runTest {
        val socket = FakeSocket()
        val (line, _) = line(socket, FakeHttp())
        line.start()
        runCurrent()
        assertEquals(1, socket.connects.size)

        socket.close("network gone")
        runCurrent()
        assertTrue(!line.online.value)
        advanceTimeBy(LiveKitLine.backoff(0) + 1)
        runCurrent()

        assertEquals(2, socket.connects.size, "redialled after the backoff")
        assertTrue(line.online.value)
    }

    @Test
    fun `the room url rule tells a node's own address from a reachable one`() {
        assertTrue(LiveKitLine.isRemoteRoomUrl("wss://calls.zillit.com/livekit"))
        assertTrue(!LiveKitLine.isRemoteRoomUrl("ws://localhost:7880"))
        assertTrue(!LiveKitLine.isRemoteRoomUrl("ws://127.0.0.1:7880"))
        assertTrue(!LiveKitLine.isRemoteRoomUrl("/livekit"))
    }

    @Test
    fun `an expired ring is dropped`() = runTest {
        val socket = FakeSocket()
        val (line, listener) = line(socket, FakeHttp())
        line.start()
        runCurrent()

        socket.push(
            """{"type":"incomingCall","callId":"old","callType":"audio","callMode":"private","from":""" +
                """{"userId":"u","displayName":"X"},"expiresAt":999}""",
        )
        runCurrent()

        assertTrue(listener.invites.isEmpty())
        assertIs<JsonPrimitive>(JsonPrimitive("sanity"))
    }
}

/** A dial from "me" on p1 — the fields every placed call in these tests shares. */
private fun dial(
    callees: List<String>,
    chatRoomId: String? = null,
    mode: CallMode = CallMode.Private,
    type: CallType = CallType.Video,
    projectName: String? = null,
) = LiveKitDial(callees, chatRoomId, mode, type, "me", "Me", "p1", projectName)
