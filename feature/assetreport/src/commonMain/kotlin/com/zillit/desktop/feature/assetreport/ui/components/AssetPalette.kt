package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType

/**
 * The register's own tints — the web design's `--keep`, `--sell`, `--purchase`,
 * `--rental` and `--consume`, light and dark.
 *
 * Everything else (surfaces, ink, lines, the accent) comes from the app theme,
 * so the page follows the app's light/dark switch. The web page carries its own
 * sun/moon toggle; the desktop's theme lives in Settings, as the hub's does.
 */
@Immutable
internal data class AssetTint(val ink: Color, val soft: Color)

@Immutable
internal data class AssetPalette(
    val keep: AssetTint,
    val sell: AssetTint,
    val purchase: AssetTint,
    val rental: AssetTint,
    val consume: AssetTint,
    /** The segmented control's track. */
    val track: Color,
    /** The export badges' two gradients. */
    val pdfFrom: Color,
    val pdfTo: Color,
    val sheetFrom: Color,
    val sheetTo: Color,
) {
    fun category(category: AssetCategory): AssetTint? = when (category) {
        AssetCategory.Keep -> keep
        AssetCategory.Sell -> sell
        AssetCategory.None -> null
    }

    fun expense(type: ExpenditureType): AssetTint? = when (type) {
        ExpenditureType.Purchase -> purchase
        ExpenditureType.Rent -> rental
        ExpenditureType.Consumption -> consume
        ExpenditureType.Unknown -> null
    }
}

private val Light = AssetPalette(
    keep = AssetTint(Color(0xFF15804A), Color(0xFFE7F6EC)),
    sell = AssetTint(Color(0xFFB4610A), Color(0xFFFDF1E0)),
    purchase = AssetTint(Color(0xFF475569), Color(0xFFEEF1F5)),
    rental = AssetTint(Color(0xFF5B53C9), Color(0xFFEEECFB)),
    consume = AssetTint(Color(0xFF0C7A6E), Color(0xFFE2F2F0)),
    track = Color(0xFFECEEF2),
    pdfFrom = Color(0xFFFF7A59),
    pdfTo = Color(0xFFE23B3B),
    sheetFrom = Color(0xFF34C97A),
    sheetTo = Color(0xFF138A52),
)

private val Dark = Light.copy(
    keep = AssetTint(Color(0xFF4ADE80), Color(0x244ADE80)),
    sell = AssetTint(Color(0xFFFBBF24), Color(0x24FBBF24)),
    purchase = AssetTint(Color(0x9EFFFFFF), Color(0x14FFFFFF)),
    rental = AssetTint(Color(0xFFA5B4FC), Color(0x24A5B4FC)),
    consume = AssetTint(Color(0xFF2DD4BF), Color(0x242DD4BF)),
    track = Color(0x0FFFFFFF),
)

@Composable
internal fun assetPalette(): AssetPalette = if (ZillitTheme.colors.isDark) Dark else Light
