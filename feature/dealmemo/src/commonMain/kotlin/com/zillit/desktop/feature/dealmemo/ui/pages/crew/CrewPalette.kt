package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme

/** "Complete your details" tokens (`CrewDetailsEditorPanel.jsx`, `wizStyles.js`), light and dark. */
@Immutable
internal data class CrewPalette(
    val page: Color,
    val card: Color,
    val cardBorder: Color,
    val headerRule: Color,
    val title: Color,
    val ink: Color,
    val label: Color,
    val wizLabel: Color,
    val muted: Color,
    val body: Color,
    val placeholder: Color,
    val inputBg: Color,
    val inputBorder: Color,
    val focusBorder: Color,
    val pickerBorder: Color,
    val menu: Color,
    val menuDivider: Color,
    val hover: Color,
    val selected: Color,
    val amber: Color,
    val brand: Color,
    val teal: Color,
    val error: Color,
    val warn: Color,
    val tile: Color,
    val fileRow: Color,
    val fileBorder: Color,
    val bar: Color,
    val footer: Color,
    val track: Color,
    val railLine: Color,
    val railHover: Color,
    val railActive: Color,
    val redBorder: Color,
    val redTop: Color,
    val blueBorder: Color,
    val blueTop: Color,
    val tealBorder: Color,
    val tealTop: Color,
    val routeActiveTop: Color,
    val cardSelected: Color,
) {
    companion object {
        val Light = CrewPalette(
            page = Color(0xFFF8F9FB),
            card = Color.White,
            cardBorder = Color(0xFFECECEA),
            headerRule = Color(0xFFE5E7EB),
            title = Color(0xFF111827),
            ink = Color(0xFF0F1115),
            label = Color(0xFF4A4D55),
            wizLabel = Color(0xFF8A8D95),
            muted = Color(0xFF8A8D95),
            body = Color(0xFF6B7280),
            placeholder = Color(0xFFB8B7B1),
            inputBg = Color.White,
            inputBorder = Color(0xFFECECEA),
            focusBorder = Color(0xFFF6D8A8),
            pickerBorder = Color(0xFFE3E2DD),
            menu = Color.White,
            menuDivider = Color(0xFFECECEA),
            hover = Color(0xFFFAF9F6),
            selected = Color(0xFFFDF2E2),
            amber = Color(0xFFE8861A),
            brand = Color(0xFFFC9404),
            teal = Color(0xFF14A394),
            error = Color(0xFFDC2626),
            warn = Color(0xFFD97706),
            tile = Color(0xFFF3F4F6),
            fileRow = Color(0xFFF9FAFB),
            fileBorder = Color(0xFFE5E7EB),
            bar = Color(0xFFF8F9FB).copy(alpha = 0.85f),
            footer = Color.White.copy(alpha = 0.95f),
            track = Color(0xFFECEBE5),
            railLine = Color(0xFFE3E2DD),
            railHover = Color(0xFFF4F3EF),
            railActive = Color(0xFFFDF2E2),
            redBorder = Color(0xFFF3C2C2),
            redTop = Color(0xFFFFF5F5),
            blueBorder = Color(0xFFCBD6F3),
            blueTop = Color(0xFFF3F6FF),
            tealBorder = Color(0xFFA8E3D9),
            tealTop = Color(0xFFEFFAF7),
            routeActiveTop = Color(0xFFFFFAF1),
            cardSelected = Color(0xFFFFFAF1),
        )

        val Dark = CrewPalette(
            page = Color(0xFF14161B),
            card = Color(0xFF141720),
            cardBorder = Color.White.copy(alpha = 0.06f),
            headerRule = Color.White.copy(alpha = 0.06f),
            title = Color(0xFFE8EAF0),
            ink = Color(0xFFE8EAF0),
            label = Color(0xFF93A0B5),
            wizLabel = Color(0xFF5A6478),
            muted = Color(0xFF8B95A8),
            body = Color(0xFF8B95A8),
            placeholder = Color(0xFF5A6478),
            inputBg = Color(0xFF1C2030),
            inputBorder = Color.White.copy(alpha = 0.08f),
            focusBorder = Color(0xFFE8B84B).copy(alpha = 0.5f),
            pickerBorder = Color.White.copy(alpha = 0.10f),
            menu = Color(0xFF1A1D23),
            menuDivider = Color.White.copy(alpha = 0.06f),
            hover = Color(0xFF22262E),
            selected = Color(0xFFE8861A).copy(alpha = 0.12f),
            amber = Color(0xFFE8B84B),
            brand = Color(0xFFFC9404),
            teal = Color(0xFF2DD4BF),
            error = Color(0xFFF87171),
            warn = Color(0xFFFBBF24),
            tile = Color.White.copy(alpha = 0.06f),
            fileRow = Color.White.copy(alpha = 0.03f),
            fileBorder = Color.White.copy(alpha = 0.08f),
            bar = Color(0xFF14161B).copy(alpha = 0.85f),
            footer = Color(0xFF1A1D24).copy(alpha = 0.95f),
            track = Color.White.copy(alpha = 0.08f),
            railLine = Color.White.copy(alpha = 0.10f),
            railHover = Color.White.copy(alpha = 0.04f),
            // Forced inline on the web, so it stays the light wash in dark too.
            railActive = Color(0xFFFDF2E2),
            redBorder = Color(0xFFF3C2C2).copy(alpha = 0.16f),
            redTop = Color(0xFF241616),
            blueBorder = Color(0xFFCBD6F3).copy(alpha = 0.16f),
            blueTop = Color(0xFF161B29),
            tealBorder = Color(0xFFA8E3D9).copy(alpha = 0.16f),
            tealTop = Color(0xFF13201F),
            routeActiveTop = Color(0xFF1F1A13),
            cardSelected = Color(0xFFE8861A).copy(alpha = 0.10f),
        )
    }
}

internal val cp: CrewPalette
    @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) CrewPalette.Dark else CrewPalette.Light

/** A section card's accent: its border and where its gradient starts. */
internal enum class CardAccent { None, Red, Blue, Teal }

/** A section card's tag chip: its ink in each theme, and the hue its 15% wash is made from (none for dim). */
@Suppress("MagicNumber") // The web's swatches, verbatim.
internal enum class TagTone(val light: Color, val dark: Color, val wash: Color?) {
    Dim(Color(0xFF6B7280), Color(0xFF8B95A8), null),
    Red(Color(0xFFDC2626), Color(0xFFF87171), Color(0xFFEF4444)),
    Blue(Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFF3B82F6)),
    Teal(Color(0xFF0D9488), Color(0xFF2DD4BF), Color(0xFF14B8A6)),
}
