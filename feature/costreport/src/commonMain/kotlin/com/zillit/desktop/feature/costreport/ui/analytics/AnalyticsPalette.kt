package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme

/** One tone's four uses: a chart fill, text on a tint, the tint, and the tint's ring. */
@Immutable
internal data class ToneColors(val fg: Color, val ink: Color, val soft: Color, val ring: Color)

/**
 * The Analytics page's colours — the web's `.ah-analytics-design` variables
 * on the light theme (warm off-white, amber accent), and the same roles taken
 * from the app theme on the dark one, where the web has none.
 */
@Immutable
internal data class AnalyticsColors(
    val isDark: Boolean,
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    /** A module card that is not selected. */
    val surfaceIdle: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val line: Color,
    val line2: Color,
    /** Rails, progress bars and bar tracks. */
    val track: Color,
    val donutTrack: Color,
    val forecastShade: Color,
    val grid: Color,
    val rowHover: Color,
    val skeleton: Color,
    val skeletonHighlight: Color,
    val tooltip: Color,
    private val tones: Map<String, ToneColors>,
) {
    val amber: Color get() = tone("amber").fg
    val red: Color get() = tone("red").fg

    /** A tone id's colours; an unknown id reads as grey. */
    fun tone(id: String?): ToneColors = tones[id] ?: tones.getValue("grey")

    /** A tone id as a chart colour; an unknown id is amber, as the web's `toneHex` makes it. */
    fun toneHex(id: String?): Color = TONE_HEX[id] ?: TONE_HEX.getValue("amber")

    /**
     * A colour the server sent — `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()`,
     * a design variable (`var(--amber)`) or a bare tone id — or null.
     */
    fun css(value: String?): Color? {
        val text = value?.trim()?.lowercase() ?: return null
        return when {
            text.startsWith("#") -> hex(text.removePrefix("#"))
            text.startsWith("rgb") -> rgb(text)
            text.startsWith("var(") -> variable(text.removePrefix("var(").removeSuffix(")").trim().removePrefix("--"))
            else -> TONE_HEX[text]
        }
    }

    private fun variable(name: String): Color? = when (name) {
        "ink" -> ink
        "ink-2" -> ink2
        "ink-3" -> ink3
        "ink-4" -> ink4
        "line" -> line
        "line-2" -> line2
        "surface" -> surface
        else -> name.substringBefore('-').let { base ->
            val tone = tones[base] ?: return null
            when (name.substringAfter('-', "")) {
                "" -> tone.fg
                "ink" -> tone.ink
                "soft" -> tone.soft
                "ring" -> tone.ring
                else -> null
            }
        }
    }

    companion object {
        val TONE_HEX = mapOf(
            "amber" to Color(0xFFE8861A),
            "blue" to Color(0xFF2862E0),
            "purple" to Color(0xFF7A4CD6),
            "teal" to Color(0xFF14A394),
            "green" to Color(0xFF1AA463),
            "red" to Color(0xFFE23B3B),
            "grey" to Color(0xFF8A8D95),
            "indigo" to Color(0xFF4F46E5),
        )

        private val LIGHT_TONES = mapOf(
            "amber" to ToneColors(Color(0xFFE8861A), Color(0xFFE8861A), Color(0xFFFDF2E2), Color(0xFFF6D8A8)),
            "green" to ToneColors(Color(0xFF1AA463), Color(0xFF0C6A3F), Color(0xFFE6F7EE), Color(0xFFC2E6D3)),
            "teal" to ToneColors(Color(0xFF14A394), Color(0xFF14A394), Color(0xFFD9F4F0), Color(0xFFA8E3D9)),
            "red" to ToneColors(Color(0xFFE23B3B), Color(0xFFB22A2A), Color(0xFFFDE7E7), Color(0xFFF4CCCC)),
            "blue" to ToneColors(Color(0xFF2862E0), Color(0xFF2862E0), Color(0xFFE9EFFF), Color(0xFFCBD6F3)),
            "purple" to ToneColors(Color(0xFF7A4CD6), Color(0xFF7A4CD6), Color(0xFFF1EBFF), Color(0xFFDBCDF6)),
            "grey" to ToneColors(Color(0xFF8A8D95), Color(0xFF4A4D55), Color(0xFFF1EFE9), Color(0xFFE3E2DD)),
            "indigo" to ToneColors(Color(0xFF4F46E5), Color(0xFF4338CA), Color(0xFFEEF0FE), Color(0xFFC9CDF8)),
        )

        val Light = AnalyticsColors(
            isDark = false,
            bg = Color(0xFFF8F9FB),
            bg2 = Color(0xFFEEF0F4),
            surface = Color.White,
            surfaceIdle = Color.White.copy(alpha = 0.55f),
            ink = Color(0xFF0F1115),
            ink2 = Color(0xFF4A4D55),
            ink3 = Color(0xFF8A8D95),
            ink4 = Color(0xFFB8B7B1),
            line = Color(0xFFECECEA),
            line2 = Color(0xFFE3E2DD),
            track = Color(0xFFF4F3EF),
            donutTrack = Color(0xFFF1EFE9),
            forecastShade = Color(0xFFFAF8F4),
            grid = Color(0xFFECECEA),
            rowHover = Color(0xFFFAFAF6),
            skeleton = Color(0xFFECEBE6),
            skeletonHighlight = Color(0xFFF5F3EF),
            tooltip = Color.White,
            tones = LIGHT_TONES,
        )

        /** The web's roles over the app's dark theme: tints become translucent washes of the tone. */
        fun dark(theme: ZillitColors): AnalyticsColors = AnalyticsColors(
            isDark = true,
            bg = theme.canvas,
            bg2 = theme.surfaceSunken,
            surface = theme.surface,
            surfaceIdle = theme.surface.copy(alpha = 0.55f),
            ink = theme.textPrimary,
            ink2 = theme.textSecondary,
            ink3 = theme.textMuted,
            ink4 = theme.textDisabled,
            line = theme.border,
            line2 = theme.borderStrong,
            track = theme.surfaceSunken,
            donutTrack = theme.surfaceSunken,
            forecastShade = Color.White.copy(alpha = 0.03f),
            grid = theme.border,
            rowHover = theme.surfaceHover,
            skeleton = theme.surfaceSunken,
            skeletonHighlight = theme.surfaceHover,
            tooltip = theme.surfaceRaised,
            tones = LIGHT_TONES.mapValues { (_, tone) ->
                ToneColors(
                    fg = tone.fg,
                    ink = lerp(tone.fg, Color.White, DARK_INK_LIFT),
                    soft = tone.fg.copy(alpha = DARK_SOFT_ALPHA),
                    ring = tone.fg.copy(alpha = DARK_RING_ALPHA),
                )
            },
        )

        private const val DARK_INK_LIFT = 0.35f
        private const val DARK_SOFT_ALPHA = 0.14f
        private const val DARK_RING_ALPHA = 0.35f
        private const val SHORT_HEX = 3
        private const val LONG_HEX = 6
        private const val ALPHA_HEX = 8
        private const val HEX_RADIX = 16
        private const val CHANNEL_MAX = 255f

        private fun hex(digits: String): Color? {
            val full = when (digits.length) {
                SHORT_HEX -> digits.map { "$it$it" }.joinToString("") + "ff"
                LONG_HEX -> digits + "ff"
                ALPHA_HEX -> digits
                else -> return null
            }
            val value = full.toLongOrNull(HEX_RADIX) ?: return null
            val argb = (value and 0xFF) shl 24 or (value ushr 8)
            return Color(argb.toInt())
        }

        private fun rgb(text: String): Color? {
            val parts = text.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
            if (parts.size < 3) return null
            val channels = parts.take(3).map { it.toFloatOrNull() ?: return null }
            val alpha = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
            return Color(channels[0] / CHANNEL_MAX, channels[1] / CHANNEL_MAX, channels[2] / CHANNEL_MAX, alpha)
        }
    }
}

internal val LocalAnalyticsColors = staticCompositionLocalOf { AnalyticsColors.Light }

/** The page's palette, provided once at its root by [ProvideAnalyticsColors]. */
internal val analyticsColors: AnalyticsColors
    @Composable @ReadOnlyComposable get() = LocalAnalyticsColors.current

@Composable
internal fun ProvideAnalyticsColors(content: @Composable () -> Unit) {
    val theme = ZillitTheme.colors
    val colors = remember(theme) { if (theme.isDark) AnalyticsColors.dark(theme) else AnalyticsColors.Light }
    CompositionLocalProvider(LocalAnalyticsColors provides colors, content = content)
}

/** The page's two type faces: the UI face for words, the mono face for every figure. */
internal object AnalyticsType {

    @Composable
    @ReadOnlyComposable
    fun text(size: Float, weight: FontWeight = FontWeight.Normal, spacingEm: Float = 0f): TextStyle =
        ZillitTheme.typography.bodyMedium.copy(
            fontSize = size.sp,
            lineHeight = (size * LINE_HEIGHT).sp,
            fontWeight = weight,
            letterSpacing = spacingEm.em,
        )

    @Composable
    @ReadOnlyComposable
    fun mono(size: Float, weight: FontWeight = FontWeight.Normal, spacingEm: Float = 0f): TextStyle =
        ZillitTheme.typography.numeric.copy(
            fontSize = size.sp,
            lineHeight = (size * LINE_HEIGHT).sp,
            fontWeight = weight,
            letterSpacing = spacingEm.em,
        )

    private const val LINE_HEIGHT = 1.3f
}
