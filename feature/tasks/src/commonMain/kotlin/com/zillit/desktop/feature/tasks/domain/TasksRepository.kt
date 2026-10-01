package com.zillit.desktop.feature.tasks.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The Tasks service (`zillit_tasks`), `/api/v2/tasks`.
 *
 * There is no project in the URL: the project and user come from the session
 * the HTTP client already attaches, so nothing here names either.
 */
interface TasksRepository {

    /**
     * Every task the caller can see — main tasks AND subtasks, page by page
     * (the service caps a page at 1000). The Board, My Tasks and Self Tasks
     * all filter this one list.
     */
    suspend fun tasks(): ZillitResult<List<Task>>

    /** Who can be assigned or @mentioned, and which of them head a department. */
    suspend fun assignees(): ZillitResult<TaskAssignees>

    /** The production's departments, named for the reader. */
    suspend fun departments(): ZillitResult<List<TaskDepartment>>

    /** One task with its comments and history. */
    suspend fun task(taskId: String): ZillitResult<Task>

    /**
     * Creates a task, or a subtask when [draft] carries a `parentId`. [uniqueId]
     * is required by the service: a retried create returns the task already made.
     */
    suspend fun create(draft: TaskDraft, uniqueId: String): TaskWrite

    /**
     * A partial update. With [expectedUpdated] the service answers 409 when
     * someone else saved first, and this returns [TaskWrite.Conflict] carrying
     * their version.
     */
    suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long? = null): TaskWrite

    /** Soft delete; a main task's subtasks (and all their comments) go with it. */
    suspend fun delete(taskId: String): ZillitResult<Unit>

    /** Adds a comment; answers the task with every comment on it. */
    suspend fun comment(taskId: String, text: String, mentions: List<String>): ZillitResult<Task>
}
