package com.zillit.desktop.feature.productionreport.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.productionreport.domain.ReportStatus

/**
 * The report module's palette — the web's `--rpt-*` tokens and the dark
 * remaps of its hard-coded colours (`reportTheme.css`, `productionReport.css`),
 * resolved once per theme so every surface reads semantic roles.
 */
@Immutable
data class ReportPalette(
    val isDark: Boolean,
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
    val chatBg: Color,
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
    val chipOnText: Color,
    val tipsBg: Color,
    val tipsText: Color,
    val tipsIconBg: Color,
    val scrim: Color,
    val segmentTrack: Color,
    val gridLine: Color,
    val undoBg: Color,
) {
    /** Light / dark pill colours per status — `STATUS_UI` and its remaps. */
    @Suppress("MagicNumber") // The web's STATUS_UI colours, as literals.
    fun status(status: ReportStatus): Pair<Color, Color> {
        val (light, dark) = when (status) {
            ReportStatus.PendingInternalApproval ->
                (Color(0xFFFFF4E5) to Color(0xFFB54708)) to (Color(0x2EB54708) to Color(0xFFFDB022))
            ReportStatus.InternalApproved, ReportStatus.ApprovedForPublish ->
                (Color(0xFFECFDF3) to Color(0xFF067647)) to (Color(0x2E067647) to Color(0xFF34D399))
            ReportStatus.PendingApproval ->
                (Color(0xFFEFF8FF) to Color(0xFF175CD3)) to (Color(0x2E175CD3) to Color(0xFF60A5FA))
            ReportStatus.ApprovalRejected ->
                (Color(0xFFFEF3F2) to Color(0xFFB42318)) to (Color(0x2EB42318) to Color(0xFFFDA29B))
            ReportStatus.Published ->
                (Color(0xFFF0F9FF) to Color(0xFF026AA2)) to (Color(0x2E026AA2) to Color(0xFF7DD3FC))
            ReportStatus.Deleted ->
                (Color(0xFFF2F4F7) to Color(0xFF475467)) to (Color(0x14FFFFFF) to Color(0xBFFFFFFF))
            ReportStatus.Draft, ReportStatus.Unknown ->
                (Color(0xFFF2F4F7) to Color(0xFF344054)) to (Color(0x14FFFFFF) to Color(0xBFFFFFFF))
        }
        return if (isDark) dark else light
    }

    companion object {
        val Light = ReportPalette(
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
            chatBg = Color(0xFFEEF1F5),
            navy = Color(0xFF1D2939),
            navyHover = Color(0xFF344054),
            slateBar = Color(0xFF475467),
            tableHeaderBg = Color(0xFF1D2939),
            tableHeaderText = Color.White,
            docInk = Color(0xFF111111),
            docRule = Color.Black,
            docSurface = Color.White,
            dash = Color(0xFFAAAAAA),
            faint = Color(0xFF999999),
            tabBadge = Color(0xFFD92D20),
            red = Color(0xFFB42318),
            redBg = Color(0xFFFEF3F2),
            redBorder = Color(0xFFFDA29B),
            green = Color(0xFF067647),
            greenBg = Color(0xFFECFDF3),
            blue = Color(0xFF175CD3),
            blueBg = Color(0xFFEFF8FF),
            chipOnText = Color(0xFFB45309),
            tipsBg = Color(0xFFFFFBF5),
            tipsText = Color(0xFF475467),
            tipsIconBg = Color(0xFFFFF3E0),
            scrim = Color(0x66000000),
            segmentTrack = Color(0xFFF2F4F7),
            gridLine = Color(0xFFE4E7EC),
            undoBg = Color(0xFF1D2939),
        )

        val Dark = ReportPalette(
            isDark = true,
            bgPrimary = Color(0xFF111827),
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
            headerBg = Color(0xFF111827),
            previewBg = Color(0xFF111827),
            editorBg = Color(0xFF20202E),
            chatBg = Color(0xFF111827),
            navy = Color(0xFF2E3147),
            navyHover = Color(0xFF3A3F5A),
            slateBar = Color(0xFF2E3147),
            tableHeaderBg = Color(0x1AFFFFFF),
            tableHeaderText = Color(0xEBFFFFFF),
            docInk = Color(0xE6FFFFFF),
            docRule = Color(0x26FFFFFF),
            docSurface = Color(0xFF24283B),
            dash = Color(0x40FFFFFF),
            faint = Color(0x4DFFFFFF),
            tabBadge = Color(0xFFFC9404),
            red = Color(0xFFFDA29B),
            redBg = Color(0x24B42318),
            redBorder = Color(0x66FDA29B),
            green = Color(0xFF34D399),
            greenBg = Color(0x2E067647),
            blue = Color(0xFF60A5FA),
            blueBg = Color(0x2E175CD3),
            chipOnText = Color(0xFFFDB022),
            tipsBg = Color(0x0AFC9404),
            tipsText = Color(0x99FFFFFF),
            tipsIconBg = Color(0x1FFC9404),
            scrim = Color(0x99000000),
            segmentTrack = Color(0x0FFFFFFF),
            gridLine = Color(0x1FFFFFFF),
            undoBg = Color(0xFF2E3147),
        )
    }
}

private val LocalReportPalette = staticCompositionLocalOf { ReportPalette.Light }

/** Chooses the palette from the app theme and provides it below. */
@Composable
fun ProvideReportPalette(content: @Composable () -> Unit) {
    val palette = if (ZillitTheme.colors.isDark) ReportPalette.Dark else ReportPalette.Light
    CompositionLocalProvider(LocalReportPalette provides palette, content = content)
}

object ReportTheme {
    val colors: ReportPalette
        @Composable @ReadOnlyComposable get() = LocalReportPalette.current
}
