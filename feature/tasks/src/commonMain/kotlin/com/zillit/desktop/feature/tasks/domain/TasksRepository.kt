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
     * Public / Private and the DPR field. Nothing offers either before a list
     * has said so.
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
     * [features] decides whether `visibility` and `dpr` are in the body at
     * all; `visibility` never goes for a subtask, which takes its main task's.
     */
    suspend fun create(
        draft: TaskDraft,
        uniqueId: String,
        quick: Boolean = false,
        features: TasksFeatures = TasksFeatures(),
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
}
