package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallEndEvent
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.data.livekit.LiveKitApi
import com.zillit.desktop.feature.calls.data.livekit.LiveKitIdentity
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.data.livekit.MeetLobby
import com.zillit.desktop.feature.calls.data.livekit.MeetLobbyRequest
import com.zillit.desktop.feature.calls.data.livekit.MeetLobbyStatus
import com.zillit.desktop.feature.calls.data.livekit.MeetProblem
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.data.livekit.MeetRejoinState
import com.zillit.desktop.feature.calls.domain.LobbyMediaAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Line 1 meet lobby, end to end against the scripted calling backend:
 * the join decision tree, the knock, the REST fallback, the roster strip, and
 * the ownership rule that keeps an ended call from bringing the lobby back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetLobbyTest {

    private class QuietSocket : SocketClient {
        override val connectionState =
            MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("fake"))
        override val messages: Flow<SocketMessage> = MutableSharedFlow()
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)

        override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> = ZillitResult.Success(Json.parseToJsonElement("{}"))
    }

    /** Records the joins and every lobby device call; the connect lands when the test says. */
    private class MeetEngine : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 16)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = true
        val joins = mutableListOf<CallJoin>()
        val lobby = mutableListOf<String>()
        var leaves = 0
        var access = LobbyMediaAccess(microphone = true, camera = true)

        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) {
            joins += params
        }

        suspend fun connect() = _events.emit(CallEngineEvent.Joined("me", 42))
        suspend fun link(state: EngineConnection) = _events.emit(CallEngineEvent.ConnectionChanged(state))
        suspend fun failed(message: String) = _events.emit(CallEngineEvent.Failed(message))
        override suspend fun leave() {
            leaves++
        }

        override suspend fun lobbyMediaAccess(video: Boolean) = access
        override fun openLobbyMedia(video: Boolean) { lobby += "open:$video" }
        override fun setLobbyCamera(on: Boolean) { lobby += "camera:$on" }
        override fun endLobbyPreview() { lobby += "end" }
        override fun closeLobbyMedia() { lobby += "close" }
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }

    private class Harness(
        val coordinator: CallCoordinator,
        val lobby: MeetLobby,
        val socket: LiveKitFakeSocket,
        val http: LiveKitFakeHttp,
        val engine: MeetEngine,
        val toasts: MutableList<String>,
        val ended: MutableList<CallEndEvent>,
        val clock: LongArray,
    )

    private fun TestScope.harness(socketDown: Boolean = false, http: LiveKitFakeHttp = LiveKitFakeHttp()): Harness {
        val socket = LiveKitFakeSocket().apply { refuse = socketDown }
        val engine = MeetEngine()
        val clock = longArrayOf(10_000L)
        val line = LiveKitLine(
            scope = backgroundScope,
            api = LiveKitApi(http, "https://calls.test/api"),
            sockets = socket,
            socketUrl = { "wss://calls.test/ws" },
            handshake = { "BLOB" },
            identity = { LiveKitIdentity("me", "Me", "p1", "Sides Testing") },
            roomUrlOverride = { null },
            nowMillis = { clock[0] },
        )
        val api = CallApi(
            apiClient = com.zillit.desktop.core.network.ApiClient(
                httpClient = io.ktor.client.HttpClient(),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = com.zillit.desktop.core.config.AppConfig(
                environment = com.zillit.desktop.core.config.Environment.Develop,
                services = mapOf(com.zillit.desktop.core.config.ZillitService.Calling to "https://127.0.0.1:9"),
                realtime = emptyMap(),
            ),
        )
        val coordinator = CallCoordinator(
            api = api,
            bus = SocketEventBus(QuietSocket()),
            engine = engine,
            scope = backgroundScope,
            selfUserId = { "me" },
            selfDeviceId = { "my-device" },
            selfName = { "Me" },
            now = { clock[0] },
            line3 = line,
        )
        val toasts = mutableListOf<String>()
        val ended = mutableListOf<CallEndEvent>()
        backgroundScope.launch { coordinator.toasts.collect { toasts += it } }
        backgroundScope.launch { coordinator.ended.collect { ended += it } }
        coordinator.start()
        runCurrent()
        return Harness(coordinator, coordinator.meetLobby!!, socket, http, engine, toasts, ended, clock)
    }

    private fun Harness.open(video: Boolean = false) {
        lobby.open(MeetLobbyRequest(meetId = "m1", title = "Sprint Review", video = video))
    }

    private fun LiveKitFakeSocket.startCalls() = frames.filter { it["type"]!!.jsonPrimitive.content == "startCall" }

    private val creds = """{"livekit":{"url":"wss://in.livekit.zillit.com","token":"tok-1"}}"""

    // ── Opening ─────────────────────────────────────────────────────────────

    @Test
    fun `a line_1 Join opens a lobby and joins nothing until Join is pressed`() = runTest {
        val h = harness()
        h.open()
        runCurrent()

        assertNotNull(h.lobby.state.value)
        assertTrue(h.http.paths().any { it.endsWith("/v1/meet/m1") }, "the link was not resolved on open")
        assertTrue(h.socket.startCalls().isEmpty(), "a lobby must never join on its own")
        assertTrue(h.engine.joins.isEmpty())
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertEquals(listOf("open:false"), h.engine.lobby, "an audio meet opens the microphone only")
    }

    @Test
    fun `a blank meet id opens no lobby`() = runTest {
        val h = harness()
        h.lobby.open(MeetLobbyRequest(meetId = " ", title = "x", video = false))
        runCurrent()
        assertNull(h.lobby.state.value)
        assertEquals(1, h.toasts.size)
    }

    @Test
    fun `a cancelled meeting says so on open, and an invalid link differently`() = runTest {
        val cancelled = harness(http = LiveKitFakeHttp().apply { answers["/v1/meet/m1"] = """{"active":false}""" })
        cancelled.open()
        runCurrent()
        assertEquals(MeetLobbyStatus.Error(MeetProblem.Cancelled, fatal = true), cancelled.lobby.state.value?.status)

        val gone = harness(http = LiveKitFakeHttp().apply { answers["/v1/meet/m1"] = "!404:no_such_meet" })
        gone.open()
        runCurrent()
        assertEquals(MeetLobbyStatus.Error(MeetProblem.LinkInvalid, fatal = true), gone.lobby.state.value?.status)
        // Nothing to retry: Join stays put.
        gone.lobby.join()
        runCurrent()
        assertTrue(gone.socket.startCalls().isEmpty())
    }

    @Test
    fun `the camera follows the meet's call type, but never over the user's own toggle`() = runTest {
        val untouched = harness(http = LiveKitFakeHttp().apply { answers["/v1/meet/m1"] = """{"callType":"video"}""" })
        untouched.open(video = false)
        runCurrent()
        assertEquals(true, untouched.lobby.state.value?.camOn)

        // Touched before the answer lands: the user's choice stands.
        val http = LiveKitFakeHttp().apply { answers["/v1/meet/m1"] = """{"callType":"video"}""" }
        val touched = harness(http = http)
        touched.open(video = false)
        touched.lobby.toggleCamera()
        touched.lobby.toggleCamera()
        runCurrent()
        assertEquals(false, touched.lobby.state.value?.camOn)
    }

    @Test
    fun `a denied microphone blocks the join, a denied camera joins audio-only`() = runTest {
        val h = harness()
        h.engine.access = LobbyMediaAccess(microphone = false, camera = false)
        h.open(video = true)
        runCurrent()
        val state = h.lobby.state.value!!
        assertTrue(state.camBlocked)
        assertFalse(state.previewing)
        assertEquals(listOf("open:false"), h.engine.lobby, "a refused camera is never opened")

        h.lobby.join()
        runCurrent()
        assertEquals(MeetLobbyStatus.Error(MeetProblem.MicrophoneDenied), h.lobby.state.value?.status)
        assertTrue(h.socket.startCalls().isEmpty())
    }

    @Test
    fun `another call already up refuses the lobby`() = runTest {
        val h = harness()
        h.coordinator.placeCall(
            chatRoomId = "room",
            receiverDeviceId = "",
            mode = com.zillit.desktop.feature.calls.domain.CallMode.Group,
            type = com.zillit.desktop.feature.calls.domain.CallType.Audio,
            provider = CallProvider.LiveKit,
            displayName = "Crew",
        )
        runCurrent()
        h.open()
        runCurrent()
        assertNull(h.lobby.state.value)
        assertTrue(h.toasts.isNotEmpty())
    }

    // ── Joining ─────────────────────────────────────────────────────────────

    @Test
    fun `startCall says meet and rings nobody, and its room is joined verbatim with the lobby's choices`() = runTest {
        val h = harness()
        h.open(video = false)
        runCurrent()
        h.lobby.toggleMicrophone()
        h.lobby.join()
        runCurrent()

        val start = h.socket.startCalls().single()
        assertEquals("meet", start["callMode"]!!.jsonPrimitive.content)
        assertEquals("m1", start["callId"]!!.jsonPrimitive.content)
        assertEquals("[]", start["calleeIds"].toString())
        assertEquals(MeetLobbyStatus.Joining, h.lobby.state.value?.status)

        h.socket.answer("startCall", creds)
        runCurrent()

        val join = h.engine.joins.single()
        assertEquals("wss://in.livekit.zillit.com", join.livekitUrl)
        assertEquals("tok-1", join.livekitToken)
        assertTrue(join.micMuted, "the lobby's muted microphone must be applied before publishing")
        val session = h.coordinator.session.value!!
        assertEquals("m1", session.callUuid)
        assertTrue(session.isMeet && session.isCalendarCall)
        assertEquals(CallProvider.LiveKit, session.provider)
        // Connecting: the lobby still owns the surface, button disabled.
        assertEquals(CallPhase.Outgoing, h.coordinator.phase.value)
        assertTrue(h.lobby.state.value?.handedOff == true)

        h.engine.connect()
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        assertNull(h.lobby.state.value, "the lobby must retire once its call connects")
        assertTrue("end" in h.engine.lobby, "the preview must go when the call takes over")
        assertFalse("close" in h.engine.lobby, "the tracks are the call's now, not stopped")
    }

    @Test
    fun `a double press starts one join`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        h.lobby.join()
        runCurrent()
        assertEquals(1, h.socket.startCalls().size)
    }

    @Test
    fun `not_allowed_to_start knocks without an error, and an admit joins with the admit's room`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", "\"not_allowed_to_start\"", ok = false)
        runCurrent()

        assertEquals(MeetLobbyStatus.Waiting, h.lobby.state.value?.status)
        val knock = h.socket.frames.last { it["type"]!!.jsonPrimitive.content == "knockCall" }
        assertEquals("m1", knock["callId"]!!.jsonPrimitive.content)
        h.socket.answer("knockCall")
        h.socket.push(
            """{"type":"guestAdmitted","callId":"m1","url":"wss://eu.livekit.zillit.com","token":"admit-tok"}""",
        )
        runCurrent()

        assertEquals("admit-tok", h.engine.joins.single().livekitToken)
        assertFalse(h.http.paths().any { it.contains("/v1/livekit/token") }, "never mint after a knock")
    }

    @Test
    fun `the three refusals of a knock stay three messages`() = runTest {
        listOf(
            "declined" to MeetProblem.KnockDeclined,
            "ended" to MeetProblem.KnockEnded,
            "linkDisabled" to MeetProblem.KnockLinkDisabled,
        ).forEach { (reason, problem) ->
            val h = harness()
            h.open()
            runCurrent()
            h.lobby.join()
            runCurrent()
            h.socket.answer("startCall", "\"not_allowed_to_start\"", ok = false)
            runCurrent()
            h.socket.answer("knockCall")
            h.socket.push("""{"type":"guestDeclined","callId":"m1","reason":"$reason"}""")
            runCurrent()
            assertEquals(MeetLobbyStatus.Error(problem), h.lobby.state.value?.status, reason)
        }
    }

    @Test
    fun `a cancelled knock ignores a later admit`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", "\"not_allowed_to_start\"", ok = false)
        runCurrent()
        h.socket.answer("knockCall")
        runCurrent()
        h.lobby.cancel()
        runCurrent()
        assertEquals(MeetLobbyStatus.Idle, h.lobby.state.value?.status)

        h.socket.push("""{"type":"guestAdmitted","callId":"m1","url":"wss://x.livekit.zillit.com","token":"late"}""")
        runCurrent()
        assertTrue(h.engine.joins.isEmpty())
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
    }

    @Test
    fun `refusals on the socket are stated, not retried over REST`() = runTest {
        listOf(
            "meet_inactive" to MeetProblem.Cancelled,
            "no_such_meet" to MeetProblem.LinkInvalid,
            "meet_not_allowed" to MeetProblem.NotAllowed,
            "caller_busy" to MeetProblem.AlreadyOnAnotherCall,
        ).forEach { (word, problem) ->
            val h = harness()
            h.open()
            runCurrent()
            h.lobby.join()
            runCurrent()
            h.socket.answer("startCall", "\"$word\"", ok = false)
            runCurrent()
            assertEquals(problem, (h.lobby.state.value?.status as? MeetLobbyStatus.Error)?.problem, word)
            assertFalse(h.http.paths().any { it.contains("/v1/livekit/token") }, word)
        }
    }

    @Test
    fun `with the socket down, REST mints only for a running room`() = runTest {
        val idle = harness(socketDown = true, http = LiveKitFakeHttp().apply {
            answers["/v1/calls/m1/active"] = """{"active":false,"ongoing":true}"""
        })
        idle.open()
        runCurrent()
        idle.lobby.join()
        runCurrent()
        assertEquals(MeetLobbyStatus.Error(MeetProblem.NotStarted), idle.lobby.state.value?.status)
        assertFalse(idle.http.paths().any { it.contains("/v1/livekit/token") }, "never mint for an unstarted meet")

        val running = harness(socketDown = true, http = LiveKitFakeHttp().apply {
            answers["/v1/calls/m1/active"] = """{"active":true}"""
            answers["/v1/livekit/token"] =
                """{"token":"rest-tok","url":"wss://in.livekit.zillit.com","identity":"me"}"""
        })
        running.open()
        runCurrent()
        running.lobby.join()
        runCurrent()
        assertEquals("rest-tok", running.engine.joins.single().livekitToken)
    }

    @Test
    fun `an ack without a room, or no ack in five seconds, falls back to REST`() = runTest {
        val http = LiveKitFakeHttp().apply {
            answers["/v1/calls/m1/active"] = """{"active":true}"""
            answers["/v1/livekit/token"] = """{"token":"rest-tok","url":"wss://in.livekit.zillit.com"}"""
        }
        val h = harness(http = http)
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("rest-tok", h.engine.joins.single().livekitToken)
    }

    @Test
    fun `already in on another device asks before switching, and Switch here joins`() = runTest {
        val h = harness(http = LiveKitFakeHttp().apply { answers["/me?userId=me"] = """{"inCall":true}""" })
        h.open()
        runCurrent()
        assertTrue(h.lobby.state.value!!.offersSwitch, "the poll should offer the switch before Join is pressed")

        h.lobby.join()
        runCurrent()
        assertEquals(MeetLobbyStatus.ConfirmSwitch, h.lobby.state.value?.status)
        assertTrue(h.socket.startCalls().isEmpty())

        h.lobby.switchHere()
        runCurrent()
        assertEquals(1, h.socket.startCalls().size)
    }

    // ── The strip ───────────────────────────────────────────────────────────

    @Test
    fun `the strip lists only who is in the call, and a failed poll keeps it`() = runTest {
        val http = LiveKitFakeHttp().apply {
            answers["/roster?userId=me"] = """{"states":[
                {"userId":"a","displayName":"Asha","state":"in_call"},
                {"userId":"b","displayName":"Ben","state":"available"},
                {"userId":"c","displayName":"Cy","state":"ringing"}]}"""
        }
        val h = harness(http = http)
        h.open()
        runCurrent()
        assertEquals(listOf("a"), h.lobby.state.value!!.attendees.map { it.userId })
        assertTrue(h.lobby.state.value!!.rosterConfirmed)

        http.answers["/roster?userId=me"] = "!503:roster_unavailable"
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("a"), h.lobby.state.value!!.attendees.map { it.userId }, "a failed poll emptied the strip")
    }

    @Test
    fun `an unknown roster never claims the room is empty or offers to start it`() = runTest {
        val h = harness(http = LiveKitFakeHttp().apply {
            answers["/roster?userId=me"] = "!503:roster_unavailable"
        })
        h.open()
        runCurrent()
        val state = h.lobby.state.value!!
        assertFalse(state.rosterConfirmed)
        assertFalse(state.offersStart)
    }

    @Test
    fun `an empty room offers Start only to someone who may start it`() = runTest {
        val anyone = harness(http = LiveKitFakeHttp().apply { answers["/v1/meet/m1"] = """{"startPolicy":"anyone"}""" })
        anyone.open()
        runCurrent()
        assertTrue(anyone.lobby.state.value!!.offersStart)

        val owner = harness(http = LiveKitFakeHttp().apply {
            answers["/v1/meet/m1"] = """{"startPolicy":"owner","ownerId":"someone-else"}"""
        })
        owner.open()
        runCurrent()
        assertFalse(owner.lobby.state.value!!.offersStart)
        assertTrue(owner.lobby.state.value!!.waitsForHost)
    }

    @Test
    fun `polling stops while the window is in the background`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        val before = h.http.paths().count { it.contains("/roster") }
        h.lobby.setFocused(false)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(before, h.http.paths().count { it.contains("/roster") })
    }

    // ── Who owns the surface ────────────────────────────────────────────────

    @Test
    fun `an incoming ring takes the surface back from the lobby`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.socket.push(
            """{"type":"incomingCall","callId":"c9","callType":"audio","callMode":"private",
               "from":{"userId":"ravi","displayName":"Ravi"},"toUserId":"me","projectId":"p1",
               "livekit":{"token":"tok","url":"wss://node.zillit.com/rtc"}}""",
        )
        runCurrent()
        assertNull(h.lobby.state.value)
        assertTrue("close" in h.engine.lobby, "the lobby's camera must be released")
    }

    @Test
    fun `ending the call lands on the normal UI, never back on the lobby`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", creds)
        runCurrent()
        h.engine.connect()
        runCurrent()

        h.coordinator.hangUp()
        runCurrent()
        h.socket.answer("leaveCall")
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertNull(h.lobby.state.value)
    }

    @Test
    fun `closing the lobby leaves no call and lets the devices go`() = runTest {
        val h = harness()
        h.open(video = true)
        runCurrent()
        h.lobby.close()
        runCurrent()
        assertNull(h.lobby.state.value)
        assertEquals(listOf("open:true", "close"), h.engine.lobby)
        assertTrue(h.socket.startCalls().isEmpty())
    }

    @Test
    fun `leaving a meet uses the meet's own leave when the socket cannot carry it`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", creds)
        runCurrent()
        h.engine.connect()
        runCurrent()
        h.coordinator.hangUp()
        runCurrent()
        h.socket.answer("leaveCall", "\"nope\"", ok = false)
        runCurrent()
        assertTrue(h.http.paths().any { it.endsWith("/v1/calls/m1/leave") }, "${h.http.paths()}")
        assertFalse(h.http.paths().any { it.endsWith("/hangup") })
    }

    /** Opens the lobby, joins over the socket, and lands in the meet. */
    private suspend fun TestScope.inMeet(h: Harness) {
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", creds)
        runCurrent()
        h.engine.connect()
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
    }

    // ── Media drops ─────────────────────────────────────────────────────────

    @Test
    fun `a dropped meet asks me first, then rejoins with a fresh token through the same connect`() = runTest {
        val http = LiveKitFakeHttp().apply {
            answers["/me?userId=me"] = """{"active":true,"ongoing":true,"inCall":false}"""
            answers["/v1/livekit/token"] = """{"token":"fresh-tok","url":"wss://in.livekit.zillit.com"}"""
        }
        val h = harness(http = http)
        inMeet(h)
        h.engine.link(EngineConnection.Disconnected)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(listOf("tok-1", "fresh-tok"), h.engine.joins.map { it.livekitToken })
        h.engine.link(EngineConnection.Connected)
        runCurrent()
        assertEquals(MeetRejoinState.Idle, h.coordinator.meetRejoinState.value)
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
    }

    @Test
    fun `while me says we are still in, no token is minted`() = runTest {
        val h = harness()
        inMeet(h)
        // Only now: said before the join, it would have asked "Switch here?" instead.
        h.http.answers["/me?userId=me"] = """{"active":true,"inCall":true}"""
        val mintsBefore = h.http.paths().count { it.contains("/v1/livekit/token") }
        h.engine.link(EngineConnection.Reconnecting)
        runCurrent()
        advanceTimeBy(12_001)
        runCurrent()
        assertEquals(mintsBefore, h.http.paths().count { it.contains("/v1/livekit/token") })
        assertEquals(1, h.engine.joins.size)
    }

    @Test
    fun `out of attempts the meet offers Rejoin and is never ended by the watchdog`() = runTest {
        val h = harness(http = LiveKitFakeHttp().apply { answers["/me?userId=me"] = "!503:unavailable" })
        inMeet(h)
        h.engine.link(EngineConnection.Disconnected)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        // An attempt's connect failing (the network is still down) is not the end of the meet.
        h.engine.failed("LiveKit join failed: network")
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(MeetRejoinState.Offered, h.coordinator.meetRejoinState.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value, "the watchdog ended a meet under its Rejoin button")

        val asked = h.http.paths().count { it.endsWith("/me?userId=me") }
        h.coordinator.rejoinMeet()
        runCurrent()
        assertEquals(asked + 1, h.http.paths().count { it.endsWith("/me?userId=me") }, "Rejoin is one more attempt")
    }

    @Test
    fun `a meet whose room is gone while the media was down ends`() = runTest {
        val h = harness(http = LiveKitFakeHttp().apply {
            answers["/me?userId=me"] = """{"active":false,"inCall":false}"""
        })
        inMeet(h)
        h.engine.link(EngineConnection.Disconnected)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertEquals(CallEndReason.RemoteEnded, h.ended.last().reason)
    }

    @Test
    fun `moved to another device ends the meet here with its own words`() = runTest {
        val h = harness()
        h.open()
        runCurrent()
        h.lobby.join()
        runCurrent()
        h.socket.answer("startCall", creds)
        runCurrent()
        h.engine.connect()
        runCurrent()

        // The joiner's own echo, while it is still settling in, is not about this device.
        h.socket.push("""{"type":"callHandledElsewhere","callId":"m1","reason":"switched"}""")
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)

        h.clock[0] += 6_000
        h.socket.push("""{"type":"callHandledElsewhere","callId":"m1","reason":"switched"}""")
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertEquals(CallEndReason.MovedElsewhere, h.ended.last().reason)
        assertFalse(h.socket.sentTypes().contains("leaveCall"), "we are already out; nothing to send")
    }
}
