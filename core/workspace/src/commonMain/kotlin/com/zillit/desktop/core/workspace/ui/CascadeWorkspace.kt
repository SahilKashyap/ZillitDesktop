package com.zillit.desktop.core.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WindowState
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceState

/**
 * Cascade mode: free-floating windows inside the workspace area (plan §3.2).
 *
 * Every visible window composes here, unlike tabs mode where only the active one
 * does — that is inherent to the mode, since they are all on screen at once. It
 * is also why cascade is opt-in rather than the default: ten floating tools cost
 * ten compositions.
 *
 * Draw order follows `zIndex`, so the reducer's z-ordering is what puts a
 * focused window on top; the UI does not maintain its own idea of stacking.
 */
@Composable
fun CascadeWorkspace(
    state: WorkspaceState,
    registry: ToolRegistry,
    onEvent: (WorkspaceEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    var workspaceSize by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .onSizeChanged { size ->
                // Dp, not raw pixels: window rects are stored in Dp so a session
                // restored on a different display scale lands in the same
                // logical place.
                with(density) {
                    workspaceSize = IntOffset(size.width.toDp().value.toInt(), size.height.toDp().value.toInt())
                }
            },
    ) {
        if (state.windows.none { it.isVisible }) {
            EmptyWorkspaceMessage()
            return@Box
        }

        state.windows
            .filter { it.state != WindowState.Minimized && it.state != WindowState.Detached }
            .sortedBy { it.zIndex }
            .forEach { window ->
                val provider = registry.resolve(window.route)
                FloatingToolWindow(
                    window = window,
                    isActive = window.id == state.activeId,
                    icon = provider?.icon ?: ZillitIcons.Tools,
                    workspaceSize = workspaceSize,
                    onFocus = { onEvent(WorkspaceEvent.Focus(window.id)) },
                    onMinimize = { onEvent(WorkspaceEvent.Minimize(window.id)) },
                    onToggleMaximize = { onEvent(WorkspaceEvent.ToggleMaximize(window.id)) },
                    onDetach = { onEvent(WorkspaceEvent.SetDetached(window.id, detached = true)) },
                    onClose = { onEvent(WorkspaceEvent.Close(window.id)) },
                    onRectChange = { rect -> onEvent(WorkspaceEvent.UpdateRect(window.id, rect)) },
                ) {
                    // Keyed by window id so each floating window keeps its own
                    // saved state, exactly as in tabs mode.
                    stateHolder.SaveableStateProvider(window.id.value) {
                        ToolWindowContent(window = window, registry = registry, onEvent = onEvent)
                    }
                }
            }
    }
}
