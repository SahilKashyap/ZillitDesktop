package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import kotlin.math.roundToInt

/**
 * A list reordered by dragging a row's handle — the web's dnd-kit sortable,
 * as both listing-order dialogs use it. The lifted row follows the pointer
 * above its neighbours; on release it lands where it was dropped, counted in
 * whole rows of [rowPitch] (row height plus the gap). [visible] may be a
 * search's subset: indices are the full list's, and dragging is off while
 * filtered, because a drop position in a partial list means nothing.
 */
@Composable
internal fun <T> ReorderList(
    visible: List<IndexedValue<T>>,
    total: Int,
    key: (T) -> String,
    rowPitch: Dp,
    gap: Dp,
    filtered: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int, item: T, lifted: Boolean, dragHandle: Modifier, rowModifier: Modifier) -> Unit,
) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val pitch = with(LocalDensity.current) { rowPitch.toPx() }
    ZillitLazyColumn(
        state = rememberLazyListState(),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        items(visible, key = { key(it.value) }) { (index, item) ->
            val id = key(item)
            val lifted = dragging == id
            val handle = if (filtered) {
                Modifier
            } else {
                Modifier.pointerInput(id, index, total) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = id
                            offset = 0f
                        },
                        onDragEnd = {
                            val to = (index + (offset / pitch).roundToInt()).coerceIn(0, total - 1)
                            dragging = null
                            offset = 0f
                            if (to != index) onMove(index, to)
                        },
                        onDragCancel = {
                            dragging = null
                            offset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                        },
                    )
                }
            }
            val rowModifier = Modifier
                .fillMaxWidth()
                .zIndex(if (lifted) 1f else 0f)
                .then(if (lifted) Modifier.offset { IntOffset(0, offset.roundToInt()) } else Modifier)
            row(index, item, lifted, handle, rowModifier)
        }
    }
}
