package com.zillit.desktop.feature.callsheet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus

/** The five looks a status pill can take — `.csc-status--{tone}`. */
enum class StatusTone { Draft, Info, Warning, Success, Error }

/** Which glyph leads a status pill. */
enum class StatusGlyph { Ring, Eye, Check, Clock, Cross, None }

/** A resolved status pill: fill, ink, rim and glyph. */
@Immutable
data class StatusLook(val background: Color, val content: Color, val border: Color, val glyph: StatusGlyph)

/**
 * The call sheet's palette. The web resolves two token families on the tool's
 * root — the report tokens (`--rpt-*`, `reportTheme.css`) on dialogs, tables
 * and cards, and the hub tokens (`--ds-*` → `--hub-*`, `callsheetRework.css`)
 * on the rework surfaces: toolbar, tab bar, chips, the Drafts table, status
 * pills and the Permission banner. Both are kept, resolved once per theme, so
 * every surface reads the family the web gives it.
 */
@Immutable
data class SheetPalette(
    val isDark: Boolean,
    // Report family.
    val bgPrimary: Color,
    val surface: Color,
    val elevated: Color,
    val sunken: Color,
    val hover: Color,
    val textPrimary: Color,
    val textStrong: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
    val textMeta: Color,
    val accent: Color,
    val accentHover: Color,
    val accentLight: Color,
    val accentTint: Color,
    val border: Color,
    val borderStrong: Color,
    val borderFaint: Color,
    val headerBg: Color,
    val previewBg: Color,
    val editorBg: Color,
    val navy: Color,
    val navyHover: Color,
    val slateBar: Color,
    val tableHeaderBg: Color,
    val tableHeaderText: Color,
    val docInk: Color,
    val docRule: Color,
    val docSurface: Color,
    val dash: Color,
    val faint: Color,
    val tabBadge: Color,
    val red: Color,
    val redBg: Color,
    val redBorder: Color,
    val green: Color,
    val greenBg: Color,
    val blue: Color,
    val blueBg: Color,
    val amber: Color,
    val amberBg: Color,
    val chipOnText: Color,
    val tipsBg: Color,
    val tipsText: Color,
    val tipsIconBg: Color,
    val scrim: Color,
    val segmentTrack: Color,
    val gridLine: Color,
    val undoBg: Color,
    // Hub family (`--ds-*`).
    val dsBg: Color,
    val dsBgSecondary: Color,
    val dsBgTertiary: Color,
    val dsBgHover: Color,
    val dsCard: Color,
    val dsText: Color,
    val dsTextSecondary: Color,
    val dsTextMuted: Color,
    val dsTextPlaceholder: Color,
    val dsBorder: Color,
    val dsBorderLight: Color,
    val dsBorderStrong: Color,
    val dsPrimary: Color,
    val dsPrimaryHover: Color,
    val dsPrimarySoft: Color,
    val dsInfo: Color,
    val dsInfoBg: Color,
    val dsSuccess: Color,
    val dsSuccessBg: Color,
    val dsError: Color,
    val dsErrorBg: Color,
) {

    /** `StatusBadge`: the label's tone and glyph, per `STATUS_UI`. */
    fun status(status: CallSheetStatus): StatusLook {
        val (tone, glyph) = when (status) {
            CallSheetStatus.Draft, CallSheetStatus.Deleted -> StatusTone.Draft to StatusGlyph.Ring
            CallSheetStatus.PendingInternalApproval -> StatusTone.Info to StatusGlyph.Eye
            CallSheetStatus.InternalApproved, CallSheetStatus.ApprovedForPublish, CallSheetStatus.Published ->
                StatusTone.Success to StatusGlyph.Check
            CallSheetStatus.PendingApproval -> StatusTone.Warning to StatusGlyph.Clock
            CallSheetStatus.ApprovalRejected -> StatusTone.Error to StatusGlyph.Cross
            CallSheetStatus.Unknown -> StatusTone.Draft to StatusGlyph.None
        }
        return tone(tone, glyph)
    }

    @Suppress("MagicNumber") // The rework's translucent tone fills, as literals.
    fun tone(tone: StatusTone, glyph: StatusGlyph = StatusGlyph.None): StatusLook = when (tone) {
        StatusTone.Draft -> StatusLook(dsBgTertiary, dsTextSecondary, dsBorder, glyph)
        StatusTone.Info -> StatusLook(Color(0x1F3B82F6), dsInfo, Color(0x593B82F6), glyph)
        StatusTone.Warning -> StatusLook(Color(0x1FE8930C), Color(0xFFF59E0B), Color(0x59E8930C), glyph)
        StatusTone.Success -> StatusLook(Color(0x1F22C55E), dsSuccess, Color(0x5922C55E), glyph)
        StatusTone.Error -> StatusLook(Color(0x1FEF4444), dsError, Color(0x59EF4444), glyph)
    }

    companion object {
        val Light = SheetPalette(
            isDark = false,
            bgPrimary = Color(0xFFF4F5F7),
            surface = Color.White,
            elevated = Color(0xFFF9FAFB),
            sunken = Color(0xFFF2F4F7),
            hover = Color(0xFFF9FAFB),
            textPrimary = Color(0xFF1D2939),
            textStrong = Color(0xFF101828),
            textSecondary = Color(0xFF475467),
            textTertiary = Color(0xFF667085),
            textMuted = Color(0xFF98A2B3),
            textMeta = Color(0xFF6F727A),
            accent = Color(0xFFFC9404),
            accentHover = Color(0xFFE08503),
            accentLight = Color(0xFFFFF7ED),
            accentTint = Color(0x14FC9404),
            border = Color(0xFFEAECF0),
            borderStrong = Color(0xFFD0D5DD),
            borderFaint = Color(0xFFF2F4F7),
            headerBg = Color.White,
            previewBg = Color(0xFFF4F5F7),
            editorBg = Color(0xFFFAFAFA),
            navy = Color(0xFF1D2939),
            navyHover = Color(0xFF344054),
            slateBar = Color(0xFF475467),
            tableHeaderBg = Color(0xFF1E2A3A),
            tableHeaderText = Color.White,
            docInk = Color(0xFF111111),
            docRule = Color.Black,
            docSurface = Color.White,
            dash = Color(0xFFAAAAAA),
            faint = Color(0xFF999999),
            tabBadge = Color(0xFFFF4D4F),
            red = Color(0xFFB42318),
            redBg = Color(0xFFFEF3F2),
            redBorder = Color(0xFFFDA29B),
            green = Color(0xFF067647),
            greenBg = Color(0xFFECFDF3),
            blue = Color(0xFF175CD3),
            blueBg = Color(0xFFEFF8FF),
            amber = Color(0xFFB54708),
            amberBg = Color(0xFFFFFAEB),
            chipOnText = Color(0xFFB45309),
            tipsBg = Color(0xFFFFFBF5),
            tipsText = Color(0xFF475467),
            tipsIconBg = Color(0xFFFFF3E0),
            scrim = Color(0x66000000),
            segmentTrack = Color(0xFFF2F4F7),
            gridLine = Color(0xFFE4E7EC),
            undoBg = Color(0xFF1D2939),
            dsBg = Color(0xFFF0F2F5),
            dsBgSecondary = Color(0xFFF9FAFB),
            dsBgTertiary = Color(0xFFE4E6EB),
            dsBgHover = Color(0xFFF9FAFB),
            dsCard = Color.White,
            dsText = Color(0xFF111827),
            dsTextSecondary = Color(0xFF374151),
            dsTextMuted = Color(0xFF6B7280),
            dsTextPlaceholder = Color(0xFF9CA3AF),
            dsBorder = Color(0xFFE5E7EB),
            dsBorderLight = Color(0xFFF3F4F6),
            dsBorderStrong = Color(0xFFD1D5DB),
            dsPrimary = Color(0xFFF59E0B),
            dsPrimaryHover = Color(0xFFD97706),
            dsPrimarySoft = Color(0x14F99E0B),
            dsInfo = Color(0xFF2563EB),
            dsInfoBg = Color(0xFFDBEAFE),
            dsSuccess = Color(0xFF059669),
            dsSuccessBg = Color(0xFFD1FAE5),
            dsError = Color(0xFFDC2626),
            dsErrorBg = Color(0xFFFEE2E2),
        )

        val Dark = SheetPalette(
            isDark = true,
            bgPrimary = Color(0xFF1A1B26),
            surface = Color(0xFF24283B),
            elevated = Color(0xFF2E3147),
            sunken = Color(0xFF2A2E42),
            hover = Color(0x0AFFFFFF),
            textPrimary = Color(0xEBFFFFFF),
            textStrong = Color(0xF2FFFFFF),
            textSecondary = Color(0xB3FFFFFF),
            textTertiary = Color(0x80FFFFFF),
            textMuted = Color(0x59FFFFFF),
            textMeta = Color(0x8CFFFFFF),
            accent = Color(0xFFFC9404),
            accentHover = Color(0xFFFDB022),
            accentLight = Color(0x1AFC9404),
            accentTint = Color(0x1FFC9404),
            border = Color(0x14FFFFFF),
            borderStrong = Color(0x26FFFFFF),
            borderFaint = Color(0x0FFFFFFF),
            headerBg = Color(0xFF1E293B),
            previewBg = Color(0xFF111827),
            editorBg = Color(0xFF20202E),
            navy = Color(0xFF2E3147),
            navyHover = Color(0xFF3A3F5A),
            slateBar = Color(0xFF2E3147),
            tableHeaderBg = Color(0xFF1E2A3A),
            tableHeaderText = Color.White,
            docInk = Color(0xFF111111),
            docRule = Color.Black,
            docSurface = Color.White,
            dash = Color(0xFFAAAAAA),
            faint = Color(0xFF999999),
            tabBadge = Color(0xFFFF4D4F),
            red = Color(0xFFFDA29B),
            redBg = Color(0x24F04438),
            redBorder = Color(0x66FDA29B),
            green = Color(0xFF34D399),
            greenBg = Color(0x2E067647),
            blue = Color(0xFF60A5FA),
            blueBg = Color(0x2E175CD3),
            amber = Color(0xFFFDB022),
            amberBg = Color(0x2EF79009),
            chipOnText = Color(0xFFFDB022),
            tipsBg = Color(0x0AFC9404),
            tipsText = Color(0x99FFFFFF),
            tipsIconBg = Color(0x1FFC9404),
            scrim = Color(0x99000000),
            segmentTrack = Color(0x0FFFFFFF),
            gridLine = Color(0x1FFFFFFF),
            undoBg = Color(0xFF2E3147),
            dsBg = Color(0xFF111827),
            dsBgSecondary = Color(0xFF151E2F),
            dsBgTertiary = Color(0xFF0F172A),
            dsBgHover = Color(0x08FFFFFF),
            dsCard = Color(0xFF1E293B),
            dsText = Color(0xEBFFFFFF),
            dsTextSecondary = Color(0xB8FFFFFF),
            dsTextMuted = Color(0x80FFFFFF),
            dsTextPlaceholder = Color(0x4DFFFFFF),
            dsBorder = Color(0x14FFFFFF),
            dsBorderLight = Color(0x0AFFFFFF),
            dsBorderStrong = Color(0x24FFFFFF),
            dsPrimary = Color(0xFFF59E0B),
            dsPrimaryHover = Color(0xFFD97706),
            dsPrimarySoft = Color(0x1FF99E0B),
            dsInfo = Color(0xFF60A5FA),
            dsInfoBg = Color(0x2660A5FA),
            dsSuccess = Color(0xFF34D399),
            dsSuccessBg = Color(0x2634D399),
            dsError = Color(0xFFF87171),
            dsErrorBg = Color(0x26F87171),
        )
    }
}

private val LocalSheetPalette = staticCompositionLocalOf { SheetPalette.Light }

/**
 * Chooses the palette from the app theme and provides it below. The web's
 * hub toggle is the app-wide theme setting on the desktop, so the tool has no
 * switch of its own.
 */
@Composable
fun ProvideSheetPalette(dark: Boolean = ZillitTheme.colors.isDark, content: @Composable () -> Unit) {
    val palette = if (dark) SheetPalette.Dark else SheetPalette.Light
    CompositionLocalProvider(LocalSheetPalette provides palette, content = content)
}

/** Forces the light palette below — the printed page and the web's always-light editor surfaces. */
@Composable
fun ProvideLightSheetPalette(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSheetPalette provides SheetPalette.Light, content = content)
}

object SheetTheme {
    val colors: SheetPalette
        @Composable @ReadOnlyComposable get() = LocalSheetPalette.current
}
