package com.zillit.desktop.feature.selectstills

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.zillit.desktop.feature.selectstills.ui.StillsDark
import com.zillit.desktop.feature.selectstills.ui.StillsLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two palettes, held to what each one has to be.
 *
 * The web's sheet is dark-only, so the light set has no source to copy and is
 * the easiest thing in the tool to get subtly wrong — an amber word that is
 * invisible on white, a wash that tints the wrong way, a token somebody added
 * to one palette and forgot in the other.
 */
class StillsPaletteTest {

    @Test
    fun `text reads against its own page, on both`() {
        assertTrue(StillsDark.ink.luminance() > StillsDark.bg.luminance(), "dark: text is not lighter than the page")
        assertTrue(StillsLight.ink.luminance() < StillsLight.bg.luminance(), "light: text is not darker than the page")
        // And the quieter text still has to be legible, not a whisper.
        assertTrue(contrast(StillsDark.muted, StillsDark.panel) > QUIET, "dark muted text is too faint on a card")
        assertTrue(contrast(StillsLight.muted, StillsLight.panel) > QUIET, "light muted text is too faint on a card")
        assertTrue(contrast(StillsDark.ink, StillsDark.panel) > BODY, "dark body text is too faint on a card")
        assertTrue(contrast(StillsLight.ink, StillsLight.panel) > BODY, "light body text is too faint on a card")
    }

    @Test
    fun `the amber darkens where it is a word and stays where it is a background`() {
        // The identity colour is the same amber on both pages…
        assertEquals(StillsDark.accent, StillsLight.accent)
        // …and carries the same dark text when it is a background.
        assertEquals(StillsDark.onAccent, StillsLight.onAccent)
        assertTrue(contrast(StillsLight.onAccent, StillsLight.accent) > BODY, "text on the amber is unreadable")
        // But as a word it cannot stay pale: that is invisible on white.
        assertTrue(
            StillsLight.accentInk.luminance() < StillsDark.accentInk.luminance(),
            "the accent as text must darken for the light page",
        )
        assertTrue(contrast(StillsLight.accentInk, StillsLight.panel) > BODY, "accent word unreadable on a light card")
        assertTrue(contrast(StillsDark.accentInk, StillsDark.panel) > BODY, "accent word unreadable on a dark card")
    }

    @Test
    fun `a decision's colour is legible as a word on its own page`() {
        listOf(
            "kept (dark)" to (StillsDark.okText to StillsDark.panel2),
            "kept (light)" to (StillsLight.okText to StillsLight.panel2),
            "discarded (dark)" to (StillsDark.badText to StillsDark.panel2),
            "discarded (light)" to (StillsLight.badText to StillsLight.panel2),
        ).forEach { (what, pair) ->
            assertTrue(contrast(pair.first, pair.second) > QUIET, "$what is too faint to read")
        }
    }

    @Test
    fun `a wash tints towards the page it is on`() {
        // White over a dark page lifts it; black over a light page sinks it.
        assertEquals(Color.White, StillsDark.overlay)
        assertEquals(Color.Black, StillsLight.overlay)
    }

    @Test
    fun `a photograph is mounted on dark whichever theme is on`() {
        assertTrue(StillsDark.stageBg.luminance() < MOUNT, "the dark stage is not dark")
        // A white surround changes how a photograph reads, so the mount stays dark.
        assertTrue(StillsLight.stageBg.luminance() < MOUNT, "the light theme's stage is not dark")
        // And a scrim dims on both pages, rather than veiling in white.
        assertTrue(StillsDark.scrim.luminance() < MOUNT)
        assertTrue(StillsLight.scrim.luminance() < MOUNT)
    }

    @Test
    fun `the surfaces actually invert, so neither palette is a copy of the other`() {
        listOf(
            "bg" to (StillsDark.bg to StillsLight.bg),
            "panel" to (StillsDark.panel to StillsLight.panel),
            "panel2" to (StillsDark.panel2 to StillsLight.panel2),
            "ink" to (StillsDark.ink to StillsLight.ink),
            "line" to (StillsDark.line to StillsLight.line),
            "bar" to (StillsDark.bar to StillsLight.bar),
        ).forEach { (name, pair) ->
            assertTrue(pair.first != pair.second, "$name was left the same in both palettes")
        }
        assertTrue(StillsDark.isDark)
        assertTrue(!StillsLight.isDark)
    }

    /** WCAG's ratio, so "legible" is a number rather than an opinion. */
    private fun contrast(a: Color, b: Color): Double {
        val one = a.luminance() + 0.05
        val two = b.luminance() + 0.05
        return if (one > two) one / two else two / one
    }

    private companion object {
        /** Readable for ordinary text. */
        const val BODY = 4.5
        /** Readable for the small grey lines, which are 12–13sp. */
        const val QUIET = 3.0
        const val MOUNT = 0.15f
    }
}
