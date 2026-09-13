package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlin.math.floor

/*
 * A cost-report table that is wider than its pane keeps its label columns
 * still and slides only the figures: every row's figures sit in a strip
 * offset by one shared ScrollState, whose range the header's strip sets.
 * Figures are never broken across lines — a column is sized to the widest
 * one it has to print instead.
 */

/** The header's figures: the one strip that scrolls for real, and so gives the shared state its range. */
@Composable
internal fun RowScope.HeaderFigures(
    scroll: ScrollState,
    width: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.weight(1f).horizontalScroll(scroll)) {
        Row(Modifier.width(width), content = content)
    }
}

/** A row's figures, clipped to the pane and slid by the header's scroll. */
@Composable
internal fun RowScope.RowFigures(
    scroll: ScrollState,
    width: Dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.weight(1f).clipToBounds()) {
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(width)
                .offset { IntOffset(-scroll.value, 0) },
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/** Sideways wheel and trackpad movement over the rows moves the figures, as it does over the header. */
internal fun Modifier.slidesFigures(scroll: ScrollState): Modifier =
    scrollable(scroll, Orientation.Horizontal, reverseDirection = true)

/** The width a column needs to print [widest] in [style] without breaking it, plus [padding]. */
@Composable
internal fun figureWidth(widest: String, style: TextStyle, padding: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(widest, style, padding, density) {
        if (widest.isEmpty()) {
            padding
        } else {
            with(
                density,
            ) { measurer.measure(widest, style, softWrap = false, maxLines = 1).size.width.toDp() } + padding
        }
    }
}

/**
 * A figure that shrinks to fit its box rather than ending in "…" — the web's
 * `FitAmount`: the font is scaled by how much the text overflows, floored to
 * half points, never below half its size.
 */
@Composable
internal fun FitFigureText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val fitted = remember(text, style, available) {
            val base = style.fontSize.value
            val measured = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
            if (measured <= available || measured == 0) {
                style
            } else {
                val scaled = floor(base * available / measured * 2f) / 2f
                style.copy(fontSize = scaled.coerceIn(base * MIN_FIT_SCALE, base).sp)
            }
        }
        ZillitText(text = text, style = fitted, color = color, maxLines = 1, textAlign = textAlign)
    }
}

/** The web's floor: past half size a figure is unreadable, and something upstream is wrong. */
private const val MIN_FIT_SCALE = 0.5f

/** Space between a figure and the next column's, so two numbers never read as one. */
internal val FIGURE_GUTTER: Dp = 10.dp
