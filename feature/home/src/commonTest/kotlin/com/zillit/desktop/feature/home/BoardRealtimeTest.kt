package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.home.data.BOARD_REALTIME_EVENTS
import com.zillit.desktop.feature.home.data.HomeRealtimeSource
import com.zillit.desktop.feature.home.data.boardRealtime
import com.zillit.desktop.feature.home.data.toRealtimeEvent
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reused boards' socket events, decoded (`socket/listenerSocket.js`).
 *
 * Two things are pinned. First, the exact wire names per board — these are
 * colon-delimited (`info:message:added`), and the underscore forms the web
 * pages appear to listen on are internal re-emits that exist on no wire;
 * subscribing to one is a silent no-op, which is precisely the failure this
 * file exists to catch. Second, the mapping: posts patch in place, and
 * everything whose payload cannot patch a row coarsens to [UnitsChanged],
 * which reloads the tab strip and the open board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardRealtimeTest {

    private fun names(board: String): Set<String> =
        BOARD_REALTIME_EVENTS.getValue(board).all.map { it.value }.toSet()

    // -- (a) the wire names, board by board --------------------------------

    @Test
    fun `info listens on the info prefix, wire-spelt`() {
        assertEquals(
            setOf(
                "info:message:added", "info:message:edited", "info:message:deleted",
                "info:message:deleted:multiple", "info:message:comment:added",
                "info:message:comment:edited", "info:message:comment:deleted",
                "info:posting-rights:update", "info:chat:archived",
                "info:message:readby:update",
            ),
            names("info"),
        )
    }

    @Test
    fun `confidential info spells its prefix with an underscore`() {
        assertEquals(
            setOf(
                "confidential_info:message:added", "confidential_info:message:edited",
                "confidential_info:message:deleted:multiple",
                "confidential_info:message:comment:added", "confidential_info:message:comment:edited",
                "confidential_info:message:comment:deleted",
                "confidential_info:posting-rights:update", "confidential_info:chat:archived",
                "confidential_info:message:readby:update",
            ),
            names("confidentialinfo"),
        )
    }

    @Test
    fun `script notes carries its rights event and reports carries none`() {
        assertEquals(
            setOf(
                "script_notes:message:added", "script_notes:message:edited",
                "script_notes:message:deleted:multiple", "script_notes:message:comment:added",
                "script_notes:message:comment:edited", "script_notes:message:comment:deleted",
                "script_notes:message:readby:update", "script_notes:posting-rights:update",
            ),
            names("script-notes"),
        )
        assertEquals(
            setOf(
                "reports:message:added", "reports:message:edited",
                "reports:message:deleted:multiple", "reports:message:comment:added",
                "reports:message:comment:edited", "reports:message:comment:deleted",
                "reports:message:readby:update",
            ),
            names("reports"),
        )
    }

    @Test
    fun `catering adds its unit lifecycle and archive events`() {
        assertEquals(
            setOf(
                "catering:message:added", "catering:message:edited", "catering:message:deleted",
                "catering:message:deleted:multiple", "catering:message:comment:added",
                "catering:message:comment:edited", "catering:message:comment:deleted",
                "catering:unit:created", "catering:unit:updated", "catering:unit:deleted",
                "catering:chat:archived", "catering:message:readby:update",
            ),
            names("catering"),
        )
    }

    @Test
    fun `accounts adds units, rights and archive events`() {
        assertEquals(
            setOf(
                "account:message:added", "account:message:edited", "account:message:deleted",
                "account:message:deleted:multiple", "account:message:comment:added",
                "account:message:comment:edited", "account:message:comment:deleted",
                "account:unit:created", "account:unit:updated", "account:unit:deleted",
                "account:viewing-rights:update", "account:posting-rights:update",
                "account:chat:archived", "account:message:readby:update",
            ),
            names("account"),
        )
    }

    @Test
    fun `wardrobe rides three message prefixes plus its record events`() {
        val wardrobe = names("wardrobe")

        for (prefix in listOf("wardrobe", "wardrobe-main-unit", "wardrobe-background-unit")) {
            assertTrue("$prefix:message:added" in wardrobe, "$prefix must be listened on")
            assertTrue("$prefix:message:comment:deleted" in wardrobe)
        }
        assertTrue("wardrobe:created" in wardrobe)
        assertTrue("wardrobe:move" in wardrobe)
        assertTrue("wardrobe:updated" in wardrobe)
        assertTrue("wardrobe:deleted" in wardrobe)
    }

    // -- the mapping -------------------------------------------------------

    private fun event(board: String, event: String, payload: String): HomeRealtimeEvent? =
        BOARD_REALTIME_EVENTS.getValue(board).toRealtimeEvent(
            SocketMessage(SocketEventName(event), Json.parseToJsonElement(payload)),
            decryptBody = { "plain:$it" },
        )

    private val post = """{"_id":"n1","unit_id":"u1","message":"68656c","sender":"u9","name":"Ada"}"""

    @Test
    fun `a post lands as NoticeAdded, decrypted, with its unit`() {
        val added = event("catering", "catering:message:added", post)

        val notice = (added as HomeRealtimeEvent.NoticeAdded).notice
        assertEquals("u1", added.unitId)
        assertEquals("n1", notice.id)
        assertEquals("plain:68656c", notice.body, "the body must go through decryptBody")
    }

    @Test
    fun `an edit lands as NoticeEdited and a delete carries the id`() {
        val edited = event("info", "info:message:edited", post)
        assertEquals("n1", (edited as HomeRealtimeEvent.NoticeEdited).notice.id)

        val deleted = event("account", "account:message:deleted", """{"_id":"n1","unit_id":"u1"}""")
        assertEquals(HomeRealtimeEvent.NoticeDeleted("u1", "n1"), deleted)
    }

    @Test
    fun `multi-deletes, comments, units, rights and archives all coarsen to a reload`() {
        val reloads = listOf(
            event("script-notes", "script_notes:message:deleted:multiple", """{"_id":"n1"}"""),
            event("reports", "reports:message:comment:added", """{"_id":"n1","comments":[{}]}"""),
            event("catering", "catering:unit:created", """{"unit_name":"Dinner"}"""),
            event("account", "account:posting-rights:update", """{"enabled":false}"""),
            event("confidentialinfo", "confidential_info:chat:archived", """{"unit_id":"u1"}"""),
        )

        reloads.forEach { assertEquals(HomeRealtimeEvent.UnitsChanged, it) }
    }

    @Test
    fun `wardrobe main and background unit posts patch like the tool's own`() {
        val added = event("wardrobe", "wardrobe-main-unit:message:added", post)
        assertEquals("n1", (added as HomeRealtimeEvent.NoticeAdded).notice.id)

        val edited = event("wardrobe", "wardrobe-background-unit:message:edited", post)
        assertEquals("n1", (edited as HomeRealtimeEvent.NoticeEdited).notice.id)
    }

    @Test
    fun `an unknown event maps to nothing`() {
        // Another board's event, and a web-only alias that exists on no wire.
        assertNull(event("info", "catering:message:added", post))
        assertNull(event("info", "info_message_added", post))
    }

    @Test
    fun `a post the reader cannot parse is dropped, not thrown`() {
        assertNull(event("info", "info:message:added", """{"status":1}"""))
        assertNull(event("info", "info:message:deleted", """{"unit_id":"u1"}"""))
    }

    // -- through the bus ---------------------------------------------------

    @Test
    fun `a board's flow delivers its own traffic and ignores the rest`() = runTest {
        val client = ScriptedSocketClient()
        val seen = mutableListOf<HomeRealtimeEvent>()
        backgroundScope.launch {
            boardRealtime(SocketEventBus(client), "catering", decryptBody = { it }).collect { seen += it }
        }
        runCurrent()

        client.deliver("catering:message:added", post)
        client.deliver("home:message:added", post) // Home's, not this board's.
        client.deliver("catering:unit:deleted", """{"unit_id":"u1"}""")
        runCurrent()

        assertEquals(2, seen.size)
        assertEquals("n1", (seen[0] as HomeRealtimeEvent.NoticeAdded).notice.id)
        assertEquals(HomeRealtimeEvent.UnitsChanged, seen[1])
    }

    /**
     * Home was the one board that ignored comments.
     *
     * Every sibling reloads on `<prefix>:message:comment:*` through
     * `chatBoard`, but Home has its own event list and had none — so the same
     * action appeared live on an Info notice and silently on a Home one.
     */
    @Test
    fun `a comment on a home notice reloads the board`() = runTest {
        val client = ScriptedSocketClient()
        val seen = mutableListOf<HomeRealtimeEvent>()
        backgroundScope.launch {
            HomeRealtimeSource(SocketEventBus(client), decryptBody = { it }).stream
                .collect { seen += it }
        }
        runCurrent()

        client.deliver("home:message:comment:added", """{"_id":"c1","unit_id":"u1"}""")
        client.deliver("home:message:comment:edited", """{"_id":"c1","unit_id":"u1"}""")
        client.deliver("home:message:comment:deleted", """{"_id":"c1","unit_id":"u1"}""")
        runCurrent()

        assertEquals(3, seen.size, "each comment event reloads once")
        seen.forEach { assertEquals(HomeRealtimeEvent.UnitsChanged, it) }
    }

    /** A post still patches in place — coarsening everything would be a regression. */
    @Test
    fun `a home post is still delivered as a post`() = runTest {
        val client = ScriptedSocketClient()
        val seen = mutableListOf<HomeRealtimeEvent>()
        backgroundScope.launch {
            HomeRealtimeSource(SocketEventBus(client), decryptBody = { it }).stream
                .collect { seen += it }
        }
        runCurrent()

        client.deliver("home:message:added", post)
        runCurrent()

        assertEquals("n1", (seen.single() as HomeRealtimeEvent.NoticeAdded).notice.id)
    }

    @Test
    fun `an unknown board yields an empty flow rather than throwing`() = runTest {
        val client = ScriptedSocketClient()
        val seen = mutableListOf<HomeRealtimeEvent>()
        backgroundScope.launch {
            boardRealtime(SocketEventBus(client), "boxschedule", decryptBody = { it }).collect { seen += it }
        }
        runCurrent()

        client.deliver("catering:message:added", post)
        runCurrent()

        assertTrue(seen.isEmpty())
    }
}

/**
 * The smallest [SocketClient] that can carry a message — `core:socket`'s
 * fake lives in that module's own test set and is not published, so the
 * board tests script their own.
 */
private class ScriptedSocketClient : SocketClient {

    private val state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    override val connectionState = state.asStateFlow()

    private val inbound = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 16)
    override val messages: Flow<SocketMessage> = inbound

    suspend fun deliver(event: String, payloadJson: String) {
        inbound.emit(SocketMessage(SocketEventName(event), Json.parseToJsonElement(payloadJson)))
    }

    override suspend fun connect(config: SocketConfig) {
        state.value = SocketConnectionState.Connected("scripted")
    }

    override suspend fun disconnect() {
        state.value = SocketConnectionState.Disconnected
    }

    override suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<JsonElement> = ZillitResult.Success(JsonNull)
}
