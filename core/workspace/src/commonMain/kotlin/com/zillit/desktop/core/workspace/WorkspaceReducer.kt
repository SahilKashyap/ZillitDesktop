package com.zillit.desktop.core.workspace

/**
 * Window lifecycle: which windows exist, and which one is on top.
 *
 * Arrangement and navigation live in [WorkspaceLayoutReducer]. Both are pure,
 * so the whole window model is testable without a UI — the web equivalent is
 * only reachable by driving React, which is why its bugs (ZL-20352, ZL-20516)
 * surfaced in production rather than in tests.
 */
object WorkspaceReducer {

    /**
     * Opens [route], or focuses the window already showing it.
     *
     * Reuse re-navigates the existing window as well as raising it: a window is
     * identified by the route it *opened* with, but its own history may have
     * moved on since, and raising alone left the user looking at the wrong page
     * (web ZL-20516).
     */
    @Suppress("LongParameterList")
    fun open(
        state: WorkspaceState,
        id: WindowId,
        route: WorkspaceRoute,
        title: String,
        iconKey: String,
        openMode: OpenMode = OpenMode.Window,
        rect: WindowRect? = null,
    ): WorkspaceState {
        if (!state.mdiEnabled) return state

        state.windows.firstOrNull { it.rootRoute.path == route.path }?.let { existing ->
            return state.reuse(existing, route)
        }

        val window = ToolWindow(
            id = id,
            title = title,
            iconKey = iconKey,
            state = if (openMode == OpenMode.Maximized) WindowState.Maximized else WindowState.Normal,
            rect = rect,
            history = listOf(route),
            zIndex = state.windows.size,
        )
        return state.copy(windows = state.windows + window).raiseWindow(id)
    }

    fun close(state: WorkspaceState, id: WindowId): WorkspaceState {
        val closing = state.window(id) ?: return state
        return state.copy(
            windows = state.windows.filterNot { it.id == id },
            recentlyClosed = state.remember(listOf(closing.rootRoute)),
        )
    }

    /** Closes everything unpinned; pinned windows survive, as in a browser. */
    fun closeAll(state: WorkspaceState, includePinned: Boolean = false): WorkspaceState {
        val survivors = if (includePinned) emptyList() else state.windows.filter { it.isPinned }
        val closed = state.windows.filterNot { survivors.contains(it) }
        return state.copy(windows = survivors, recentlyClosed = state.remember(closed.map { it.rootRoute }))
    }

    fun closeOthers(state: WorkspaceState, keep: WindowId): WorkspaceState {
        val closed = state.windows.filter { it.id != keep && !it.isPinned }
        return state.copy(
            windows = state.windows.filter { it.id == keep || it.isPinned },
            recentlyClosed = state.remember(closed.map { it.rootRoute }),
        )
    }

    fun focus(state: WorkspaceState, id: WindowId): WorkspaceState = state.raiseWindow(id)

    fun minimize(state: WorkspaceState, id: WindowId): WorkspaceState = state.mapWindow(id) { window ->
        if (window.state == WindowState.Minimized) {
            window
        } else {
            window.copy(state = WindowState.Minimized, previousState = window.state)
        }
    }

    /** Brings a window back to whatever state it held before minimizing. */
    fun restore(state: WorkspaceState, id: WindowId): WorkspaceState = state
        .mapWindow(id) { it.copy(state = it.previousState ?: WindowState.Normal, previousState = null) }
        .raiseWindow(id)

    fun toggleMaximize(state: WorkspaceState, id: WindowId): WorkspaceState = state
        .mapWindow(id) { window ->
            if (window.state == WindowState.Maximized) {
                // Also drop `moved`, so restoring returns to the compact cascade
                // default rather than whatever oversized rect the window was
                // dragged to before maximizing — otherwise "restore" looks like
                // it did nothing.
                window.copy(state = WindowState.Normal, moved = false)
            } else {
                window.copy(state = WindowState.Maximized, previousState = window.state)
            }
        }
        .raiseWindow(id)

    fun togglePin(state: WorkspaceState, id: WindowId): WorkspaceState =
        state.mapWindow(id) { it.copy(isPinned = !it.isPinned) }

    fun setDirty(state: WorkspaceState, id: WindowId, dirty: Boolean): WorkspaceState =
        state.mapWindow(id) { it.copy(isDirty = dirty) }

    fun setTitle(state: WorkspaceState, id: WindowId, title: String): WorkspaceState =
        state.mapWindow(id) { it.copy(title = title) }

    private fun WorkspaceState.reuse(existing: ToolWindow, route: WorkspaceRoute): WorkspaceState =
        mapWindow(existing.id) { window ->
            window.copy(
                state = if (window.state == WindowState.Minimized) {
                    window.previousState ?: WindowState.Normal
                } else {
                    window.state
                },
                history = if (window.route.path == route.path) window.history else window.history + route,
            )
        }.raiseWindow(existing.id)
}
