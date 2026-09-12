package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The filing surface's colours — the web's `VC` tokens (`mtd-tokens.js`).
 *
 * Every one is derived from the app's own theme rather than declared, so the
 * surface follows light and dark with the rest of the application. Where the
 * web darkens a hue for legible text on its pale wash (green, amber, the
 * accent's text), the light scheme mixes the theme's hue toward black by the
 * same amount; the dark scheme keeps the theme's own, already-lifted hues.
 */
@Immutable
internal data class MtdPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    /** Card footers and summary strips — a step off the card. */
    val surface2: Color,
    /** Hover wells and the box badge. */
    val surface3: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val border2: Color,
    val divider: Color,
    val accent: Color,
    val accentHover: Color,
    /** Text on the accent fill — dark, as the web's CTA wears it. */
    val accentInk: Color,
    val accentText: Color,
    val accentWash: Color,
    val accentBorder: Color,
    val green: Color,
    val greenWash: Color,
    val greenBorder: Color,
    val amber: Color,
    val amberWash: Color,
    val amberBorder: Color,
    val blue: Color,
    val blueWash: Color,
    val blueBorder: Color,
    val red: Color,
    val redWash: Color,
    val redBorder: Color,
    /** The confirmation pill: ink on light, raised surface on dark. */
    val toast: Color,
    val onToast: Color,
)

@Composable
internal fun mtdPalette(): MtdPalette {
    val colors = ZillitTheme.colors
    return remember(colors) { colors.toMtd() }
}

private fun ZillitColors.toMtd(): MtdPalette {
    val dark = isDark
    fun deepen(color: Color) = if (dark) color else lerp(color, Color.Black, DEEPEN)
    fun wash(color: Color, soft: Color) = if (dark) soft else lerp(surface, color, WASH)
    fun edge(color: Color) = if (dark) color.copy(alpha = EDGE_ALPHA_DARK) else lerp(surface, color, EDGE)
    return MtdPalette(
        isDark = dark,
        bg = surfaceSunken,
        surface = surface,
        surface2 = if (dark) lerp(surface, surfaceRaised, HALF) else surfaceSunken,
        surface3 = surfaceHover,
        ink = textPrimary,
        ink2 = lerp(textPrimary, textSecondary, INK2),
        ink3 = textSecondary,
        muted = textSecondary,
        faint = borderStrong,
        border = border,
        border2 = borderStrong,
        divider = divider,
        accent = accent,
        accentHover = accentHover,
        accentInk = if (dark) textOnAccent else textPrimary,
        accentText = if (dark) accentText else lerp(accent, Color.Black, DEEPEN),
        accentWash = accentSoft,
        accentBorder = edge(accent),
        green = deepen(success),
        greenWash = wash(success, successSoft),
        greenBorder = edge(success),
        amber = deepen(warning),
        amberWash = wash(warning, warningSoft),
        amberBorder = edge(warning),
        blue = deepen(info),
        blueWash = wash(info, infoSoft),
        blueBorder = edge(info),
        red = danger,
        redWash = wash(danger, dangerSoft),
        redBorder = edge(danger),
        toast = if (dark) surfaceRaised else textPrimary,
        onToast = if (dark) textPrimary else surface,
    )
}

private const val DEEPEN = 0.33f
private const val WASH = 0.1f
private const val EDGE = 0.32f
private const val EDGE_ALPHA_DARK = 0.34f
private const val HALF = 0.5f
private const val INK2 = 0.55f

/**
 * The surface's type, sized as the web sets it.
 *
 * Built on the theme's own families — its sans for words, its mono (DM Mono
 * where bundled) for VAT numbers, codes and money, which is what lines a
 * column of figures up.
 */
@Composable
internal fun mtdText(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    tracking: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
): TextStyle {
    val base = if (mono) ZillitTheme.typography.numeric else ZillitTheme.typography.bodyMedium
    return base.copy(
        fontSize = size,
        fontWeight = weight,
        fontFamily = if (mono) base.fontFamily ?: FontFamily.Monospace else base.fontFamily,
        letterSpacing = tracking,
        lineHeight = if (lineHeight == TextUnit.Unspecified) (size.value * LINE).sp else lineHeight,
    )
}

/** The web's uppercase eyebrow — 10.5px, bold, widely tracked. */
@Composable
internal fun mtdEyebrow(size: TextUnit = 10.5.sp): TextStyle =
    mtdText(size = size, weight = FontWeight.Bold, tracking = 0.07.em)

private const val LINE = 1.4f
