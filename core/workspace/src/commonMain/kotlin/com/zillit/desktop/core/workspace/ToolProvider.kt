package com.zillit.desktop.core.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * How a feature module plugs into the workspace.
 *
 * This is the *only* way content enters a window. There is deliberately no
 * central `when (path)` dispatch — that switch is what forces every feature
 * team to edit the same file, and it is why the tool modules in the roadmap
 * (M8–M11) can be built in parallel by different people.
 *
 * The web equivalent is `toolRegistry.js`; [path] values match its keys so deep
 * links behave identically across web and desktop.
 */
interface ToolProvider {

    /** Route path this provider owns, e.g. `/film-tools/budget`. */
    val path: String

    val title: String

    /**
     * The window title for [route] — [title] unless the provider serves
     * several faces from one path, the way Home also serves the tools grid.
     * A tab named for the provider rather than what it shows reads as the
     * wrong window.
     */
    fun titleFor(route: WorkspaceRoute): String = title

    val icon: ImageVector

    /**
     * The icon for [route] — [icon] unless the provider serves several faces
     * from one path. Pairs with [titleFor]: a tab labelled "Film Tools" under
     * Home's house icon reads as the wrong window just as surely as a wrong
     * title does.
     */
    fun iconFor(route: WorkspaceRoute): ImageVector = icon

    /** Whether the tool opens filling the workspace or as a normal window. */
    val openMode: OpenMode get() = OpenMode.Window

    val defaultSize: DpSize get() = DpSize(1024.dp, 700.dp)

    /**
     * True for sub-applications that manage their own internal routing —
     * Account Hub, Sides, Settings, the Permission Grid. The host then hands
     * them the whole window rather than resolving sub-routes itself.
     */
    val hostsOwnRoutes: Boolean get() = false

    /** Live unread count shown on the tab. Null means no badge. */
    @Composable
    fun badge(): Int? = null

    @Composable
    fun Content(route: WorkspaceRoute, navigator: WindowNavigator)
}

/**
 * What a tool can do to its own window.
 *
 * Scoped to one window on purpose: a tool can navigate itself, rename its tab
 * or mark itself dirty, but it cannot reach across and disturb another window.
 */
interface WindowNavigator {
    val windowId: WindowId
    val canGoBack: Boolean

    fun navigate(route: WorkspaceRoute)
    fun back()

    /** Opens a route in a *different* window — "open in new tab". */
    fun openInNewWindow(route: WorkspaceRoute)

    fun setTitle(title: String)

    /** Marks unsaved changes, so closing the window prompts first. */
    fun setDirty(dirty: Boolean)

    fun close()
}

/**
 * Resolves a route to its provider.
 *
 * Populated by dependency injection from every feature module's `ToolProvider`
 * registration.
 */
class ToolRegistry(providers: List<ToolProvider>) {

    private val byPath: Map<String, ToolProvider> = providers.associateBy { it.path }

    val all: List<ToolProvider> = providers.sortedBy { it.title }

    /**
     * Longest-prefix match, so `/film-tools/budget/detail/42` resolves to the
     * `/film-tools/budget` provider without every tool having to enumerate its
     * sub-routes.
     */
    fun resolve(route: WorkspaceRoute): ToolProvider? =
        byPath[route.path] ?: byPath.entries
            .filter { route.path.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value

    fun isKnown(path: String): Boolean = byPath.containsKey(path)
}
