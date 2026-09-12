package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The web module's colour words — green, red, amber — as theme roles.
 *
 * The app's `StatusTone` names workflow feelings and draws "done" in teal,
 * which is right for a queue and wrong here: a reconciliation's matched line,
 * signed-off period and clear fraud check are all *green* on the web, and a
 * suggestion is *amber*. These keep the web's words while still reading the
 * theme, so dark mode stays correct.
 */
internal enum class BrTone { Green, Red, Amber, Blue, Teal, Purple, Gray, Gold, Accent }

@Composable
internal fun BrTone.fg(): Color {
    val c = ZillitTheme.colors
    return when (this) {
        BrTone.Green -> c.success
        BrTone.Red -> c.danger
        BrTone.Amber -> c.warning
        BrTone.Blue -> c.info
        BrTone.Teal -> c.teal
        BrTone.Purple -> c.violet
        BrTone.Gray -> c.textSecondary
        BrTone.Gold -> c.gold
        BrTone.Accent -> c.accentText
    }
}

@Composable
internal fun BrTone.bg(): Color {
    val c = ZillitTheme.colors
    return when (this) {
        BrTone.Green -> c.successSoft
        BrTone.Red -> c.dangerSoft
        BrTone.Amber -> c.warningSoft
        BrTone.Blue -> c.infoSoft
        BrTone.Teal -> c.tealSoft
        BrTone.Purple -> c.violetSoft
        BrTone.Gray -> c.surfaceHover
        BrTone.Gold -> c.goldSoft
        BrTone.Accent -> c.accentSoft
    }
}

/** The line a tinted surface is edged in: its own hue, faint. */
@Composable
internal fun BrTone.edge(): Color = fg().copy(alpha = EDGE_ALPHA)

private const val EDGE_ALPHA = 0.3f
