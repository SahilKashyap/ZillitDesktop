package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/**
 * Grid geometry a drag needs to turn pixels into schedule deltas: how wide a
 * day is, how tall an hour is (zero on the month grid, where vertical steps
 * are weeks), and how a vertical cell step converts to days.
 */
internal data class DragGrid(
    val dayWidthPx: Float,
    val hourHeightPx: Float,
    /** Days per vertical cell step — 7 on the month grid, 0 on time grids. */
    val daysPerRow: Int,
    /** Cell height for row stepping; only the month grid has one. */
    val rowHeightPx: Float = 0f,
)

/**
 * Makes an event block draggable to reschedule.
 *
 * The element itself follows the pointer (translation, no drop targets to
 * track), and on release the accumulated offset becomes whole-day and
 * snapped-minute deltas for [onMove]. A sub-cell drag rounds back to zero and
 * nothing fires — clicks still open the event because drag detection waits
 * for touch slop.
 */
@Composable
internal fun Modifier.draggableEvent(
    event: CalendarEvent,
    grid: () -> DragGrid,
    /**
     * Fires true at drag start and false at its end, so the containers can
     * lift the dragged block's cell with `zIndex` — without it the block
     * slides UNDER every later-composed sibling cell, half-hidden.
     */
    onDragging: (Boolean) -> Unit = {},
    onMove: (dayDelta: Int, minuteDelta: Int) -> Unit,
): Modifier {
    var offset by remember(event.id) { mutableStateOf(Offset.Zero) }

    return this
        .graphicsLayer {
            translationX = offset.x
            translationY = offset.y
            if (offset != Offset.Zero) {
                shadowElevation = DRAG_ELEVATION
                alpha = DRAG_ALPHA
            }
        }
        .pointerInput(event.id) {
            detectDragGestures(
                onDragStart = { onDragging(true) },
                onDrag = { change, amount ->
                    change.consume()
                    offset += amount
                },
                onDragEnd = {
                    val geometry = grid()
                    val (days, minutes) = offset.toDeltas(geometry)
                    offset = Offset.Zero
                    onDragging(false)
                    if (days != 0 || minutes != 0) onMove(days, minutes)
                },
                onDragCancel = {
                    offset = Offset.Zero
                    onDragging(false)
                },
            )
        }
}

/**
 * Makes a block's bottom edge draggable to change its duration.
 *
 * Vertical only — the start stays put, the end follows. The handle consumes
 * its own pointer events, so it wins over the block's move-drag beneath it.
 */
@Composable
internal fun Modifier.resizableEventEdge(
    event: CalendarEvent,
    grid: () -> DragGrid,
    onResize: (minuteDelta: Int) -> Unit,
): Modifier {
    var dragged by remember(event.id) { mutableStateOf(0f) }

    return this.pointerInput(event.id) {
        detectDragGestures(
            onDrag = { change, amount ->
                change.consume()
                dragged += amount.y
            },
            onDragEnd = {
                val geometry = grid()
                val minutes = if (geometry.hourHeightPx > 0) {
                    ((dragged / geometry.hourHeightPx * MINUTES_PER_HOUR) / SNAP_MINUTES)
                        .roundToInt() * SNAP_MINUTES
                } else {
                    0
                }
                dragged = 0f
                if (minutes != 0) onResize(minutes)
            },
            onDragCancel = { dragged = 0f },
        )
    }
}

/** Pixels → (whole days, snapped minutes) against the grid's geometry. */
internal fun Offset.toDeltas(grid: DragGrid): Pair<Int, Int> {
    val columns = if (grid.dayWidthPx > 0) (x / grid.dayWidthPx).roundToInt() else 0

    return if (grid.daysPerRow > 0) {
        // Month grid: vertical steps are weeks, and time never changes.
        val rows = if (grid.rowHeightPx > 0) (y / grid.rowHeightPx).roundToInt() else 0
        (rows * grid.daysPerRow + columns) to 0
    } else {
        // Time grid: vertical is minutes, snapped so drops land on the kind
        // of times people actually schedule.
        val minutes = if (grid.hourHeightPx > 0) {
            ((y / grid.hourHeightPx * MINUTES_PER_HOUR) / SNAP_MINUTES).roundToInt() * SNAP_MINUTES
        } else {
            0
        }
        columns to minutes
    }
}

private const val MINUTES_PER_HOUR = 60
private const val SNAP_MINUTES = 15
private const val DRAG_ELEVATION = 8f
private const val DRAG_ALPHA = 0.85f
