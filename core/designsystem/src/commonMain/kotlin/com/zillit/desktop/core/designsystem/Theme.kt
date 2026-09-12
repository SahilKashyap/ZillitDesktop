package com.zillit.desktop.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * The user's theme choice. `System` follows the OS appearance.
 */
enum class ThemeMode { Light, Dark, System;

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: System
    }
}

/**
 * Root theme.
 *
 * Provides both the Zillit token set (what our components read) and a Material 3
 * `ColorScheme` derived from it (so stock Material components inherit the theme
 * rather than rendering purple).
 *
 * Colour changes are animated: an instant flip between light and dark is
 * jarring on a large screen, and the transition costs nothing.
 */
@Composable
fun ZillitTheme(
    darkTheme: Boolean = false,
    animateThemeChange: Boolean = true,
    content: @Composable () -> Unit,
) {
    val target = if (darkTheme) DarkColors else LightColors
    val colors = if (animateThemeChange) target.animated() else target
    val fonts = rememberZillitFonts()

    CompositionLocalProvider(
        LocalZillitColors provides colors,
        LocalZillitSpacing provides ZillitSpacing(),
        LocalZillitFonts provides fonts,
        LocalZillitTypography provides ZillitTypography(fonts),
        LocalZillitShapes provides ZillitShapes(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            content = content,
        )
    }
}

/**
 * Token accessors. `ZillitTheme.colors.textMuted` reads better at a call site
 * than `LocalZillitColors.current.textMuted`, and it keeps the composition
 * locals out of feature code.
 */
object ZillitTheme {
    val colors: ZillitColors
        @Composable @ReadOnlyComposable get() = LocalZillitColors.current

    val spacing: ZillitSpacing
        @Composable @ReadOnlyComposable get() = LocalZillitSpacing.current

    val typography: ZillitTypography
        @Composable @ReadOnlyComposable get() = LocalZillitTypography.current

    /** The bundled typefaces — Inter, DM Mono, Syne. */
    val fonts: ZillitFonts
        @Composable @ReadOnlyComposable get() = LocalZillitFonts.current

    val shapes: ZillitShapes
        @Composable @ReadOnlyComposable get() = LocalZillitShapes.current
}

@Composable
private fun ZillitColors.animated(): ZillitColors {
    val spec = tween<Color>(durationMillis = THEME_TRANSITION_MILLIS)

    // Only the large areas are animated. Animating all 40 roles costs 40
    // animation nodes per recomposition for changes nobody perceives.
    val canvasAnimated by animateColorAsState(canvas, spec, label = "canvas")
    val surfaceAnimated by animateColorAsState(surface, spec, label = "surface")
    val sunkenAnimated by animateColorAsState(surfaceSunken, spec, label = "surfaceSunken")
    val textAnimated by animateColorAsState(textPrimary, spec, label = "textPrimary")
    val borderAnimated by animateColorAsState(border, spec, label = "border")
    val tabBarAnimated by animateColorAsState(tabBar, spec, label = "tabBar")
    val railAnimated by animateColorAsState(railBackground, spec, label = "railBackground")

    return copy(
        canvas = canvasAnimated,
        surface = surfaceAnimated,
        surfaceSunken = sunkenAnimated,
        textPrimary = textAnimated,
        border = borderAnimated,
        tabBar = tabBarAnimated,
        railBackground = railAnimated,
    )
}

/**
 * Derives Material 3's scheme from the Zillit roles, so a Material `Button` or
 * `DropdownMenu` used anywhere in the app is already on-brand in both themes.
 */
private fun ZillitColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = textOnAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentText,
        secondary = secondary,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textSecondary,
        error = danger,
        errorContainer = dangerSoft,
        outline = border,
        outlineVariant = divider,
        scrim = scrim,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = textOnAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentText,
        secondary = secondary,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textSecondary,
        error = danger,
        errorContainer = dangerSoft,
        outline = border,
        outlineVariant = divider,
        scrim = scrim,
    )
}

private const val THEME_TRANSITION_MILLIS = 220
