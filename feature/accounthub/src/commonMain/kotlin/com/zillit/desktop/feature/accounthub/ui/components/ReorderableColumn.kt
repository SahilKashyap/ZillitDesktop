package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * A column whose rows are reordered by dragging — the web's drag and drop in
 * the form editor's Rearrange panel.
 *
 * The dragged row follows the pointer while the rows it passes slide out of
 * its way, and nothing changes in the document until it is dropped: one
 * [onMove] per drop, naming the dragged row and the row whose place it takes,
 * by key. Keys rather than positions because the lists this sorts are
 * filtered views of a longer document.
 *
 * A press that never moves stays a click, so a row can still open on click.
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> String,
    onMove: (fromKey: String, toKey: String) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    row: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val gap = with(LocalDensity.current) { spacing.toPx() }
    val keys = items.map(key)
    val latestKeys by rememberUpdatedState(keys)
    val latestMove by rememberUpdatedState(onMove)

    val from = dragging?.let { keys.indexOf(it) } ?: -1
    val target = if (from < 0) -1 else dropIndex(keys, heights, from, offset, gap)
    val travel = if (from < 0) 0f else (heights[keys[from]] ?: 0) + gap

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, item ->
            val rowKey = keys[index]
            key(rowKey) {
                // Snapped, not animated, once the drop lands: the rows are then
                // at their new places, and easing them back from the shifted
                // position would show them jumping away and returning.
                val slide by animateFloatAsState(
                    targetValue = slideFor(index, from, target, travel),
                    animationSpec = if (dragging == null) snap() else tween(SLIDE_MS),
                    label = "reorderSlide",
                )
                val drag = DragHandlers(
                    onStart = {
                        dragging = rowKey
                        offset = 0f
                    },
                    onDrag = { offset += it },
                    onEnd = { cancelled ->
                        val current = latestKeys
                        val start = current.indexOf(rowKey)
                        if (!cancelled && start >= 0) {
                            val end = dropIndex(current, heights, start, offset, gap)
                            if (end != start) latestMove(rowKey, current[end])
                        }
                        dragging = null
                        offset = 0f
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heights[rowKey] = it.height }
                        .zIndex(if (index == from) 1f else 0f)
                        .graphicsLayer { translationY = if (index == from) offset else slide }
                        .reorderDrag(rowKey, drag),
                ) {
                    row(item, index == from)
                }
            }
        }
    }
}

/** What a row does as it is picked up, moved by a vertical amount, and put down or abandoned. */
private class DragHandlers(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: (cancelled: Boolean) -> Unit,
)

private fun Modifier.reorderDrag(rowKey: String, handlers: DragHandlers): Modifier = pointerInput(rowKey) {
    detectDragGestures(
        onDragStart = { handlers.onStart() },
        onDragEnd = { handlers.onEnd(false) },
        onDragCancel = { handlers.onEnd(true) },
        onDrag = { change, amount ->
            change.consume()
            handlers.onDrag(amount.y)
        },
    )
}

/**
 * How far the row at [index] slides out of the dragged row's way: up by the
 * dragged row's height when the drag has passed it going down, down when it
 * has passed going up, and not at all otherwise.
 */
private fun slideFor(index: Int, from: Int, target: Int, travel: Float): Float = when {
    from < 0 || index == from -> 0f
    target > from && index in (from + 1)..target -> -travel
    target < from && index in target until from -> travel
    else -> 0f
}

/**
 * Where the dragged row would land: the row whose centre, in the layout as it
 * stands, is nearest the dragged row's centre.
 */
private fun dropIndex(keys: List<String>, heights: Map<String, Int>, from: Int, offset: Float, gap: Float): Int {
    var top = 0f
    val centres = keys.map { rowKey ->
        val height = (heights[rowKey] ?: 0).toFloat()
        val centre = top + height / 2
        top += height + gap
        centre
    }
    val moving = centres[from] + offset
    return centres.indices.minByOrNull { abs(centres[it] - moving) } ?: from
}

private const val SLIDE_MS = 140
