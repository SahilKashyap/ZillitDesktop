package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WindowState
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceState
import com.zillit.desktop.core.workspace.ui.ToolWindowContent

/**
 * Renders every torn-off window as a **real OS window** (plan §3.2).
 *
 * This is the capability the web app fundamentally cannot have. Its cascade mode
 * fakes floating windows with `z-index` divs inside one viewport, so they are
 * trapped in the browser tab: they cannot cross to a second monitor, cannot be
 * tiled by the window manager, and do not appear in Alt-Tab or Mission Control.
 *
 * A detached window is a `Window` composable, so the OS treats it as a
 * first-class window. Dragging it to another display is a native operation
 * nobody had to implement.
 *
 * State follows the tool: the underlying `ToolWindow` and its saved state keep
 * the same identity across detach and re-dock, so scroll position and
 * in-progress edits come along.
 */
@Composable
fun DetachedToolWindows(
    state: WorkspaceState,
    registry: ToolRegistry,
    onEvent: (WorkspaceEvent) -> Unit,
    darkTheme: Boolean,
    /**
     * For the location picker: a torn-off window is its own composition, so
     * without its own mount every location field in it would degrade to
     * plain text while the docked one offers a map.
     */
    graph: AppGraph,
) {
    val stateHolder = rememberSaveableStateHolder()

    state.windows
        .filter { it.state == WindowState.Detached }
        .forEach { window ->
            key(window.id.value) {
                val windowState = rememberWindowState(
                    size = window.rect
                        ?.let { DpSize(it.width.dp, it.height.dp) }
                        ?: DpSize(DETACHED_WIDTH, DETACHED_HEIGHT),
                    position = window.rect
                        ?.let { WindowPosition(it.x.dp, it.y.dp) }
                        ?: WindowPosition.PlatformDefault,
                )

                Window(
                    // Closing a torn-off window re-docks the tool rather than
                    // destroying it. Losing the user's work because they closed
                    // the wrong window would be a nasty surprise — they can
                    // still close it properly from the tab strip.
                    onCloseRequest = { onEvent(WorkspaceEvent.SetDetached(window.id, detached = false)) },
                    state = windowState,
                    title = "${window.title} — Zillit",
                ) {
                    ZillitTheme(darkTheme = darkTheme) {
                        LocationPickerMount(graph) {
                            Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
                                stateHolder.SaveableStateProvider(window.id.value) {
                                    ToolWindowContent(window = window, registry = registry, onEvent = onEvent)
                                }
                            }
                        }
                    }
                }
            }
        }
}

private val DETACHED_WIDTH = 1024.dp
private val DETACHED_HEIGHT = 700.dp
