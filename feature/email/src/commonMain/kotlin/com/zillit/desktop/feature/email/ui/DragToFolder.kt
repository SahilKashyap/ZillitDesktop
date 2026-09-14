package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlin.math.roundToInt

/** A row being dragged: which, and where the pointer is, in root pixels. */
data class RowDrag(val rowId: String, val position: Offset)

/**
 * Dragging a row onto a folder — the web's card drag (`useEmailCardDragDrop`,
 * `EmailSidebarItem.onDrop`), done with plain pointer events rather than the
 * OS drag machinery: the drop target is our own sidebar, in our own window,
 * and a Compose-level drag lets the chip follow the pointer and the folder
 * light up the way the browser's does.
 *
 * The sidebar reports where each folder row sits ([onFolderBounds]); a drop
 * lands on whichever contains the pointer. Sent and Drafts refuse drops,
 * and so does the open folder — moving mail into the folder it is already
 * in is a no-op that would still round-trip to the server.
 */
class DragToFolder(private val onDrop: (rowId: String, folderName: String) -> Unit) {

    var drag: RowDrag? by mutableStateOf(null)
        private set

    private val folderBounds = mutableStateMapOf<String, FolderBounds>()

    /** Folders that refuse a drop: the open one, Sent and Drafts. */
    var refused: Set<String> by mutableStateOf(emptySet())

    /** The folder under the pointer, if it accepts a drop. */
    val target: String?
        get() {
            val at = drag?.position ?: return null
            return folderBounds.entries
                .firstOrNull { (name, bounds) -> bounds.contains(at.x, at.y) && name !in refused }
                ?.key
        }

    fun onFolderBounds(folderName: String, bounds: FolderBounds?) {
        if (bounds == null) folderBounds.remove(folderName) else folderBounds[folderName] = bounds
    }

    /** What a row wears to become draggable. */
    fun source(rowId: String): Modifier = Modifier.dragSource(this, rowId)

    internal fun start(rowId: String, at: Offset) {
        drag = RowDrag(rowId, at)
    }

    internal fun move(delta: Offset) {
        drag = drag?.let { it.copy(position = it.position + delta) }
    }

    internal fun finish() {
        val dropped = drag ?: return
        val folder = target
        drag = null
        if (folder != null) onDrop(dropped.rowId, folder)
    }

    internal fun cancel() {
        drag = null
    }
}

private fun Modifier.dragSource(drag: DragToFolder, rowId: String): Modifier {
    var origin = Offset.Zero
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(rowId) {
            detectDragGestures(
                onDragStart = { offset -> drag.start(rowId, origin + offset) },
                onDrag = { change, delta ->
                    change.consume()
                    drag.move(delta)
                },
                onDragEnd = { drag.finish() },
                onDragCancel = { drag.cancel() },
            )
        }
}

/**
 * The chip that rides the pointer while a row is dragged — the web's
 * "Move conversation" tag. Drawn by the screen's root box, above everything.
 */
@Composable
internal fun DragChip(drag: DragToFolder) {
    val current = drag.drag ?: return
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (current.position.x + CHIP_OFFSET).roundToInt(),
                    (current.position.y + CHIP_OFFSET).roundToInt(),
                )
            }
            .shadow(CHIP_LIFT, ZillitTheme.shapes.medium)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.accent)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = if (drag.target != null) "Drop to move" else "Move conversation",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textOnAccent,
        )
    }
}

@Composable
internal fun rememberDragToFolder(onDrop: (String, String) -> Unit): DragToFolder = remember { DragToFolder(onDrop) }

private const val CHIP_OFFSET = 12f
private val CHIP_LIFT = 8.dp
