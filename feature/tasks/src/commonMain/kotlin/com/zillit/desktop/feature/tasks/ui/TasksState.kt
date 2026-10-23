package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskFilter
import com.zillit.desktop.feature.tasks.domain.TaskLookup
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.formHasContent
import com.zillit.desktop.feature.tasks.domain.formSig
import com.zillit.desktop.feature.tasks.domain.isDirty
import com.zillit.desktop.feature.tasks.domain.subtasksOf as subtasksIn

/**
 * The tool's views, as the tab bar lists them: Board · Assigned · Self ·
 * Drafts. [Drafts] is only ever reachable where the service keeps drafts and
 * the viewer may post ([TasksUiState.draftsOn]).
 */
enum class TasksView { Board, Mine, Self, Drafts }

/**
 * The "To Me | To Other" tab on Assigned Tasks and on Drafts. Remembered per
 * view while the tool is open, as the web's `scope` is.
 */
enum class TaskScope { Me, Other }

/**
 * Who is looking, and the crew around them. Fed by the host from the project
 * context, with the open production's id, so a socket frame for another
 * production is ignored.
 */
data class TaskPeople(val me: String? = null, val crew: List<TaskPerson> = emptyList(), val projectId: String? = null)

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
    /** Which of the two tabs at the foot of the panel is open; a new task has neither. */
    val tab: PanelTab = PanelTab.Comments,
    /** A new task opened from "Assign to someone" starts with the crew picker open. */
    val pickAssignee: Boolean = false,

    // -- a new task: Public / Private, and the draft behind it ---------------

    /**
     * A new MAIN task has no Public / Private until its creator picks one: the
     * form's [TaskDraft.visibility] is only a placeholder meanwhile. A subtask
     * follows its main task, and a saved draft that already chose one opens
     * with it.
     */
    val visPending: Boolean = false,
    /**
     * How many Creates were pressed without a choice. Each one outlines the
     * control in amber and brings it into view, so a second press says it
     * again rather than nothing.
     */
    val visNag: Int = 0,
    /** The saved draft this form is, if any. */
    val draftId: String? = null,
    /** False while that draft is still being read (its form is not on screen yet). */
    val draftReady: Boolean = true,
    /** The draft's own images and files, as the service has them. */
    val draftFiles: List<TaskAttachment> = emptyList(),
    /**
     * Ones taken out of the form, by id. They leave the draft only on Save
     * draft or just before Create, so "Don't save" keeps them.
     */
    val droppedFiles: List<String> = emptyList(),
    /** Delete draft was pressed once: the next press deletes. */
    val deleteArmed: Boolean = false,
    /** What the form looked like when it opened, or was last saved as a draft. */
    val savedSig: String = "",
    /**
     * Set once this form has been let go — saved as a draft, discarded or
     * created: there is nothing left to ask before the move that follows.
     */
    val released: Boolean = false,

    // -- images and files in the description --------------------------------

    /**
     * Prepared and waiting for Create.
     *
     * Only ever non-empty while [creating]: on a task that exists each one
     * goes up as it is added, so there is nothing to wait.
     */
    val pending: List<PreparedAttachment> = emptyList(),
    /** How many are going up right now — one "Uploading…" tile each. */
    val uploading: Int = 0,
    /**
     * The bytes of each attachment that has been fetched, by id.
     *
     * Held here rather than decoded per row: a thumbnail would otherwise
     * re-fetch its signed link on every recomposition of the panel.
     */
    val imageBytes: Map<String, ByteArray> = emptyMap(),
    /**
     * Attachments whose image could not be fetched, even after a fresh link.
     *
     * Separate from "not fetched yet", because a row that failed must say so
     * rather than ask again for ever.
     */
    val imagesFailed: Set<String> = emptySet(),
    /** The image open in the full-size viewer, by id; null when it is closed. */
    val viewing: String? = null,
    /**
     * A download link just fetched, for the host to open once.
     *
     * The service's `?download=1` link is what saves the file under its own
     * name; the view model has no way to the OS, so the panel opens it and
     * clears this.
     */
    val openUrl: String? = null,
) {
    val ready: Boolean get() = draft != null && (if (creating) draftReady else task != null)

    /** The task's own images and files, in the order the service keeps them. */
    val attachments: List<TaskAttachment> get() = task?.attachments.orEmpty()

    val images: List<TaskAttachment> get() = attachments.filter { it.isImage }

    val files: List<TaskAttachment> get() = attachments.filterNot { it.isImage }

    /** The draft's images and files still in the form: they go with Create, or stay with Save draft. */
    val keptDraftFiles: List<TaskAttachment> get() = draftFiles.filterNot { it.id in droppedFiles }

    /**
     * The keys of everything the form holds that a draft would keep: the saved
     * draft's own images and files, then the ones waiting in the browser.
     */
    val fileKeys: List<String>
        get() = keptDraftFiles.map { DRAFT_FILE_PREFIX + it.id } + pending.map { it.key }

    /**
     * A new task with something in it, changed since it opened or was last
     * saved as a draft — the only kind that is worth asking about on the way out.
     */
    val started: Boolean
        get() = creating && ready && formHasContent(draft, fileKeys.size, draftId) &&
            formSig(draft, visPending, fileKeys) != savedSig

    /**
     * Public / Private still to choose: a new MAIN task, where the service
     * keeps the field. A subtask follows its main task, so there is nothing to
     * choose for one.
     */
    fun visUnchosen(features: TasksFeatures): Boolean =
        creating && features.visibility && visPending && draft?.parentId == null

    /** A saved draft's own images, still in the form. */
    val keptDraftImages: List<TaskAttachment> get() = keptDraftFiles.filter { it.isImage }

    /** A saved draft's own files, still in the form. */
    val keptDraftDocs: List<TaskAttachment> get() = keptDraftFiles.filterNot { it.isImage }

    /** What a new task's description shows under the box until Create. */
    val pendingImages: List<PreparedAttachment> get() = pending.filter { it.kind == AttachmentKind.Image }

    val pendingFiles: List<PreparedAttachment> get() = pending.filter { it.kind == AttachmentKind.File }

    /**
     * Whether there are unsaved edits. [features] is what the service can
     * keep: a field it cannot store is never a change, so it never makes the
     * save bar appear.
     */
    fun dirty(features: TasksFeatures): Boolean = !creating && isDirty(orig, draft, features)
}

/**
 * What a saved draft's own image or file is keyed by in the form, so the one
 * waiting in the browser and the one the draft already holds can share a list.
 */
const val DRAFT_FILE_PREFIX = "draft:"

/** The two tabs at the bottom of the panel. Every task opens on [Comments]. */
enum class PanelTab { Comments, History }

/**
 * "Save this task as a draft?" — asked before a started new task is let go,
 * and drawn over the panel (the web's `LeavePrompt`).
 *
 * [fromDraft] words it as "Save changes to your draft?" instead. [then] is
 * what the user was doing when the question came up (open another task, close
 * the tool…): it runs once the question is answered, and a bare close has none.
 */
data class LeaveAsk(val fromDraft: Boolean, val then: TasksEvent? = null)

/**
 * The "Create Task" popup: what kind of task this is to be. [status] is the
 * Board column whose + opened it, kept so the new task starts in that column;
 * null from the tab bar's own button.
 */
data class TaskChooser(val status: TaskStatus? = null)

data class TasksUiState(
    val viewer: TasksViewer = TasksViewer(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /**
     * What this deployment of the service can keep. All are off until a list
     * has answered, and **off means gone**: no Visibility row, tag or menu
     * item without [TasksFeatures.visibility]; no Save draft, Drafts tab or
     * "Save this task as a draft?" without [TasksFeatures.drafts].
     */
    val features: TasksFeatures = TasksFeatures(),
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
    /** My drafts, newest first; only ever mine, because nobody else sees one. */
    val drafts: List<SavedTaskDraft> = emptyList(),
    /** A drafts read has answered at least once. */
    val draftsLoaded: Boolean = false,
    /** "To Me | To Other" on Assigned Tasks. */
    val mineScope: TaskScope = TaskScope.Me,
    /** "To Me | To Other" on Drafts. */
    val draftsScope: TaskScope = TaskScope.Me,
    /** The Drafts tab decided for itself once which tab to open on (the web's `decided`). */
    val draftsScopeDecided: Boolean = false,
    /** "Save this task as a draft?", over the panel; null when nothing is asked. */
    val leaveAsk: LeaveAsk? = null,
    val panel: TaskPanel? = null,
    /** The "Create Task" popup; it never opens while the panel is open. */
    val chooser: TaskChooser? = null,
    /** Tasks with a one-click write in flight. */
    val busy: Set<String> = emptySet(),
    /**
     * Board cards dropped on another column, with the status they were dropped
     * on: the card waits there while the write is in flight, and the entry
     * goes when the server answers (saved or refused).
     */
    val moving: Map<String, TaskStatus> = emptyMap(),
    /** A new self task is being added from the quick-add row. */
    val quickAdding: Boolean = false,
    /** Goes up each time a quick add is saved, so the row clears what was typed only then. */
    val quickAdded: Int = 0,
    val error: String? = null,
    /** A rule the client checked before sending anything ("2 subtasks are still open"). */
    val notice: String? = null,
    /** What the server announced for a write it saved, as the web toasts it. */
    val success: String? = null,
) {
    val canPost: Boolean get() = viewer.canPost

    /**
     * Whether drafts are offered at all: the service keeps them and this
     * viewer may create tasks. Off means gone — no Drafts tab, no Save draft
     * and no question on the way out.
     */
    val draftsOn: Boolean get() = viewer.canCall && viewer.canPost && features.drafts

    /** Whether the open panel has unsaved edits, judged against what the service keeps. */
    val panelDirty: Boolean get() = panel?.dirty(features) == true
    // Looked up once per state, not once per row.
    val mainTasks: List<Task> by lazy { tasks.filter { !it.isSubtask } }
    val byId: Map<String, Task> by lazy { tasks.associateBy { it.id } }
    val crewById: Map<String, TaskPerson> by lazy { crew.associateBy { it.id } }
    val assignableCrew: List<TaskPerson>
        get() = assignableIds?.let { ids -> crew.filter { it.id in ids } } ?: crew

    fun departmentName(id: String?): String = departments.firstOrNull { it.id == id }?.name.orEmpty()

    fun subtasksOf(taskId: String?): List<Task> = subtasksIn(tasks, taskId)

    val lookup: TaskLookup get() = TaskLookup(crewById, ::departmentName)

    /**
     * Open main tasks · open tasks assigned to me, subtasks included · my open
     * main self tasks · how many drafts I have.
     */
    val counts: TaskCounts
        get() = TaskCounts(
            board = mainTasks.count { it.status.isOpen },
            mine = tasks.count { it.assigneeId == me && it.status.isOpen },
            self = mainTasks.count { it.isSelf && it.createdBy == me && it.status.isOpen },
            drafts = drafts.size,
        )
}

data class TaskCounts(val board: Int, val mine: Int, val self: Int, val drafts: Int = 0)

sealed interface TasksEvent {
    /** Resolve the rights and load. Safe to send again. */
    data object Load : TasksEvent

    /** A silent reload: no spinner. */
    data object Refresh : TasksEvent
    data class ViewChanged(val view: TasksView) : TasksEvent
    data class BoardFilterChanged(val filter: TaskFilter) : TasksEvent
    data class MineQueryChanged(val query: String) : TasksEvent
    data class SelfQueryChanged(val query: String) : TasksEvent

    /** "To Me | To Other" on Assigned Tasks or on Drafts. */
    data class ScopeChanged(val view: TasksView, val scope: TaskScope) : TasksEvent
    data object DismissMessage : TasksEvent

    // -- one-click writes, from a card, a row or the ⋯ menu --
    data class SetStatus(val task: Task, val status: TaskStatus) : TasksEvent

    /**
     * "Make private" / "Make public" from the ⋯ menu: a main task only, and
     * only where the service keeps visibility and the server allows it.
     */
    data class SetVisibility(val task: Task, val visibility: TaskVisibility) : TasksEvent
    data class Delete(val task: Task) : TasksEvent

    /** A Board card dropped on another column: it waits there until the server answers. */
    data class Move(val task: Task, val status: TaskStatus) : TasksEvent

    /** Share a self task (or take it back, with a null person) straight from its row. */
    data class Share(val taskId: String, val assigneeId: String?) : TasksEvent
    /**
     * The Self Tasks quick-add row. [visibility] is the Public | Private it
     * picked — null where the service does not keep the field, and the row
     * refuses to add without a choice where it does.
     */
    data class QuickAdd(
        val title: String,
        val dueDate: String?,
        val assigneeId: String?,
        val visibility: TaskVisibility? = null,
    ) : TasksEvent
    /** Ask an admin for posting rights; the panel closes first, as the web's does. */
    data object AskForRights : TasksEvent

    // -- the Create Task popup --
    /** [status] is the Board column whose + was pressed; null from the tab bar. */
    data class OpenChooser(val status: TaskStatus? = null) : TasksEvent
    data object CloseChooser : TasksEvent

    // -- the panel --
    data class OpenTask(val taskId: String) : TasksEvent

    /** [pickAssignee] opens the form with the crew picker already open ("Assign to someone"). */
    data class NewTask(val seed: TaskDraft = TaskDraft(), val pickAssignee: Boolean = false) : TasksEvent
    data class NewSubtask(val main: Task) : TasksEvent
    data object BackToParent : TasksEvent

    /** Close, or refuse while there are unsaved edits. */
    data object ClosePanel : TasksEvent

    /** Cancel a new task; a new subtask goes back to its main task. */
    data object CancelCreate : TasksEvent

    // -- drafts --

    /** Catch up with drafts saved somewhere else; does nothing where drafts are off. */
    data object LoadDrafts : TasksEvent

    /** The Drafts tab opened on an empty tab while the other had some: show the other. */
    data class DraftsScopeDecided(val scope: TaskScope) : TasksEvent

    /** Open a saved draft in the panel, to finish it. */
    data class OpenDraft(val draft: SavedTaskDraft) : TasksEvent

    /** Keep the open new-task form as a draft (new, or an update of the one it is). */
    data object SaveDraft : TasksEvent

    /** 🗑 on a draft row: pressed twice, the second press deletes. */
    data class DeleteDraft(val draft: SavedTaskDraft) : TasksEvent

    /** Delete draft in the panel's footer: pressed twice, as the row's 🗑 is. */
    data object DeleteOpenDraft : TasksEvent

    /** Lets go of the first press of a Delete draft that was never confirmed. */
    data object DisarmDeleteDraft : TasksEvent

    /** "Save this task as a draft?": its three answers. */
    data object LeaveSave : TasksEvent
    data object LeaveDiscard : TasksEvent
    data object LeaveStay : TasksEvent
    data class DraftChanged(val draft: TaskDraft) : TasksEvent

    /** The header button and the status buttons: into the editable copy. */
    data class PickStatus(val status: TaskStatus) : TasksEvent

    /** The panel's Public | Private switch: into the editable copy. */
    data class PickVisibility(val visibility: TaskVisibility) : TasksEvent

    /** The ⋯ menu and the cancelled banner: saved at once, unless there are unsaved edits. */
    data class ChooseStatus(val status: TaskStatus) : TasksEvent

    /** The panel's ⋯ menu: saved at once, unless there are unsaved edits — then it joins them. */
    data class ChooseVisibility(val visibility: TaskVisibility) : TasksEvent

    /** Comments | History at the foot of the panel. */
    data class TabChanged(val tab: PanelTab) : TasksEvent
    data class Save(val overwrite: Boolean = false) : TasksEvent
    data object Create : TasksEvent
    data object Discard : TasksEvent
    data object LoadTheirs : TasksEvent
    data object DeleteOpenTask : TasksEvent
    data class CommentChanged(val text: String) : TasksEvent
    data object SendComment : TasksEvent

    // -- images and files in the description --

    /** "Add image" / "Attach file": asks the host for a file dialog. */
    data class PickAttachments(val imagesOnly: Boolean) : TasksEvent

    /**
     * Files chosen, pasted or dropped on the description.
     *
     * The same event for all three: the checks, the shrinking and the limit
     * of 20 are the same whichever way a file arrived.
     */
    data class AddAttachments(val files: List<PickedTaskFile>) : TasksEvent

    /** Removes one the task has; the panel asks first. */
    data class RemoveAttachment(val attachment: TaskAttachment) : TasksEvent

    /** Takes away one that was waiting for Create. */
    data class RemovePendingAttachment(val key: String) : TasksEvent

    /** Opens one image full size, and moves between them. */
    data class OpenAttachment(val attachmentId: String) : TasksEvent
    data object CloseAttachment : TasksEvent

    /** Fetches one image's bytes for its thumbnail; does nothing once it has them. */
    data class LoadAttachment(val attachment: TaskAttachment) : TasksEvent

    /** Fetches a fresh link and the bytes behind it, for a thumbnail that failed. */
    data class ReloadAttachment(val attachment: TaskAttachment) : TasksEvent

    /** Asks for a `download=1` link; the panel opens it and clears it. */
    data class DownloadAttachment(val attachment: TaskAttachment) : TasksEvent
    data object OpenedUrl : TasksEvent
}
