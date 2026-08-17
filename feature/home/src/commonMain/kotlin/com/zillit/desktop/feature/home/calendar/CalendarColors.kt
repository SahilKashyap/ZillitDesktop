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
 * The palette the form offers. First entry is "no colour" (the web's white
 * default, shown as the accent); the rest are the hues the web's picker
 * commonly lands on.
 */
internal val EVENT_PALETTE = listOf(
    "",
    "#f5222d",
    "#fa8c16",
    "#fadb14",
    "#52c41a",
    "#1677ff",
    "#722ed1",
)

private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
