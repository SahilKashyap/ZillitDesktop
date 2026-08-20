package com.zillit.desktop.feature.permissiongrid.domain

/**
 * Which half of the production a right belongs to.
 *
 * A tool can appear on both the dashboard and the tools grid, and its rights
 * in each are separate rows written to separate URLs — the same split the
 * admin rights page keeps (`permissions/users/<section>/access`).
 */
enum class GridSection(val wire: String, val label: String) {
    Home("home", "Home"),
    Tools("tools", "Film Tools"),
}

/**
 * Who the grid grants to.
 *
 * The web calls the crew axis `crewlist` in its own state and `users` on the
 * wire; only the wire name travels, so that is what this carries. Each axis
 * identifies its subject by a different body key, which is the whole reason
 * this is an enum rather than a string — writing `user_id` for a department
 * row is accepted by nothing and silently grants no one.
 */
enum class GridAxis(val wire: String, val entityKey: String, val label: String) {
    Crew("users", "user_id", "People"),
    Departments("departments", "department_id", "Departments"),
    Designations("designations", "designation_id", "Designations"),
}

/** The three independent rights, as the write endpoint names them. */
enum class AccessKind(val wire: String, val label: String) {
    View("view", "View"),
    Post("post", "Post"),
    Download("download", "Download"),
}

/**
 * One subject — a person, a department or a designation.
 *
 * [department] and [designation] are only populated on the crew axis, where
 * the web shows them as their own frozen columns beside the name.
 */
data class GridSubject(
    val id: String,
    val name: String,
    val department: String? = null,
    val designation: String? = null,
)

/**
 * One cell: what this subject may do with one tool.
 *
 * The `*Locked` flags are the server saying this particular right is not this
 * admin's to change — the backend cascades some of them itself (Main Budget
 * Full → Department) and rejects a client that writes them directly. Only an
 * explicit `false` on the wire locks a right; absence means "changeable", or
 * every production whose server predates the flag would render read-only.
 */
data class GridCell(
    val unitId: String,
    val unitName: String,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val viewLocked: Boolean = false,
    val postLocked: Boolean = false,
    val downloadLocked: Boolean = false,
    /** A write is in flight for this cell; the boxes stay put until it lands. */
    val busy: Boolean = false,
) {
    fun granted(kind: AccessKind): Boolean = when (kind) {
        AccessKind.View -> canView
        AccessKind.Post -> canPost
        AccessKind.Download -> canDownload
    }

    fun locked(kind: AccessKind): Boolean = when (kind) {
        AccessKind.View -> viewLocked
        AccessKind.Post -> postLocked
        AccessKind.Download -> downloadLocked
    }

    fun granting(kind: AccessKind, enable: Boolean): GridCell = when (kind) {
        AccessKind.View -> copy(canView = enable)
        AccessKind.Post -> copy(canPost = enable)
        AccessKind.Download -> copy(canDownload = enable)
    }
}

/** One row: a subject and its cells, keyed by the tool's `unit_name`. */
data class GridRow(
    val subject: GridSubject,
    val cells: Map<String, GridCell>,
)

/**
 * One page of the grid.
 *
 * [columns] are `unit_name` keys in the order they should be shown; the
 * display title is the same key read through the label dictionary, which is
 * why the raw key is what travels.
 */
data class GridPage(
    val columns: List<String>,
    val rows: List<GridRow>,
    val total: Int,
) {
    companion object {
        val Empty = GridPage(emptyList(), emptyList(), 0)
    }
}
