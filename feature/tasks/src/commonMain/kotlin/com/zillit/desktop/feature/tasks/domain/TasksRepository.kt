package com.zillit.desktop.feature.tasks.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The Tasks service (`zillit_tasks`), `/api/v2/tasks`.
 *
 * There is no project in the URL: the project and user come from the session
 * the HTTP client already attaches, so nothing here names either.
 */
interface TasksRepository {

    /** The visible tasks, and what this deployment of the service can keep. */
    data class TaskList(val tasks: List<Task>, val features: TasksFeatures)

    /**
     * Every task the caller can see — main tasks AND subtasks, page by page
     * (the service caps a page at 1000). The Board, My Tasks and Self Tasks
     * all filter this one list.
     *
     * The answer also carries `data.features`: whether this deployment keeps
     * Public / Private, attachments and drafts. Nothing offers any of them
     * before a list has said so.
     */
    suspend fun tasks(): ZillitResult<TaskList>

    /** Who can be assigned or @mentioned, and which of them head a department. */
    suspend fun assignees(): ZillitResult<TaskAssignees>

    /** The production's departments, named for the reader. */
    suspend fun departments(): ZillitResult<List<TaskDepartment>>

    /** One task with its comments and history. */
    suspend fun task(taskId: String): ZillitResult<Task>

    /**
     * Creates a task, or a subtask when [draft] carries a `parentId`. [uniqueId]
     * is required by the service: a retried create returns the task already made.
     * [quick] is the Self Tasks quick-add row: only title, due date and assignee
     * go, as the web sends them, and the server fills in the rest.
     *
     * [features] decides whether `visibility` is in the body at all; it never
     * goes for a subtask, which takes its main task's.
     *
     * [draftId] names the saved draft this form came from: the service then
     * moves that draft's images and files onto the new task and deletes the
     * draft, in this same request (404 `task_draft_not_exists` when it is
     * already gone, and nothing is created then).
     */
    suspend fun create(
        draft: TaskDraft,
        uniqueId: String,
        quick: Boolean = false,
        features: TasksFeatures = TasksFeatures(),
        draftId: String? = null,
    ): TaskWrite

    /**
     * A partial update. With [expectedUpdated] the service answers 409 when
     * someone else saved first, and this returns [TaskWrite.Conflict] carrying
     * their version.
     */
    suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long? = null): TaskWrite

    /**
     * Soft delete; a main task's subtasks (and all their comments) go with it.
     * Answers the server's own message (a message key), or null when it sent none.
     */
    suspend fun delete(taskId: String): ZillitResult<String?>

    /** Adds a comment; answers the task with every comment on it. */
    suspend fun comment(taskId: String, text: String, mentions: List<String>): ZillitResult<Task>

    // -- drafts: a new task saved to finish later ---------------------------

    /**
     * My drafts, newest `updated` first. Their attachments carry no `url`
     * here: only an answer about one draft does.
     */
    suspend fun drafts(): ZillitResult<List<SavedTaskDraft>>

    /** One draft, its attachments with their links. */
    suspend fun draft(draftId: String): ZillitResult<SavedTaskDraft>

    /**
     * Keeps a started form as a new draft. The service cleans the `data`
     * rather than refusing an unfinished form; it does refuse past the limit
     * of 50 a person a project (422 `task_draft_limit`).
     */
    suspend fun createDraft(isSelf: Boolean, parentId: String?, data: SavedDraftData): DraftWrite

    /** Replaces the draft's fields. */
    suspend fun updateDraft(draftId: String, data: SavedDraftData): DraftWrite

    /** Deletes one; its images and files go with it. */
    suspend fun deleteDraft(draftId: String): ZillitResult<String?>

    /**
     * Adds one prepared image or file to a DRAFT, the same three steps as
     * [addAttachment] under the draft's own path. They move onto the task when
     * it is created from the draft.
     */
    suspend fun addDraftAttachment(draftId: String, item: PreparedAttachment): DraftAttachmentWrite

    /** Takes one off a draft; answers the draft. */
    suspend fun removeDraftAttachment(draftId: String, attachmentId: String): DraftAttachmentWrite

    // -- images and files in the description ---------------------------------

    /**
     * Adds one prepared image or file to a task, in the service's three steps:
     * an upload link, the bytes straight to storage, then "attach".
     *
     * The bytes do **not** go through the API client: the signed link is the
     * permission and it is made for exactly the headers the service handed
     * out, so sending `moduledata` or a Bearer alongside makes storage refuse
     * a request it would otherwise have taken. See [AttachmentUpload].
     *
     * Answers the task as the service now has it, or where it stopped.
     */
    suspend fun addAttachment(taskId: String, item: PreparedAttachment): AttachmentWrite

    /** Removes one; answers the task. */
    suspend fun removeAttachment(taskId: String, attachmentId: String): AttachmentWrite

    /**
     * A fresh signed link for one attachment — to show an image whose link
     * has run out, or, with [download], to save the file under its own name.
     */
    suspend fun attachmentLink(
        taskId: String,
        attachmentId: String,
        download: Boolean = false,
    ): ZillitResult<String>

    /** The bytes of a signed link, for a thumbnail and the full-size view. */
    suspend fun attachmentBytes(url: String): ByteArray?
}

/** What an attachment write answered. */
sealed interface AttachmentWrite {
    /** The task as the service now has it, with the message it announced. */
    data class Saved(val task: Task, val message: String?) : AttachmentWrite

    /**
     * Refused, and by which step: the service's own message for a link or an
     * attach, and no message at all when **storage** would not take the bytes
     * — there is no envelope in that case, which is why the client has a
     * sentence of its own for it.
     */
    data class Refused(
        val step: AttachmentStep,
        val message: String?,
        val httpStatus: Int,
        val error: com.zillit.desktop.core.common.ZillitError,
    ) : AttachmentWrite
}

/** What an attachment write on a draft answered; see [AttachmentWrite] for the task's own. */
sealed interface DraftAttachmentWrite {
    data class Saved(val draft: SavedTaskDraft, val message: String?) : DraftAttachmentWrite

    data class Refused(
        val step: AttachmentStep,
        val message: String?,
        val httpStatus: Int,
        val error: com.zillit.desktop.core.common.ZillitError,
    ) : DraftAttachmentWrite
}

/** How far adding one got. */
enum class AttachmentStep { Link, Upload, Attach }
