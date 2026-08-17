package com.zillit.desktop.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4pt spacing scale.
 *
 * The Android app sizes through `sdp` (screen-density scaling), which desktop
 * does not need — density is fixed. A named scale also stops the drift you get
 * when every screen picks its own padding.
 */
@Immutable
data class ZillitSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

val LocalZillitSpacing = staticCompositionLocalOf { ZillitSpacing() }

/** Corner radii and the fixed chrome dimensions the workspace depends on. */
@Immutable
data class ZillitShapes(
    val small: RoundedCornerShape = RoundedCornerShape(4.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(6.dp),
    val large: RoundedCornerShape = RoundedCornerShape(10.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
    /** Tabs are square-bottomed so they read as attached to the workspace. */
    val tab: RoundedCornerShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
)

val LocalZillitShapes = staticCompositionLocalOf { ZillitShapes() }

/**
 * Fixed chrome sizes. Mirrors the web app's `TAB_STRIP_H = 46` so the two
 * clients look like the same product side by side.
 */
object ZillitDimens {
    val topBarHeight: Dp = 48.dp
    val tabStripHeight: Dp = 46.dp
    val statusBarHeight: Dp = 26.dp
    val railWidth: Dp = 60.dp
    val railWidthExpanded: Dp = 200.dp
    val tabMinWidth: Dp = 120.dp
    val tabMaxWidth: Dp = 220.dp
    val controlHeight: Dp = 32.dp
    val controlHeightSmall: Dp = 26.dp
    val iconSmall: Dp = 14.dp
    val icon: Dp = 18.dp
    val iconLarge: Dp = 22.dp
    val windowMinWidth: Dp = 420.dp
    val windowMinHeight: Dp = 320.dp
}
