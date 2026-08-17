package com.zillit.desktop.core.workspace

/**
 * Arrangement and per-window navigation.
 *
 * Split from [WorkspaceReducer] because the two answer different questions —
 * "which windows exist" versus "how are they laid out and where is each one".
 */
object WorkspaceLayoutReducer {

    // -- per-window navigation --------------------------------------------
    //
    // Each window owns a private history — the equivalent of the web app's
    // per-window MemoryRouter. Navigating in one window never touches another,
    // which is what makes several tools open at once actually usable.

    fun navigate(state: WorkspaceState, id: WindowId, route: WorkspaceRoute): WorkspaceState =
        state.mapWindow(id) { window ->
            if (window.route.path == route.path) window else window.copy(history = window.history + route)
        }

    fun back(state: WorkspaceState, id: WindowId): WorkspaceState = state.mapWindow(id) { window ->
        if (window.canGoBack) window.copy(history = window.history.dropLast(1)) else window
    }

    // -- geometry ----------------------------------------------------------

    fun updateRect(state: WorkspaceState, id: WindowId, rect: WindowRect): WorkspaceState =
        state.mapWindow(id) { window ->
            if (window.state == WindowState.Maximized) window else window.copy(rect = rect, moved = true)
        }

    /** Tears a window out into its own OS window, or docks it back. */
    fun setDetached(state: WorkspaceState, id: WindowId, detached: Boolean): WorkspaceState =
        state.mapWindow(id) { window ->
            when {
                detached && window.state != WindowState.Detached ->
                    window.copy(state = WindowState.Detached, previousState = window.state)

                !detached && window.state == WindowState.Detached ->
                    window.copy(state = window.previousState ?: WindowState.Normal, previousState = null)

                else -> window
            }
        }

    // -- layout ------------------------------------------------------------

    /**
     * Switching modes un-maximizes everything.
     *
     * Most tools open maximized, so without this every window would still fill
     * the workspace in cascade mode and simply overlap — which reads as "the
     * button did nothing".
     */
    fun toggleLayoutMode(state: WorkspaceState): WorkspaceState = state.copy(
        layoutMode = if (state.layoutMode == LayoutMode.Tabs) LayoutMode.Cascade else LayoutMode.Tabs,
        windows = state.windows.map { window ->
            if (window.state == WindowState.Maximized) window.copy(state = WindowState.Normal) else window
        },
    )

    fun reorder(state: WorkspaceState, from: Int, to: Int): WorkspaceState {
        val ordered = state.orderedWindows
        if (from !in ordered.indices || to !in ordered.indices || from == to) return state
        val mutable = ordered.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return state.copy(windows = mutable)
    }

    /**
     * Cascade geometry for a newly opened window: clamped to the workspace,
     * docked bottom-right like a Gmail compose window, then offset diagonally
     * so stacked windows do not perfectly overlap. Ported from the web
     * `defaultRect`.
     */
    fun defaultRect(index: Int, workspaceWidth: Int, workspaceHeight: Int): WindowRect {
        val width = minOf(DEFAULT_WIDTH, (workspaceWidth * WIDTH_RATIO).toInt())
        val height = minOf(DEFAULT_HEIGHT, (workspaceHeight * HEIGHT_RATIO).toInt())
        val offset = (index % CASCADE_STEPS) * CASCADE_OFFSET
        return WindowRect(
            x = maxOf(EDGE_INSET, workspaceWidth - width - CASCADE_OFFSET - offset),
            y = maxOf(EDGE_INSET, workspaceHeight - height - BOTTOM_INSET - offset),
            width = width,
            height = height,
        )
    }

    private const val DEFAULT_WIDTH = 1024
    private const val DEFAULT_HEIGHT = 700
    private const val WIDTH_RATIO = 0.94
    private const val HEIGHT_RATIO = 0.90
    private const val CASCADE_STEPS = 6
    private const val CASCADE_OFFSET = 28
    private const val EDGE_INSET = 12
    private const val BOTTOM_INSET = 20
}
