package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The crew sheet's own tints — `CrewListCustom.css`'s `--cl-*` tokens that the
 * app theme has no word for: the unit and department bands, the column-header
 * ink, the green "Not on Zillit" note, the drawer's amber accents.
 *
 * Surfaces, text and lines come from the app theme, so the sheet follows the
 * app's light/dark switch; the web's own sun/moon toggle is not ported, as the
 * hub's is not — the desktop's theme lives in Settings.
 */
@Immutable
internal data class CrewPalette(
    val unitBand: Color,
    val unitInk: Color,
    val departmentBand: Color,
    val departmentInk: Color,
    /** 14:1 on the header strip — the web darkened it for legibility. */
    val columnHeaderInk: Color,
    val columnHeaderStrip: Color,
    val externalInk: Color,
    val error: Color,
    val lockedField: Color,
    val consentBorder: Color,
    val consentFill: Color,
    val consentInk: Color,
    /** The drawer's icon tiles and the grip chip. */
    val accentTile: Color,
    val grip: Color,
    val skeleton: Color,
    val paper: Color,
)

private val Light = CrewPalette(
    unitBand = Color(0xFF666666),
    unitInk = Color.White,
    departmentBand = Color(0xFF999999),
    departmentInk = Color.White,
    columnHeaderInk = Color(0xFF1F2937),
    columnHeaderStrip = Color(0xFFF9FAFB),
    externalInk = Color(0xFF059669),
    error = Color(0xFFE74C3C),
    lockedField = Color(0xFFF3F4F6),
    consentBorder = Color(0xFFFFE2C2),
    consentFill = Color(0xFFFFF8F0),
    consentInk = Color(0xFF475467),
    accentTile = Color(0xFFFDF0DC),
    grip = Color(0xFFF99300),
    skeleton = Color(0xFFECEEF1),
    paper = Color.White,
)

private val Dark = Light.copy(
    unitBand = Color(0xFF334155),
    departmentBand = Color(0xFF475569),
    columnHeaderInk = Color(0xEBFFFFFF),
    columnHeaderStrip = Color(0xFF172033),
    externalInk = Color(0xFF34D399),
    error = Color(0xFFF87171),
    lockedField = Color(0xFF0B1120),
    consentBorder = Color(0x40F99300),
    consentFill = Color(0x14F99300),
    consentInk = Color(0xB8FFFFFF),
    accentTile = Color(0x24F99300),
    skeleton = Color(0x14FFFFFF),
)

@Composable
internal fun crewPalette(): CrewPalette = if (ZillitTheme.colors.isDark) Dark else Light
