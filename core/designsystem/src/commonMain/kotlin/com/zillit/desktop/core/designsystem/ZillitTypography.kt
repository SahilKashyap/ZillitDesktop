package com.zillit.desktop.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale, tuned for desktop reading distance.
 *
 * Smaller than the Android scale on purpose: a phone is held ~35cm away, a
 * monitor ~65cm, and desktop users expect more information per screen. Scaling
 * the mobile sizes up is the single most common way a ported app ends up
 * feeling like a blown-up phone app.
 */
@Immutable
data class ZillitTypography(
    val displayLarge: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    val titleLarge: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val titleMedium: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    val titleSmall: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    val bodyLarge: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    val bodyMedium: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    val bodySmall: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    val label: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    val labelSmall: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    /**
     * A table's column headings.
     *
     * Uppercase, bold and widely tracked in DM Mono, as the web sets every
     * `TH` (`tracking-[0.12em]`) — the spacing is what makes a row of short
     * headings read as headings rather than as more data. Its own style
     * rather than [labelSmall], which also carries chips and footnotes that
     * must stay in the body face.
     */
    val columnHeader: TextStyle = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.12.em,
    ),
    /** Tabular data — Account Hub, budgets, reports. */
    val numeric: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    val button: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
) {
    /**
     * The same scale wearing the app's typefaces.
     *
     * Body styles take Inter; the two that carry figures and column headings
     * take DM Mono, which is what the web does and the reason a column of
     * amounts lines up.
     */
    internal fun with(fonts: ZillitFonts): ZillitTypography = ZillitTypography(
        displayLarge = displayLarge.copy(fontFamily = fonts.display),
        titleLarge = titleLarge.copy(fontFamily = fonts.sans),
        titleMedium = titleMedium.copy(fontFamily = fonts.sans),
        titleSmall = titleSmall.copy(fontFamily = fonts.sans),
        bodyLarge = bodyLarge.copy(fontFamily = fonts.sans),
        bodyMedium = bodyMedium.copy(fontFamily = fonts.sans),
        bodySmall = bodySmall.copy(fontFamily = fonts.sans),
        label = label.copy(fontFamily = fonts.sans),
        labelSmall = labelSmall.copy(fontFamily = fonts.sans),
        columnHeader = columnHeader.copy(fontFamily = fonts.mono),
        numeric = numeric.copy(fontFamily = fonts.mono),
        button = button.copy(fontFamily = fonts.sans),
    )
}

/** The scale with the bundled families applied. */
internal fun ZillitTypography(fonts: ZillitFonts): ZillitTypography = ZillitTypography().with(fonts)

val LocalZillitTypography = staticCompositionLocalOf { ZillitTypography() }
