package com.zillit.desktop.feature.costreport.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costreport.domain.CrColumnGroup
import com.zillit.desktop.feature.costreport.domain.CrSection

/**
 * The cost report's own colours — the web's `SEC_CLR`, the column-group
 * accents and the amber CTA. Kept here rather than in the theme because they
 * mean something only on this report: a band's colour is how an accountant
 * finds ABOVE THE LINE at a glance.
 */
internal object CrPalette {
    val ACTUALS = Color(0xFF0C7A6E)
    val COMMITS = Color(0xFF1D4ED8)
    val FORECAST = Color(0xFFB95B00)
    val CTA = Color(0xFFEA7A0E)
    val CTA_DARK = Color(0xFFFBBF24)
    val ANALYTICS = Color(0xFFE8861A)
    val LOCK_RED = Color(0xFFDC2626)

    /** The editable columns' wash, and their header label and pencil. */
    val EDITABLE_TINT = Color(0x332DD4BF)
    val EDITABLE_ACCENT = Color(0xF20D9488)
    val OVER_TINT = Color(0x1AF87171)

    private val SECTION = mapOf(
        "atl" to Color(0xFFC9A84C),
        "prod" to Color(0xFF60A5FA),
        "post" to Color(0xFF2DD4BF),
        "other" to Color(0xFFFB923C),
        "cont" to Color(0xFF7E8FA8),
        CrSection.UNCODED_SECTION_ID to Color(0xFFDC2626),
        // Violet, deliberately not the Non-Allocated red: these are expected
        // budget lines, and sharing the error colour was the confusion the
        // split was made to end.
        CrSection.CONTRACTUAL_SECTION_ID to Color(0xFF8B5CF6),
    )

    fun section(id: String): Color = SECTION[id.lowercase()] ?: CTA

    fun group(group: CrColumnGroup): Color? = when (group) {
        CrColumnGroup.Actuals -> ACTUALS
        CrColumnGroup.Commitments -> COMMITS
        CrColumnGroup.Forecast -> FORECAST
        CrColumnGroup.Budget, CrColumnGroup.Analysis -> null
    }

    /** Over budget: red, lighter in the dark theme so it still reads. */
    val over: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) Color(0xFFF87171) else Color(0xFFB91C1C)

    /** Under budget: teal-green, likewise. */
    val under: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) Color(0xFF34D399) else Color(0xFF0C7A6E)

    val cta: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) CTA_DARK else CTA

    /** Text on the CTA fill: white on the light theme's orange, near-black on the dark theme's yellow. */
    val ctaInk: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) Color(0xFF1A1408) else Color.White

    val grandTotalBackground: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) Color(0xFF0E2825) else Color(0xFFE8F5F1)

    val grandTotalInk: Color
        @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) Color(0xFF5EC9B1) else Color(0xFF0C7A6E)
}
