@file:Suppress("MaxLineLength") // Models.

package com.zillit.desktop.feature.tasks.domain

/**
 * The four states a task can be in.
 *
 * The Board shows three columns, not four: a cancelled task has no column of
 * its own and sits at the bottom of Done (see [boardColumn]). Use [isOpen] and
 * [isClosed] rather than comparing against `Done`.
 */
enum class TaskStatus(val wire: String) {
    Todo("todo"),
    Progress("progress"),
    Done("done"),
    Cancelled("cancelled"),
    ;

    val isOpen: Boolean get() = this == Todo || this == Progress
    val isClosed: Boolean get() = !isOpen

    companion object {
        /** The Board's columns, and the status buttons in the panel. */
        val board: List<TaskStatus> = listOf(Todo, Progress, Done)

        /** A status this build does not know reads as To do rather than dropping the task. */
        fun fromWire(value: String?): TaskStatus = entries.firstOrNull { it.wire == value } ?: Todo
    }
}

/** Declared high-first: the order [compareTasks] sorts by. */
enum class TaskPriority(val wire: String) {
    High("high"),
    Med("med"),
    Low("low"),
    ;

    companion object {
        fun fromWire(value: String?): TaskPriority = entries.firstOrNull { it.wire == value } ?: Med
    }
}

/**
 * One task, or one subtask — a subtask is a full task whose [parentId] names
 * its main task (one level only). The list the service returns mixes both.
 *
 * People are **ProjectUser ids**, the same id the crew list carries as
 * `user_id`. [dueDate] is a calendar day (`"2026-10-06"`), not an instant, so
 * it reads the same in every timezone.
 */
data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val departmentId: String? = null,
    val scenes: String = "",
    val dueDate: String? = null,
    val priority: TaskPriority = TaskPriority.Med,
    val status: TaskStatus = TaskStatus.Todo,
    val assigneeId: String? = null,
    val isSelf: Boolean = false,
    val parentId: String? = null,
    /** What the service sends for a subtask whose main task the caller cannot see. */
    val parentTitle: String = "",
    val createdBy: String? = null,
    val assignedBy: String? = null,
    val assignedAtMillis: Long = 0,
    val createdMillis: Long = 0,
    /** Echoed back as `expected_updated`, so a save is refused when someone else saved first. */
    val updatedMillis: Long = 0,
    val commentCount: Int = 0,
    /** Worked out on the server from `hod_access` / `admin_access`; never guessed here. */
    val canDelete: Boolean = false,
    val canReassign: Boolean = false,
    /** Present only on a single-task read; the list's copy has neither. */
    val comments: List<TaskComment>? = null,
    val history: List<TaskHistoryEntry>? = null,
) {
    val isSubtask: Boolean get() = parentId != null
}

data class TaskComment(
    val id: String,
    val senderId: String?,
    val text: String,
    val mentions: List<String> = emptyList(),
    val createdMillis: Long = 0,
)

/**
 * One line of a task's history, newest first.
 *
 * [action] is `created`, `updated`, `subtask_added` or `subtask_deleted`.
 * [reason] marks a change the server made by itself: `main_cancelled` and
 * `subtask_open`.
 */
data class TaskHistoryEntry(
    val id: String,
    val userId: String?,
    /** The name stored with the entry, kept for someone who has since left. */
    val userName: String,
    val action: String,
    val changes: List<TaskChange> = emptyList(),
    val assigneeId: String? = null,
    val subtaskTitle: String = "",
    val reason: String? = null,
    val createdMillis: Long = 0,
)

data class TaskChange(val field: String, val from: String?, val to: String?)

/** A person a task can be assigned to or @mentioned. */
data class TaskPerson(
    val id: String,
    val fullName: String,
    val designation: String = "",
    val department: String = "",
) {
    /** "Name (Designation)" — one line of text for labels and tooltips. */
    val nameRole: String get() = if (designation.isNotBlank()) "$fullName ($designation)" else fullName
}

data class TaskDepartment(val id: String, val name: String)

/** `GET /v2/tasks/assignees`: who can be assigned, and which of them head a department. */
data class TaskAssignees(val userIds: Set<String>, val hodIds: Set<String>)

/** What the Tasks tool lets this viewer do, from the tool's own row. */
data class TasksViewer(
    /** The rights list has answered. Until it has, nothing may call the service. */
    val resolved: Boolean = false,
    val enabled: Boolean = false,
    val canView: Boolean = false,
    val canPost: Boolean = false,
) {
    /**
     * Whether a request may go out at all. Deny until confirmed: the service
     * answers `403 tasks_tool_not_enabled` for a production without the tool,
     * and a 403 anywhere in this app is read as "no longer a member".
     */
    val canCall: Boolean get() = resolved && enabled && canView

    companion object {
        const val TOOL = "tasks_tool"

        /**
         * From the tool's row **as issued**, with no admin bypass: the service
         * decides on `view_access` / `posting_access` alone, and a write it
         * refuses with a 403 would read as "no longer a member".
         */
        fun from(permissions: com.zillit.desktop.core.permissions.ProjectPermissions): TasksViewer {
            // An empty list is "not answered yet", not a denial.
            if (permissions.tools.isEmpty()) return TasksViewer()
            val row = permissions.tools.firstOrNull { it.identifier == TOOL }
            return TasksViewer(
                resolved = true,
                enabled = row?.enabled == true,
                canView = row?.canView == true,
                canPost = row?.canPost == true,
            )
        }
    }
}

/** The service's own answer to one write: the task, or why not. */
sealed interface TaskWrite {
    data class Saved(val task: Task) : TaskWrite

    /** 409: someone else saved first. [latest] is their version. */
    data class Conflict(val latest: Task) : TaskWrite

    /** Refused or failed; [message] is the server's message key, or null for a transport failure. */
    data class Refused(val message: String?, val status: Int, val error: com.zillit.desktop.core.common.ZillitError) : TaskWrite {
        val isGone: Boolean get() = status == NOT_FOUND
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}
