package com.zillit.desktop.feature.tasks.domain

/*
 * Drafts: a new task someone started and saved to finish later.
 *
 * A draft is **not** a task. It has no list anyone else can read, no history,
 * no notification and no socket event: only its owner ever sees one, and the
 * service answers 404 `task_draft_not_exists` to anybody else. The tool offers
 * them only where the list said `features.drafts` is exactly `true` and the
 * viewer may post (see [TasksFeatures.drafts]).
 *
 * Creating from a draft is the ordinary create with `draft_id`: the service
 * moves the draft's images and files onto the new task and deletes the draft,
 * in that one request.
 */

/**
 * One saved draft, as `GET /v2/tasks/drafts` lists it.
 *
 * [attachments] carry a `url` only in the answers about ONE draft, so a row in
 * the list can count them but not show a thumbnail.
 */
data class SavedTaskDraft(
    val id: String,
    /** What the task will be: a self task, or a project task. */
    val isSelf: Boolean = false,
    /** Set when the draft is a shared task (a subtask) of that main task. */
    val parentId: String? = null,
    val parentTitle: String = "",
    val data: SavedDraftData = SavedDraftData(),
    val attachments: List<TaskAttachment> = emptyList(),
    val createdMillis: Long = 0,
    val updatedMillis: Long = 0,
)

/**
 * The form as it was saved — anything may be empty, because a draft is an
 * unfinished form rather than a task the service checked.
 *
 * [visibility] is null while Public / Private has not been chosen, and for a
 * shared task, which takes its main task's.
 */
data class SavedDraftData(
    val title: String = "",
    val description: String = "",
    val assigneeId: String? = null,
    val visibility: TaskVisibility? = null,
    val dueDate: String? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val priority: TaskPriority = TaskPriority.Med,
    val departmentId: String? = null,
    val scenes: String = "",
)

/** What a draft write answered. */
sealed interface DraftWrite {
    /** The draft as the service now has it, with the message it announced. */
    data class Saved(val draft: SavedTaskDraft, val message: String? = null) : DraftWrite

    /** Refused; [message] is the server's message key, or null for a transport failure. */
    data class Refused(
        val message: String?,
        val status: Int,
        val error: com.zillit.desktop.core.common.ZillitError,
    ) : DraftWrite {
        /** The draft is gone — deleted somewhere else. */
        val isGone: Boolean get() = status == DRAFT_NOT_FOUND && message == DRAFT_NOT_EXISTS
    }

    private companion object {
        const val DRAFT_NOT_FOUND = 404
        const val DRAFT_NOT_EXISTS = "task_draft_not_exists"
    }
}

/**
 * The `data` of a draft (POST / PUT `/drafts`): the form exactly as it is.
 *
 * [chosen] is false while Public / Private has not been picked — the form's
 * own [TaskDraft.visibility] is only a placeholder then, so nothing is sent
 * and the draft opens again with the choice still to make. A shared task sends
 * none either: it takes its main task's.
 */
fun draftData(draft: TaskDraft, chosen: Boolean = true): SavedDraftData = SavedDraftData(
    title = draft.title,
    description = draft.description,
    assigneeId = draft.assigneeId,
    visibility = draft.visibility.takeIf { chosen && draft.parentId == null },
    dueDate = draft.dueDate,
    status = draft.status,
    priority = draft.priority,
    departmentId = draft.departmentId,
    scenes = draft.scenes,
)

/**
 * The panel's form for a saved draft: its fields, what kind of task it will be,
 * and [parentId] when it is a shared task whose main task is still in the list
 * (null when it is not — the draft then opens as a task of its own).
 *
 * A draft saved as cancelled opens as To do: a task is never created cancelled.
 * Without a choice of Public / Private the form falls back on the placeholder
 * [toDraft] uses, and the panel keeps "not chosen yet" apart.
 */
fun formOfDraft(saved: SavedTaskDraft, parentId: String? = null): TaskDraft {
    val data = saved.data
    return TaskDraft(
        title = data.title,
        description = data.description,
        departmentId = data.departmentId,
        scenes = data.scenes,
        dueDate = data.dueDate,
        priority = data.priority,
        status = if (data.status == TaskStatus.Cancelled) TaskStatus.Todo else data.status,
        assigneeId = data.assigneeId,
        isSelf = saved.isSelf,
        visibility = data.visibility ?: if (saved.isSelf) TaskVisibility.Private else TaskVisibility.Public,
        parentId = parentId,
    )
}

/** Whether the draft's own Public / Private has been chosen. */
val SavedTaskDraft.visibilityChosen: Boolean get() = data.visibility != null

/**
 * Whether a new-task form holds anything worth keeping as a draft: a name, a
 * description, scenes, a due date, an assignee, images or files — or it already
 * is a saved draft. Status, priority, department and Public / Private alone are
 * not enough.
 */
fun formHasContent(draft: TaskDraft?, files: Int = 0, draftId: String? = null): Boolean {
    if (draft == null) return false
    return draft.title.isNotBlank() || draft.description.isNotBlank() || draft.scenes.isNotBlank() ||
        draft.dueDate != null || draft.assigneeId != null || files > 0 || draftId != null
}

/**
 * A fingerprint of a new-task form — its fields, whether Public / Private is
 * still to choose, and which images and files it holds — to tell whether
 * anything changed since it opened or was last saved as a draft.
 *
 * An empty text and no text at all are the same form, as the web's `norm` has
 * it: a draft is not "changed" because a field was typed into and cleared.
 */
fun formSig(draft: TaskDraft?, visPending: Boolean = false, files: List<String> = emptyList()): String {
    if (draft == null) return ""
    fun norm(value: String?): String = value?.trim().orEmpty()
    return listOf(
        norm(draft.title),
        norm(draft.description),
        norm(draft.departmentId),
        norm(draft.scenes),
        norm(draft.dueDate),
        draft.priority.wire,
        draft.status.wire,
        norm(draft.assigneeId),
        draft.visibility.wire,
        visPending.toString(),
        files.joinToString(","),
    ).joinToString("\u0000")
}

/**
 * "To Me" on the Drafts tab: the draft is assigned to me, or it is a self task
 * with nobody on it (so it is for me). Everything else is "To Other" —
 * assigned to someone else, or a project task nobody is assigned to yet.
 */
fun draftIsToMe(saved: SavedTaskDraft, me: String?): Boolean {
    val assignee = saved.data.assigneeId
    return if (assignee != null) assignee == me else saved.isSelf
}

/** Drafts newest first (`updated`), as the service lists them. */
val compareDrafts: Comparator<SavedTaskDraft> = compareByDescending { it.updatedMillis }

/**
 * Assigned Tasks → To Other: a task I assigned to someone else. Who assigned
 * it falls back to the creator for rows from before the server recorded it
 * ([assignerOf]).
 */
fun assignedByMeToOther(task: Task, me: String?): Boolean =
    task.assigneeId != null && task.assigneeId != me && assignerOf(task) == me
