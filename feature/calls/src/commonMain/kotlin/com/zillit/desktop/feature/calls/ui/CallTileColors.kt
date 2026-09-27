package com.zillit.desktop.feature.calls.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Each person's colour on the call stage — the web's `lineTwo/ui/colors.ts`,
 * rule for rule.
 *
 * ## Why it is not the app's avatar hue
 *
 * The rest of Zillit hues an avatar from a hash of the name, which is fine for
 * a list where two similar colours sit rows apart. A call grid is eight faces
 * at once, and the colour is load-bearing there: it is the tile background, the
 * speaking ring and the initial disc, so two people a shade apart read as the
 * same person's tile moving. The web solved that with a fixed eight-colour
 * palette, a letter index and a collision bump, and the two clients have to
 * agree — the same person must be the same colour on a desktop and in a browser
 * for anyone to describe a call out loud ("the blue tile is frozen").
 *
 * ## The rule
 *
 * The base colour comes from the FIRST LETTER of the display name, not a hash
 * of the id: ids are minted per session and would flicker on every rejoin,
 * where a name does not. People who share a first letter therefore share a
 * colour, which is deliberate — until they share a NAME, when the second and
 * third "Sahil Kashyap" are bumped to the next palette slots so no two
 * identical names are ever the same colour. The bump order is by user id so
 * that every client bumps the same person, and it is stable for the call.
 */
object CallTileColors {

    /** The web's palette, in its order — index 0 is A. */
    val PALETTE: List<Color> = listOf(
        Color(0xFF1A73E8), // blue
        Color(0xFFD93025), // red
        Color(0xFF188038), // green
        Color(0xFFF9AB00), // yellow
        Color(0xFF9334E6), // purple
        Color(0xFF12B5CB), // teal
        Color(0xFFE8710A), // orange
        Color(0xFFE52592), // pink
    )

    /**
     * One name's colour, with no call around it — the fallback the web's
     * `avatarColor` is, for a tile the assignment has not reached yet.
     */
    fun of(name: String): Color = PALETTE[slotFor(name, bump = 0)]

    /**
     * Every tile's colour for one call.
     *
     * Keyed by [CallTile.key] so an unclaimed media stream — a tile with no
     * user id at all — still gets one, and sorted by key so the collision bump
     * lands on the same person whichever client is drawing.
     */
    fun assign(tiles: List<CallTile>): Map<String, Color> {
        val bumps = mutableMapOf<String, Int>()
        val assigned = mutableMapOf<String, Color>()
        for (tile in tiles.sortedBy { it.key }) {
            val key = tile.name.trim().lowercase()
            val bump = bumps.getOrElse(key) { 0 }
            bumps[key] = bump + 1
            assigned[tile.key] = PALETTE[slotFor(tile.name, bump)]
        }
        return assigned
    }

    /**
     * A-Z onto palette slots, anything else onto its code point.
     *
     * `charCodeAt(0) - 65` on the web, and the same modulo, so a name starting
     * with a digit or a non-Latin letter lands on the same slot in both.
     */
    private fun slotFor(name: String, bump: Int): Int {
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: 'A'
        val base = if (first in 'A'..'Z') first.code - 'A'.code else first.code
        return (base + bump).mod(PALETTE.size)
    }
}

/**
 * The colours assigned for the call now on screen, by [CallTile.key].
 *
 * A composition local because the collision bump needs the WHOLE list and the
 * tiles are drawn from four places — the grid, the duo layout, the pinned
 * stage and the pill's thumbnail — none of which has any other reason to know
 * about colour. The stage provides it once; a tile that is drawn outside one
 * (a render test, the thumbnail) falls back to the letter colour, which is
 * what the web's `colorOf` does when its map misses.
 */
val LocalCallTileColors = staticCompositionLocalOf { emptyMap<String, Color>() }
