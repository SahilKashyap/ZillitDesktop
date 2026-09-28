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
import com.zillit.desktop.feature.calls.data.IN_CALL_KIND_REACTION
import com.zillit.desktop.feature.calls.data.InCallData
import com.zillit.desktop.feature.calls.data.livekit.LiveKitApi
import com.zillit.desktop.feature.calls.data.livekit.LiveKitCallPolicy
import com.zillit.desktop.feature.calls.data.livekit.LiveKitIdentity
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallRingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coordinator on Line 3, against the scripted calling backend — the
 * web's in-call orchestration (`App.tsx`'s socket cases), pinned as what
 * goes on the wire and what the state machine does with what comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Line3CoordinatorTest {

    /** The Zillit socket, present and silent: Line 3 never speaks on it. */
    private class QuietSocket : SocketClient {
        override val connectionState =
            MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("fake"))
        private val _messages = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 8)
        override val messages: Flow<SocketMessage> = _messages
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        val sent = mutableListOf<SocketEventName>()
        override suspend fun <T> emit(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<Unit> {
            sent += event
            return ZillitResult.Success(Unit)
        }

        override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> = ZillitResult.Success(Json.parseToJsonElement("{}"))
    }

    /** Joins at once and records what the coordinator asked of the room. */
    private class RoomEngine : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 16)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = true
        val holds = mutableListOf<Boolean>()
        val subscriptions = mutableListOf<Triple<String, Boolean, Boolean>>()
        val chats = mutableListOf<String>()
        val hands = mutableListOf<Boolean>()
        var mics = mutableListOf<Boolean>()
        var joined = 0
        val prewarmed = mutableListOf<String>()
        val claimed = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val mediaPrewarms = mutableListOf<Pair<Boolean, Boolean>>()

        override fun prewarm(callId: String, url: String, preconnectToken: String) {
            prewarmed += "$callId|$url|$preconnectToken"
        }

        override fun claimPrewarm(callId: String) { claimed += callId }
        override fun dropPrewarm(callId: String) { dropped += callId }
        override fun prewarmMedia(video: Boolean, audio: Boolean) { mediaPrewarms += video to audio }

        /** False holds the connect open: the test decides when the room is up. */
        var autoJoin = true
        private var pendingIdentity: String? = null
        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) {
            joined++
            if (autoJoin) {
                _events.emit(CallEngineEvent.Joined(params.identity, 42))
            } else {
                pendingIdentity = params.identity
            }
        }

        suspend fun completeJoin() {
            pendingIdentity?.let { _events.emit(CallEngineEvent.Joined(it, 42)) }
            pendingIdentity = null
        }

        suspend fun push(event: CallEngineEvent) = _events.emit(event)
        override suspend fun leave() = Unit
        override fun setMicrophoneMuted(muted: Boolean) { mics += muted }
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setHold(on: Boolean) { holds += on }
        override fun setPeerSubscribed(userId: String, video: Boolean, on: Boolean) {
            subscriptions += Triple(userId, video, on)
        }

        override fun setHandRaised(raised: Boolean) { hands += raised }
        override fun sendChat(id: String, text: String, atMillis: Long): Boolean {
            chats += text
            return true
        }

        override suspend fun destroy() = Unit
    }

    private class Harness(
        val coordinator: CallCoordinator,
        val socket: LiveKitFakeSocket,
        val http: LiveKitFakeHttp,
        val engine: RoomEngine,
        val notices: MutableList<String>,
        val toasts: MutableList<String>,
        val data: MutableList<InCallData>,
    )

    private fun TestScope.harness(): Harness {
        val socket = LiveKitFakeSocket()
        val http = LiveKitFakeHttp()
        val engine = RoomEngine()
        val line = LiveKitLine(
            scope = backgroundScope,
            api = LiveKitApi(http, "https://calls.test/api"),
            sockets = socket,
            socketUrl = { "wss://calls.test/ws" },
            handshake = { "BLOB" },
            identity = { LiveKitIdentity("me", "Me", "p1", "Sides Testing") },
            roomUrlOverride = { null },
            nowMillis = { 1_000L },
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
            now = { 5_000L },
            line3 = line,
            webOrigin = { "https://dev.zillit.com/" },
        )
        val notices = mutableListOf<String>()
        val toasts = mutableListOf<String>()
        val data = mutableListOf<InCallData>()
        backgroundScope.launch { coordinator.notices.collect { notices += it } }
        backgroundScope.launch { coordinator.toasts.collect { toasts += it } }
        backgroundScope.launch { coordinator.inCallData.collect { data += it } }
        coordinator.start()
        runCurrent()
        return Harness(coordinator, socket, http, engine, notices, toasts, data)
    }

    /**
     * The ring's `preconnectToken` is the whole point of Line 3's fast answer:
     * the room is joined HIDDEN while the phone rings and the accept upgrades
     * that participant in place, rather than starting a connect from nothing.
     * The desktop parsed the token from day one and never used it.
     */
    @Test
    fun `a ring pre-connects to the room, the accept claims it, and the end lets it go`() = runTest {
        val h = harness()
        h.socket.push(warmRing)
        runCurrent()

        assertEquals(
            listOf("c1|wss://node.zillit.com/rtc|warm-tok"),
            h.engine.prewarmed,
            "the ring did not pre-connect on its locked token",
        )

        h.coordinator.accept()
        runCurrent()
        // Claimed BEFORE the accept goes out: terminal events routinely arrive
        // around an accept, and dropping the room then reads to the server as
        // the callee leaving — ending the call they just answered.
        assertEquals(listOf("c1"), h.engine.claimed)
        // And the devices open now, not after the room exists.
        assertEquals(listOf(false to true), h.engine.mediaPrewarms, "an audio ring opens the mic only")
        h.socket.answer("acceptCall")
        runCurrent()

        h.coordinator.hangUp()
        runCurrent()
        // The teardown waits on `leaveCall`, as it does in the field.
        h.socket.answer("leaveCall")
        runCurrent()
        assertTrue("c1" in h.engine.dropped, "the warm room was never let go")
    }

    /** A ring with no pre-connect token is the ordinary path: nothing to warm. */
    @Test
    fun `a ring without a pre-connect token warms nothing`() = runTest {
        val h = harness()
        h.socket.push(ring)
        runCurrent()
        assertEquals(emptyList(), h.engine.prewarmed)
    }

    /**
     * `ring_shown` and `accept_tap` bracket the answer, and `accept_sent` says
     * which transport carried it. A tap with no matching send is the proof that
     * an answer never reached the backend — the pair exists for that.
     */
    @Test
    fun `the ten diagnostics are sent, and only inside a call`() = runTest {
        val h = harness()
        h.socket.push(ring)
        runCurrent()
        assertTrue(h.socket.logged("ring_shown"), "the ring was not reported: ${h.socket.sentTypes()}")

        h.coordinator.accept()
        runCurrent()
        assertTrue(h.socket.logged("accept_tap"))
        h.socket.answer("acceptCall")
        runCurrent()
        assertTrue(h.socket.logged("accept_sent"))

        // The page's own measurements travel the same road, and carry the
        // platform marker so one server log can tell a desktop from a phone.
        h.engine.push(CallEngineEvent.Telemetry("audio", mapOf("outPkts" to "31")))
        h.engine.push(CallEngineEvent.SelfSpeaking(speaking = true, level = 0.4))
        runCurrent()
        assertTrue(h.socket.logged("audio"))
        assertTrue(h.socket.logged("speak"))
        assertEquals("desktop", h.socket.logFor("audio")?.get("platform")?.jsonPrimitive?.content)

        h.coordinator.hangUp()
        runCurrent()
        h.socket.answer("leaveCall")
        runCurrent()
        assertTrue(h.socket.logged("disconnect"), "the reason a call ended is the most-read line of all")

        val before = h.socket.frames.size
        h.engine.push(CallEngineEvent.Telemetry("audio", mapOf("outPkts" to "0")))
        runCurrent()
        assertEquals(before, h.socket.frames.size, "between calls, it is silent")
    }

    /**
     * The outgoing screen's own line. A roster row is Ringing from the moment
     * we dial, so only these can say "Ringing…" — reading the roster left the
     * card on "Calling…" for the whole ring.
     */
    @Test
    fun `the ring's progress is tracked per callee, and cleared with the call`() = runTest {
        val h = harness()
        h.coordinator.placeCall(
            chatRoomId = "",
            receiverDeviceId = "",
            mode = com.zillit.desktop.feature.calls.domain.CallMode.Private,
            type = com.zillit.desktop.feature.calls.domain.CallType.Audio,
            displayName = "Vivek",
            provider = CallProvider.LiveKit,
            receiverUserId = "vivek",
            projectId = "p1",
            callerUserId = "me",
        )
        runCurrent()
        assertEquals(
            mapOf("vivek" to CallRingState.Calling),
            h.coordinator.ringStatuses.value,
            "the card says Calling… from the press",
        )
        // The provisional session carries NO call id (`callUuid = ""`), so the
        // ring events cannot be matched to it until `startCall` is acked and the
        // minted id is adopted. That is the real sequence: the seed above is
        // what the card shows in the meantime.
        assertEquals("", h.coordinator.session.value!!.callUuid, "nothing is named until the server answers")
        h.socket.answer("startCall", """{"livekit":{"token":"t","url":"wss://node.zillit.com/rtc"}}""")
        runCurrent()
        val placed = h.coordinator.session.value!!.callUuid
        assertTrue(placed.isNotBlank(), "the minted call id was never adopted")

        // Their device put the popup up.
        h.socket.push("""{"type":"callRinging","callId":"$placed","userId":"vivek"}""")
        runCurrent()
        assertEquals(CallRingState.Ringing, h.coordinator.ringStatuses.value["vivek"])
        // Our OWN row going in_call is not an answer — the server marks the
        // caller the moment we join our own room, and reading it as one said
        // "Joining…" while their phone was still ringing.
        h.socket.push("""{"type":"callAccepted","callId":"$placed","userId":"me"}""")
        runCurrent()
        assertEquals(CallRingState.Ringing, h.coordinator.ringStatuses.value["vivek"])
        assertNull(h.coordinator.ringStatuses.value["me"])

        h.coordinator.hangUp()
        runCurrent()
        // Unanswered and ours, so the teardown cancels rather than leaves.
        h.socket.answer("cancelCall")
        runCurrent()
        assertEquals(emptyMap(), h.coordinator.ringStatuses.value, "no ring outlives its call")
    }

    /**
     * Who may be rung in is the SERVER's list — the roster's `available` rows.
     * The open production's crew is not it: the call may belong to another
     * production, and the server knows people this client never fetched.
     */
    @Test
    fun `the addable list comes from the roster and goes with the call`() = runTest {
        val h = harness()
        inCall(h)
        h.socket.answer(
            "getCallRoster",
            """{"states":[{"userId":"vivek","displayName":"Vivek","state":"in_call"},
                {"userId":"asha","displayName":"Asha","state":"available","designationName":"Gaffer"}]}""",
        )
        runCurrent()

        val addable = h.coordinator.addableFromRoster.value
        assertEquals(listOf("asha"), addable.map { it.userId })
        assertEquals("Gaffer", addable.single().designation)

        h.coordinator.hangUp()
        runCurrent()
        h.socket.answer("leaveCall")
        runCurrent()
        assertEquals(emptyList(), h.coordinator.addableFromRoster.value)
    }

    /**
     * The Refresh button: the server is told to RE-SEED from project membership
     * and reconcile against the live room before it answers, so somebody who
     * joined or left on another client shows up. `getCallRoster` alone would
     * answer from whatever the server already held.
     */
    @Test
    fun `a forced refresh re-seeds before it reads`() = runTest {
        val h = harness()
        inCall(h)
        val before = h.socket.sentTypes().count { it == "getCallRoster" }

        h.coordinator.refreshRoster(force = true)
        runCurrent()
        assertTrue("refreshRoster" in h.socket.sentTypes(), "the server was not asked to re-seed")
        // Re-seeding answers before the new rows are pushed, so the read is
        // what makes the list visibly update on the tap.
        h.socket.answer("refreshRoster")
        runCurrent()
        assertEquals(
            before + 1,
            h.socket.sentTypes().count { it == "getCallRoster" },
            "a re-seed with no read leaves the list looking unchanged",
        )
    }

    private val warmRing = """
        {"type":"incomingCall","callId":"c1","callType":"audio","callMode":"private",
         "from":{"userId":"vivek","displayName":"Vivek"},"toUserId":"me","projectId":"p1",
         "livekit":{"token":"tok","url":"wss://node.zillit.com/rtc","preconnectToken":"warm-tok"}}
    """.trimIndent()

    private val ring = """
        {"type":"incomingCall","callId":"c1","callType":"audio","callMode":"private",
         "from":{"userId":"vivek","displayName":"Vivek"},"toUserId":"me","projectId":"p1",
         "livekit":{"token":"tok","url":"wss://node.zillit.com/rtc"}}
    """.trimIndent()

    /** Rings this device, answers, and lands in the call. */
    private fun TestScope.inCall(h: Harness) {
        h.socket.push(ring)
        runCurrent()
        assertEquals(CallPhase.Incoming, h.coordinator.phase.value)
        h.coordinator.accept()
        runCurrent()
        h.socket.answer("acceptCall")
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        assertEquals(CallProvider.LiveKit, h.coordinator.session.value?.provider)
    }

    @Test
    fun `hold mutes and blinds this leg, tells the server, and resume restores what was on`() = runTest {
        val h = harness()
        inCall(h)
        assertFalse(h.coordinator.micMuted.value)

        h.coordinator.toggleHold()
        runCurrent()
        assertTrue(h.coordinator.onHold.value)
        assertTrue(h.coordinator.micMuted.value, "held: mic off")
        assertFalse(h.coordinator.cameraOn.value)
        assertEquals(listOf(true), h.engine.holds)
        assertTrue("holdCall" in h.socket.sentTypes())
        h.socket.answer("holdCall")

        h.coordinator.toggleHold()
        runCurrent()
        assertFalse(h.coordinator.onHold.value)
        assertFalse(h.coordinator.micMuted.value, "resumed: the mic that was on is on again")
        assertFalse(h.coordinator.cameraOn.value, "the camera that was off stays off")
        assertEquals(listOf(true, false), h.engine.holds)
        assertTrue("resumeCall" in h.socket.sentTypes())
    }

    @Test
    fun `someone else's hold badges their row, and our own from another device lands here too`() = runTest {
        val h = harness()
        inCall(h)
        h.socket.push("""{"type":"callHeld","callId":"c1","userId":"vivek"}""")
        runCurrent()
        assertTrue(h.coordinator.session.value!!.participants.single { it.userId == "vivek" }.onHold)
        h.socket.push("""{"type":"callResumed","callId":"c1","userId":"vivek"}""")
        runCurrent()
        assertFalse(h.coordinator.session.value!!.participants.single { it.userId == "vivek" }.onHold)

        // The server is the authority on our own hold as well — applied, never echoed back.
        val before = h.socket.sentTypes().count { it == "holdCall" }
        h.socket.push("""{"type":"callHeld","callId":"c1","userId":"me"}""")
        runCurrent()
        assertTrue(h.coordinator.onHold.value)
        assertEquals(before, h.socket.sentTypes().count { it == "holdCall" }, "a reported hold is not re-requested")
        // A duplicate re-sets the same flag; it does not toggle it back.
        h.socket.push("""{"type":"callHeld","callId":"c1","userId":"me"}""")
        runCurrent()
        assertTrue(h.coordinator.onHold.value)
    }

    @Test
    fun `a reaction goes to the server and floats only when it comes back`() = runTest {
        val h = harness()
        inCall(h)
        h.coordinator.sendReaction("👍")
        runCurrent()
        assertTrue("reactCall" in h.socket.sentTypes())
        assertTrue(h.data.isEmpty(), "nothing floats before the echo")
        h.socket.answer("reactCall")

        h.socket.push("""{"type":"callReaction","callId":"c1","userId":"vivek","emoji":"👍"}""")
        h.socket.push("""{"type":"callReaction","callId":"c1","userId":"me","emoji":"👍"}""")
        runCurrent()
        assertEquals(listOf("vivek", "me"), h.data.map { it.fromUserId })
        assertEquals(IN_CALL_KIND_REACTION, h.data.first().kind)
        assertEquals("Vivek", h.data.first().name, "named from the roster")
        assertEquals("Me", h.data.last().name)
    }

    @Test
    fun `the host's policy locks this user out of hands, sharing and chat, by name`() = runTest {
        val h = harness()
        inCall(h)
        assertFalse(h.coordinator.isHost, "the callee is not the host")
        h.socket.push(
            """{"type":"callPolicyChanged","callId":"c1","policy":
               {"on":true,"handRaiseAllowed":false,"screenShareLocked":true,
                "chatEnabled":false,"reactionsAllowed":false}}""",
        )
        runCurrent()
        h.coordinator.toggleHand()
        h.coordinator.startScreenShare()
        h.coordinator.sendInCallMessage("hello")
        h.coordinator.sendReaction("🎉")
        runCurrent()
        assertFalse(h.coordinator.handRaised.value)
        assertTrue(h.engine.chats.isEmpty())
        assertTrue(h.socket.sentTypes().none { it == "reactCall" })
        assertEquals(
            listOf(
                "The host has disabled raising hands",
                "The host has disabled screen sharing",
                "The host has disabled chat",
                "The host has disabled reactions",
            ),
            h.notices,
        )
        assertTrue(h.coordinator.line3State.value.policy.on)
    }

    @Test
    fun `a host action and a lowered hand are honoured, cooperatively`() = runTest {
        val h = harness()
        inCall(h)
        h.coordinator.toggleHand()
        runCurrent()
        assertTrue(h.coordinator.handRaised.value)

        h.socket.push("""{"type":"handsLowered","callId":"c1","userIds":["me","vivek"]}""")
        runCurrent()
        assertFalse(h.coordinator.handRaised.value)
        assertEquals(listOf(true, false), h.engine.hands)

        h.socket.push("""{"type":"hostAction","callId":"c1","action":"muteAll"}""")
        runCurrent()
        assertTrue(h.coordinator.micMuted.value)
        assertEquals(listOf(true), h.engine.mics)
    }

    @Test
    fun `chat blocked by the host refuses the send, and unblocked lets it through`() = runTest {
        val h = harness()
        inCall(h)
        h.socket.push("""{"type":"chatBlockChanged","callId":"c1","userId":"me","blocked":true}""")
        runCurrent()
        h.coordinator.sendInCallMessage("hi")
        runCurrent()
        assertTrue(h.engine.chats.isEmpty())
        assertTrue(h.coordinator.line3State.value.isChatBlocked("me"))
        assertTrue("The host blocked you from chat" in h.notices)

        h.socket.push("""{"type":"chatBlockChanged","callId":"c1","userId":"me","blocked":false}""")
        runCurrent()
        h.coordinator.sendInCallMessage("hi")
        runCurrent()
        assertEquals(listOf("hi"), h.engine.chats)
    }

    @Test
    fun `mute for myself is this client's alone, and survives nothing but the call`() = runTest {
        val h = harness()
        inCall(h)
        h.coordinator.setListen("vivek", listen = false)
        h.coordinator.setWatch("vivek", watch = false)
        assertEquals(setOf("vivek"), h.coordinator.line3State.value.deafened)
        assertEquals(setOf("vivek"), h.coordinator.line3State.value.hidden)
        assertEquals(
            listOf(Triple("vivek", false, false), Triple("vivek", true, false)),
            h.engine.subscriptions,
        )
        assertTrue(h.socket.sentTypes().none { it.startsWith("mute") }, "nobody is told")
        h.coordinator.hangUp()
        runCurrent()
        h.socket.answer("leaveCall")
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertTrue(h.coordinator.line3State.value.deafened.isEmpty())
    }

    @Test
    fun `removed by the host ends the call and says who`() = runTest {
        val h = harness()
        inCall(h)
        h.socket.push("""{"type":"removedFromCall","callId":"c1","by":{"userId":"vivek","displayName":"Vivek"}}""")
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertTrue("Vivek removed you from the call" in h.toasts)
    }

    @Test
    fun `a second ring while on a call is a banner, and each answer goes on the wire`() = runTest {
        val h = harness()
        inCall(h)
        val second = """
            {"type":"incomingCall","callId":"c2","callType":"video","callMode":"private",
             "from":{"userId":"sam","displayName":"Sam"},"toUserId":"me","projectId":"p1",
             "livekit":{"token":"tok2","url":"wss://node.zillit.com/rtc"}}
        """.trimIndent()
        h.socket.push(second)
        runCurrent()
        assertEquals("c2", h.coordinator.secondCall.value?.callUuid)
        assertEquals("c1", h.coordinator.session.value?.callUuid, "the live call is untouched")
        assertTrue("declineCall" !in h.socket.sentTypes(), "never declined for the user")
        assertTrue("ringingAck" in h.socket.sentTypes())

        h.coordinator.declineSecondCall()
        runCurrent()
        assertNull(h.coordinator.secondCall.value)
        val declined = h.socket.frames.last { it["type"]!!.jsonPrimitive.content == "declineCall" }
        assertEquals("c2", declined["callId"]!!.jsonPrimitive.content)

        // Again, and this time End & Accept: the old call is left BEFORE the new one is answered.
        h.socket.push(second.replace("c2", "c3"))
        runCurrent()
        h.coordinator.endAndAcceptSecondCall()
        runCurrent()
        h.socket.answer("leaveCall")
        runCurrent()
        h.socket.answer("acceptCall")
        runCurrent()
        val types = h.socket.sentTypes()
        assertTrue(types.lastIndexOf("leaveCall") < types.lastIndexOf("acceptCall"))
        assertEquals("c3", h.coordinator.session.value?.callUuid)
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        assertEquals(2, h.engine.joined)
    }

    /**
     * The server broadcasts `callHandledElsewhere` to every session of the
     * user, the one that just answered included, and lists us in the call
     * the moment the accept lands. Neither may end the call taken HERE —
     * only a ring still up is theirs to dismiss.
     */
    @Test
    fun `answering here survives the server's handled-elsewhere echo and its active list`() = runTest {
        val h = harness()
        h.engine.autoJoin = false
        h.socket.push(ring)
        runCurrent()
        h.coordinator.accept()
        runCurrent()
        h.socket.answer("acceptCall")
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value, "accepting is the transition")
        assertEquals(1, h.engine.joined)

        h.socket.push("""{"type":"callHandledElsewhere","callId":"c1"}""")
        h.socket.push(
            """{"type":"activeCallsChanged","calls":[{"callId":"c1",
               "inCallUsers":[{"userId":"vivek","displayName":"Vivek"},{"userId":"me","displayName":"Me"}]}]}""",
        )
        h.socket.push("""{"type":"callCancelled","callId":"c1"}""")
        runCurrent()
        assertEquals("c1", h.coordinator.session.value?.callUuid, "the call we took survives the ring's dismissals")
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)

        h.engine.completeJoin()
        runCurrent()
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        assertTrue(h.toasts.none { it.startsWith("Missed call") })
    }

    /** The same list while the ring is still UNanswered here is a late ring: it stops. */
    @Test
    fun `a ring the server says we answered elsewhere stops here`() = runTest {
        val h = harness()
        h.socket.push(ring)
        runCurrent()
        h.socket.push(
            """{"type":"activeCallsChanged","calls":[{"callId":"c1",
               "inCallUsers":[{"userId":"vivek","displayName":"Vivek"},{"userId":"me","displayName":"Me"}]}]}""",
        )
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
    }

    @Test
    fun `a cancelled ring is a missed call, named`() = runTest {
        val h = harness()
        h.socket.push(ring)
        runCurrent()
        h.socket.push("""{"type":"callCancelled","callId":"c1"}""")
        runCurrent()
        assertEquals(CallPhase.Idle, h.coordinator.phase.value)
        assertTrue("Missed call from Vivek" in h.toasts)
    }

    @Test
    fun `the active list is kept, and joining one accepts on both wires and walks in`() = runTest {
        val h = harness()
        h.http.answers["/accept"] = """{"callId":"c9","livekit":{"token":"t9","url":"wss://node.zillit.com/rtc"}}"""
        h.socket.push(
            """{"type":"activeCallsChanged","calls":[{"callId":"c9","callType":"video","callMode":"group",
               "chatRoomId":"r","chatRoomName":"Camera dept","projectId":"p1","callerId":"vivek","callerName":"Vivek",
               "userIds":["vivek","me"],"inCallUsers":[{"userId":"vivek","displayName":"Vivek"}]}]}""",
        )
        runCurrent()
        assertEquals("c9", h.coordinator.activeCalls.value.single().callId)

        h.coordinator.joinActiveCall("c9")
        runCurrent()
        h.socket.answer("acceptCall")
        runCurrent()
        assertEquals("/v1/calls/c9/accept", h.http.paths().last())
        assertEquals(CallPhase.InCall, h.coordinator.phase.value)
        val session = h.coordinator.session.value!!
        assertEquals("Camera dept", session.title)
        assertEquals("vivek", session.callerUserId)
        assertEquals(1, h.engine.joined)
        assertFalse(h.coordinator.isHost)

        h.coordinator.joinActiveCall("c9")
        assertEquals(1, h.engine.joined, "refused while a call is up")
    }

    @Test
    fun `the invite link is the web's, and the host's controls reach the wire`() = runTest {
        val h = harness()
        inCall(h)
        assertEquals("https://dev.zillit.com/call/c1", h.coordinator.inviteLink())
        h.socket.push("""{"type":"callPolicyChanged","callId":"c1","policy":{"on":true,"linkJoinEnabled":false}}""")
        runCurrent()
        assertNull(h.coordinator.inviteLink(), "link joining off: nothing to copy")

        // Not the host: the panel's writes are refused here, never sent.
        h.coordinator.setCallPolicy(LiveKitCallPolicy(on = false))
        runCurrent()
        assertTrue("setCallPolicy" !in h.socket.sentTypes())
        assertTrue(h.coordinator.line3State.value.policy.on)
    }

    @Test
    fun `recording on Line 3 is marked with the server first, and a second recorder is refused`() = runTest {
        val h = harness()
        h.http.answers["/v1/livekit/recording/mark-started"] = "!409:already_recording"
        inCall(h)
        h.coordinator.toggleRecording()
        runCurrent()
        assertFalse(h.coordinator.recording.value)
        assertTrue("Someone is already recording this call" in h.notices)

        // The room's own metadata names the recorder for the banner.
        h.engine.push(CallEngineEvent.RecordingBy("vivek"))
        runCurrent()
        assertEquals("Vivek", h.coordinator.recordedBy.value)
        h.engine.push(CallEngineEvent.RecordingBy(""))
        runCurrent()
        assertEquals("", h.coordinator.recordedBy.value)
    }
}
