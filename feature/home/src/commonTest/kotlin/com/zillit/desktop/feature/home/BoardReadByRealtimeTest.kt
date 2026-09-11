package com.zillit.desktop.feature.home

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.home.data.BOARD_REALTIME_EVENTS
import com.zillit.desktop.feature.home.data.toRealtimeEvent
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.applyRealtime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A receipt is not a post.
 *
 * The board shows nothing for a read-by frame; only an open read-by panel
 * moves. These pin that each board carries its own prefixed name, that the
 * message id is read from the row rather than from `data`, and that a
 * malformed frame is dropped instead of refreshing a panel about nothing.
 */
class BoardReadByRealtimeTest {

    @Test
    fun `every reused board carries its own read-by name`() {
        val expected = mapOf(
            "info" to "info:message:readby:update",
            "confidentialinfo" to "confidential_info:message:readby:update",
            "reports" to "reports:message:readby:update",
            "script-notes" to "script_notes:message:readby:update",
            "catering" to "catering:message:readby:update",
            "account" to "account:message:readby:update",
        )

        expected.forEach { (board, name) ->
            val names = BOARD_REALTIME_EVENTS.getValue(board).all.map { it.value }
            assertTrue(name in names, "$board should listen on $name")
        }
    }

    /** Wardrobe's three chats each carry one, as its three message prefixes do. */
    @Test
    fun `wardrobe carries one per prefix`() {
        val names = BOARD_REALTIME_EVENTS.getValue("wardrobe").all.map { it.value }

        assertTrue("wardrobe:message:readby:update" in names)
        assertTrue("wardrobe-main-unit:message:readby:update" in names)
        assertTrue("wardrobe-background-unit:message:readby:update" in names)
    }

    /** Home's own board declares it beside its message names. */
    @Test
    fun `home declares its read-by name`() {
        assertEquals("home:message:readby:update", ZillitSocketEvents.Home.MessageReadBy.value)
        assertTrue(ZillitSocketEvents.Home.MessageReadBy in ZillitSocketEvents.Home.All)
    }

    @Test
    fun `a receipt names the message it is about`() {
        val board = BOARD_REALTIME_EVENTS.getValue("info")
        val frame = SocketMessage(
            SocketEventName("info:message:readby:update"),
            Json.parseToJsonElement("""[{"project_id":"p1","message_id":"m7"}]"""),
        )

        assertEquals(HomeRealtimeEvent.ReadByChanged("m7"), board.toRealtimeEvent(frame) { it })
    }

    /** The id sits beside `project_id`, not inside `data` — a flat row works too. */
    @Test
    fun `a flat row is read the same way`() {
        val board = BOARD_REALTIME_EVENTS.getValue("account")
        val frame = SocketMessage(
            SocketEventName("account:message:readby:update"),
            Json.parseToJsonElement("""{"message_id":"m9"}"""),
        )

        assertEquals(HomeRealtimeEvent.ReadByChanged("m9"), board.toRealtimeEvent(frame) { it })
    }

    @Test
    fun `a receipt naming nothing is dropped`() {
        val board = BOARD_REALTIME_EVENTS.getValue("info")
        val frame = SocketMessage(
            SocketEventName("info:message:readby:update"),
            Json.parseToJsonElement("""{"project_id":"p1"}"""),
        )

        assertNull(board.toRealtimeEvent(frame) { it })
    }

    /** The board list is untouched by a receipt. */
    @Test
    fun `a receipt changes no notice`() {
        val notices = emptyList<Notice>()

        assertEquals(notices, notices.applyRealtime(HomeRealtimeEvent.ReadByChanged("m7"), selectedUnitId = "u1"))
    }
}
