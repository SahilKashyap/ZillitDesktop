package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.feature.calls.data.livekit.LiveKitApi
import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitCallPolicy
import com.zillit.desktop.feature.calls.data.livekit.LiveKitJoin
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

    private class Listener : LiveKitLineListener {
        val invites = mutableListOf<CallSession>()
        val rings = mutableListOf<Triple<String, CallStatus, Boolean>>()
        val dismissed = mutableListOf<Pair<String, LiveKitDismissal>>()
        val ended = mutableListOf<String>()
        val rosters = mutableListOf<List<CallParticipant>>()
        val activeCalls = mutableListOf<List<LiveKitActiveCall>>()
        val reactions = mutableListOf<Triple<String, String, String>>()
        override fun onReaction(callId: String, userId: String, emoji: String) {
            reactions += Triple(callId, userId, emoji)
        }
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
        override fun onDismissed(callId: String, why: LiveKitDismissal, byName: String) {
            dismissed += callId to why
        }
        override fun onEnded(reason: String) { ended += reason }
        override fun onRoster(callId: String, participants: List<CallParticipant>) { rosters += participants }
        override fun onActiveCalls(calls: List<LiveKitActiveCall>) { activeCalls += calls }
    }

    private fun kotlinx.coroutines.test.TestScope.line(
        socket: LiveKitFakeSocket,
        http: LiveKitFakeHttp,
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
        val socket = LiveKitFakeSocket()
        val (line, _) = line(socket, LiveKitFakeHttp())
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
        val socket = LiveKitFakeSocket()
        val (line, listener) = line(socket, LiveKitFakeHttp())
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
        assertEquals("me-there", invite.selfUserId, "the callee's id on the ringing project")
        assertTrue(socket.sentTypes().contains("ringingAck"), socket.sentTypes().toString())
    }

    @Test
    fun `placing a call creates over REST, rings over the socket, and joins with the ack's room`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp().apply { answers["/v1/calls"] = """{"callId":"c9","wsUrl":"","token":""}""" }
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
        assertEquals("p1" to "me", http.calls.single().third, "signed as the caller on the call's project")
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
        val socket = LiveKitFakeSocket().apply { refuse = true }
        val http = LiveKitFakeHttp().apply {
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
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp().apply {
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

    /**
     * Android and the web join whatever reachable address the server names;
     * the configured one is only the stand-in for an unreachable one.
     */
    @Test
    fun `a reachable room from the server beats the configured one`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp().apply {
            answers["/v1/calls"] = """{"callId":"c9","livekit":{"token":"t","url":"wss://node-eu.zillit.com"}}"""
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

        assertEquals("wss://node-eu.zillit.com", (placed.await() as ZillitResult.Success).data.url)
        assertTrue(!http.paths().contains("/v1/livekit/token"), "nothing to mint: the ring's room is reachable")
    }

    @Test
    fun `without a usable room the token is minted`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp().apply {
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
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
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
        val socket = LiveKitFakeSocket().apply { refuse = true }
        val http = LiveKitFakeHttp().apply {
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
        assertEquals("p2" to "me-there", auth, "signed as the call's project and the callee's id there")

        line.decline(ring)
        assertTrue(http.paths().last().endsWith("/v1/calls/c1/hangup"))
    }

    @Test
    fun `ring states, dismissals and endings reach the listener in the machine's words`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, listener) = line(socket, LiveKitFakeHttp())
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
    fun `a reaction goes over the socket, and over REST when the socket refuses`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
        val (line, listener) = line(socket, http)
        line.start()
        runCurrent()

        val first = async { line.react("c", "👍", "p1", "me") }
        runCurrent()
        socket.answer("reactCall")
        first.await()
        val sent = socket.frames.last { it["type"]!!.jsonPrimitive.content == "reactCall" }
        assertEquals("👍", sent["emoji"]!!.jsonPrimitive.content)
        assertTrue(http.paths().none { it.endsWith("/react") }, "the socket carried it")

        val second = async { line.react("c", "❤️", "p1", "me") }
        runCurrent()
        socket.answer("reactCall", "\"policy\"", ok = false)
        second.await()
        assertEquals("/v1/calls/c/react", http.paths().last())

        // Nothing floats until the server echoes it.
        socket.push("""{"type":"callReaction","callId":"c","userId":"me","emoji":"👍"}""")
        runCurrent()
        assertEquals(Triple("c", "me", "👍"), listener.reactions.single())
    }

    @Test
    fun `hold and resume ride the socket with REST twins`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()

        val held = async { line.hold("c", on = true, "p1", "me") }
        runCurrent()
        socket.answer("holdCall")
        held.await()
        assertTrue("holdCall" in socket.sentTypes())

        line.disconnect("test")
        line.hold("c", on = false, "p1", "me")
        assertEquals("/v1/calls/c/resume", http.paths().last())
    }

    @Test
    fun `the host's verbs are socket-only and carry their fields`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, _) = line(socket, LiveKitFakeHttp())
        line.start()
        runCurrent()

        val policy = async {
            line.setCallPolicy("c", LiveKitCallPolicy(on = true, chatEnabled = false).toPatch())
        }
        runCurrent()
        val patch = socket.frames.last { it["type"]!!.jsonPrimitive.content == "setCallPolicy" }["patch"]!!.jsonObject
        assertEquals("false", patch["chatEnabled"]!!.jsonPrimitive.content)
        socket.answer("setCallPolicy")
        assertTrue(policy.await())

        val block = async { line.blockChat("c", "u", blocked = true) }
        runCurrent()
        val sent = socket.frames.last { it["type"]!!.jsonPrimitive.content == "blockChat" }
        assertEquals("u", sent["userId"]!!.jsonPrimitive.content)
        assertEquals("true", sent["blocked"]!!.jsonPrimitive.content)
        socket.answer("blockChat")
        assertTrue(block.await())

        val kick = async { line.removeFromCall("c", "u") }
        runCurrent()
        socket.answer("removeFromCall", "\"not_host\"", ok = false)
        assertTrue(!kick.await(), "a refusal is reported, not thrown")

        line.disconnect("test")
        assertTrue(!line.hostAction("c", "muteAll"), "no socket, no host verb")
    }

    @Test
    fun `a host mute and a recording mark are REST, and a second recorder is refused`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
        http.answers["/v1/livekit/recording/mark-started"] = "!409:already_recording"
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()
        val me = LiveKitIdentity("me", "Me", "p1", "Sides Testing")

        assertTrue(line.muteParticipant("c", "u", camera = true, self = me))
        val mute = http.calls.last { it.first.endsWith("/v1/livekit/mute") }
        assertEquals("camera", mute.second!!["source"]!!.jsonPrimitive.content)
        assertEquals("u", mute.second!!["targetUserId"]!!.jsonPrimitive.content)

        val refused = line.markRecording("c", on = true, self = me)
        assertIs<ZillitResult.Failure>(refused)
        val http409 = refused.error as com.zillit.desktop.core.common.ZillitError.Http
        assertEquals("already_recording", http409.serverMessage)
        assertIs<ZillitResult.Success<Unit>>(line.markRecording("c", on = false, self = me))
    }

    @Test
    fun `joining an active call accepts on the socket, then over REST for the room`() = runTest {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
        http.answers["/accept"] = """{"callId":"c9","livekit":{"token":"t9","url":"wss://node.zillit.com/rtc"}}"""
        val (line, _) = line(socket, http)
        line.start()
        runCurrent()

        val joined = async { line.joinActive("c9", LiveKitIdentity("me", "Me", "p1", "Sides Testing")) }
        runCurrent()
        socket.answer("acceptCall")
        val join = assertIs<ZillitResult.Success<LiveKitJoin>>(joined.await()).data
        assertEquals("c9", join.callId)
        assertEquals("t9", join.token)
        assertEquals("wss://node.zillit.com/rtc", join.url)
        assertEquals("/v1/calls/c9/accept", http.paths().last())
        assertTrue(http.paths().none { it.endsWith("/token") }, "the accept carried the room; nothing minted")
    }

    @Test
    fun `a dropped socket comes back on its own`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, _) = line(socket, LiveKitFakeHttp())
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
    fun `the heartbeat's answer reaches the listener as the server's active calls`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, listener) = line(socket, LiveKitFakeHttp())
        line.start()
        runCurrent()
        socket.answer(
            "listActiveCalls",
            """{"calls":[{"callId":"c1","inCallUsers":[{"userId":"me","displayName":"Me"}]},""" +
                """{"callId":"c2","inCallUsers":[]}]}""",
        )
        runCurrent()
        assertEquals(listOf("c1", "c2"), listener.activeCalls.single().map { it.callId })
        assertEquals(listOf("me"), listener.activeCalls.single().first().inCallUserIds)
    }

    @Test
    fun `a ring already dealt with here does not sound again`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, listener) = line(socket, LiveKitFakeHttp())
        line.start()
        runCurrent()
        val ring = """{"type":"incomingCall","callId":"c1","callType":"audio","callMode":"private",""" +
            """"from":{"userId":"u","displayName":"X"}}"""
        socket.push(ring)
        runCurrent()
        assertEquals(1, listener.invites.size)
        assertTrue(
            socket.sentTypes().count { it == "listActiveCalls" } >= 2,
            "a ring asks for the server's list at once",
        )
        socket.push("""{"type":"callHandledElsewhere","callId":"c1"}""")
        runCurrent()
        socket.push(ring)
        runCurrent()
        assertEquals(1, listener.invites.size, "the re-emitted ring is ignored")
        assertEquals(listOf("c1" to LiveKitDismissal.HandledElsewhere), listener.dismissed)
    }

    @Test
    fun `an expired ring is dropped`() = runTest {
        val socket = LiveKitFakeSocket()
        val (line, listener) = line(socket, LiveKitFakeHttp())
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
