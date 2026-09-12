package com.zillit.desktop.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.zillit.desktop.core.designsystem.generated.resources.Res
import com.zillit.desktop.core.designsystem.generated.resources.dm_mono_400
import com.zillit.desktop.core.designsystem.generated.resources.dm_mono_500
import com.zillit.desktop.core.designsystem.generated.resources.inter_400
import com.zillit.desktop.core.designsystem.generated.resources.inter_500
import com.zillit.desktop.core.designsystem.generated.resources.inter_600
import com.zillit.desktop.core.designsystem.generated.resources.inter_700
import com.zillit.desktop.core.designsystem.generated.resources.syne_600
import com.zillit.desktop.core.designsystem.generated.resources.syne_700
import com.zillit.desktop.core.designsystem.generated.resources.syne_800
import org.jetbrains.compose.resources.Font

/**
 * The Account Hub's typefaces, which are now the whole app's.
 *
 * The web loads Inter, Syne and DM Mono from Google Fonts
 * (`src/accountHub/styles.css`); the desktop bundles the same families, so a
 * screen ported from the hub reads identically rather than falling back to
 * whatever sans the platform happens to ship. The files are the Google Fonts
 * releases under the SIL Open Font License.
 *
 * [mono] is not decoration: every figure, reference and table header on the
 * web is DM Mono, and proportional digits make a column of amounts impossible
 * to scan.
 */
@Immutable
data class ZillitFonts(
    /** Body and everything else — Inter. */
    val sans: FontFamily = FontFamily.Default,
    /** Figures, references, table headers — DM Mono. */
    val mono: FontFamily = FontFamily.Monospace,
    /** Display headings — Syne. */
    val display: FontFamily = FontFamily.Default,
)

/**
 * Loads the bundled families.
 *
 * Called once by [ZillitTheme]; the resource loader caches the bytes, so this
 * is not a per-frame cost.
 */
@Composable
internal fun rememberZillitFonts(): ZillitFonts = ZillitFonts(
    sans = FontFamily(
        Font(Res.font.inter_400, FontWeight.Normal),
        Font(Res.font.inter_500, FontWeight.Medium),
        Font(Res.font.inter_600, FontWeight.SemiBold),
        Font(Res.font.inter_700, FontWeight.Bold),
    ),
    mono = FontFamily(
        Font(Res.font.dm_mono_400, FontWeight.Normal),
        Font(Res.font.dm_mono_500, FontWeight.Medium),
    ),
    display = FontFamily(
        Font(Res.font.syne_600, FontWeight.SemiBold),
        Font(Res.font.syne_700, FontWeight.Bold),
        Font(Res.font.syne_800, FontWeight.ExtraBold),
    ),
)

val LocalZillitFonts = staticCompositionLocalOf { ZillitFonts() }
