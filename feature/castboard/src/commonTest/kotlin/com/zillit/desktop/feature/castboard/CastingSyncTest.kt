package com.zillit.desktop.feature.castboard

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.castboard.data.castingSyncEvents
import com.zillit.desktop.feature.castboard.domain.BoardTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire names each board listens on.
 *
 * Derived from `BoardTool.segment`, which is the wire's own prefix, so the two
 * boards cannot drift apart. A typo here is silence, not an error.
 */
class CastingSyncTest {

    private fun names(board: BoardTool) = castingSyncEvents(board).map(SocketEventName::value)

    @Test
    fun `casting listens for its own records only`() {
        assertEquals(listOf("casting:created", "casting:updated", "casting:deleted"), names(BoardTool.Casting))
    }

    @Test
    fun `wardrobe adds the move, spelled as the wire spells it`() {
        // `wardrobe:move`, not `:moved` — the web and Android agree, and
        // casting has no move at all.
        assertEquals(
            listOf("wardrobe:created", "wardrobe:updated", "wardrobe:deleted", "wardrobe:move"),
            names(BoardTool.Wardrobe),
        )
    }

    @Test
    fun `the discussion family stays out of this list`() {
        // Those ride the Home feed engine via BoardRealtimeEvents; two reload
        // paths on one board would race each other.
        listOf(BoardTool.Casting, BoardTool.Wardrobe).forEach { board ->
            assertTrue(names(board).none { it.contains(":message:") }, board.segment)
        }
    }

    @Test
    fun `neither board listens for the other's records`() {
        assertFalse(names(BoardTool.Casting).any { it.startsWith("wardrobe") })
        assertFalse(names(BoardTool.Wardrobe).any { it.startsWith("casting") })
    }

    // -- the discussion ----------------------------------------------------

    private fun chat(board: BoardTool) =
        com.zillit.desktop.feature.castboard.data.castingDiscussionEvents(board).map(SocketEventName::value)

    @Test
    fun `a board listens on all three of its chats`() {
        // The tool's own thread plus one per unit — the wire carries three,
        // and hearing only the first leaves the unit tabs silent.
        val names = chat(BoardTool.Casting)
        listOf("casting", "casting-main-unit", "casting-background-unit").forEach { prefix ->
            assertTrue(names.any { it.startsWith("$prefix:message:") }, prefix)
        }
    }

    @Test
    fun `each chat carries the six-event family`() {
        val names = chat(BoardTool.Casting)
        listOf(
            "message:added", "message:edited", "message:deleted:multiple",
            "message:comment:added", "message:comment:edited", "message:comment:deleted",
        ).forEach { suffix -> assertTrue("casting:$suffix" in names, suffix) }
    }

    @Test
    fun `the singular delete is not on the wire for these boards`() {
        // Only the plural is sent here, unlike Home. Subscribing to a name the
        // server never emits is silence that reads like a broken feature.
        assertFalse("casting:message:deleted" in chat(BoardTool.Casting))
    }

    @Test
    fun `read-by rides the tool's own prefix only`() {
        val names = chat(BoardTool.Casting)
        assertTrue("casting:message:readby:update" in names)
        assertFalse("casting-main-unit:message:readby:update" in names)
    }

    @Test
    fun `wardrobe gets its own three chats, not casting's`() {
        val names = chat(BoardTool.Wardrobe)
        assertTrue("wardrobe-background-unit:message:added" in names)
        assertFalse(names.any { it.startsWith("casting") })
    }
}
