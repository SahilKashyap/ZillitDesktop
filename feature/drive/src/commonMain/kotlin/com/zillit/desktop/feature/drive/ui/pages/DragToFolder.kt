@file:Suppress("MatchingDeclarationName") // The state and the modifiers that feed it belong together.

package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveItem
import kotlin.math.roundToInt

/**
 * Drag a row or a card onto a folder to move it — the web's dnd-kit setup
 * (`DraggableDroppableRow`, `DraggableDroppableCard`).
 *
 * Every folder row registers its bounds in window coordinates; the dragged
 * item follows the pointer as a chip; on release, the folder under the
 * pointer (other than the item itself) receives the move. Nothing here
 * talks to the server — the drop hands (item, folderId) to the caller.
 */
internal class DragToFolderState {
    var dragging: DriveItem? by mutableStateOf(null)
        private set
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set
    var hoverFolderId: String? by mutableStateOf(null)
        private set

    private val targets = mutableMapOf<String, Rect>()

    fun register(folderId: String, bounds: Rect) {
        targets[folderId] = bounds
    }

    fun begin(item: DriveItem, at: Offset) {
        dragging = item
        pointer = at
        hoverFolderId = null
    }

    fun moveBy(delta: Offset) {
        pointer += delta
        val over = targets.entries.firstOrNull { (id, rect) -> rect.contains(pointer) && id != dragging?.id }?.key
        hoverFolderId = over
    }

    /** Ends the drag, answering the folder it landed on, if any. */
    fun drop(): String? {
        val target = hoverFolderId
        dragging = null
        hoverFolderId = null
        return target
    }

    fun cancel() {
        dragging = null
        hoverFolderId = null
    }
}

/** Makes [item] draggable. The chip starts after the platform's touch slop, so clicks still land. */
@Composable
internal fun Modifier.dragSource(
    state: DragToFolderState,
    item: DriveItem,
    enabled: Boolean,
    onDrop: (DriveItem, String) -> Unit,
): Modifier {
    if (!enabled) return this
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(item.id, enabled) {
            detectDragGestures(
                onDragStart = { start -> state.begin(item, origin + start) },
                onDrag = { change, delta ->
                    change.consume()
                    state.moveBy(delta)
                },
                onDragEnd = {
                    val target = state.drop()
                    if (target != null && target != item.id) onDrop(item, target)
                },
                onDragCancel = { state.cancel() },
            )
        }
}

/** Registers a folder's bounds so a drag can land on it. */
@Composable
internal fun Modifier.dropTarget(state: DragToFolderState, folderId: String): Modifier =
    onGloballyPositioned { coords ->
        val position = coords.positionInRoot()
        state.register(
            folderId,
            Rect(position.x, position.y, position.x + coords.size.width, position.y + coords.size.height),
        )
    }

/**
 * The chip that follows the pointer — the web's `DragOverlay`. Placed in
 * the listing's own Box; it converts the window-coordinate pointer into
 * that Box's frame.
 */
@Composable
internal fun DragOverlay(state: DragToFolderState) {
    val item = state.dragging ?: return
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .onGloballyPositioned { containerOrigin = it.parentLayoutCoordinates?.positionInRoot() ?: Offset.Zero }
            .offset {
                val local = state.pointer - containerOrigin
                IntOffset((local.x + CHIP_OFFSET).roundToInt(), (local.y + CHIP_OFFSET).roundToInt())
            }
            .shadow(CHIP_SHADOW, ZillitTheme.shapes.medium)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceRaised)
            .border(1.dp, ZillitTheme.colors.accent, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)
            .widthIn(max = CHIP_MAX_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(
            icon = if (item.isFolder) ZillitIcons.Folder else ZillitIcons.File,
            tint = if (item.isFolder) ZillitTheme.colors.accent else ZillitTheme.colors.textSecondary,
            size = ZillitTheme.spacing.lg,
        )
        ZillitText(text = item.name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
    }
}

private const val CHIP_OFFSET = 12f
private val CHIP_SHADOW = 8.dp
private val CHIP_MAX_WIDTH = 260.dp
