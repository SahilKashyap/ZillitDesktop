package com.zillit.desktop.feature.home

import androidx.compose.ui.graphics.Color
import com.zillit.desktop.feature.home.calendar.DEFAULT_EVENT_COLOR
import com.zillit.desktop.feature.home.calendar.EVENT_PALETTE
import com.zillit.desktop.feature.home.calendar.parseEventColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Event colours come from the web's free picker, so the parser sees anything —
 * and a value it cannot read must cost that event its colour, not the grid.
 */
class CalendarColorsTest {

    @Test
    fun `six hex digits parse, with or without the hash`() {
        assertEquals(Color(0xFFF5222D), parseEventColor("#f5222d"))
        assertEquals(Color(0xFFF5222D), parseEventColor("f5222d"))
        assertEquals(Color(0xFFFFFFFF), parseEventColor("#FFFFFF"))
    }

    @Test
    fun `anything else refuses to parse rather than guessing`() {
        assertNull(parseEventColor(null))
        assertNull(parseEventColor(""))
        assertNull(parseEventColor("#fff"))
        assertNull(parseEventColor("#f5222d00"))
        assertNull(parseEventColor("red"))
        assertNull(parseEventColor("#zzzzzz"))
    }

    @Test
    fun `every palette entry is usable, and the default leads`() {
        // The web's EVENT_COLORS, in its order: the default is what a new
        // event wears, so it is the one the picker opens on.
        assertEquals(DEFAULT_EVENT_COLOR, EVENT_PALETTE.first())
        EVENT_PALETTE.forEach { hex ->
            assertEquals(true, parseEventColor(hex) != null, "palette entry $hex must parse")
        }
    }
}
