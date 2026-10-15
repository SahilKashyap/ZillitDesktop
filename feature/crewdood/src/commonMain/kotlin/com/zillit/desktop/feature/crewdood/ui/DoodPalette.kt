package com.zillit.desktop.feature.crewdood.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.crewdood.domain.DayCode
import com.zillit.desktop.feature.crewdood.domain.DoodBand

/**
 * The chart's own colours — the design's `--cd-*` tokens (`crewDood.css`),
 * light and dark, value for value.
 *
 * Surfaces, ink and lines come from the app theme so the page follows the
 * app's light/dark switch; what is here is only what the DOOD itself means:
 * the bar fills, the day-off grey and the holiday hatch.
 */
@Immutable
internal data class DoodPalette(
    /** SW and the left half of SWF. */
    val accent: Color,
    /** W's fill, and the Shoot band's tint. */
    val work: Color,
    val workInk: Color,
    /** WF, and the right half of SWF. */
    val finish: Color,
    val shootInk: Color,
    val shootTint: Color,
    /** A weekly day off's column. */
    val weekend: Color,
    /** The stripe a holiday's hatch draws over [weekend]. */
    val holiday: Color,
    val dim: Color,
    val muted: Color,
    /** The hovered column's wash. */
    val columnHighlight: Color,
    /** The tool name in the top bar, in the web's amber crumb. */
    val crumb: Color,
) {
    /** The bar's fill, or null where the column's own shading shows through (OFF / HOL). */
    fun fill(code: DayCode): Color? = when (code) {
        DayCode.Start -> accent
        DayCode.Finish -> finish
        DayCode.Work -> work
        // SWF is start and finish in one cell: the design paints it as a
        // 50/50 split, which the cell draws itself from these two.
        DayCode.Single -> accent
        DayCode.Off, DayCode.Hol, DayCode.Blank -> null
    }

    /** The bar's text colour. */
    fun ink(code: DayCode, onSurface: Color): Color = when (code) {
        DayCode.Start, DayCode.Finish, DayCode.Single -> Color.White
        DayCode.Work -> workInk
        DayCode.Hol -> muted
        DayCode.Off -> dim
        DayCode.Blank -> onSurface
    }

    fun bandFill(band: DoodBand?, surface: Color, track: Color): Color = when (band) {
        DoodBand.Prep -> track
        DoodBand.Shoot -> shootTint
        DoodBand.Post -> weekend
        null -> surface
    }

    fun bandInk(band: DoodBand?, primary: Color): Color = when (band) {
        DoodBand.Shoot -> shootInk
        DoodBand.Post -> muted
        else -> primary
    }
}

private val Light = DoodPalette(
    accent = Color(0xFFE8740C),
    work = Color(0xFFFDE8D2),
    workInk = Color(0xFF9C4A05),
    finish = Color(0xFF9C4A05),
    shootInk = Color(0xFF9C4A05),
    shootTint = Color(0xFFFDE8D2),
    weekend = Color(0xFFF4F4F2),
    holiday = Color(0xFFE2C9CE),
    dim = Color(0xFF74746F),
    muted = Color(0xFF4B4B48),
    columnHighlight = Color(0x12E8740C),
    crumb = Color(0xFFEA7A0E),
)

private val Dark = Light.copy(
    work = Color(0xFF4A2C12),
    workInk = Color(0xFFFDBA74),
    finish = Color(0xFFB85A0A),
    shootInk = Color(0xFFFDBA74),
    shootTint = Color(0xFF3A2713),
    weekend = Color(0xFF1A1D26),
    holiday = Color(0xFF3D2C33),
    dim = Color(0xFF6B7383),
    muted = Color(0xFF9AA3B2),
    crumb = Color(0xFFFBBF24),
)

@Composable
internal fun doodPalette(): DoodPalette = if (ZillitTheme.colors.isDark) Dark else Light
