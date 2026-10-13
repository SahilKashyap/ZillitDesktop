package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.cabinet.CabinetEntry
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
import com.zillit.desktop.feature.chat.cabinet.CabinetRoomPages
import com.zillit.desktop.feature.chat.cabinet.GroupChatCabinetViewer
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ChatRepositoryImpl
import com.zillit.desktop.feature.chat.domain.ChatMessage
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The File Cabinet's Group Chat row: the archive request the web sends
 * (`getArchivedMessage({filecabinet: true})`), the room list behind it, and the
 * backwards paging the viewer adds.
 */
class CabinetGroupChatTest {

    private val requests = mutableListOf<HttpRequestData>()

    /** A socket that never connects: the archive is REST-only. */
    private object NoSocket : SocketClient {
        override val connectionState: StateFlow<SocketConnectionState> =
            MutableStateFlow(SocketConnectionState.Disconnected)
        override val messages: Flow<SocketMessage> = emptyFlow()
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

    private fun repository(answer: String): ChatRepositoryImpl {
        val engine = MockEngine { request ->
            requests += request
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        )
        return ChatRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ Factory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = config,
            bus = SocketEventBus(NoSocket),
            myUserId = { "me" },
            projectId = { "p1" },
            encrypt = { it },
            decrypt = { cipher -> cipher.removePrefix("hex:") },
        )
    }

    @Test
    fun `the archive asks the group-chat archive route with the cabinet flag alone`() = runTest {
        val repo = repository(
            """{"status":1,"data":{"chat_records":[
                {"_id":"m2","sender":"u1","receiver":"room1","message":"hex:later","created":2000,"message_type":"text"},
                {"_id":"m1","sender":"me","receiver":"room1","message":"hex:earlier","created":1000,"message_type":"text"}
            ]}}""",
        )

        val page = (repo.cabinetHistory("room1", 5_000L) as ZillitResult.Success).data

        val sent = requests.single()
        assertEquals("GET", sent.method.value)
        assertEquals("chat.test", sent.url.host)
        assertEquals("/api/v2/group-chat/archive/room1/5000/previous", sent.url.encodedPath)
        // The web sends this flag and nothing else; the listing's cnc_section pair is not part of it.
        assertEquals(setOf("isFileCabinet"), sent.url.parameters.names())
        assertEquals("true", sent.url.parameters["isFileCabinet"])
        // Ascending by time, decrypted, and read as a room's lines.
        assertEquals(listOf("earlier", "later"), page.map { it.body })
        assertTrue(page.all { it.isGroup })
        assertTrue(page.first().isMine)
    }

    @Test
    fun `the room list keeps a disabled room the Chats tab would hide`() = runTest {
        val repo = repository(
            """{"status":1,"data":{"chat_rooms":[
                {"_id":"r1","room_name":"Camera","enabled":true},
                {"_id":"r2","room_name":"Retired","enabled":false},
                {"_id":"r3","room_name":"Quick call","is_random_call_group":true}
            ]}}""",
        )

        val entries = GroupChatCabinetViewer(repo).entries()

        assertEquals(listOf("r1" to "Camera", "r2" to "Retired"), entries.map { it.key to it.name })
        assertEquals("/api/v2/chat-room", requests.single().url.encodedPath)
    }

    @Test
    fun `a failing room list is an empty submenu`() = runTest {
        val failing = object : ChatRepository by FakeChatRepository() {
            override suspend fun cabinetRooms() = ZillitResult.Failure(ZillitError.Unknown("down"))
        }
        assertEquals(emptyList<CabinetEntry>(), GroupChatCabinetViewer(failing).entries())
    }

    private fun line(n: Int, at: Long) = ChatMessage(
        id = "m$n", uniqueId = "m$n", senderId = "u1", receiverId = "room1",
        body = "line $n", timestampMillis = at, isMine = false, isGroup = true,
    )

    /** Pages keyed by the stamp the viewer asks from. */
    private class Archive(val pages: Map<Long, List<ChatMessage>>) : ChatRepository by FakeChatRepository() {
        val asked = mutableListOf<Long>()
        override suspend fun cabinetHistory(roomId: String, beforeMillis: Long): ZillitResult<List<ChatMessage>> {
            asked += beforeMillis
            return ZillitResult.Success(pages[beforeMillis].orEmpty())
        }
    }

    @Test
    fun `older pages merge in order and a page of nothing new retires the button`() = runTest {
        val archive = Archive(
            mapOf(
                9_000L to listOf(line(3, 3_000), line(4, 4_000)),
                3_000L to listOf(line(1, 1_000), line(2, 2_000), line(3, 3_000)),
            ),
        )
        val pages = CabinetRoomPages(archive, "room1") { 9_000L }

        pages.loadFirst()
        assertEquals(listOf("m3", "m4"), pages.messages.map { it.id })
        assertTrue(pages.hasOlder)

        pages.loadOlder()
        assertEquals(listOf("m1", "m2", "m3", "m4"), pages.messages.map { it.id })
        assertTrue(pages.hasOlder)
        assertEquals(listOf(9_000L, 3_000L), archive.asked)

        pages.loadOlder() // asks from 1_000: nothing there
        assertFalse(pages.hasOlder)
        assertEquals(4, pages.messages.size)
    }

    @Test
    fun `an empty archive and a refused one are told apart`() = runTest {
        val empty = CabinetRoomPages(Archive(emptyMap()), "room1") { 1L }
        empty.loadFirst()
        assertTrue(empty.messages.isEmpty())
        assertFalse(empty.failed)
        assertFalse(empty.hasOlder)

        val refusing = object : ChatRepository by FakeChatRepository() {}
        val refused = CabinetRoomPages(refusing, "room1") { 1L }
        refused.loadFirst()
        assertTrue(refused.failed)
        assertNull(refused.messages.firstOrNull())
    }
}
