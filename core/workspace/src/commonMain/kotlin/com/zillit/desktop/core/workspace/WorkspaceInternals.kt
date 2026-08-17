package com.zillit.desktop.core.workspace

/**
 * Shared state helpers for the reducers.
 *
 * Both helpers return the **identical instance** when nothing changed. That is
 * load-bearing, not an optimisation: `WorkspaceState` drives a `StateFlow`, and
 * an equal-but-new instance still invalidates every window's composition. The
 * web version rebuilt its window array on every mousedown and wiped
 * in-progress form input as a result (ZL-20352).
 */

internal fun WorkspaceState.raiseWindow(id: WindowId): WorkspaceState {
    val target = window(id) ?: return this
    if (windows.all { it.zIndex <= target.zIndex }) return this

    val reordered = windows.filter { it.id != id }.sortedBy { it.zIndex } + target
    val depthById = reordered.withIndex().associate { (index, window) -> window.id to index }
    return copy(windows = windows.map { it.copy(zIndex = depthById.getValue(it.id)) })
}

internal inline fun WorkspaceState.mapWindow(
    id: WindowId,
    transform: (ToolWindow) -> ToolWindow,
): WorkspaceState {
    val current = window(id) ?: return this
    val updated = transform(current)
    if (updated == current) return this
    return copy(windows = windows.map { if (it.id == id) updated else it })
}

internal fun WorkspaceState.remember(closed: List<WorkspaceRoute>): List<WorkspaceRoute> =
    (closed + recentlyClosed).take(WorkspaceState.MAX_RECENTLY_CLOSED)
