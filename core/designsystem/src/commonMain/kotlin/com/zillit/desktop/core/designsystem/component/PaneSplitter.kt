package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The grab bar between two side-by-side panes.
 *
 * Reports the drag in **pixels** rather than owning a width: only the caller
 * knows what the panes are allowed to become — their minimums, and what the
 * remaining space is — and a splitter that kept its own number would disagree
 * with the layout the moment the window was resized.
 *
 * It is deliberately wider than the line it draws. A one-point target is a
 * desktop cliché and it is missable; [SPLITTER_TARGET] is what the pointer
 * hits, [SPLITTER_LINE] is what the eye sees, and the line lights up in the
 * accent while the pointer is over it or the drag is live — with the ↔ cursor,
 * that is the whole affordance.
 */
@Composable
fun ZillitPaneSplitter(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragEnd: () -> Unit = {},
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(SPLITTER_TARGET)
            .fillMaxHeight()
            .hoverable(interaction)
            .then(rememberHorizontalResizeCursor())
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragEnd()
                    },
                ) { change, delta ->
                    // Consumed so a list or a scrolling message body underneath
                    // does not also act on the same drag.
                    change.consume()
                    onDrag(delta)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(SPLITTER_LINE)
                .fillMaxHeight()
                .background(if (hovered || dragging) colors.accent else colors.divider),
        )
    }
}

/**
 * The ↔ cursor while the pointer is over a splitter.
 *
 * Platform-shaped because Compose's common `PointerIcon` has no resize cursor —
 * the desktop builds one from the AWT cursor, and anywhere without cursors this
 * is simply empty.
 */
@Composable
expect fun rememberHorizontalResizeCursor(): Modifier

/** What the pointer hits — bigger than the line, so the bar is easy to grab. */
private val SPLITTER_TARGET = 8.dp

/** What the eye sees: a hairline, like every other divider in the app. */
private val SPLITTER_LINE = 1.dp
