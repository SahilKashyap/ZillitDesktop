package com.zillit.desktop.core.workspace

import com.zillit.desktop.core.mvvm.ZillitViewModel

/**
 * Everything the user can do to the workspace.
 *
 * A closed event set rather than loose callbacks: the tab strip, the rail, the
 * keyboard shortcut handler and the window chrome all funnel through here, so
 * "close a window" behaves the same however it was triggered.
 */
sealed interface WorkspaceEvent {
    data class Open(val route: WorkspaceRoute) : WorkspaceEvent
    data class Close(val id: WindowId) : WorkspaceEvent
    data class Focus(val id: WindowId) : WorkspaceEvent
    data class Minimize(val id: WindowId) : WorkspaceEvent
    data class Restore(val id: WindowId) : WorkspaceEvent
    data class ToggleMaximize(val id: WindowId) : WorkspaceEvent
    data class TogglePin(val id: WindowId) : WorkspaceEvent
    data class SetDetached(val id: WindowId, val detached: Boolean) : WorkspaceEvent
    data class Navigate(val id: WindowId, val route: WorkspaceRoute) : WorkspaceEvent
    data class Back(val id: WindowId) : WorkspaceEvent
    data class SetTitle(val id: WindowId, val title: String) : WorkspaceEvent
    data class SetDirty(val id: WindowId, val dirty: Boolean) : WorkspaceEvent
    data class UpdateRect(val id: WindowId, val rect: WindowRect) : WorkspaceEvent
    data class Reorder(val from: Int, val to: Int) : WorkspaceEvent
    data class CloseOthers(val id: WindowId) : WorkspaceEvent
    data object CloseAll : WorkspaceEvent

    /**
     * The UI language changed: every window's title is re-asked of its
     * provider. Titles are stored at open time (a screen may override its
     * own with [SetTitle]), so nothing else would redraw "Home" as "Accueil".
     * A screen that set its own title sets it again on its next
     * recomposition, which the language change also triggers.
     */
    data object RefreshTitles : WorkspaceEvent

    /**
     * Every window goes, pinned included — the user left the production.
     *
     * Distinct from [CloseAll], which spares pinned windows because the user
     * asked to tidy up. Here the windows hold another production's data, so a
     * pinned one surviving the switch is not a convenience, it is the wrong
     * production's content sitting in the new one's workspace.
     */
    data object CloseAllForProjectSwitch : WorkspaceEvent
    data object ToggleLayoutMode : WorkspaceEvent

    /** Classic ⇄ windowed; the app feeds this from the stored preference. */
    data class SetViewMode(val mode: ViewMode) : WorkspaceEvent
    data object ReopenLastClosed : WorkspaceEvent
    data object FocusNext : WorkspaceEvent
    data object FocusPrevious : WorkspaceEvent
    data class FocusIndex(val index: Int) : WorkspaceEvent
}

/** One-shot outcomes the UI must react to but not re-render. */
sealed interface WorkspaceEffect {
    data class ConfirmClose(val id: WindowId, val title: String) : WorkspaceEffect
    data class UnknownTool(val path: String) : WorkspaceEffect
}

/**
 * Owns workspace state (plan §3).
 *
 * All logic lives in the pure reducers; this only sequences them, resolves
 * routes through the [ToolRegistry], and persists the session. That split is
 * what makes the window model testable without a UI.
 */
class WorkspaceViewModel(
    private val registry: ToolRegistry,
    private val sessionStore: WorkspaceSessionStore,
    private val idGenerator: () -> String,
    initialState: WorkspaceState = WorkspaceState(),
) : ZillitViewModel<WorkspaceState, WorkspaceEvent, WorkspaceEffect>(initialState) {

    // A dispatch over a sealed event set. Cyclomatic complexity counts every
    // branch, but exhaustive `when` over a closed hierarchy is the point of the
    // pattern — splitting it would hide the fact that every event is handled.
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: WorkspaceEvent) {
        when (event) {
            is WorkspaceEvent.Open -> open(event.route)
            is WorkspaceEvent.Close -> closeWindow(event.id)
            is WorkspaceEvent.Focus -> reduce { WorkspaceReducer.focus(it, event.id) }
            is WorkspaceEvent.Minimize -> reduce { WorkspaceReducer.minimize(it, event.id) }
            is WorkspaceEvent.Restore -> reduce { WorkspaceReducer.restore(it, event.id) }
            is WorkspaceEvent.ToggleMaximize -> reduce { WorkspaceReducer.toggleMaximize(it, event.id) }
            is WorkspaceEvent.TogglePin -> reduce { WorkspaceReducer.togglePin(it, event.id) }
            is WorkspaceEvent.SetTitle -> reduce { WorkspaceReducer.setTitle(it, event.id, event.title) }
            is WorkspaceEvent.SetDirty -> reduce { WorkspaceReducer.setDirty(it, event.id, event.dirty) }
            is WorkspaceEvent.CloseOthers -> reduce { WorkspaceReducer.closeOthers(it, event.id) }
            WorkspaceEvent.CloseAll -> reduce { WorkspaceReducer.closeAll(it) }
            WorkspaceEvent.RefreshTitles -> reduce { state ->
                state.copy(
                    windows = state.windows.map { window ->
                        registry.resolve(window.route)?.let { window.copy(title = it.titleFor(window.route)) } ?: window
                    },
                )
            }
            WorkspaceEvent.CloseAllForProjectSwitch ->
                reduce { WorkspaceReducer.closeAll(it, includePinned = true) }

            is WorkspaceEvent.Navigate ->
                reduce { WorkspaceLayoutReducer.navigate(it, event.id, event.route) }

            is WorkspaceEvent.Back -> reduce { WorkspaceLayoutReducer.back(it, event.id) }

            is WorkspaceEvent.UpdateRect ->
                reduce { WorkspaceLayoutReducer.updateRect(it, event.id, event.rect) }

            is WorkspaceEvent.SetDetached ->
                reduce { WorkspaceLayoutReducer.setDetached(it, event.id, event.detached) }

            is WorkspaceEvent.Reorder ->
                reduce { WorkspaceLayoutReducer.reorder(it, event.from, event.to) }

            WorkspaceEvent.ToggleLayoutMode -> reduce { WorkspaceLayoutReducer.toggleLayoutMode(it) }
            is WorkspaceEvent.SetViewMode -> reduce { WorkspaceLayoutReducer.setViewMode(it, event.mode) }
            WorkspaceEvent.ReopenLastClosed -> reopenLastClosed()
            WorkspaceEvent.FocusNext -> cycleFocus(offset = 1)
            WorkspaceEvent.FocusPrevious -> cycleFocus(offset = -1)
            is WorkspaceEvent.FocusIndex -> focusIndex(event.index)
        }
    }

    /** Restores the workspace saved on the previous run. */
    fun restoreSession() = launch {
        sessionStore.load()?.let { restored -> setState { restored } }
    }

    private fun open(route: WorkspaceRoute) {
        val provider = registry.resolve(route)
        if (provider == null) {
            sendEffect(WorkspaceEffect.UnknownTool(route.path))
            return
        }
        reduce {
            WorkspaceReducer.open(
                state = it,
                id = WindowId(idGenerator()),
                route = route,
                title = provider.titleFor(route),
                iconKey = provider.path,
                openMode = provider.openMode,
                rect = WorkspaceLayoutReducer.defaultRect(
                    index = it.windows.size,
                    workspaceWidth = DEFAULT_WORKSPACE_WIDTH,
                    workspaceHeight = DEFAULT_WORKSPACE_HEIGHT,
                ),
            )
        }
    }

    /**
     * Asks before discarding unsaved work. The confirmation is an effect, not
     * state, so it does not replay when the window is resized.
     */
    private fun closeWindow(id: WindowId) {
        val window = currentState.window(id) ?: return
        if (window.isDirty) {
            sendEffect(WorkspaceEffect.ConfirmClose(id, window.title))
        } else {
            reduce { WorkspaceReducer.close(it, id) }
        }
    }

    /** Closes without prompting — called after the user confirms. */
    fun confirmClose(id: WindowId) = reduce { WorkspaceReducer.close(it, id) }

    private fun reopenLastClosed() {
        currentState.recentlyClosed.firstOrNull()?.let { route ->
            setState { copy(recentlyClosed = recentlyClosed.drop(1)) }
            open(route)
        }
    }

    private fun cycleFocus(offset: Int) {
        val ordered = currentState.orderedWindows
        if (ordered.size < 2) return
        val currentIndex = ordered.indexOfFirst { it.id == currentState.activeId }.coerceAtLeast(0)
        val next = ordered[(currentIndex + offset + ordered.size) % ordered.size]
        reduce { WorkspaceReducer.restore(it, next.id) }
    }

    private fun focusIndex(index: Int) {
        currentState.orderedWindows.getOrNull(index)?.let { window ->
            reduce { WorkspaceReducer.restore(it, window.id) }
        }
    }

    /**
     * Applies a reducer and persists the result.
     *
     * Persistence is skipped when the reducer returned the identical instance —
     * which is how re-focusing the frontmost window avoids a disk write on
     * every click.
     */
    private fun reduce(transform: (WorkspaceState) -> WorkspaceState) {
        val before = currentState
        val after = transform(before)
        if (after === before) return
        setState { after }
        launch { sessionStore.save(after) }
    }

    private companion object {
        const val DEFAULT_WORKSPACE_WIDTH = 1440
        const val DEFAULT_WORKSPACE_HEIGHT = 900
    }
}

/**
 * Persists the open window set across launches.
 *
 * The web app cannot do this — a refresh drops every window, and switching
 * project closes them all. Restoring the exact workspace is one of the clearer
 * reasons to have a desktop client at all (plan §3.2).
 */
interface WorkspaceSessionStore {
    suspend fun load(): WorkspaceState?
    suspend fun save(state: WorkspaceState)
    suspend fun clear()
}
