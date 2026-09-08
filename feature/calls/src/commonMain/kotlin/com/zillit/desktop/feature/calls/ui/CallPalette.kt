package com.zillit.desktop.feature.calls.ui

import androidx.compose.ui.graphics.Color

/**
 * The call surface's own colours — the web's Line 3 tokens
 * (`lineTwo/ui/styles.css:3-19`, `colors.ts:2-41`), not the app theme's.
 *
 * A call is one dark stage on every client, light mode included: the picture
 * is what the eye should rest on, and a stage that followed the workspace
 * theme put white chrome around a dark video. Named here once so the dock,
 * the top bar and the ring card cannot drift from each other, or from the
 * page that draws the tiles (`call.html`'s `:root` carries the same values).
 */
internal object CallPalette {
    val surface = Color(0xFF202124)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF9AA0A6)
    val control = Color(0xFF3C4043)
    val tileIdle = Color(0xFF2B2D31)
    val controlGroup = Color(0xFF333537)
    val caret = Color(0xFF2D2F31)
    val accent = Color(0xFF8AB4F8)
    val onAccent = Color(0xFF202124)
    val danger = Color(0xFFEA4335)
    val offPill = Color(0xFFF2B8B5)
    val onOffPill = Color(0xFF601410)
    val offCaret = Color(0xFF5C1A16)
    val amber = Color(0xFFFBBC04)
    val amberSoft = Color(0xFFFDE293)
    val green = Color(0xFF34A853)
    val menu = Color(0xFF303134)
    val pill = Color(0x14FFFFFF)
    val scrim = Color(0x73000000)

    /** The eight letter colours the web assigns avatars by first letter (`colors.ts:2-41`). */
    private val letters = listOf(
        0xFF1A73E8, 0xFFD93025, 0xFF188038, 0xFFF9AB00, 0xFF9334E6, 0xFF12B5CB, 0xFFE8710A, 0xFFE52592,
    ).map(::Color)

    fun letterColour(name: String): Color {
        val first = name.trim().firstOrNull()?.uppercaseChar()?.code ?: 0
        return letters[first % letters.size]
    }
}
