package com.zillit.desktop.feature.permissiongrid.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Which half of the production a right belongs to — the web's first select.
 *
 * A tool can appear on both the dashboard and the tools grid, and its rights
 * in each are separate rows written to separate URLs. The web opens on Home.
 */
enum class GridSection(val wire: String, private val labelKey: String) {
    Home("home", S.home),
    Tools("tools", S.tools),
    ;

    val label: String get() = str(labelKey)
}

/**
 * Who the grid grants to — the web's second select, in its order.
 *
 * Two of the four are people: **User Permissions** reads `users` and sorts by
 * name, **Based on Crew List** reads `crewlist`, keeps the crew list's own
 * order and is where the web opens. Both write to `users` (ZL-16966); only the
 * read differs. Each axis identifies its subject by a different body key,
 * which is the whole reason this is an enum rather than a string — writing
 * `user_id` for a department row is accepted by nothing and grants no one.
 */
enum class GridAxis(
    val readWire: String,
    val writeWire: String,
    val entityKey: String,
    private val labelKey: String,
) {
    Users("users", "users", "user_id", S.desktop_pg_user_permissions),
    CrewList("crewlist", "users", "user_id", S.desktop_pg_based_on_crew_list),
    Departments("departments", "departments", "department_id", S.desktop_pg_department_permissions),
    Designations("designations", "designations", "designation_id", S.desktop_pg_designation_permissions),
    ;

    val label: String get() = str(labelKey)

    /** The two people axes share a subject: a user, keyed by `user_id`. */
    val isPeople: Boolean get() = this == Users || this == CrewList
}

/**
 * The designations axis's third select: every designation on the production,
 * or only the ones somebody hired holds. The web defaults to the hired ones
 * and sends `only_used_designation=1` for them — and nothing at all for "all".
 */
enum class DesignationFilter(private val labelKey: String, private val hintKey: String) {
    All(S.desktop_pg_all_designations, S.desktop_pg_all_designations_hint),
    Used(S.desktop_pg_hired_crew_designations, S.desktop_pg_hired_designations_hint),
    ;

    val label: String get() = str(labelKey)
    val hint: String get() = str(hintKey)
}

/**
 * The three independent rights, in the order the web stacks them in a cell —
 * Viewing, Download, Posting (web `6e43d23e5`).
 */
enum class AccessKind(val wire: String, private val labelKey: String) {
    View("view", S.txt_viewing),
    Download("download", S.download),
    Post("post", S.txt_posting),
    ;

    val label: String get() = str(labelKey)
}

/**
 * A frozen column on the left of the grid, named by the server's `headers`.
 *
 * `user_label` is the person — face, name, Admin chip, job title beneath;
 * the other two are plain text. Which ones a page carries, and in what order,
 * is the server's call: the crew axis sends `user_label, department_label`.
 */
enum class SubjectColumn(val header: String) {
    User("user_label"),
    Department("department_label"),
    Designation("designation_label"),
    ;

    companion object {
        fun of(header: String): SubjectColumn? = entries.firstOrNull { it.header == header }

        /** What a page that names none of them shows: the axis's own subject. */
        fun defaultFor(axis: GridAxis): List<SubjectColumn> = when (axis) {
            GridAxis.Users, GridAxis.CrewList -> listOf(User, Department)
            GridAxis.Departments -> listOf(Department)
            GridAxis.Designations -> listOf(Designation)
        }
    }
}

/**
 * One subject — a person, a department or a designation.
 *
 * [department] and [designation] are display text, already localised, and
 * populated whenever the row carries them: the frozen columns read them on
 * every axis, and a designation row names its department too.
 */
data class GridSubject(
    val id: String,
    val name: String,
    val department: String? = null,
    val designation: String? = null,
    /** `admin_access` — an administrator, whose boxes the grid will not move. */
    val isAdmin: Boolean = false,
)

/**
 * One cell: what this subject may do with one tool.
 *
 * The `*Locked` flags are the server saying a right is not this admin's to
 * change — the backend cascades some of them itself (Main Budget Full →
 * Department, ZL-17812) and rejects a client that writes them directly. The
 * web reads them unevenly and so does this: View and Download lock only on an
 * explicit `viewingUpdatable`/`downloadUpdatable: false`, while Posting is
 * open only on an explicit `postingUpdatable: true`.
 *
 * The busy flags are per right, as on the web: only the box being written is
 * held, not its two neighbours.
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
    val busyView: Boolean = false,
    val busyPost: Boolean = false,
    val busyDownload: Boolean = false,
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

    fun busy(kind: AccessKind): Boolean = when (kind) {
        AccessKind.View -> busyView
        AccessKind.Post -> busyPost
        AccessKind.Download -> busyDownload
    }

    fun granting(kind: AccessKind, enable: Boolean): GridCell = when (kind) {
        AccessKind.View -> copy(canView = enable)
        AccessKind.Post -> copy(canPost = enable)
        AccessKind.Download -> copy(canDownload = enable)
    }

    fun holding(kind: AccessKind, busy: Boolean): GridCell = when (kind) {
        AccessKind.View -> copy(busyView = busy)
        AccessKind.Post -> copy(busyPost = busy)
        AccessKind.Download -> copy(busyDownload = busy)
    }

    /**
     * This cell as the sync event leaves it: present flags land, absent ones
     * keep their value, and every in-flight flag clears — the server's ack is
     * the authoritative state a pending write was waiting for.
     */
    fun syncedWith(sync: RightsSync): GridCell = copy(
        canView = sync.view ?: canView,
        canPost = sync.post ?: canPost,
        canDownload = sync.download ?: canDownload,
        viewLocked = sync.viewUnlocked?.not() ?: viewLocked,
        postLocked = sync.postUnlocked?.not() ?: postLocked,
        downloadLocked = sync.downloadUnlocked?.not() ?: downloadLocked,
        busyView = false,
        busyPost = false,
        busyDownload = false,
    )
}

/** One row: a subject and its cells, keyed by the tool's `unit_name`. */
data class GridRow(
    val subject: GridSubject,
    val cells: Map<String, GridCell>,
) {
    /**
     * Whether the box for [kind] on [unitName] shows ticked.
     *
     * Its own flag, except on the department budget, which also shows ticked
     * when Main Budget grants the right — the web's ZL-17812 override: the
     * server locks Department while Main grants it, and a locked, unticked box
     * reads as "not granted" when the right is held through Main.
     */
    fun shownGranted(unitName: String, kind: AccessKind): Boolean {
        val own = cells[unitName]?.granted(kind) == true
        if (unitName != DEPARTMENT_BUDGET) return own
        val main = cells[MAIN_BUDGET] ?: return own
        val throughMain = when (kind) {
            AccessKind.View -> main.canView
            AccessKind.Post -> main.canView && main.canPost
            AccessKind.Download -> main.canDownload
        }
        return own || throughMain
    }

    /**
     * Whether the box for [kind] on [unitName] can be pressed, before the
     * reader's own right to edit the grid is considered.
     *
     * An administrator's boxes are read-only — an admin passes every check
     * already — bar Transportation posting, which is still theirs to be given.
     * Account Hub never moves: its access follows department and designation
     * (ZL-20803), so a box here could only disagree with the tool.
     */
    fun editable(unitName: String, kind: AccessKind): Boolean {
        val cell = cells[unitName] ?: return false
        val adminLocked = subject.isAdmin && !(kind == AccessKind.Post && unitName == TRANSPORTATION)
        return !cell.busy(kind) && !cell.locked(kind) && !isAccountHub(unitName) && !adminLocked
    }

    companion object {
        const val MAIN_BUDGET = "main_budget_label"
        const val DEPARTMENT_BUDGET = "department_budget_label"
        const val TRANSPORTATION = "transportation_label"
        const val DEAL_MEMO = "deal_memo_label"

        /** The web normalises the spelling rather than guess one (`account_hub_label`, `account_hub_tool`). */
        fun isAccountHub(unitName: String): Boolean =
            unitName.replace(Regex("_(label|tool)$"), "") == "account_hub"
    }
}

/**
 * One page of the grid.
 *
 * [columns] are `unit_name` keys in the order they should be shown; the
 * display title is the same key read through the label dictionary, which is
 * why the raw key is what travels. [subjects] are the frozen columns before
 * them.
 */
data class GridPage(
    val columns: List<String>,
    val rows: List<GridRow>,
    val total: Int,
    val subjects: List<SubjectColumn> = emptyList(),
) {
    /**
     * The page with one server-announced change folded in — the web's
     * `handleAccessGridSync` (`AccessGrid.jsx`, ZL-17812). Rows are matched
     * by the subject's id, which the event names as `user_id`: on the
     * department and designation axes nothing matches and the page is
     * returned untouched, exactly as the web's row filter behaves.
     */
    fun syncedWith(sync: RightsSync): GridPage {
        val merged = rows.map { row ->
            if (row.subject.id != sync.userId) return@map row
            val cell = row.cells[sync.unitName] ?: return@map row
            row.copy(cells = row.cells + (sync.unitName to cell.syncedWith(sync)))
        }
        return if (merged == rows) this else copy(rows = merged)
    }

    /** The page with one cell changed, by subject and tool. */
    fun changing(subjectId: String, unitName: String, change: (GridCell) -> GridCell): GridPage =
        copy(
            rows = rows.map { row ->
                if (row.subject.id != subjectId) return@map row
                val cell = row.cells[unitName] ?: return@map row
                row.copy(cells = row.cells + (unitName to change(cell)))
            },
        )

    companion object {
        val Empty = GridPage(emptyList(), emptyList(), 0)
    }
}

/**
 * The read-only Default Grid (web `Defaultgrid.jsx`): what each department or
 * designation is granted by default, every tool split into Viewing, Download
 * and Posting.
 *
 * [first] is the server's first header — the subject column's title key.
 */
data class DefaultGridPage(
    val first: String,
    val columns: List<String>,
    val rows: List<DefaultGridRow>,
) {
    companion object {
        val Empty = DefaultGridPage("department_label", emptyList(), emptyList())
    }
}

/**
 * One department or designation and its default rights, keyed by `unit_name`
 * in the order the row sent them — which is the Excel export's column order.
 */
data class DefaultGridRow(
    val key: String,
    val name: String,
    val cells: List<GridCell>,
) {
    val byUnit: Map<String, GridCell> by lazy { cells.associateBy { it.unitName } }
}

/**
 * One `access-grid:*-rights:update:sync` event: the server's word on one
 * subject's rights for one tool. Every flag is nullable because the payload
 * carries only what changed — absence keeps the cell's current value, the
 * web's `pick` helper.
 */
data class RightsSync(
    val projectId: String? = null,
    val userId: String,
    val unitName: String,
    val view: Boolean? = null,
    val post: Boolean? = null,
    val download: Boolean? = null,
    /** `viewingUpdatable` and siblings — true means the right is editable. */
    val viewUnlocked: Boolean? = null,
    val postUnlocked: Boolean? = null,
    val downloadUnlocked: Boolean? = null,
)
