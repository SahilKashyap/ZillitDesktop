package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The deal memo pages' colours — the web module's tokens
 * (`DealMemoModule.jsx`, `DMDealsPage.jsx`, `DMConfigPage.jsx`), light and dark.
 *
 * The app theme has no names for most of these: the web's warm hairlines, its
 * two ambers (the CTA `#e8861a` and the brand `#fc9404`), the status pill
 * inks. Kept in one place so a page never invents its own shade.
 */
@Immutable
data class DmPalette(
    val page: Color,
    val card: Color,
    val cardBorder: Color,
    val control: Color,
    val controlBorder: Color,
    val controlHoverBorder: Color,
    val controlHoverBg: Color,
    val tableHeader: Color,
    val rowHover: Color,
    val groupHeader: Color,
    val soft: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val placeholder: Color,
    val accent: Color,
    val accentHover: Color,
    val brand: Color,
    val brandText: Color,
    val amberSoft: Color,
    val amberRing: Color,
    val amberInk: Color,
    val noticeOrange: Color,
    val noticeOrangeHover: Color,
    val green: Color,
    val greenHover: Color,
    val greenInk: Color,
    val greenSoft: Color,
    val greenRing: Color,
    val teal: Color,
    val red: Color,
    val redHover: Color,
    val redInk: Color,
    val redSoft: Color,
    val blueInk: Color,
    val blueSoft: Color,
    val blueRing: Color,
    val purpleInk: Color,
    val purpleSoft: Color,
    val purpleRing: Color,
    val track: Color,
    val separatorDot: Color,
    val unread: Color,
    val skeleton: Color,
    val modalFooter: Color,
    val hairline: Color,
) {
    companion object {
        val Light = DmPalette(
            page = Color(0xFFF8F9FB),
            card = Color.White,
            cardBorder = Color(0xFFECECEA),
            control = Color.White,
            controlBorder = Color(0xFFECECEA),
            controlHoverBorder = Color(0xFFD6D4CC),
            controlHoverBg = Color(0xFFFAFAF6),
            tableHeader = Color(0xFFFAFAF6),
            rowHover = Color(0xFFFAFAF6),
            groupHeader = Color(0xFFFAF7F1),
            soft = Color(0xFFFAFAF6),
            ink = Color(0xFF0F1115),
            ink2 = Color(0xFF4A4D55),
            ink3 = Color(0xFF8A8D95),
            placeholder = Color(0xFFB8B7B1),
            accent = Color(0xFFE8861A),
            accentHover = Color(0xFFD6770F),
            brand = Color(0xFFFC9404),
            brandText = Color(0xFFE08600),
            amberSoft = Color(0xFFFDF2E2),
            amberRing = Color(0xFFF6D8A8),
            amberInk = Color(0xFF7A4F00),
            noticeOrange = Color(0xFFEA7A0E),
            noticeOrangeHover = Color(0xFFD46D09),
            green = Color(0xFF1AA463),
            greenHover = Color(0xFF149255),
            greenInk = Color(0xFF0C6A3F),
            greenSoft = Color(0xFFE6F7EE),
            greenRing = Color(0xFFC2E6D3),
            teal = Color(0xFF14A394),
            red = Color(0xFFD64545),
            redHover = Color(0xFFC23B3B),
            redInk = Color(0xFFB22A2A),
            redSoft = Color(0xFFFDE7E7),
            blueInk = Color(0xFF2862E0),
            blueSoft = Color(0xFFE9EFFF),
            blueRing = Color(0xFFCBD6F3),
            purpleInk = Color(0xFF7A4CD6),
            purpleSoft = Color(0xFFF1EBFF),
            purpleRing = Color(0xFFDBCDF6),
            track = Color(0xFFF1EFE9),
            separatorDot = Color(0xFFC9C8C2),
            unread = Color(0xFFEF4444),
            skeleton = Color(0xFFE5E7EB),
            modalFooter = Color(0xFFF9FAFB),
            hairline = Color(0xFFE2E4E9),
        )

        val Dark = DmPalette(
            page = Color(0xFF07090C),
            card = Color(0xFF0D1117),
            cardBorder = Color(0xFF1E2535),
            control = Color(0xFF14171C),
            controlBorder = Color.White.copy(alpha = 0.08f),
            controlHoverBorder = Color.White.copy(alpha = 0.16f),
            controlHoverBg = Color.White.copy(alpha = 0.04f),
            tableHeader = Color(0xFF131820),
            rowHover = Color(0xFF1A2030),
            groupHeader = Color(0xFF12161F),
            soft = Color.White.copy(alpha = 0.04f),
            ink = Color(0xFFE8EAF0),
            ink2 = Color(0xFF8B95A8),
            ink3 = Color(0xFF5A6478),
            placeholder = Color.White.copy(alpha = 0.30f),
            accent = Color(0xFFFBBF24),
            accentHover = Color(0xFFF59E0B),
            brand = Color(0xFFFC9404),
            brandText = Color(0xFFFBBF24),
            amberSoft = Color(0xFFF59E0B).copy(alpha = 0.12f),
            amberRing = Color(0xFFF59E0B).copy(alpha = 0.30f),
            amberInk = Color(0xFFFBBF24),
            noticeOrange = Color(0xFFF59E2C),
            noticeOrangeHover = Color(0xFFF3B365),
            green = Color(0xFF1AA463),
            greenHover = Color(0xFF149255),
            greenInk = Color(0xFF86EFAC),
            greenSoft = Color(0xFF22C55E).copy(alpha = 0.14f),
            greenRing = Color(0xFF22C55E).copy(alpha = 0.30f),
            teal = Color(0xFF2DD4BF),
            red = Color(0xFFD64545),
            redHover = Color(0xFFC23B3B),
            redInk = Color(0xFFFCA5A5),
            redSoft = Color(0xFFF87171).copy(alpha = 0.10f),
            blueInk = Color(0xFF60A5FA),
            blueSoft = Color(0xFF3B82F6).copy(alpha = 0.12f),
            blueRing = Color(0xFF3B82F6).copy(alpha = 0.30f),
            purpleInk = Color(0xFFC084FC),
            purpleSoft = Color(0xFFA855F7).copy(alpha = 0.12f),
            purpleRing = Color(0xFFA855F7).copy(alpha = 0.30f),
            track = Color.White.copy(alpha = 0.06f),
            separatorDot = Color.White.copy(alpha = 0.25f),
            unread = Color(0xFFEF4444),
            skeleton = Color.White.copy(alpha = 0.08f),
            modalFooter = Color(0xFF1E2128),
            hairline = Color.White.copy(alpha = 0.08f),
        )
    }
}

/** The palette for the theme on screen. */
val dm: DmPalette
    @Composable @ReadOnlyComposable get() = if (ZillitTheme.colors.isDark) DmPalette.Dark else DmPalette.Light

/** The page's type, built on the bundled families. */
object DmType {

    /** Inter at an exact web size. */
    @Composable
    @ReadOnlyComposable
    fun sans(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        tracking: TextUnit = TextUnit.Unspecified,
    ): TextStyle =
        TextStyle(fontFamily = ZillitTheme.fonts.sans, fontSize = size, fontWeight = weight, letterSpacing = tracking)

    /** DM Mono — references, figures, codes, table headings. */
    @Composable
    @ReadOnlyComposable
    fun mono(
        size: TextUnit,
        weight: FontWeight = FontWeight.Medium,
        tracking: TextUnit = TextUnit.Unspecified,
    ): TextStyle =
        TextStyle(fontFamily = ZillitTheme.fonts.mono, fontSize = size, fontWeight = weight, letterSpacing = tracking)

    /** Syne — the module title and modal titles. */
    @Composable
    @ReadOnlyComposable
    fun display(
        size: TextUnit,
        weight: FontWeight = FontWeight.Bold,
        tracking: TextUnit = TextUnit.Unspecified,
    ): TextStyle =
        TextStyle(
            fontFamily = ZillitTheme.fonts.display,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = tracking,
        )

    /** The mono uppercase eyebrow: `QUICK FILTERS:`, table headings, stat labels. */
    @Composable
    @ReadOnlyComposable
    fun eyebrow(size: TextUnit = 10.5.sp, tracking: TextUnit = 0.1.em): TextStyle =
        mono(size, FontWeight.Medium, tracking)
}
