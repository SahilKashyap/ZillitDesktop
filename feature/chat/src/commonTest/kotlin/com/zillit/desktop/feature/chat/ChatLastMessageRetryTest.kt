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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A lost ack on the listing's warm fetch.
 *
 * `private-chat:get-messages` and `group-chat:get-messages` each timed out in
 * a week of field logs, and every one read as a flat failure: the row's line,
 * and the thread behind it, stayed at whatever this computer already had. The
 * socket was connected for all of them — `emitForAck` answers `NoConnection`
 * at once when it is not — so the frame went out and nothing came back inside
 * the window, which for a read is worth asking again.
 *
 * Once, and only for a timeout. Two lost acks in a row is the connection
 * rather than the race, `NoConnection` has nothing to ask over, and a server
 * that refused the ask once refuses it again.
 */
class ChatLastMessageRetryTest {

    /** A socket whose acks are scripted, so a test can lose one. */
    private class ScriptedSocket(private val answers: List<ZillitResult<JsonElement>>) : SocketClient {
        val asked = mutableListOf<String>()

        override val connectionState: StateFlow<SocketConnectionState> =
            MutableStateFlow(SocketConnectionState.Connected("s1"))
        override val messages: Flow<SocketMessage> = emptyFlow()
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit

        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
            ZillitResult.Success(Unit)

        override suspend fun emit(event: SocketEventName) = ZillitResult.Success(Unit)

        override suspend fun <T> emitForAck(
            event: SocketEventName,
            payload: T,
            serializer: KSerializer<T>,
        ): ZillitResult<JsonElement> {
            val next = answers.getOrNull(asked.size)
            asked += event.value
            return next ?: ZillitResult.Failure(ZillitError.Unknown("the script ran out of answers"))
        }
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun repository(socket: SocketClient): ChatRepositoryImpl = ChatRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({
                // No REST call belongs to this ask; a 500 proves none was made.
                Factory(MockEngine { respond("", HttpStatusCode.InternalServerError) })
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

    private fun answered(body: String) = ZillitResult.Success(Json.parseToJsonElement(body))

    private fun lostAck() = ZillitResult.Failure(ZillitError.Timeout("no ack within 10000ms"))

    private companion object {
        /** One row, in the socket ack's own `detail` envelope. */
        const val ONE_ROW = """{"detail":{"chat_records":[
            {"_id":"m-1","sender":"u-a","receiver":"me","message":"hex:on my way","created":2000}
        ]}}"""
    }

    @Test
    fun `a lost ack is asked again, and the second answer is the one used`() = runTest {
        val socket = ScriptedSocket(listOf(lostAck(), answered(ONE_ROW)))

        val answer = repository(socket).lastMessage("u-a", isGroup = false)

        assertEquals(
            listOf("private-chat:get-messages", "private-chat:get-messages"),
            socket.asked,
            "asked twice, on the same event",
        )
        val newest = assertIs<ZillitResult.Success<*>>(answer).data
        assertEquals("on my way", (newest as com.zillit.desktop.feature.chat.domain.ChatMessage).body)
    }

    @Test
    fun `a room's retry asks the group event, not the private one`() = runTest {
        val socket = ScriptedSocket(listOf(lostAck(), answered(ONE_ROW)))

        repository(socket).lastMessage("room-1", isGroup = true)

        assertEquals(listOf("group-chat:get-messages", "group-chat:get-messages"), socket.asked)
    }

    @Test
    fun `two lost acks in a row are not asked a third time`() = runTest {
        val socket = ScriptedSocket(listOf(lostAck(), lostAck()))

        val answer = repository(socket).lastMessage("u-a", isGroup = false)

        assertEquals(2, socket.asked.size, "the retry is one retry, not a loop")
        assertIs<ZillitError.Timeout>(assertIs<ZillitResult.Failure>(answer).error)
    }

    /** Nothing went out, so there is nothing to ask again — and the caller is told so. */
    @Test
    fun `a socket that is not connected is not asked twice`() = runTest {
        val socket = ScriptedSocket(listOf(ZillitResult.Failure(ZillitError.NoConnection("no socket"))))

        val answer = repository(socket).lastMessage("u-a", isGroup = false)

        assertEquals(1, socket.asked.size)
        assertIs<ZillitError.NoConnection>(assertIs<ZillitResult.Failure>(answer).error)
    }

    /** A server that said no says no again; the retry is for silence only. */
    @Test
    fun `a refusal in the ack is not asked again`() = runTest {
        val socket = ScriptedSocket(listOf(answered("""{"success":false,"message":"route_not_found"}""")))

        val answer = repository(socket).lastMessage("u-a", isGroup = false)

        assertEquals(1, socket.asked.size)
        assertIs<ZillitResult.Failure>(answer)
    }

    @Test
    fun `an answer with no rows is a success with nothing in it`() = runTest {
        val socket = ScriptedSocket(listOf(answered("""{"detail":{"chat_records":[]}}""")))

        val answer = repository(socket).lastMessage("u-a", isGroup = false)

        assertEquals(1, socket.asked.size)
        assertNull(assertIs<ZillitResult.Success<*>>(answer).data)
    }
}
