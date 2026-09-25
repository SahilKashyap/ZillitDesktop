package com.zillit.desktop.core.workspace

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * State model for the multi-window workspace (plan §3).
 *
 * Ported from the web app's `WindowManagerContext.jsx`. Everything here is
 * serializable because the open set is persisted across launches — something
 * the web version cannot do, since a refresh drops every window.
 */

@Serializable
@JvmInline
value class WindowId(val value: String)

/**
 * What a window shows.
 *
 * `path` values match the web `toolRegistry` keys, so a deep link works
 * identically in both clients.
 */
@Serializable
sealed interface WorkspaceRoute {
    val path: String

    @Serializable
    data object Home : WorkspaceRoute {
        override val path: String get() = "/home"
    }

    @Serializable
    data class Tool(override val path: String, val unitId: String? = null) : WorkspaceRoute

    @Serializable
    data class Chat(val roomId: String, val isGroup: Boolean) : WorkspaceRoute {
        override val path: String get() = "/cnc/$roomId"
    }

    @Serializable
    data class EmailCompose(val draftId: String? = null) : WorkspaceRoute {
        override val path: String get() = "/email/compose/${draftId.orEmpty()}"
    }
}

/**
 * `Detached` has no web equivalent — it is a window torn off into a real OS
 * window (plan §3.2). Carrying it from the start means the reducer never has to
 * be retrofitted for multi-window.
 */
@Serializable
enum class WindowState { Normal, Minimized, Maximized, Detached }

/** `Tabs` = one maximized workspace; `Cascade` = free-floating MDI windows. */
@Serializable
enum class LayoutMode { Tabs, Cascade }

/**
 * The web's sidebar switch (`SideMenu.jsx` `toggleViewMode`, persisted as
 * `mdi_view_mode`): `Windowed` is the multi-window workspace with its tab
 * strip; `Classic` is full-page navigation — one tool at a time, and opening
 * another replaces it.
 */
@Serializable
enum class ViewMode { Windowed, Classic }

/** How a tool asks to open, mirroring the web registry's `openMode`. */
@Serializable
enum class OpenMode { Window, Maximized }

@Serializable
data class WindowRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * One open window.
 *
 * `history` is this window's private navigation stack — the equivalent of the
 * web app's per-window `MemoryRouter`. Navigating inside a window never touches
 * any other window, which is what makes "three tools open at once" actually
 * work.
 */
@Immutable
@Serializable
data class ToolWindow(
    val id: WindowId,
    val title: String,
    val iconKey: String,
    val state: WindowState = WindowState.Normal,
    val previousState: WindowState? = null,
    val rect: WindowRect? = null,
    val moved: Boolean = false,
    val zIndex: Int = 0,
    val history: List<WorkspaceRoute> = emptyList(),
    val isDirty: Boolean = false,
    val isPinned: Boolean = false,
) {
    /** The route currently displayed — the top of this window's own stack. */
    val route: WorkspaceRoute get() = history.last()

    /** The route the window opened with; its identity for dedupe and reuse. */
    val rootRoute: WorkspaceRoute get() = history.first()

    val canGoBack: Boolean get() = history.size > 1
    val isVisible: Boolean get() = state != WindowState.Minimized

    init {
        require(history.isNotEmpty()) { "a window must always have at least one route" }
    }
}

/**
 * The whole workspace.
 *
 * `mdiEnabled` mirrors the web app's Remote Config gate: when false the app
 * falls back to a single-window view and `openWindow` is a no-op (plan §3.1).
 */
@Immutable
@Serializable
data class WorkspaceState(
    val windows: List<ToolWindow> = emptyList(),
    val layoutMode: LayoutMode = LayoutMode.Tabs,
    val viewMode: ViewMode = ViewMode.Windowed,
    val mdiEnabled: Boolean = true,
    val recentlyClosed: List<WorkspaceRoute> = emptyList(),
) {
    /** The window shown in the workspace: the topmost non-minimized one. */
    val activeWindow: ToolWindow?
        get() = windows.filter { it.isVisible }.maxByOrNull { it.zIndex }

    val activeId: WindowId? get() = activeWindow?.id

    val hasDirtyWindows: Boolean get() = windows.any { it.isDirty }

    fun window(id: WindowId): ToolWindow? = windows.firstOrNull { it.id == id }

    /** Tab order: pinned first, then by open order. Mirrors browser behaviour. */
    val orderedWindows: List<ToolWindow>
        get() = windows.sortedWith(compareByDescending<ToolWindow> { it.isPinned }.thenBy { windows.indexOf(it) })

    internal companion object {
        const val MAX_RECENTLY_CLOSED = 10
    }
}
