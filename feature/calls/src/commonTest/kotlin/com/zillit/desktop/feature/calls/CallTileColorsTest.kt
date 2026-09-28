package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.ui.CallTile
import com.zillit.desktop.feature.calls.ui.CallTileColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The stage's colours, against the web's `colors.ts`.
 *
 * A call grid is eight faces at once and the colour is the identity cue, so the
 * two rules worth pinning are that it is derived from the NAME (stable across
 * rejoins, where a session id would flicker) and that two identical names never
 * share one.
 */
class CallTileColorsTest {

    private fun tile(key: String, name: String) = CallTile(key = key, name = name, userId = key)

    @Test
    fun `a colour follows the name, not the session`() {
        assertEquals(CallTileColors.of("Vivek Mishra"), CallTileColors.of("vivek mishra"))
        assertEquals(CallTileColors.of("Aisha Khan"), CallTileColors.PALETTE[0], "A is the first slot")
        assertEquals(CallTileColors.of("Bilal"), CallTileColors.PALETTE[1])
        // Leading space and case are noise; a blank name still gets a colour
        // rather than a crash or a transparent tile.
        assertEquals(CallTileColors.of("  ana"), CallTileColors.of("Ana"))
        assertTrue(CallTileColors.of("") in CallTileColors.PALETTE)
        assertTrue(CallTileColors.of("7-Eleven") in CallTileColors.PALETTE, "a non-letter start still lands in range")
    }

    @Test
    fun `people who share a name are bumped to different colours`() {
        val tiles = listOf(
            tile("u-1", "Sahil Kashyap"),
            tile("u-2", "Sahil Kashyap"),
            tile("u-3", "Sahil Kashyap"),
        )
        val assigned = CallTileColors.assign(tiles)
        assertEquals(3, assigned.values.toSet().size, "three identical names, three colours")
        assertEquals(CallTileColors.of("Sahil Kashyap"), assigned["u-1"], "the first keeps the letter colour")
        assertNotEquals(assigned["u-1"], assigned["u-2"])
    }

    @Test
    fun `the bump lands on the same person whichever client is drawing`() {
        val one = listOf(tile("u-b", "Ana"), tile("u-a", "Ana"))
        val other = listOf(tile("u-a", "Ana"), tile("u-b", "Ana"))
        assertEquals(
            CallTileColors.assign(one),
            CallTileColors.assign(other),
            "sorted by key, so the order the tiles arrive in cannot change who is bumped",
        )
    }

    @Test
    fun `different first letters keep their own colours`() {
        val assigned = CallTileColors.assign(
            listOf(tile("u-1", "Ana"), tile("u-2", "Bilal"), tile("u-3", "Chandra")),
        )
        assertEquals(3, assigned.values.toSet().size)
    }

    @Test
    fun `every tile gets a colour, including one bound to no user`() {
        // An unclaimed media stream has a key and no user id — the tile drawn
        // for a peer the roster has not caught up with.
        val assigned = CallTileColors.assign(listOf(CallTile(key = "uid:4231", name = "")))
        assertEquals(1, assigned.size)
        assertTrue(assigned.getValue("uid:4231") in CallTileColors.PALETTE)
    }
}
