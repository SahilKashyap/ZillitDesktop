package com.zillit.desktop.core.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.workspace.LayoutMode
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.ToolWindow
import com.zillit.desktop.core.workspace.WindowId
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.WorkspaceState

/**
 * Renders the workspace in whichever layout mode is active.
 *
 * The two modes differ only in presentation — both resolve routes through the
 * same [ToolRegistry] and give each window the same saved-state treatment, so a
 * tool cannot tell which mode it is in and does not need to.
 */
@Composable
fun Workspace(
    state: WorkspaceState,
    registry: ToolRegistry,
    onEvent: (WorkspaceEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.layoutMode) {
        LayoutMode.Tabs -> WorkspaceHost(state, registry, onEvent, modifier)
        LayoutMode.Cascade -> CascadeWorkspace(state, registry, onEvent, modifier)
    }
}

/**
 * Tabs mode: renders only the active window's content.
 *
 * Only the active window composes: ten open tools cost one composition, not
 * ten. That is what lets the desktop client hold far more open work than the
 * browser version, which must keep every window mounted to avoid losing it.
 *
 * ## State-retention contract — read before writing a tool
 *
 * Each window's content is wrapped in a [SaveableStateHolder] keyed by
 * [WindowId]. That preserves **`rememberSaveable`**, not plain `remember`:
 * switching tabs disposes the inactive window's composition, so anything held
 * in a bare `remember` is gone when the user comes back.
 *
 * So, inside a `ToolProvider.Content`:
 *
 *  - `rememberSaveable` — scroll offsets, expanded rows, filter selections,
 *    text field contents. Survives tab switches *and* session restore.
 *  - a window-scoped `ViewModel` — anything large, async, or not serializable.
 *  - plain `remember` — only for state that is genuinely derived and cheap to
 *    rebuild, such as an animation target.
 *
 * Getting this wrong is silent: the tool looks correct until a user switches
 * away mid-form and loses their input. `AppShellTest` covers the contract.
 */
@Composable
fun WorkspaceHost(
    state: WorkspaceState,
    registry: ToolRegistry,
    onEvent: (WorkspaceEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateHolder = rememberSaveableStateHolder()
    val active = state.activeWindow

    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (active == null) {
            EmptyWorkspaceMessage()
        } else {
            // Keyed by window id, not by route: navigating inside a window must
            // keep its saved state, and two windows on the same tool must not
            // share it.
            stateHolder.SaveableStateProvider(active.id.value) {
                ToolWindowContent(window = active, registry = registry, onEvent = onEvent)
            }
        }
    }
}

/**
 * Resolves a window's current route to its provider and renders it.
 *
 * Public because torn-off OS windows live in `desktopApp` and render through
 * this too — which is what guarantees a tool behaves identically whether it is
 * a tab, a floating window, or its own OS window.
 */
@Composable
fun ToolWindowContent(
    window: ToolWindow,
    registry: ToolRegistry,
    onEvent: (WorkspaceEvent) -> Unit,
) {
    val provider = registry.resolve(window.route)
    if (provider == null) {
        MissingTool(window.route)
        return
    }

    val navigator = remember(window.id, window.canGoBack) {
        WindowNavigatorImpl(windowId = window.id, canGoBack = window.canGoBack, onEvent = onEvent)
    }

    provider.Content(route = window.route, navigator = navigator)
}

/**
 * A tool's handle on its own window. Scoped so a tool can drive itself but
 * cannot reach across and disturb another window.
 */
private class WindowNavigatorImpl(
    override val windowId: WindowId,
    override val canGoBack: Boolean,
    private val onEvent: (WorkspaceEvent) -> Unit,
) : WindowNavigator {
    override fun navigate(route: WorkspaceRoute) = onEvent(WorkspaceEvent.Navigate(windowId, route))
    override fun back() = onEvent(WorkspaceEvent.Back(windowId))
    override fun openInNewWindow(route: WorkspaceRoute) = onEvent(WorkspaceEvent.Open(route))
    override fun setTitle(title: String) = onEvent(WorkspaceEvent.SetTitle(windowId, title))
    override fun setDirty(dirty: Boolean) = onEvent(WorkspaceEvent.SetDirty(windowId, dirty))
    override fun close() = onEvent(WorkspaceEvent.Close(windowId))
}

@Composable
internal fun EmptyWorkspaceMessage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ZillitText(
            text = "Nothing open",
            style = ZillitTheme.typography.titleMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = "Pick a tool from the sidebar to open it here.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * Shown when a route has no registered provider — during the roadmap this is
 * the normal state for tools that have not been built yet, so it names the
 * route rather than showing a generic error.
 */
@Composable
private fun MissingTool(route: WorkspaceRoute) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ZillitText(
            text = "Not available yet",
            style = ZillitTheme.typography.titleMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(
            text = route.path,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = "This tool arrives in a later module.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}
