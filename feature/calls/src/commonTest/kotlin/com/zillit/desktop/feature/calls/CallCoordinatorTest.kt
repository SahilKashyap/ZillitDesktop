package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.data.CallStatusPlane
import com.zillit.desktop.feature.calls.data.InCallData
import com.zillit.desktop.feature.calls.data.PlaneEvent
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallTimeouts
import com.zillit.desktop.feature.calls.domain.NoopCallEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phase machine, driven purely over the fake socket.
 *
 * The REST side is a stub [CallApi] whose HTTP always fails fast (the
 * coordinator treats REST failures as advisory for statuses), so what these
 * tests pin is the part no live test can: ordering, timeouts and the gates.
 */
class CallCoordinatorTest {

    private class FakeSocket : SocketClient {
        override val connectionState =
            MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("fake"))
        private val _messages = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 64)
        override val messages: Flow<SocketMessage> = _messages
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        /** Everything the coordinator put on the wire, in order. */
        val sent = mutableListOf<Pair<SocketEventName, JsonElement>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> emit(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<Unit> {
            sent += event to Json.encodeToJsonElement(serializer, payload)
            return ZillitResult.Success(Unit)
        }

        override suspend fun emit(event: SocketEventName): ZillitResult<Unit> =
            ZillitResult.Success(Unit)

        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> = ZillitResult.Success(Json.parseToJsonElement("{}"))

        suspend fun deliver(event: SocketEventName, json: String) {
            _messages.emit(SocketMessage(event, Json.parseToJsonElement(json)))
        }
    }

    /** A hand-driven mirror: the test pushes events, and records announcements. */
    private class FakePlane : CallStatusPlane {
        val events = MutableSharedFlow<PlaneEvent>(extraBufferCapacity = 8)
        val announced = mutableListOf<com.zillit.desktop.feature.calls.domain.CallStatus>()
        val extras = mutableListOf<Map<String, Any>>()
        override suspend fun announceSelf(
            session: com.zillit.desktop.feature.calls.domain.CallSession,
            status: com.zillit.desktop.feature.calls.domain.CallStatus,
            extra: Map<String, Any>,
        ) {
            announced += status
            extras += extra
        }

        override suspend fun announceCallEnded(
            session: com.zillit.desktop.feature.calls.domain.CallSession,
        ) = Unit

        override fun watch(
            session: com.zillit.desktop.feature.calls.domain.CallSession,
        ): Flow<PlaneEvent> = events

        suspend fun push(event: PlaneEvent) = events.emit(event)
    }

    /**
     * An engine that joins as a uid of the media stack's own choosing.
     *
     * This is what Agora does when a call arrives without a uid for us: the
     * number on the wire is not the number in the invite.
     */
    private class UidPickingEngine(private val issued: Int) : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = true
        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) {
            _events.emit(CallEngineEvent.Joined(params.channel, issued))
        }

        override suspend fun leave() = Unit
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }


    /** Joins, then lets a test push whatever the media stack would report. */
    private class ScriptableEngine : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 16)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = true
        var left = false
            private set

        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) {
            _events.emit(CallEngineEvent.Joined(params.channel, 42))
        }

        suspend fun push(event: CallEngineEvent) = _events.emit(event)

        override suspend fun leave() { left = true }
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }

    /** An engine that is present but cannot come up — Chromium still downloading. */
    private class DeadEngine : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = false
        override suspend fun initialize(): Boolean = false
        override suspend fun join(params: CallJoin) = Unit
        override suspend fun leave() = Unit
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }

    private fun TestScope.coordinator(
        socket: FakeSocket,
        plane: FakePlane? = null,
        engine: CallEngine = NoopCallEngine(),
        now: () -> Long = { 1_000L },
    ): CallCoordinator {
        val bus = SocketEventBus(socket)
        // ApiClient with an unroutable base: every REST call fails as a
        // ZillitResult.Failure, which the status paths tolerate by design.
        val api = CallApi(
            apiClient = com.zillit.desktop.core.network.ApiClient(
                httpClient = io.ktor.client.HttpClient(),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = com.zillit.desktop.core.config.AppConfig(
                environment = com.zillit.desktop.core.config.Environment.Develop,
                // Port 9 refuses instantly: every REST call resolves to a
                // ZillitResult.Failure instead of throwing out of the scope.
                services = mapOf(
                    com.zillit.desktop.core.config.ZillitService.Calling to "https://127.0.0.1:9",
                ),
                realtime = emptyMap(),
            ),
        )
        val coordinator = CallCoordinator(
            api = api,
            bus = bus,
            engine = engine,
            scope = backgroundScope,
            selfUserId = { "me" },
            selfDeviceId = { "my-device" },
            plane = plane ?: FakePlane(),
            now = now,
        )
        coordinator.start()
        runCurrent()
        return coordinator
    }

    private val ring = """
        {"call_uuid":"u1","room_id":"r1","project_id":"p1","call_mode":"private",
         "call_type":"audio","line":"agora","agora_channel_name":"chan","agora_token":"tok",
         "sender_user_id":"caller","sender_device_id":"caller-device","caller_name":"Vivek",
         "receiver_user_id":"me"}
    """.trimIndent()

    @Test
    fun `a ring moves Idle to Incoming`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        assertEquals(CallPhase.Incoming, coordinator.phase.value)
        assertEquals("u1", coordinator.session.value?.callUuid)
    }

    @Test
    fun `our own ring echo does not ring us`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        val echo = ring.replace("caller-device", "my-device")
        socket.deliver(ZillitSocketEvents.Calls.Incoming, echo)
        runCurrent()
        assertEquals(CallPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `accept joins media and lands InCall`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()
        assertEquals(CallPhase.InCall, coordinator.phase.value)
    }

    @Test
    fun `the uid the media stack issues reaches the session and the mirror`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane, UidPickingEngine(ISSUED_UID))
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            // The invite named no uid, so the session carried 0 until the
            // engine reported the one Agora actually issued.
            assertEquals(ISSUED_UID, coordinator.session.value?.localUid)
            // As a STRING on the wire, deliberately. iOS declares
            // `agoraUID: String?` and decodes rows with a strict JSONDecoder,
            // so a numeric uid throws typeMismatch and costs iOS the whole
            // row — status, raised hand and screen share with it.
            assertEquals(
                ISSUED_UID.toString(),
                plane.extras.mapNotNull { it["agora_uid"] }.lastOrNull(),
            )
        }

    @Test
    fun `decline returns to Idle and reports Declined`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        val endings = mutableListOf<CallEndReason>()
        backgroundScope.launch { coordinator.ended.collect { endings += it.reason } }
        runCurrent()
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.decline()
        runCurrent()
        assertEquals(CallPhase.Idle, coordinator.phase.value)
        assertEquals(listOf(CallEndReason.Declined), endings)
    }

    @Test
    fun `an unanswered ring times out to Idle`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        advanceTimeBy(CallTimeouts.INCOMING_MS + 1_000)
        runCurrent()
        assertEquals(CallPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a second ring while ringing is refused, first call keeps its state`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            socket.deliver(
                ZillitSocketEvents.Calls.Incoming,
                ring.replace("u1", "u2").replace("r1", "r2"),
            )
            runCurrent()
            assertEquals("u1", coordinator.session.value?.callUuid)
        }

    @Test
    fun `remote ended clears the call`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()
        socket.deliver(
            ZillitSocketEvents.Calls.Ended,
            """{"room_id":"r1","message":"Call has ended"}""",
        )
        runCurrent()
        assertEquals(CallPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `answering on another device stops this ring quietly`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            val endings = mutableListOf<CallEndReason>()
            backgroundScope.launch { coordinator.ended.collect { endings += it.reason } }
            runCurrent()
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            socket.deliver(
                ZillitSocketEvents.Calls.Update,
                """{"roomId":"r1","userId":"me","status":"incall"}""",
            )
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
            assertEquals(listOf(CallEndReason.PickedElsewhere), endings)
        }

    @Test
    fun `an ended event for a different room is ignored`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        socket.deliver(ZillitSocketEvents.Calls.Ended, """{"room_id":"other"}""")
        runCurrent()
        assertEquals(CallPhase.Incoming, coordinator.phase.value)
    }

    @Test
    fun `firestore mirror ends the ring when our phone answers`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane)
            val endings = mutableListOf<CallEndReason>()
            backgroundScope.launch { coordinator.ended.collect { endings += it.reason } }
            runCurrent()

            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            // Ringing was mirrored into our Firestore row.
            assertEquals(
                listOf(com.zillit.desktop.feature.calls.domain.CallStatus.Ringing),
                plane.announced,
            )

            // The same account answers on Android: our row flips in_call from
            // another device, and this desktop must stop ringing quietly.
            plane.push(
                PlaneEvent.UserStatus(
                    deviceId = "their-phone",
                    userId = "me",
                    status = com.zillit.desktop.feature.calls.domain.CallStatus.InCall,
                    updatedFrom = "Android",
                ),
            )
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
            assertEquals(listOf(CallEndReason.PickedElsewhere), endings)
        }

    @Test
    fun `firestore End Call verdict clears the call`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val plane = FakePlane()
        val coordinator = coordinator(socket, plane)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()

        plane.push(PlaneEvent.Ended("u1"))
        runCurrent()
        assertEquals(CallPhase.Idle, coordinator.phase.value)
    }


    @Test
    fun `an engine that cannot join still lands InCall, so the call can be hung up`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            // The posture that shipped: Chromium mid-download, so initialize()
            // refuses and no Joined ever arrives.
            val coordinator = coordinator(socket, engine = DeadEngine())
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            // Accepting is the transition. Waiting for the engine strands the
            // machine in Incoming with its ring timeout already cancelled —
            // ring card up, no hang-up, and every later call refused.
            assertEquals(CallPhase.InCall, coordinator.phase.value)

            // Reachable, which is the point: before the fix hangUp() returned
            // at its phase check and this call could never be ended. Ending
            // rather than Idle because the teardown's REST leg is real IO and
            // does not run in virtual time.
            coordinator.hangUp()
            runCurrent()
            assertEquals(CallPhase.Ending, coordinator.phase.value)
        }

    @Test
    fun `our own in_call, echoed back by the mirror, does not end the call we just took`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane)
            val endings = mutableListOf<CallEndReason>()
            backgroundScope.launch { coordinator.ended.collect { endings += it.reason } }
            runCurrent()
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            // The row accept() just wrote, read back by the 2 s poll. It is
            // ours, stamped Desktop — not another device answering.
            plane.push(
                PlaneEvent.UserStatus(
                    deviceId = "my-device",
                    userId = "me",
                    status = com.zillit.desktop.feature.calls.domain.CallStatus.InCall,
                    updatedFrom = "Desktop",
                ),
            )
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
            assertEquals(emptyList(), endings)
        }

    // ── Reactions and in-call chat ──────────────────────────────────────

    private val groupRing = ring.replace(
        RECEIVER_FIELD,
        RECEIVER_FIELD + ""","call_users":[
            {"user_id":"me","device_id":"my-device","name":"Me"},
            {"user_id":"alice","device_id":"d1","name":"Alice"},
            {"user_id":"alice","device_id":"d2","name":"Alice"},
            {"user_id":"bob","device_id":"d3","name":"Bob"}]""",
    )

    /**
     * The peers a relay has to address, stripped of their device suffix.
     *
     * The roster keys people by `userId:deviceId` so two devices of one person
     * are two tiles. The CNC's rooms are per *user*. Sending the composite id
     * addresses a room nobody is in — which is exactly how this feature works
     * one-to-one and goes silent the moment a call has three people.
     */
    @Test
    fun `a reaction is addressed to each peer's user room, without device suffixes`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, groupRing)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.sendReaction("🎉")
            runCurrent()

            val relay = socket.sent.last { it.first == ZillitSocketEvents.Calls.Relay }.second
            val rooms = relay.jsonObject["rooms"]!!.jsonArray.map { it.jsonPrimitive.content }
            // Alice appears once despite two devices, and we are not in our
            // own audience.
            assertEquals(listOf("alice", "bob"), rooms)
        }

    @Test
    fun `a held key sends one reaction, not a stream`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        var clock = 1_000L
        val coordinator = coordinator(socket, now = { clock })
        socket.deliver(ZillitSocketEvents.Calls.Incoming, groupRing)
        runCurrent()
        coordinator.accept()
        runCurrent()

        coordinator.sendReaction("🎉")
        clock += 100
        coordinator.sendReaction("🎉")
        clock += 100
        coordinator.sendReaction("🎉")
        runCurrent()
        assertEquals(1, socket.sent.count { it.first == ZillitSocketEvents.Calls.Relay })

        // Past the gap, the next one goes.
        clock += 600
        coordinator.sendReaction("🎉")
        runCurrent()
        assertEquals(2, socket.sent.count { it.first == ZillitSocketEvents.Calls.Relay })
    }

    /**
     * The relay can echo our own send back, and a reconnect can replay a line.
     * Either would double a message on screen.
     */
    @Test
    fun `an id already shown does not arrive twice`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        val seen = mutableListOf<InCallData>()
        val job = launch { coordinator.inCallData.collect { seen += it } }
        runCurrent()

        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()

        val line = """
            {"v":1,"kind":"message","room_id":"r1","text":"two minutes",
             "name":"Alice","from_user_id":"alice","id":"abc","ts":1000}
        """.trimIndent()
        socket.deliver(ZillitSocketEvents.Calls.InCallData, line)
        socket.deliver(ZillitSocketEvents.Calls.InCallData, line)
        runCurrent()
        job.cancel()

        assertEquals(1, seen.size)
        assertEquals("two minutes", seen.single().text)
    }

    @Test
    fun `our own line coming back off the relay is ignored`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        val seen = mutableListOf<InCallData>()
        val job = launch { coordinator.inCallData.collect { seen += it } }
        runCurrent()

        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()

        socket.deliver(
            ZillitSocketEvents.Calls.InCallData,
            """{"v":1,"kind":"reaction","room_id":"r1","emoji":"🎉",
                "from_user_id":"me","id":"not-an-id-we-issued","ts":1000}""",
        )
        runCurrent()
        job.cancel()

        assertEquals(emptyList(), seen)
    }

    /**
     * Reactions are for the call, not the room. Sending one before anyone has
     * joined would address a channel that does not exist yet.
     */
    @Test
    fun `nothing goes on the relay before the call connects`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, groupRing)
        runCurrent()

        coordinator.sendReaction("🎉")
        coordinator.sendInCallMessage("hello")
        runCurrent()

        assertEquals(0, socket.sent.count { it.first == ZillitSocketEvents.Calls.Relay })
    }

    @Test
    fun `accepting a video call shows the camera as on`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        val videoRing = ring.replace(""""call_type":"audio"""", """"call_type":"video","has_video":true""")
        socket.deliver(ZillitSocketEvents.Calls.Incoming, videoRing)
        runCurrent()
        coordinator.accept()
        runCurrent()
        // The engine joined with video and the camera light is on; a false
        // here draws "Camera on" over a live camera and eats the first press.
        assertEquals(true, coordinator.cameraOn.value)
    }

    @Test
    fun `an outgoing call has a card and a cancel before the server answers`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            coordinator.placeCall(
                chatRoomId = "room",
                receiverDeviceId = "their-device",
                mode = com.zillit.desktop.feature.calls.domain.CallMode.Private,
                type = com.zillit.desktop.feature.calls.domain.CallType.Audio,
                displayName = "Vivek",
            )
            // Before any response: the create-call POST rides a 60 s timeout,
            // and a null session here means no card and no way to cancel.
            assertEquals(CallPhase.Outgoing, coordinator.phase.value)
            assertEquals("Vivek", coordinator.session.value?.title)

            coordinator.hangUp()
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }

    private companion object {
        /** A uid no invite would carry, so only the engine can be its source. */
        const val ISSUED_UID = 937_217_754
    }

    @Test
    fun `a token that expires ends the call instead of leaving it running`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)

            // There is no renewal route on the backend, so the only honest
            // response is to end it — the phones do the same.
            engine.push(CallEngineEvent.TokenExpired)
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }

    @Test
    fun `a brief media blip is ridden out, a permanent one ends the call`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            // Dropped, then back within the grace period: still a live call.
            engine.push(CallEngineEvent.ConnectionChanged(EngineConnection.Reconnecting))
            runCurrent()
            advanceTimeBy(10_000)
            engine.push(CallEngineEvent.ConnectionChanged(EngineConnection.Connected))
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)

            // Dropped and never recovered: the SDK would retry forever, so the
            // watchdog is the only thing that ends it.
            engine.push(CallEngineEvent.ConnectionChanged(EngineConnection.Disconnected))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }
}

/** The tail of the canned ring, spliced on when a test needs a roster. */
private const val RECEIVER_FIELD = """"receiver_user_id":"me""""
