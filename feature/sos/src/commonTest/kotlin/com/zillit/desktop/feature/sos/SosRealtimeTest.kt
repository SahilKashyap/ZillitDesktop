package com.zillit.desktop.feature.sos

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.sos.data.SOS_SYNC_EVENTS
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alarm, arriving without a refresh.
 *
 * `projectsos:alert:sent` was handled nowhere on the desktop — Android, iOS
 * and the web all raise an alert on it, and this client sat silent until
 * somebody pulled to refresh (found 2026-09-07). On a unit that is the one
 * event where being a minute late is the whole problem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosRealtimeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Just enough of the transport to push a frame at the bus. */
    private class FakeSocket : SocketClient {
        private val state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("test"))
        override val connectionState = state.asStateFlow()

        private val _messages = MutableSharedFlow<SocketMessage>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val messages: Flow<SocketMessage> = _messages.asSharedFlow()

        suspend fun deliver(event: SocketEventName) =
            _messages.emit(SocketMessage(event = event, payload = JsonNull))

        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)
        override suspend fun emit(event: SocketEventName) = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> = ZillitResult.Success(JsonNull)
    }

    private fun viewModel(repository: FakeSosRepository, socket: FakeSocket) = SosViewModel(
        repository = repository,
        nowMillis = { 1_000L },
        viewer = { SosViewer(userId = "u-me", isAdmin = false, phone = "7700900000") },
        crew = { emptyList() },
        locationFix = { null },
        events = SocketEventBus(socket),
    )

    @Test
    fun `the alarm event is the one the other clients carry`() {
        // `projectsos:`, not `project:sos:` — a typo here is silence.
        assertEquals(listOf("projectsos:alert:sent"), SOS_SYNC_EVENTS.map(SocketEventName::value))
    }

    @Test
    fun `an alarm raised on set reloads the feed without a refresh`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val socket = FakeSocket()
        val model = viewModel(repository, socket)
        model.start()
        advanceUntilIdle()
        val afterStart = repository.alertCursors.size

        socket.deliver(SOS_SYNC_EVENTS.single())
        advanceUntilIdle()

        assertEquals(afterStart + 1, repository.alertCursors.size, "the alarm must refetch the feed")
    }

    @Test
    fun `a second alarm is not swallowed`() = runTest(dispatcher) {
        // No debounce and no echo suppression here, deliberately: two alarms
        // in quick succession are two emergencies, not a duplicate.
        val repository = FakeSosRepository()
        val socket = FakeSocket()
        val model = viewModel(repository, socket)
        model.start()
        advanceUntilIdle()
        val afterStart = repository.alertCursors.size

        socket.deliver(SOS_SYNC_EVENTS.single())
        advanceUntilIdle()
        socket.deliver(SOS_SYNC_EVENTS.single())
        advanceUntilIdle()

        assertEquals(afterStart + 2, repository.alertCursors.size)
    }

    @Test
    fun `unrelated traffic on the socket is ignored`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val socket = FakeSocket()
        val model = viewModel(repository, socket)
        model.start()
        advanceUntilIdle()
        val afterStart = repository.alertCursors.size

        socket.deliver(SocketEventName("home:message:added"))
        advanceUntilIdle()

        assertEquals(afterStart, repository.alertCursors.size)
    }

    @Test
    fun `revisiting the screen does not stack a second listener`() = runTest(dispatcher) {
        // `start` runs on every visit; one alarm must still mean one refetch.
        val repository = FakeSosRepository()
        val socket = FakeSocket()
        val model = viewModel(repository, socket)
        model.start()
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()
        val afterStart = repository.alertCursors.size

        socket.deliver(SOS_SYNC_EVENTS.single())
        advanceUntilIdle()

        assertEquals(afterStart + 1, repository.alertCursors.size)
    }

    @Test
    fun `a build with no socket still works, just not live`() = runTest(dispatcher) {
        val repository = FakeSosRepository()
        val model = SosViewModel(
            repository = repository,
            nowMillis = { 1_000L },
            viewer = { SosViewer(userId = "u-me", isAdmin = false, phone = "7700900000") },
            crew = { emptyList() },
            locationFix = { null },
        )

        model.start()
        advanceUntilIdle()

        assertTrue(repository.alertCursors.isNotEmpty(), "the screen still loads on its own")
    }
}
