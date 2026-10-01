package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskFilter
import com.zillit.desktop.feature.tasks.domain.TaskLookup
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.isDirty
import com.zillit.desktop.feature.tasks.domain.subtasksOf as subtasksIn

/** The three views of the sidebar. */
enum class TasksView { Board, Mine, Self }

/** Who is looking, and the crew around them. Fed by the host from the project context. */
data class TaskPeople(val me: String? = null, val crew: List<TaskPerson> = emptyList())

/**
 * The task panel — a main task, a subtask, or the form for a new one.
 *
 * One [TaskPanel] per thing the panel is pointed at: the editable copy, a save
 * in flight and the comment box all belong to one task, and none of it may
 * leak into the next. A reply that arrives for a panel that has since been
 * replaced is dropped by comparing [key].
 */
data class TaskPanel(
    val key: Long,
    val creating: Boolean,
    val taskId: String? = null,
    /** The server's copy (edit mode). */
    val task: Task? = null,
    val orig: TaskDraft? = null,
    val draft: TaskDraft? = null,
    /** For a new subtask, its main task. */
    val parentId: String? = null,
    val parentTitle: String = "",
    val saving: Boolean = false,
    /** Closing, or moving to another task, was refused because of unsaved edits. */
    val nag: Boolean = false,
    /** 409: the version someone else saved first. */
    val conflict: Task? = null,
    val comment: String = "",
    val sending: Boolean = false,
    /** The task has been in the list at some point: leaving it means it is gone. */
    val seenInList: Boolean = false,
    /** One id per new task, reused if the create is retried, so a lost reply never makes two. */
    val uniqueId: String = "",
) {
    val ready: Boolean get() = draft != null && (creating || task != null)
    val dirty: Boolean get() = !creating && isDirty(orig, draft)
}

data class TasksUiState(
    val viewer: TasksViewer = TasksViewer(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val me: String? = null,
    val crew: List<TaskPerson> = emptyList(),
    /** Who the pickers may offer; null until the service has answered (then everyone). */
    val assignableIds: Set<String>? = null,
    val hodIds: Set<String> = emptySet(),
    val departments: List<TaskDepartment> = emptyList(),
    val view: TasksView = TasksView.Board,
    val boardFilter: TaskFilter = TaskFilter(),
    val mineQuery: String = "",
    val selfQuery: String = "",
    val panel: TaskPanel? = null,
    /** Tasks with a one-click write in flight. */
    val busy: Set<String> = emptySet(),
    /** A new self task is being added from the quick-add row. */
    val quickAdding: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canPost: Boolean get() = viewer.canPost
    // Looked up once per state, not once per row.
    val mainTasks: List<Task> by lazy { tasks.filter { !it.isSubtask } }
    val byId: Map<String, Task> by lazy { tasks.associateBy { it.id } }
    val crewById: Map<String, TaskPerson> by lazy { crew.associateBy { it.id } }
    val assignableCrew: List<TaskPerson>
        get() = assignableIds?.let { ids -> crew.filter { it.id in ids } } ?: crew

    fun departmentName(id: String?): String = departments.firstOrNull { it.id == id }?.name.orEmpty()

    fun subtasksOf(taskId: String?): List<Task> = subtasksIn(tasks, taskId)

    val lookup: TaskLookup get() = TaskLookup(crewById, ::departmentName)

    /** Open main tasks · open tasks assigned to me, subtasks included · my open main self tasks. */
    val counts: TaskCounts
        get() = TaskCounts(
            board = mainTasks.count { it.status.isOpen },
            mine = tasks.count { it.assigneeId == me && it.status.isOpen },
            self = mainTasks.count { it.isSelf && it.createdBy == me && it.status.isOpen },
        )
}

data class TaskCounts(val board: Int, val mine: Int, val self: Int)

sealed interface TasksEvent {
    /** Resolve the rights and load. Safe to send again. */
    data object Load : TasksEvent

    /** A silent reload: no spinner. */
    data object Refresh : TasksEvent
    data class ViewChanged(val view: TasksView) : TasksEvent
    data class BoardFilterChanged(val filter: TaskFilter) : TasksEvent
    data class MineQueryChanged(val query: String) : TasksEvent
    data class SelfQueryChanged(val query: String) : TasksEvent
    data object DismissMessage : TasksEvent

    // -- one-click writes, from a card, a row or the ⋯ menu --
    data class SetStatus(val task: Task, val status: TaskStatus) : TasksEvent
    data class Delete(val task: Task) : TasksEvent

    /** Share a self task (or take it back, with a null person) straight from its row. */
    data class Share(val taskId: String, val assigneeId: String?) : TasksEvent
    data class QuickAdd(val title: String, val dueDate: String?, val assigneeId: String?) : TasksEvent
    data object AskForRights : TasksEvent

    // -- the panel --
    data class OpenTask(val taskId: String) : TasksEvent
    data class NewTask(val seed: TaskDraft = TaskDraft()) : TasksEvent
    data class NewSubtask(val main: Task) : TasksEvent
    data object BackToParent : TasksEvent

    /** Close, or refuse while there are unsaved edits. */
    data object ClosePanel : TasksEvent

    /** Cancel a new task; a new subtask goes back to its main task. */
    data object CancelCreate : TasksEvent
    data class DraftChanged(val draft: TaskDraft) : TasksEvent

    /** The header button and the status buttons: into the editable copy. */
    data class PickStatus(val status: TaskStatus) : TasksEvent

    /** The ⋯ menu and the cancelled banner: saved at once, unless there are unsaved edits. */
    data class ChooseStatus(val status: TaskStatus) : TasksEvent
    data class Save(val overwrite: Boolean = false) : TasksEvent
    data object Create : TasksEvent
    data object Discard : TasksEvent
    data object LoadTheirs : TasksEvent
    data object DeleteOpenTask : TasksEvent
    data class CommentChanged(val text: String) : TasksEvent
    data object SendComment : TasksEvent
}
