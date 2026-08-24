package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** See the `expect` declaration for what the rail is and where it sits. */
@Composable
actual fun ZillitScrollRail(state: LazyListState, modifier: Modifier, reverseLayout: Boolean) {
    Rail(state, rememberScrollbarAdapter(state), reverseLayout, modifier)
}

@Composable
actual fun ZillitScrollRail(state: ScrollState, modifier: Modifier) {
    Rail(state, rememberScrollbarAdapter(state), reverseLayout = false, modifier = modifier)
}

@Composable
actual fun ZillitScrollRail(state: LazyGridState, modifier: Modifier) {
    Rail(state, rememberScrollbarAdapter(state), reverseLayout = false, modifier = modifier)
}

/**
 * The rail itself. [state] drives the arrows, [adapter] the thumb — the same
 * scroll position through two lenses, which is why both arrive.
 *
 * In a `reverseLayout` list the content's start renders at the bottom, so the
 * *visual* up arrow must scroll towards the end — the sign flip below — and
 * the platform scrollbar is told the same so the thumb tracks the eye.
 */
@Composable
private fun Rail(
    state: ScrollableState,
    adapter: ScrollbarAdapter,
    reverseLayout: Boolean,
    modifier: Modifier,
) {
    // Nothing to scroll, nothing to paint — the rail vanishes entirely
    // rather than standing as a dead strip beside short content.
    if (!state.canScrollForward && !state.canScrollBackward) return

    val stepPx = with(LocalDensity.current) { SCROLL_RAIL_STEP.toPx() }
    val up = if (reverseLayout) stepPx else -stepPx

    Column(
        modifier = modifier.width(SCROLL_RAIL_WIDTH).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailArrow(
            icon = ZillitIcons.ChevronUp,
            description = "Scroll up",
            enabled = if (reverseLayout) state.canScrollForward else state.canScrollBackward,
            state = state,
            travel = up,
        )
        VerticalScrollbar(
            adapter = adapter,
            modifier = Modifier.weight(1f),
            reverseLayout = reverseLayout,
            style = railStyle(),
        )
        RailArrow(
            icon = ZillitIcons.ChevronDown,
            description = "Scroll down",
            enabled = if (reverseLayout) state.canScrollBackward else state.canScrollForward,
            state = state,
            travel = -up,
        )
    }
}

/**
 * One end's arrow: a click nudges, holding repeats — the scrollbar button
 * every desktop toolkit ships, at this app's size.
 */
@Composable
private fun RailArrow(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    state: ScrollableState,
    travel: Float,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scope = rememberCoroutineScope()

    // Holding repeats after a beat; the single-click step lives in `onClick`
    // below. Each step is launched into the composition's scope, not run in
    // this effect: the effect dies the instant the button is released, and a
    // step animated inside it was cancelled after a frame — a click that
    // scrolled two pixels and looked like a click that did nothing.
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        delay(ARROW_HOLD_DELAY_MS)
        while (pressed && enabled) {
            scope.launch { state.animateScrollBy(travel) }
            delay(ARROW_REPEAT_MS)
        }
    }

    Box(
        modifier = Modifier
            .height(ARROW_HEIGHT)
            .width(SCROLL_RAIL_WIDTH)
            .background(if (hovered && enabled) ZillitTheme.colors.surfaceHover else ZillitTheme.colors.surface)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                scope.launch { state.animateScrollBy(travel) }
            },
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = icon,
            contentDescription = description,
            tint = if (enabled) ZillitTheme.colors.textMuted else ZillitTheme.colors.border,
            size = ARROW_ICON,
        )
    }
}

@Composable
private fun railStyle() = ScrollbarStyle(
    minimalHeight = THUMB_MIN,
    thickness = THUMB_THICKNESS,
    shape = ZillitTheme.shapes.small,
    hoverDurationMillis = THUMB_HOVER_MS,
    // `border` blends into the canvas in both themes — the thumb must read
    // at rest, it is the affordance the rail exists for.
    unhoverColor = ZillitTheme.colors.textMuted.copy(alpha = THUMB_REST_ALPHA),
    hoverColor = ZillitTheme.colors.textMuted,
)

private val ARROW_HEIGHT = SCROLL_RAIL_WIDTH
private val ARROW_ICON = SCROLL_RAIL_WIDTH * 3 / 4
private val THUMB_MIN = SCROLL_RAIL_WIDTH * 2
private val THUMB_THICKNESS = SCROLL_RAIL_WIDTH / 2
private const val THUMB_HOVER_MS = 300
private const val ARROW_REPEAT_MS = 120L
private const val ARROW_HOLD_DELAY_MS = 300L
private const val THUMB_REST_ALPHA = 0.5f
