package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The desktop scrollbar every scrollable wears: an up arrow, the draggable
 * thumb, a down arrow — in one slim rail on the scrollable's right edge.
 *
 * The wheel fix ([rememberWheelScroll]) serves the mouse's wheel; this serves
 * its pointer. The arrows step by [SCROLL_RAIL_STEP] per click — the classic
 * scrollbar-button nudge — and hold down to keep scrolling; the thumb drags
 * and its track pages, both from the platform scrollbar underneath.
 *
 * Place it inside a `Box` over the scrollable, aligned `CenterEnd`; it fills
 * the height it is given and paints nothing at all while the content fits,
 * so it can be added unconditionally.
 *
 * `expect` for the same reason as [rememberWheelScroll]: the platform
 * scrollbar it wraps is desktop-only API.
 */
@Composable
expect fun ZillitScrollRail(state: LazyListState, modifier: Modifier = Modifier, reverseLayout: Boolean = false)

/** [ZillitScrollRail] for a plain [androidx.compose.foundation.verticalScroll] column. */
@Composable
expect fun ZillitScrollRail(state: ScrollState, modifier: Modifier = Modifier)

/**
 * [ZillitScrollRail] laid on its side, for a pane that scrolls across —
 * a table too wide for its column. Place it along the bottom edge.
 */
@Composable
expect fun ZillitHorizontalScrollRail(state: ScrollState, modifier: Modifier = Modifier)

/** [ZillitScrollRail] for a lazy grid. */
@Composable
expect fun ZillitScrollRail(state: LazyGridState, modifier: Modifier = Modifier)

/**
 * One arrow click's travel. Three wheel lines, so the button and the wheel
 * speak the same dialect of "a little further".
 */
val SCROLL_RAIL_STEP: Dp = WHEEL_LINE * 3

/** The rail's footprint; content showing through beside it stays readable. */
val SCROLL_RAIL_WIDTH: Dp = 16.dp
