package com.zillit.desktop.feature.home.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * `#rrggbb` → a colour, or null for anything else.
 *
 * Strictly six hex digits: the wire's colours come from the web's free
 * picker, and a value that fails to parse must fall back rather than crash a
 * cell mid-composition.
 */
internal fun parseEventColor(raw: String?): Color? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    if (hex.length != HEX_DIGITS) return null
    val value = hex.toLongOrNull(HEX_RADIX) ?: return null
    return Color(OPAQUE or value)
}

/**
 * The colour this event wears everywhere — chip, stripe, block.
 *
 * The web's default is white (`color: '#ffffff'`), which on its light month
 * grid means "no colour chosen". Painting white chips here would make them
 * invisible on the light theme, so the default — and anything unparseable —
 * wears the accent instead.
 */
@Composable
internal fun CalendarEvent.tint(): Color {
    val parsed = parseEventColor(colorHex)
    return if (parsed == null || parsed == Color.White) ZillitTheme.colors.accent else parsed
}

/**
 * The palette the form offers — the web's `EVENT_COLORS`, in its order.
 *
 * The same twelve the web's picker presets to, so an event coloured on one
 * client reads as a deliberate choice on the other instead of as the nearest
 * hue the desktop happened to carry.
 */
internal val EVENT_PALETTE = listOf(
    "#fc9404",
    "#C7784A",
    "#7A8B6E",
    "#C4697A",
    "#5B7FA5",
    "#A67B5B",
    "#D4A853",
    "#5A6B4F",
    "#8B6E8B",
    "#6B8F9C",
    "#D4956B",
    "#785A3C",
)

/** The web's `DEFAULT_EVENT_COLOR`: what a new event wears unless it says otherwise. */
internal const val DEFAULT_EVENT_COLOR = "#fc9404"

private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
