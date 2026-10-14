package com.zillit.desktop.feature.transporthub.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The Transportation Hub's own colours — `hubTheme.css`, value for value.
 *
 * The hub is the one tool the web draws in its own skin rather than the app's:
 * an amber accent shared with CNC, a cool grey page behind white cards, and
 * status hues tuned per theme (deeper in light, brighter in dark). Reading
 * these instead of the app's tokens is what makes the hub look like the hub.
 * The light and dark sets follow the app's own theme.
 */
@Immutable
data class HubPalette(
    val bg: Color,
    val bgAlt: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHover: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textPlaceholder: Color,
    val accent: Color,
    val accentDark: Color,
    val accentLight: Color,
    val accentBg: Color,
    val accentBorder: Color,
    val blue: Color,
    val blueBg: Color,
    val green: Color,
    val greenBg: Color,
    val red: Color,
    val redBg: Color,
    val border: Color,
    val borderLight: Color,
    val borderStrong: Color,
    val menuHoverBg: Color,
    val menuActiveBg: Color,
    val menuActiveText: Color,
    /** The segmented control's chosen item — solid amber in light, a raised chip in dark. */
    val segmentActiveBg: Color,
    val segmentActiveText: Color,
    val isDark: Boolean,
) {
    /** The accent gradient's far end — `linear-gradient(135deg, #f59e0b, #f97316)`. */
    val accentGradientEnd: Color get() = ORANGE_500

    companion object {
        val ORANGE_500 = Color(0xFFF97316)
    }
}

val HubLight = HubPalette(
    bg = Color(0xFFF0F2F5),
    bgAlt = Color(0xFFE4E6EB),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF9FAFB),
    surfaceHover = Color(0xFFF3F4F6),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF374151),
    textMuted = Color(0xFF6B7280),
    textPlaceholder = Color(0xFF9CA3AF),
    accent = Color(0xFFF59E0B),
    accentDark = Color(0xFFD97706),
    accentLight = Color(0xFFFBBF24),
    accentBg = Color(0x14F99E0B),
    accentBorder = Color(0x4DF99E0B),
    blue = Color(0xFF2563EB),
    blueBg = Color(0xFFDBEAFE),
    green = Color(0xFF059669),
    greenBg = Color(0xFFD1FAE5),
    red = Color(0xFFDC2626),
    redBg = Color(0xFFFEE2E2),
    border = Color(0xFFE5E7EB),
    borderLight = Color(0xFFF3F4F6),
    borderStrong = Color(0xFFD1D5DB),
    menuHoverBg = Color(0x0AF99E0B),
    menuActiveBg = Color(0x1AF99E0B),
    menuActiveText = Color(0xFFD97706),
    segmentActiveBg = Color(0xFFF59E0B),
    segmentActiveText = Color(0xFFFFFFFF),
    isDark = false,
)

val HubDark = HubPalette(
    bg = Color(0xFF111827),
    bgAlt = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    surfaceAlt = Color(0xFF151E2F),
    surfaceHover = Color(0x0AFFFFFF),
    textPrimary = Color(0xEBFFFFFF),
    textSecondary = Color(0xB8FFFFFF),
    textMuted = Color(0x80FFFFFF),
    textPlaceholder = Color(0x4DFFFFFF),
    accent = Color(0xFFF59E0B),
    accentDark = Color(0xFFD97706),
    accentLight = Color(0xFFFBBF24),
    accentBg = Color(0x1FF99E0B),
    accentBorder = Color(0x59F99E0B),
    blue = Color(0xFF60A5FA),
    blueBg = Color(0x2660A5FA),
    green = Color(0xFF34D399),
    greenBg = Color(0x2634D399),
    red = Color(0xFFF87171),
    redBg = Color(0x26F87171),
    border = Color(0x14FFFFFF),
    borderLight = Color(0x0AFFFFFF),
    borderStrong = Color(0x24FFFFFF),
    menuHoverBg = Color(0x0FF99E0B),
    menuActiveBg = Color(0x1FF99E0B),
    menuActiveText = Color(0xFFFBBF24),
    segmentActiveBg = Color(0xFF1E293B),
    segmentActiveText = Color(0xFFFBBF24),
    isDark = true,
)

/** The palette for the app's current theme. */
val hub: HubPalette
    @Composable get() = if (ZillitTheme.colors.isDark) HubDark else HubLight
