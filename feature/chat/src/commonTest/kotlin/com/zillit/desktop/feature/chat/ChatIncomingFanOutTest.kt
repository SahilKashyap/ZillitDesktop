package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.chat.data.ChatRepositoryImpl
import com.zillit.desktop.feature.chat.domain.ChatMessage
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One socket frame, three subscribers.
 *
 * `ChatRepository.incoming` is a COLD flow, and three places collect it for
 * the whole session: the chat view model (`main.kt`), the desktop
 * notification alerts (`Alerts.kt`) and the message widget
 * (`MessageWidget.kt`). Everything downstream of the merge therefore ran once
 * per subscriber — one arrival rebuilt the thread list three times, wrote the
 * row to SQLite three times (synchronously, on the UI thread: `viewModelScope`
 * and `LaunchedEffect` are both Main) and logged three "message in" lines,
 * which is how the fan-out was spotted in the first place.
 *
 * Filing is now gated on the row being new. These tests pin both halves: every
 * subscriber still gets every frame, and a row that genuinely changed — an
 * edit, a reaction, a delivery tick — still files, because the gate compares
 * the row and not its id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIncomingFanOutTest {

    /** A socket that delivers what a test hands it and refuses every emit. */
    private class TestSocket : SocketClient {
        val frames = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 16)
        override val connectionState: StateFlow<SocketConnectionState> =
            MutableStateFlow(SocketConnectionState.Connected(socketId = "test"))
        override val messages: Flow<SocketMessage> = frames
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Failure(ZillitError.NoConnection("no socket"))
        override suspend fun emit(event: SocketEventName) = ZillitResult.Failure(ZillitError.NoConnection("no socket"))
        override suspend fun <T> emitForAck(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Failure(ZillitError.NoConnection("no socket"))
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun repository(socket: TestSocket) = ChatRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({
                Factory(MockEngine { respond("{}", HttpStatusCode.OK) })
            }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        ),
        bus = SocketEventBus(socket),
        myUserId = { "me" },
        projectId = { "p1" },
        encrypt = { it },
        decrypt = { cipher -> cipher.removePrefix("hex:") },
    )

    private fun frame(event: String, body: String) = SocketMessage(
        SocketEventName(event),
        Json.parseToJsonElement(body),
    )

    private val arrival = """
        {"success":true,"detail":{"_id":"m1","unique_id":"u-1","sender":"u1","receiver":"me",
         "message":"hex:running late","created":300,"project_id":"p1"}}
    """.trimIndent()

    @Test
    fun `every subscriber gets the frame, and the thread keeps one row`() = runTest(StandardTestDispatcher()) {
        val socket = TestSocket()
        val repo = repository(socket)
        val seen = List(3) { mutableListOf<ChatMessage>() }
        // The three the app really has: the chat model, the alerts and the widget.
        // Held and cancelled rather than launched in `backgroundScope`, which
        // `advanceUntilIdle` would never drive (see the engine tests in
        // core:sync); an uncancelled collector on an endless flow just hangs
        // `runTest` out.
        val jobs = seen.map { sink -> launch { repo.incoming.collect { sink += it } } }
        advanceUntilIdle()

        socket.frames.emit(frame("private_chat", arrival))
        advanceUntilIdle()
        jobs.forEach { it.cancel() }

        seen.forEachIndexed { index, sink ->
            assertEquals(1, sink.size, "subscriber $index was still delivered the frame")
            assertEquals("running late", sink.single().body)
        }
        assertEquals(1, repo.cached("u1")?.size, "and the thread holds the row once")
    }

    @Test
    fun `an edit to the same row still files`() = runTest(StandardTestDispatcher()) {
        val socket = TestSocket()
        val repo = repository(socket)
        val jobs = List(3) { launch { repo.incoming.collect { } } } +
            launch { repo.edits.collect { } }
        advanceUntilIdle()

        socket.frames.emit(frame("private_chat", arrival))
        advanceUntilIdle()
        socket.frames.emit(
            frame(
                "private-chat:edit",
                """
                {"success":true,"detail":{"_id":"m1","unique_id":"u-1","sender":"u1","receiver":"me",
                 "message":"hex:here now","created":300,"edited":400,"project_id":"p1"}}
                """.trimIndent(),
            ),
        )
        advanceUntilIdle()
        jobs.forEach { it.cancel() }

        val thread = repo.cached("u1").orEmpty()
        assertEquals(1, thread.size, "one row, not two")
        assertEquals("here now", thread.single().body, "the gate compares the row, not its id")
    }

    @Test
    fun `a reaction on the same row still files`() = runTest(StandardTestDispatcher()) {
        val socket = TestSocket()
        val repo = repository(socket)
        val jobs = List(3) { launch { repo.incoming.collect { } } }
        advanceUntilIdle()

        socket.frames.emit(frame("private_chat", arrival))
        advanceUntilIdle()
        socket.frames.emit(
            frame(
                "update_reaction",
                """
                {"success":true,"detail":{"_id":"m1","unique_id":"u-1","sender":"u1","receiver":"me",
                 "message":"hex:running late","created":300,"project_id":"p1",
                 "reactions":[{"user_id":"me","reaction":"👍"}]}}
                """.trimIndent(),
            ),
        )
        advanceUntilIdle()
        jobs.forEach { it.cancel() }

        val thread = repo.cached("u1").orEmpty()
        assertEquals(1, thread.size)
        assertEquals(listOf("👍"), thread.single().reactions.map { it.emoji })
    }
}
