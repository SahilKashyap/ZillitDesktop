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
import com.zillit.desktop.feature.calls.data.allOthersGone
import com.zillit.desktop.feature.calls.data.callDeserted
import com.zillit.desktop.feature.calls.data.CallCoordinator
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.data.CallStatusPlane
import com.zillit.desktop.feature.calls.data.InCallData
import com.zillit.desktop.feature.calls.data.PlaneEvent
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallTimeouts
import com.zillit.desktop.feature.calls.domain.NoopCallEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import com.zillit.desktop.feature.calls.domain.CallRingState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phase machine, driven purely over the fake socket.
 *
 * The REST side is a stub [CallApi] whose HTTP always fails fast (the
 * coordinator treats REST failures as advisory for statuses), so what these
 * tests pin is the part no live test can: ordering, timeouts and the gates.
 */
/*
 * LargeClass: one phase machine, one set of tests for it, sharing one fake
 * socket, fake plane and scriptable engine. Splitting by feature would mean
 * copying that harness into each piece — and the harness is where the subtle
 * parts live (the REST stub that fails fast, the ring fixture every test
 * builds on), so several copies of it is how they drift apart.
 */
@Suppress("LargeClass")
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

        /** What every ack answers; the CNC's call-response ack is `{success, message}`. */
        var ack = "{}"

        /** Everything sent for an ack, in order. */
        val asked = mutableListOf<Pair<SocketEventName, JsonElement>>()

        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> {
            asked += event to Json.encodeToJsonElement(serializer, payload)
            return ZillitResult.Success(Json.parseToJsonElement(ack))
        }

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

        /** Rows written for somebody other than us: key to fields. */
        val rows = mutableListOf<Pair<String, Map<String, Any>>>()

        override suspend fun updateUserFields(
            session: com.zillit.desktop.feature.calls.domain.CallSession,
            deviceId: String,
            fields: Map<String, Any>,
        ) {
            rows += deviceId to fields
        }

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

        /** Recording is available here; the file arrives when a test pushes it. */
        override suspend fun startAudioRecording(): Boolean = true

        /** The source the last share was asked for, or "" for none yet. */
        var sharedSource: String? = null
            private set
        var shareStarts = 0
            private set

        override suspend fun startScreenShare(sourceId: String?): Boolean {
            sharedSource = sourceId
            shareStarts += 1
            return true
        }

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
        share: com.zillit.desktop.feature.calls.domain.CallRecordingShare? = null,
        callApi: CallApi? = null,
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
            api = callApi ?: api,
            bus = bus,
            engine = engine,
            scope = backgroundScope,
            selfUserId = { "me" },
            selfDeviceId = { "my-device" },
            plane = plane ?: FakePlane(),
            share = share,
            now = now,
        )
        coordinator.start()
        runCurrent()
        return coordinator
    }

    /**
     * A create-call response for Line 1 with no `mediasoup_server_url`, so the
     * session is adopted but [CallSession.isJoinable] is false and `joinMedia`
     * returns before touching an engine: the socket is then the only thing that
     * can move the phase, which is exactly what these tests are about.
     */
    private val created = """
        {"status":1,"data":{
          "call_uuid":"u1","room_id":"r1","project_id":"p1","line":"mediasoup",
          "call_mode":"private","call_type":"audio",
          "call_users":[
            {"user_id":"me","device_id":"my-device","status":"caller"},
            {"user_id":"them","device_id":"their-device","status":"ringing"}
          ]}}
    """.trimIndent()

    /** A [CallApi] that answers the create-call POST with [created]. */
    private fun placingApi(): CallApi {
        val engine = MockEngine {
            respond(created, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CallApi(
            apiClient = com.zillit.desktop.core.network.ApiClient(
                httpClient = com.zillit.desktop.core.network.HttpClientFactory
                    .create({ CoordinatorMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = com.zillit.desktop.core.config.AppConfig(
                environment = com.zillit.desktop.core.config.Environment.Develop,
                services = mapOf(
                    com.zillit.desktop.core.config.ZillitService.Calling to "https://calls.test",
                ),
                realtime = emptyMap(),
            ),
        )
    }

    /**
     * Waits for the mock engine, which answers on Ktor's own IO dispatcher,
     * without letting the virtual clock move.
     *
     * `yield` rather than a real `delay`: suspending the test body on a real
     * dispatcher lets `runTest` run the virtual clock forward while it waits,
     * which fires the 60 s ring timeout the instant the session is adopted and
     * ends the call before the test has begun. Yielding keeps a runnable task
     * on the test scheduler at all times, so the clock stays where it is and
     * only the real thread makes progress.
     */
    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_SPINS) {
            runCurrent()
            if (condition()) return
            yield()
        }
        runCurrent()
    }

    /** Places a 1:1 Line 1 call and waits for the server session to be adopted. */
    private suspend fun TestScope.ringing(
        socket: FakeSocket,
        receiverUserId: String = "them",
        is247Call: Boolean = false,
        engine: CallEngine = NoopCallEngine(),
        plane: FakePlane? = null,
    ): CallCoordinator {
        val coordinator = coordinator(socket, callApi = placingApi(), engine = engine, plane = plane)
        coordinator.placeCall(
            chatRoomId = "",
            receiverDeviceId = "their-device",
            mode = CallMode.Private,
            type = CallType.Audio,
            provider = com.zillit.desktop.feature.calls.domain.CallProvider.Mediasoup,
            receiverUserId = receiverUserId,
            is247Call = is247Call,
        )
        settle { coordinator.session.value?.roomId == "r1" }
        assertEquals(CallPhase.Outgoing, coordinator.phase.value, "the call must still be ringing")
        return coordinator
    }

    /**
     * The user's report: an outgoing call sat on a running 00:39 timer reading
     * "1 in call" while the callee's tile still said "Ringing…". The SFU marks
     * every joiner `in_call` — the caller's own join included — and the server
     * broadcast that back, which this read as an answer.
     */
    @Test
    fun `our own in_call while the call is still ringing is not an answer`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket)

            socket.deliver(
                ZillitSocketEvents.Calls.Update,
                """{"roomId":"r1","userId":"me","status":"incall"}""",
            )
            runCurrent()
            advanceTimeBy(39_000)
            runCurrent()

            assertEquals(CallPhase.Outgoing, coordinator.phase.value)
            val roster = coordinator.session.value?.participants.orEmpty()
            assertEquals(
                CallStatus.Ringing,
                roster.first { it.userId == "them" }.status,
                "the callee never answered",
            )
            assertEquals(
                CallStatus.Caller,
                roster.first { it.userId == "me" }.status,
                "our own row must not have been marked connected either",
            )
        }

    /** The control: a real remote answer must still connect the call. */
    @Test
    fun `a real remote in_call still answers an outgoing call`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket)

            socket.deliver(
                ZillitSocketEvents.Calls.Update,
                """{"roomId":"r1","userId":"them","status":"incall"}""",
            )
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)

            // And the ring timeout was cancelled by the answer, not left armed.
            advanceTimeBy(CallTimeouts.OUTGOING_MS + 1_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    /**
     * The worse half of the same bug: the false answer also cancelled the
     * no-answer timeout, so a call nobody picked up never ended at all.
     */
    @Test
    fun `an outgoing call nobody answers still rings out`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket)
            val endings = mutableListOf<CallEndReason>()
            val watch = launch { coordinator.ended.collect { endings += it.reason } }

            socket.deliver(
                ZillitSocketEvents.Calls.Update,
                """{"roomId":"r1","userId":"me","status":"incall"}""",
            )
            runCurrent()
            advanceTimeBy(CallTimeouts.OUTGOING_MS + 1_000)
            runCurrent()
            // The teardown reports the missed call before it finishes, and
            // those round trips land on the mock engine's own thread.
            settle { coordinator.phase.value == CallPhase.Idle }

            assertEquals(CallPhase.Idle, coordinator.phase.value)
            assertEquals(listOf(CallEndReason.Timeout), endings)
            watch.cancel()
        }

    /**
     * A support call rings the user's OWN primary device, so there our own id
     * genuinely is the far end. The guard above must not swallow that answer.
     */
    @Test
    fun `a support call answered on our own device still connects`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket, receiverUserId = "me", is247Call = true)

            socket.deliver(
                ZillitSocketEvents.Calls.Update,
                """{"roomId":"r1","userId":"me","status":"incall"}""",
            )
            runCurrent()

            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    /** A Line 1 ring, with the SFU it would join. */
    private val line1Ring = """
        {"call_uuid":"m1","room_id":"m1","project_id":"p1","call_mode":"private",
         "call_type":"audio","line":"mediasoup","mediasoup_server_url":"sfu.test",
         "sender_user_id":"caller","sender_device_id":"caller-device","caller_name":"Vivek",
         "receiver_user_id":"me"}
    """.trimIndent()

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
            // As a STRING on the wire, matching what iOS writes. Its reader
            // is tolerant of either form, so this is fleet consistency rather
            // than the row-discarding hazard an earlier note here described.
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

    @Test
    fun `an outage on a call that failed does not end the next call`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            // The first call drops and then fails outright, clock running.
            engine.push(CallEngineEvent.ConnectionChanged(EngineConnection.Reconnecting))
            runCurrent()
            engine.push(CallEngineEvent.Failed("produce-mic: OverconstrainedError"))
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)

            // Seconds later the next call is answered, and outlives the grace.
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring.replace("u1", "u2").replace("r1", "r2"))
            runCurrent()
            coordinator.accept()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    @Test
    fun `a failed call's page closing after the teardown arms nothing`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            engine.push(CallEngineEvent.Failed("produce-mic: OverconstrainedError"))
            runCurrent()

            // What the logs showed: the dead page's socket reports its close
            // only after the call is gone. Line 3's warm-room accept sends no
            // CONNECTED to cancel it, so nothing else would have stopped it.
            engine.push(CallEngineEvent.ConnectionChanged(EngineConnection.Disconnected))
            runCurrent()
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring.replace("u1", "u2").replace("r1", "r2"))
            runCurrent()
            coordinator.accept()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    /** Everything handed to the sender, so a test can read what was posted. */
    private class RecordingSink : com.zillit.desktop.feature.calls.domain.CallRecordingShare {
        val sent = mutableListOf<
            Pair<
                com.zillit.desktop.feature.calls.domain.CallRecording,
                List<com.zillit.desktop.feature.calls.domain.CallChatTarget>,
                >,
            >()

        override suspend fun share(
            recording: com.zillit.desktop.feature.calls.domain.CallRecording,
            targets: List<com.zillit.desktop.feature.calls.domain.CallChatTarget>,
        ): ZillitResult<Unit> {
            sent += recording to targets
            return ZillitResult.Success(Unit)
        }
    }

    @Test
    fun `a finished recording is sent to the other person, not just saved`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val sink = RecordingSink()
            val coordinator = coordinator(socket, engine = engine, share = sink)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            coordinator.toggleRecording()
            runCurrent()

            engine.push(
                CallEngineEvent.RecordingSaved(
                    path = "/tmp/call.m4a",
                    contentType = "audio/mp4",
                    durationMillis = 12_000L,
                ),
            )
            runCurrent()

            val (recording, targets) = sink.sent.single()
            assertEquals("/tmp/call.m4a", recording.path)
            // Both ride along because a voice note without them is an
            // unplayable row: the type is what marks it audio, the length is
            // the bubble's clock.
            assertEquals("audio/mp4", recording.contentType)
            assertEquals(12_000L, recording.durationMillis)
            assertEquals(
                listOf(com.zillit.desktop.feature.calls.domain.CallChatTarget("caller", isGroup = false)),
                targets,
            )
        }

    @Test
    fun `a recording that outlives its call still knows where it was going`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val sink = RecordingSink()
            val coordinator = coordinator(socket, engine = engine, share = sink)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            coordinator.toggleRecording()
            runCurrent()

            // The page is still writing the file when the call ends, and the
            // finished recording lands afterwards. By then the live session is
            // gone — reading the recipient off `session` here would send the
            // recording to nobody.
            socket.deliver(
                ZillitSocketEvents.Calls.Ended,
                """{"room_id":"r1","message":"Call has ended"}""",
            )
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
            assertNull(coordinator.session.value)

            engine.push(CallEngineEvent.RecordingSaved("/tmp/call.m4a", "audio/mp4", 3_000L))
            runCurrent()

            assertEquals(
                listOf(com.zillit.desktop.feature.calls.domain.CallChatTarget("caller", isGroup = false)),
                sink.sent.single().second,
            )
        }

    @Test
    fun `no sender wired leaves the recording a local file`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine, share = null)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            val toasts = mutableListOf<String>()
            backgroundScope.launch { coordinator.toasts.collect { toasts += it } }
            runCurrent()
            engine.push(CallEngineEvent.RecordingSaved("/tmp/call.webm"))
            runCurrent()

            // The path is still announced: an unsendable recording that is
            // silently discarded is worse than one the user can go and find.
            assertEquals(listOf("Recording saved to /tmp/call.webm"), toasts)
        }

    @Test
    fun `the picked screen is the one the engine is told to share`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.startScreenShare("window:187:0")
            runCurrent()

            // The whole point of the picker: an id chosen up here has to reach
            // the capture unaltered, because Chromium has no dialog of its own
            // to ask with and will otherwise take the entire desktop.
            assertEquals("window:187:0", engine.sharedSource)
        }

    @Test
    fun `sharing with no choice asks for the default source`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.startScreenShare(null)
            runCurrent()

            assertNull(engine.sharedSource)
            assertEquals(1, engine.shareStarts)
        }

    @Test
    fun `a second share while one runs is ignored`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.startScreenShare("screen:1:0")
            runCurrent()
            // The engine confirms the capture exists.
            engine.push(CallEngineEvent.ScreenShare(sharing = true))
            runCurrent()
            coordinator.startScreenShare("window:187:0")
            runCurrent()

            // Starting a second capture over a live one publishes a track the
            // first one is still holding; the button stops instead.
            assertEquals(1, engine.shareStarts)
            assertEquals("screen:1:0", engine.sharedSource)
        }

    /**
     * A second ring over a live call is the user's to answer, on every line:
     * the Decline / End & Accept banner, never an automatic refusal. It rings
     * for the caller while the user decides.
     */
    @Test
    fun `a second ring over a live call is offered, not declined`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane = plane)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            plane.announced.clear()

            val second = ring.replace("\"u1\"", "\"u2\"").replace("caller-device", "other-device")
            socket.deliver(ZillitSocketEvents.Calls.Incoming, second)
            runCurrent()

            assertEquals("u2", coordinator.secondCall.value?.callUuid)
            assertEquals(CallPhase.InCall, coordinator.phase.value, "the live call carries on")
            assertEquals(listOf(CallStatus.Ringing), plane.announced)

            // Decline: told to the roster as well as the caller — on the Agora
            // line the row is the only channel the caller watches.
            coordinator.declineSecondCall()
            runCurrent()
            assertNull(coordinator.secondCall.value)
            assertEquals(listOf(CallStatus.Ringing, CallStatus.Declined), plane.announced)
            assertEquals("u1", coordinator.session.value?.callUuid)
        }

    @Test
    fun `End and Accept leaves the live call and answers the waiting one`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket, plane = FakePlane())
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            val second = ring.replace("\"u1\"", "\"u2\"").replace("caller-device", "other-device")
            socket.deliver(ZillitSocketEvents.Calls.Incoming, second)
            runCurrent()

            coordinator.endAndAcceptSecondCall()
            // The hang-up's REST round trip runs on Ktor's own dispatcher.
            settle { coordinator.session.value?.callUuid == "u2" }

            assertEquals("u2", coordinator.session.value?.callUuid)
            assertNull(coordinator.secondCall.value)
        }

    @Test
    fun `a second ring nobody answers goes away as not answered`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane = plane)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            val second = ring.replace("\"u1\"", "\"u2\"").replace("caller-device", "other-device")
            socket.deliver(ZillitSocketEvents.Calls.Incoming, second)
            runCurrent()
            plane.announced.clear()

            advanceTimeBy(46_000)
            runCurrent()

            assertNull(coordinator.secondCall.value)
            assertTrue(CallStatus.NotAnswered in plane.announced)
        }

    @Test
    fun `an added person gets a roster row before their device rings`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane = plane)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.addUser(userId = "u9", deviceId = "dev9", name = "Asha")
            runCurrent()

            // On Line 1 the backend writes no row for an invitee, so until
            // their device rings and writes its own there is nothing to say an
            // invite is pending — and somebody who never rings at all stays
            // invisible, so the room invites them twice.
            val (key, fields) = plane.rows.single()
            assertEquals("dev9", key)
            assertEquals("add_in_call", fields["current_status"])
            assertEquals("u9", fields["user_id"])
            assertEquals("Asha", fields["user_name"])
        }


    @Test
    fun `an invite nobody answers in a minute is marked not answered`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket, plane = FakePlane())
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            coordinator.addUser(userId = "u9", deviceId = "dev9", name = "Asha")
            runCurrent()
            advanceTimeBy(59_000)
            runCurrent()
            assertEquals(
                CallStatus.Ringing,
                coordinator.session.value?.participants?.single { it.userId == "u9" }?.status,
            )

            advanceTimeBy(2_000)
            runCurrent()
            // Out of the Ringing section and into Left / Declined as "No answer",
            // where its Add rings them again.
            assertEquals(
                CallStatus.NotAnswered,
                coordinator.session.value?.participants?.single { it.userId == "u9" }?.status,
            )
        }

    @Test
    fun `cancelling a ring declines it for them and puts them back to addable`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = coordinator(socket, plane = plane)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()
            coordinator.addUser(userId = "u9", deviceId = "dev9", name = "Asha")
            runCurrent()
            plane.rows.clear()

            coordinator.cancelInvite("u9")
            runCurrent()

            assertTrue(coordinator.session.value?.participants.orEmpty().none { it.userId == "u9" })
            // Line 2: their own row says declined, which is what stops their phone.
            val (key, fields) = plane.rows.single()
            assertEquals("dev9", key)
            assertEquals("declined", fields["current_status"])
            // And no expiry fires later for a ring that is gone.
            advanceTimeBy(61_000)
            runCurrent()
            assertTrue(coordinator.session.value?.participants.orEmpty().none { it.userId == "u9" })
        }

    @Test
    fun `nobody ever answering is not an empty room`() {
        // A ringing call has no connected peers either, and ending it because
        // of that would hang up on every outgoing call the moment it started.
        assertFalse(
            allOthersGone(
                participants = listOf(CallParticipant(userId = "them", status = CallStatus.Ringing)),
                everConnected = emptySet(),
                livePeerCount = 0,
            ),
        )
    }

    @Test
    fun `the room is empty once everyone who answered has left`() {
        assertTrue(
            allOthersGone(
                participants = listOf(CallParticipant(userId = "them", status = CallStatus.Left)),
                everConnected = setOf("them"),
                livePeerCount = 0,
            ),
        )
    }

    @Test
    fun `someone still sending media keeps the room occupied`() {
        // The roster row is allowed to lag. Media is the fact.
        assertFalse(
            allOthersGone(
                participants = listOf(CallParticipant(userId = "them", status = CallStatus.Left)),
                everConnected = setOf("them"),
                livePeerCount = 1,
            ),
        )
    }

    @Test
    fun `one of several leaving does not empty the room`() {
        assertFalse(
            allOthersGone(
                participants = listOf(
                    CallParticipant(userId = "a", status = CallStatus.Left),
                    CallParticipant(userId = "b", status = CallStatus.InCall),
                ),
                everConnected = setOf("a", "b"),
                livePeerCount = 0,
            ),
        )
    }

    @Test
    fun `a call whose other end hangs up ends itself`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
        runCurrent()
        coordinator.accept()
        runCurrent()

        // They answer, then hang up.
        socket.deliver(
            ZillitSocketEvents.Calls.Update,
            """{"roomId":"r1","userId":"caller","status":"incall"}""",
        )
        runCurrent()
        socket.deliver(
            ZillitSocketEvents.Calls.Update,
            """{"roomId":"r1","userId":"caller","status":"leave"}""",
        )
        runCurrent()
        assertEquals(CallPhase.InCall, coordinator.phase.value, "must not end before the grace")

        advanceTimeBy(3_000)
        runCurrent()

        // Nothing used to notice this: the desktop sat in an empty room with
        // the timer running and the microphone live until the user looked.
        assertEquals(CallPhase.Idle, coordinator.phase.value)
    }

    /**
     * The case users hit (Line 2, 2026-10-04): a call this device received,
     * where the caller's row says `caller` and no `in_call` ever arrives for
     * them. Their media came and went — and nothing ended the call, because
     * nobody had ever been "connected" on the roster.
     */
    @Test
    fun `a received 1 to 1 call ends when the caller's media goes, with no roster word`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            engine.push(CallEngineEvent.PeerJoined(7))
            runCurrent()
            engine.push(CallEngineEvent.PeerLeft(7))
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value, "must not end before the grace")

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }

    /**
     * The report of 2026-10-05: on Line 1 the other person's phone joined the
     * room under a different id than their row, so their leaving matched no
     * row — the row read `in_call` for ever and the call never ended. Their
     * media leaving is the witness, on a 5 s grace.
     */
    @Test
    fun `a Line 1 call ends once every stream is gone, whatever a stale row says`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = ringing(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)

            engine.push(CallEngineEvent.PeerJoined(4242))
            runCurrent()
            engine.push(CallEngineEvent.PeerLeft(4242))
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value, "a peer gets its reconnect grace")

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }

    /**
     * The "Guest" of 2026-10-05: the seed row written for someone added
     * mid-call carries flags, and folding them created a media peer for a
     * person who never joined — a stream nobody owned.
     */
    @Test
    fun `on Line 1 a Firestore row never invents a peer`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val coordinator = ringing(socket, engine = ScriptableEngine(), plane = plane)
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()

            plane.events.emit(
                PlaneEvent.UserFlags(
                    deviceId = "seed-device",
                    userId = "added",
                    agoraUid = 0,
                    sharing = false,
                    handRaised = false,
                    muted = true,
                ),
            )
            runCurrent()

            assertTrue(coordinator.media.value.peers.isEmpty(), "peers: ${coordinator.media.value.peers}")
        }

    /** The phone's ringing-ack overtaking its join must not put a live person back on the ring. */
    @Test
    fun `a late ringing does not undo an answer whose media is live`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = ringing(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()
            engine.push(CallEngineEvent.PeerJoined(com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf("them")))
            runCurrent()

            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"ringing"}""")
            runCurrent()

            assertEquals(
                CallStatus.InCall,
                coordinator.session.value?.participants?.single { it.userId == "them" }?.status,
            )
        }

    /**
     * A phone joins the Line 1 room while it is still ringing. The web holds
     * the caller's ring for that (`gateOnAnswer`): only their media is an answer.
     */
    @Test
    fun `on Line 1 a ringing phone joining the room is not an answer, its media is`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = ringing(socket, engine = engine)
            val them = com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf("them")

            engine.push(CallEngineEvent.PeerJoined(them, "them:phone"))
            runCurrent()
            assertEquals(CallPhase.Outgoing, coordinator.phase.value, "a prewarmed join is still a ring")

            engine.push(CallEngineEvent.PeerJoined(them, "them:phone", withMedia = true))
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    /** Line 1 answers over the socket first, in the words the web sends: `incall`, never `in_call`. */
    @Test
    fun `a Line 1 accept goes over the socket as incall`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, line1Ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            val (event, body) = socket.asked.last()
            assertEquals(ZillitSocketEvents.Calls.Response, event)
            assertEquals("incall", (body as JsonObject)["response"]?.jsonPrimitive?.content)
        }

    /** The caller gave up as we pressed accept: the server says so, and we do not sit in an empty room. */
    @Test
    fun `an accept the server answers call_ended does not join`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket().apply { ack = """{"success":false,"message":"call_ended"}""" }
            val coordinator = coordinator(socket)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, line1Ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            assertEquals(CallPhase.Idle, coordinator.phase.value)
        }

    /** A Line 1 caller hears "Ringing…" once the callee's device acknowledges, as Line 3's does. */
    @Test
    fun `a Line 1 callee's ringing reaches the caller's status line`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket)
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"ringing"}""")
            runCurrent()
            assertEquals(CallRingState.Ringing, coordinator.ringStatuses.value["them"])
        }

    /** Our other device took the call (protoo 4409): we let go quietly, never "Call failed". */
    @Test
    fun `a call taken over by another device ends as picked up elsewhere`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = ringing(socket, engine = engine)
            val ended = mutableListOf<CallEndReason>()
            backgroundScope.launch { coordinator.ended.collect { ended += it.reason } }
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()

            engine.push(CallEngineEvent.SessionReplaced)
            runCurrent()

            assertEquals(CallPhase.Idle, coordinator.phase.value)
            assertEquals(listOf(CallEndReason.PickedElsewhere), ended)
        }

    /** A Line 1 guest knocking is listed for the host, and goes when anyone answers it. */
    @Test
    fun `a Line 1 guest request is listed until it is answered`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = ringing(socket)
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()

            socket.deliver(
                ZillitSocketEvents.Calls.GuestJoinRequest,
                """{"room_id":"r1","request_id":"g1","guest_name":"Ann"}""",
            )
            runCurrent()
            assertEquals(listOf("Ann"), coordinator.line1Guests.value.map { it.guestName })

            socket.deliver(ZillitSocketEvents.Calls.GuestJoinResponded, """{"room_id":"r1","request_id":"g1"}""")
            runCurrent()
            assertTrue(coordinator.line1Guests.value.isEmpty())
        }

    /** A guest's chat reaches us over the SFU — they have no socket to relay through. */
    @Test
    fun `a chat line relayed by the SFU is shown`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = ringing(socket, engine = engine)
            val lines = mutableListOf<String>()
            backgroundScope.launch { coordinator.inCallData.collect { lines += it.text } }
            socket.deliver(ZillitSocketEvents.Calls.Update, """{"roomId":"r1","userId":"them","status":"in_call"}""")
            runCurrent()

            engine.push(
                CallEngineEvent.RoomData(
                    """{"kind":"message","room_id":"r1","from_user_id":"guest_7","text":"hello","id":"x1"}""",
                ),
            )
            runCurrent()

            assertEquals(listOf("hello"), lines)
        }

    /**
     * Line 2: a phone whose row names a different user id than the roster's
     * never healed onto a roster row, and its stream drew as "Guest". The row
     * still says who that uid is.
     */
    @Test
    fun `a Line 2 stream is named by its Firestore row whatever the roster says`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val plane = FakePlane()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, plane = plane, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            plane.events.emit(
                PlaneEvent.UserFlags(
                    deviceId = "phone",
                    userId = "u-other-project",
                    agoraUid = 555,
                    sharing = false,
                    handRaised = false,
                    userName = "Sahil Kashyap",
                ),
            )
            runCurrent()
            engine.push(CallEngineEvent.PeerJoined(555))
            runCurrent()

            val peer = coordinator.media.value.peers.getValue(555)
            assertEquals("Sahil Kashyap", peer.displayName)
            assertEquals("u-other-project", peer.identity)
        }

    /** Answered, and the caller's stream has not arrived yet: that is not an ending. */
    @Test
    fun `a 1 to 1 call never seen to carry media is not ended for being empty`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val engine = ScriptableEngine()
            val coordinator = coordinator(socket, engine = engine)
            socket.deliver(ZillitSocketEvents.Calls.Incoming, ring)
            runCurrent()
            coordinator.accept()
            runCurrent()

            // Something that re-asks "is anyone here" with no peer ever seen.
            engine.push(CallEngineEvent.PeerLeft(7))
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(CallPhase.InCall, coordinator.phase.value)
        }

    @Test
    fun `the media rule covers groups too, and yields to a roster row still in the call`() {
        val them = CallParticipant(userId = "them", status = CallStatus.Caller)
        assertTrue(callDeserted(true, 0, listOf(them), "me"))
        assertFalse(callDeserted(false, 0, listOf(them), "me"))
        assertFalse(callDeserted(true, 1, listOf(them), "me"))
        val stillIn = CallParticipant(userId = "them", status = CallStatus.InCall)
        assertFalse(callDeserted(true, 0, listOf(stillIn), "me"))
        // A group whose last talker left, with one invite still unanswered.
        val ringing = CallParticipant(userId = "late", status = CallStatus.Ringing)
        val gone = CallParticipant(userId = "them", status = CallStatus.Left)
        assertTrue(callDeserted(true, 0, listOf(gone, ringing), "me"))
    }

    @Test
    fun `the kind of call the caller asked for survives the request`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            coordinator.placeCall(
                chatRoomId = "group-9",
                receiverDeviceId = "",
                mode = CallMode.Group,
                type = CallType.Audio,
                isCalendarCall = true,
            )
            runCurrent()

            // Stamped on the provisional session, before the server answers:
            // the controls have to be right while it is still connecting, and
            // the create-call response does not carry this back.
            assertTrue(coordinator.session.value?.isCalendarCall == true)
        }

    @Test
    fun `a support call is marked as one from the moment it is placed`() =
        runTest(StandardTestDispatcher()) {
            val socket = FakeSocket()
            val coordinator = coordinator(socket)
            coordinator.placeCall(
                chatRoomId = "",
                receiverDeviceId = "my-primary-device",
                mode = CallMode.Private,
                type = CallType.Audio,
                is247Call = true,
            )
            runCurrent()

            // Nothing on the wire says "support" — the backend decides that by
            // routing — so this flag is the only thing that hides Record and
            // Add people, and it has to be true while the call is ringing.
            assertTrue(coordinator.session.value?.is247Call == true)
        }

    @Test
    fun `an ordinary call is neither`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val coordinator = coordinator(socket)
        coordinator.placeCall(
            chatRoomId = "",
            receiverDeviceId = "their-device",
            mode = CallMode.Private,
            type = CallType.Audio,
        )
        runCurrent()

        val session = coordinator.session.value
        assertFalse(session?.isCalendarCall == true)
        assertFalse(session?.is247Call == true)
    }

    @Test
    fun `being alone in a calendar room is not an ending`() = runTest(StandardTestDispatcher()) {
        val socket = FakeSocket()
        val engine = ScriptableEngine()
        val coordinator = coordinator(socket, engine = engine)
        socket.deliver(
            ZillitSocketEvents.Calls.Incoming,
            ring.replace(""""call_mode":"private"""", """"call_mode":"group","is_calendar_call":true"""),
        )
        runCurrent()
        coordinator.accept()
        runCurrent()

        // The other side leaves; on an ordinary call this ends it.
        socket.deliver(
            ZillitSocketEvents.Calls.Update,
            """{"roomId":"r1","userId":"caller","status":"incall"}""",
        )
        runCurrent()
        socket.deliver(
            ZillitSocketEvents.Calls.Update,
            """{"roomId":"r1","userId":"caller","status":"leave"}""",
        )
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        // Someone who opens the room early, or outstays the others, is still
        // in a room the event owns — hanging up on them would close it.
        assertEquals(CallPhase.InCall, coordinator.phase.value)
    }
}

/** The tail of the canned ring, spliced on when a test needs a roster. */
private const val RECEIVER_FIELD = """"receiver_user_id":"me""""

/** Hands the same mock engine back to the app's client factory. */
private class CoordinatorMockEngineFactory(private val engine: MockEngine) :
    io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit) = engine
}

/** Spins on the test scheduler while the mock engine answers on a real thread. */
private const val SETTLE_SPINS = 200_000
