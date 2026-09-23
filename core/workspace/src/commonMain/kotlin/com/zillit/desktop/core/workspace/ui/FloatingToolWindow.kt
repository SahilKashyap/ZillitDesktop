package com.zillit.desktop.core.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolWindow
import com.zillit.desktop.core.workspace.WindowRect
import kotlin.math.roundToInt

/**
 * A draggable, resizable floating window — cascade mode (plan §3.2).
 *
 * ## Why drag state is local
 *
 * Position and size are tracked in local composable state during a gesture and
 * committed to the workspace **once, on release**. Writing every drag frame to
 * `WorkspaceState` would push a new state through the `StateFlow` at pointer
 * frequency, invalidating every window's composition ~120 times a second and
 * persisting the session on each one.
 *
 * That is the same class of mistake as web ZL-20352, where rebuilding the window
 * array on each mousedown wiped in-progress form input. The reducer is for
 * committed facts, not for gesture frames.
 */
@Composable
internal fun FloatingToolWindow(
    window: ToolWindow,
    isActive: Boolean,
    icon: ImageVector,
    workspaceSize: IntOffset,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    onRectChange: (WindowRect) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val maximized = window.state == com.zillit.desktop.core.workspace.WindowState.Maximized

    val committed = window.rect ?: DEFAULT_RECT
    // Live geometry during a gesture; reset whenever the committed rect changes
    // (another actor moved the window, or it was maximized).
    var dragRect by remember(committed, maximized) { mutableStateOf(committed) }

    val rect = if (maximized) {
        WindowRect(0, 0, workspaceSize.x, workspaceSize.y)
    } else {
        dragRect
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(rect.x, rect.y) }
            .size(rect.width.dp, rect.height.dp)
            .shadow(if (isActive) ACTIVE_ELEVATION else IDLE_ELEVATION, ZillitTheme.shapes.large)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = if (isActive) colors.accent.copy(alpha = ACTIVE_BORDER_ALPHA) else colors.border,
                shape = ZillitTheme.shapes.large,
            )
            // Focus on press, before children consume the event, so clicking
            // anywhere in a background window raises it.
            .pointerInput(window.id) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onFocus()
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            WindowTitleBar(
                window = window,
                icon = icon,
                isActive = isActive,
                onDrag = { dx, dy ->
                    if (!maximized) {
                        dragRect = dragRect.movedBy(dx, dy, workspaceSize)
                    }
                },
                onDragEnd = { if (!maximized) onRectChange(dragRect) },
                onMinimize = onMinimize,
                onToggleMaximize = onToggleMaximize,
                onDetach = onDetach,
                onClose = onClose,
            )
            HorizontalDivider(color = colors.divider)
            Box(Modifier.fillMaxSize()) { content() }
        }

        if (!maximized) {
            ResizeHandle(
                modifier = Modifier.align(Alignment.BottomEnd),
                onResize = { dx, dy -> dragRect = dragRect.resizedBy(dx, dy) },
                onResizeEnd = { onRectChange(dragRect) },
            )
        }
    }
}

@Composable
private fun WindowTitleBar(
    window: ToolWindow,
    icon: ImageVector,
    isActive: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(if (isActive) colors.surfaceHover else colors.surfaceSunken)
            .pointerInput(window.id) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, delta ->
                    change.consume()
                    onDrag(delta.x, delta.y)
                }
            }
            // Double-click the title bar to maximize — universal desktop idiom.
            .pointerInput(window.id) {
                detectTapGestures(onDoubleTap = { onToggleMaximize() })
            }
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon, tint = if (isActive) colors.accent else colors.textSecondary, size = ZillitDimens.iconSmall)
        ZillitText(
            text = window.title,
            modifier = Modifier.weight(1f),
            style = ZillitTheme.typography.label,
            color = if (isActive) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        ZillitIconButton(ZillitIcons.Detach, "Open ${window.title} in its own window", onDetach, size = CONTROL_SIZE)
        ZillitIconButton(ZillitIcons.Minimize, "Minimize ${window.title}", onMinimize, size = CONTROL_SIZE)
        ZillitIconButton(
            icon = if (window.state == com.zillit.desktop.core.workspace.WindowState.Maximized) {
                ZillitIcons.Restore
            } else {
                ZillitIcons.Maximize
            },
            contentDescription = str(S.desktop_workspace_maximize_or_restore_window, window.title),
            onClick = onToggleMaximize,
            size = CONTROL_SIZE,
        )
        ZillitIconButton(ZillitIcons.Close, "Close ${window.title}", onClose, size = CONTROL_SIZE)
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(RESIZE_HANDLE_SIZE)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd,
                ) { change, delta ->
                    change.consume()
                    onResize(delta.x, delta.y)
                }
            },
    ) {
        // A visible grip: an invisible resize target is undiscoverable, and
        // desktop users expect the corner to be grabbable.
        ZillitIcon(
            icon = ZillitIcons.Detach,
            tint = ZillitTheme.colors.textMuted.copy(alpha = GRIP_ALPHA),
            size = 10.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
        )
    }
}

private fun WindowRect.movedBy(dx: Float, dy: Float, bounds: IntOffset): WindowRect {
    // Clamped so a window cannot be dragged fully off-screen and stranded —
    // at least a title bar's worth stays reachable.
    val maxX = (bounds.x - MIN_VISIBLE).coerceAtLeast(0)
    val maxY = (bounds.y - MIN_VISIBLE).coerceAtLeast(0)
    return copy(
        x = (x + dx.roundToInt()).coerceIn(0, maxX),
        y = (y + dy.roundToInt()).coerceIn(0, maxY),
    )
}

private fun WindowRect.resizedBy(dx: Float, dy: Float): WindowRect = copy(
    width = (width + dx.roundToInt()).coerceAtLeast(MIN_WIDTH),
    height = (height + dy.roundToInt()).coerceAtLeast(MIN_HEIGHT),
)

private val DEFAULT_RECT = WindowRect(x = 40, y = 40, width = 900, height = 600)
private val TITLE_BAR_HEIGHT: Dp = 34.dp
private val CONTROL_SIZE: Dp = 22.dp
private val RESIZE_HANDLE_SIZE: Dp = 18.dp
private val ACTIVE_ELEVATION: Dp = 16.dp
private val IDLE_ELEVATION: Dp = 4.dp
private const val ACTIVE_BORDER_ALPHA = 0.5f
private const val GRIP_ALPHA = 0.7f
private const val MIN_VISIBLE = 120
private const val MIN_WIDTH = 420
private const val MIN_HEIGHT = 320
