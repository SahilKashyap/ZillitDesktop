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
import com.zillit.desktop.feature.calls.data.CallEndReason
import com.zillit.desktop.feature.calls.data.CallStatusPlane
import com.zillit.desktop.feature.calls.data.PlaneEvent
import com.zillit.desktop.feature.calls.data.PlaneJoin
import com.zillit.desktop.feature.calls.data.readActiveGroupCalls
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.OngoingVerb
import com.zillit.desktop.feature.calls.ui.inRoom
import com.zillit.desktop.feature.calls.ui.ongoingByRow
import com.zillit.desktop.feature.calls.ui.ongoingCalls
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Join for Line 2 (Agora) and Line 3 (mediasoup) group calls: the
 * active-group-calls rows as sessions, the rows and verbs the screens draw
 * from them, and the coordinator answering the ring a member never got.
 */
class GroupCallJoinTest {

    // ── The wire ────────────────────────────────────────────────────────

    private val rows = """
        {"calls":[
          {"line":"mediasoup","current_status":"incall","room_id":"room-m","chat_room_id":"g1",
           "chat_room_name":"Camera Dept","call_type":"video","invite_code":"inv-m",
           "mediasoup_server_url":"sfu.test","receiver_user_id":"first-rung",
           "call_users":[{"user_id":"ann","status":"caller"},{"user_id":"me","current_status":"incall"}]},
          {"line":"mediasoup","current_status":"end_call","room_id":"room-x","chat_room_id":"g2"},
          {"line":"agora","call_uuid":"agora-1","chat_room_id":"g3","call_type":"audio",
           "agora_channel_name":"row-channel","agora_token":"row-token",
           "call_users":[{"user_id":"bob","device_id":"bob-phone","current_status":"in_call"}]},
          {"line":"livekit","call_uuid":"lk-1","chat_room_id":"g4"}
        ]}
    """.trimIndent()

    private fun parsed() = readActiveGroupCalls(Json.parseToJsonElement(rows), "me", "my-device")

    @Test
    fun `live Line 2 and 3 rows become sessions and the rest are dropped`() {
        val calls = parsed()
        assertEquals(listOf("room-m", "agora-1"), calls.map { it.callUuid })
        val mediasoup = calls.first()
        assertEquals(CallProvider.Mediasoup, mediasoup.provider)
        assertEquals(CallMode.Group, mediasoup.mode)
        assertEquals("inv-m", mediasoup.sfuRoomId)
        assertTrue(mediasoup.hasVideo)
        // The row's receiver is whoever was rung first, never us.
        assertEquals("me", mediasoup.selfUserId)
        assertEquals("Camera Dept", mediasoup.callerName)
        assertEquals(CallProvider.Agora, calls[1].provider)
    }

    @Test
    fun `a row says Switch here when we are in it elsewhere and lands on its own history row`() {
        val ongoing = ongoingCalls(emptyList(), liveCallId = null, selfUserId = "me", projectId = null, parsed())
        val mediasoup = ongoing.single { it.callId == "room-m" }
        assertEquals(OngoingVerb.Switch, mediasoup.verb)
        assertEquals(listOf("ann", "me"), mediasoup.inCall.map { it.first })
        assertEquals(OngoingVerb.Join, ongoing.single { it.callId == "agora-1" }.verb)
        assertEquals("agora-1", ongoing.inRoom("g3")?.callId)

        // A mediasoup history row files the call under its invite code or room.
        val history = listOf(entry("inv-m"), entry("agora-1"), entry("old"))
        assertEquals(setOf("inv-m", "agora-1"), ongoingByRow(history, ongoing).keys)
    }

    @Test
    fun `the call open here says Return by any of its ids`() {
        val ongoing = ongoingCalls(emptyList(), liveCallId = "inv-m", selfUserId = "me", projectId = null, parsed())
        assertEquals(OngoingVerb.Return, ongoing.single { it.callId == "room-m" }.verb)
    }

    private fun entry(uuid: String) = CallLogEntry(
        callUuid = uuid,
        direction = CallLogDirection.Incoming,
        mode = CallMode.Group,
        type = CallType.Audio,
        missed = false,
        durationMillis = 0,
        startedAtMillis = 0,
    )

    // ── The coordinator ─────────────────────────────────────────────────

    @Test
    fun `joining an Agora group call answers it with the call document's credentials`() = runTest {
        val plane = Plane(PlaneJoin(ended = false, channelName = "doc-channel", token = "doc-token", selfAgoraUid = 7))
        val engine = RecordingEngine()
        val coordinator = coordinator(plane, engine)
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }

        coordinator.joinGroupCall("agora-1")
        settle { engine.joined != null }

        assertEquals(CallPhase.InCall, coordinator.phase.value)
        val session = coordinator.session.value!!
        assertEquals(CallProvider.Agora, session.provider)
        assertEquals("me", session.selfUserId)
        val join = engine.joined!!
        assertEquals("doc-channel", join.channel)
        assertEquals("doc-token", join.token)
        // Not in it anywhere else: no takeover was raised.
        assertEquals(0, plane.claims)
        assertTrue(CallStatus.InCall in plane.announced)
    }

    @Test
    fun `an Agora call whose document says it is over is not joined`() = runTest {
        val plane = Plane(PlaneJoin(ended = true))
        val coordinator = coordinator(plane, RecordingEngine())
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }

        coordinator.joinGroupCall("agora-1")
        settle { false }

        assertEquals(CallPhase.Idle, coordinator.phase.value)
        assertNull(coordinator.session.value)
    }

    @Test
    fun `joining a mediasoup group call dials its SFU room`() = runTest {
        val engine = RecordingEngine()
        val coordinator = coordinator(Plane(null), engine)
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }

        // Named by its room, as the history files it.
        coordinator.joinGroupCall("room-m")
        settle { engine.joined != null }

        assertEquals(CallPhase.InCall, coordinator.phase.value)
        val join = engine.joined!!
        assertEquals(CallProvider.Mediasoup, join.provider)
        assertEquals("sfu.test", join.sfuHost)
        assertEquals("inv-m", join.roomId)
    }

    @Test
    fun `Switch here on Agora tells our other device to stand down`() = runTest {
        val plane = Plane(PlaneJoin(ended = false))
        val coordinator = coordinator(
            plane,
            RecordingEngine(),
            body = """{"status":1,"data":{"calls":[{"line":"agora","call_uuid":"agora-1","chat_room_id":"g3",
                "agora_channel_name":"c","agora_token":"t",
                "call_users":[{"user_id":"me","device_id":"my-phone","current_status":"in_call"}]}]}}""",
        )
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }

        coordinator.joinGroupCall("agora-1")
        settle { coordinator.phase.value == CallPhase.InCall }

        assertEquals(1, plane.claims)
    }

    @Test
    fun `a join is refused while another call is up`() = runTest {
        val engine = RecordingEngine()
        val coordinator = coordinator(Plane(PlaneJoin(ended = false)), engine)
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }
        coordinator.joinGroupCall("room-m")
        settle { engine.joined != null }

        coordinator.joinGroupCall("agora-1")
        settle { false }

        assertEquals("room-m", coordinator.session.value?.callUuid)
    }

    // ── Being switched away from ────────────────────────────────────────

    @Test
    fun `another device of ours taking the Agora call sends this desktop out of it`() = runTest {
        val plane = Plane(PlaneJoin(ended = false))
        val coordinator = coordinator(plane, RecordingEngine())
        val ended = mutableListOf<CallEndReason>()
        backgroundScope.launch { coordinator.ended.collect { ended += it.reason } }
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }
        coordinator.joinGroupCall("agora-1")
        settle { coordinator.phase.value == CallPhase.InCall }

        // Someone else's takeover is not ours to obey.
        plane.events.emit(PlaneEvent.Takeover("ann-phone", "ann"))
        settle { false }
        assertEquals(CallPhase.InCall, coordinator.phase.value)

        plane.events.emit(PlaneEvent.Takeover("my-phone", "me"))
        settle { coordinator.phase.value == CallPhase.Idle }

        assertEquals(CallPhase.Idle, coordinator.phase.value)
        assertEquals(listOf(CallEndReason.PickedElsewhere), ended)
    }

    @Test
    fun `joining clears the takeover flag on our own row`() = runTest {
        val plane = Plane(PlaneJoin(ended = false))
        val coordinator = coordinator(plane, RecordingEngine())
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }
        coordinator.joinGroupCall("agora-1")
        settle { plane.extras.any { it.containsKey("joined_from_other_device") } }

        assertTrue(plane.extras.any { it["joined_from_other_device"] == false })
    }

    @Test
    fun `a Line 3 call ignores the Agora takeover flag`() = runTest {
        val plane = Plane(null)
        val engine = RecordingEngine()
        val coordinator = coordinator(plane, engine)
        settle { coordinator.activeGroupCalls.value.isNotEmpty() }
        coordinator.joinGroupCall("room-m")
        settle { engine.joined != null }

        plane.events.emit(PlaneEvent.Takeover("my-phone", "me"))
        settle { false }

        assertEquals(CallPhase.InCall, coordinator.phase.value)
    }

    // ── Harness ─────────────────────────────────────────────────────────

    private fun TestScope.coordinator(
        plane: Plane,
        engine: CallEngine,
        body: String = """{"status":1,"data":$rows}""",
    ): CallCoordinator {
        val mock = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = CallApi(
            apiClient = com.zillit.desktop.core.network.ApiClient(
                httpClient = com.zillit.desktop.core.network.HttpClientFactory.create({ Factory(mock) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = com.zillit.desktop.core.config.AppConfig(
                environment = com.zillit.desktop.core.config.Environment.Develop,
                services = mapOf(com.zillit.desktop.core.config.ZillitService.Calling to "https://calls.test"),
                realtime = emptyMap(),
            ),
        )
        return CallCoordinator(
            api = api,
            bus = SocketEventBus(Socket()),
            engine = engine,
            scope = backgroundScope,
            selfUserId = { "me" },
            selfDeviceId = { "my-device" },
            plane = plane,
        ).also {
            it.start()
            runCurrent()
        }
    }

    /** The mock answers on Ktor's own dispatcher; spin without moving the virtual clock. */
    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SPINS) {
            runCurrent()
            if (condition()) return
            yield()
        }
        runCurrent()
    }

    private class Plane(private val join: PlaneJoin?) : CallStatusPlane {
        val announced = mutableListOf<CallStatus>()
        val extras = mutableListOf<Map<String, Any>>()
        var claims = 0
        override suspend fun announceSelf(session: CallSession, status: CallStatus, extra: Map<String, Any>) {
            announced += status
            extras += extra
        }

        override suspend fun announceCallEnded(session: CallSession) = Unit
        val events = MutableSharedFlow<PlaneEvent>(extraBufferCapacity = 8)
        override fun watch(session: CallSession): Flow<PlaneEvent> = events
        override suspend fun readJoin(callUuid: String, selfUserId: String): PlaneJoin? = join
        override suspend fun claimFromOtherDevices(callUuid: String, selfUserId: String) {
            claims += 1
        }
    }

    private class RecordingEngine : CallEngine {
        private val _events = MutableSharedFlow<CallEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<CallEngineEvent> = _events.asSharedFlow()
        override val isReady: Boolean = true
        var joined: CallJoin? = null
        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) {
            joined = params
        }

        override suspend fun leave() = Unit
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }

    private class Socket : SocketClient {
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

    private class Factory(private val engine: MockEngine) :
        io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
        override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit) = engine
    }

    private companion object {
        const val SPINS = 200_000
    }
}
