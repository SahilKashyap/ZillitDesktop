@file:Suppress("MaxLineLength") // Wire.

package com.zillit.desktop.feature.tasks.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAssignees
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `zillit_tasks`. Every call is project-scoped, so every call rides
 * [RequestModule.ProjectUser].
 *
 * Rule denials from the service are **422, never 403** (reassigning someone
 * else's self task, deleting without rights, an assignee who cannot view
 * Tasks): a 403 anywhere in this app is read as "no longer a member". The only
 * 403 is `tasks_tool_not_enabled`, which is why nothing calls until the tool's
 * rights row has confirmed it (see [com.zillit.desktop.feature.tasks.domain.TasksViewer.canCall]).
 */
class TasksRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : TasksRepository {

    /**
     * Null when this environment has no Tasks host configured. [AppConfig.apiV2]
     * throws for a missing service, and a throw inside a view model's coroutine
     * would end the app — so the absence is carried here and answered as a
     * failure by [call], which every request goes through.
     */
    private val root: String? get() = runCatching { config.apiV2(ZillitService.Tasks).trimEnd('/') + "/tasks" }.getOrNull()
    private val core get() = config.apiV2()

    override suspend fun tasks(): ZillitResult<List<Task>> {
        val all = mutableListOf<Task>()
        for (page in 1..MAX_PAGES) {
            val answer = call(HttpVerb.Get, "", query = mapOf("page" to page, "limit" to PAGE_SIZE))
            val (batch, total) = when (answer) {
                is ZillitResult.Failure -> return ZillitResult.Failure(answer.error)
                is ZillitResult.Success -> answer.data.data.toTaskPage()
            }
            all += batch
            if (batch.isEmpty() || all.size >= (total ?: all.size)) break
        }
        return ZillitResult.Success(all)
    }

    override suspend fun assignees(): ZillitResult<TaskAssignees> =
        call(HttpVerb.Get, "/assignees").mapData { data ->
            val obj = data as? JsonObject
            fun ids(key: String) = (obj?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            TaskAssignees(userIds = ids("user_ids"), hodIds = ids("hod_user_ids"))
        }

    /** The core service's department list; names arrive as label keys. */
    override suspend fun departments(): ZillitResult<List<TaskDepartment>> =
        send(HttpVerb.Get, "${core}departments", query = mapOf("designations" to "true")).mapData { data ->
            val seen = mutableSetOf<String>()
            (data as? JsonArray).orEmpty().mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val id = (obj["_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: (obj["department_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = (obj["department_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.localised()
                if (name.isNullOrBlank() || !seen.add(id)) null else TaskDepartment(id, name)
            }.sortedBy { it.name.lowercase() }
        }

    override suspend fun task(taskId: String): ZillitResult<Task> =
        call(HttpVerb.Get, "/$taskId").mapTask()

    override suspend fun create(draft: TaskDraft, uniqueId: String, quick: Boolean): TaskWrite {
        // The Self Tasks quick-add row sends only what it asks for, as the web's
        // does; the server fills in the rest.
        if (quick) {
            val body = buildJsonObject {
                put("unique_id", uniqueId)
                put("title", draft.title.trim())
                put("due_date", draft.dueDate)
                put("assignee_id", draft.assigneeId)
                put("is_self", true)
            }
            return call(HttpVerb.Post, "", body = body).asWrite()
        }
        val body = buildJsonObject {
            put("unique_id", uniqueId)
            put("title", draft.title.trim())
            put("description", draft.description)
            put("department_id", draft.departmentId)
            put("scenes", draft.scenes)
            put("due_date", draft.dueDate)
            put("priority", draft.priority.wire)
            put("status", draft.status.wire)
            put("assignee_id", draft.assigneeId)
            put("is_self", draft.isSelf)
            // Only for a subtask: a null would be refused as a malformed id.
            draft.parentId?.let { put("parent_id", it) }
        }
        return call(HttpVerb.Post, "", body = body).asWrite()
    }

    override suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long?): TaskWrite {
        val body = buildJsonObject {
            changes.forEach { (key, value) -> put(key, value) }
            expectedUpdated?.let { put("expected_updated", it) }
        }
        val answer = call(HttpVerb.Put, "/$taskId", body = body)
        // The 409's body (their version) does not survive the client's error
        // path, so read it again: that is the same task the service would have sent.
        if (answer is ZillitResult.Failure && answer.error.httpStatus == CONFLICT) {
            return when (val latest = task(taskId)) {
                is ZillitResult.Success -> TaskWrite.Conflict(latest.data)
                is ZillitResult.Failure -> TaskWrite.Refused(latest.error.serverMessageOrNull, latest.error.httpStatus ?: 0, latest.error)
            }
        }
        return answer.asWrite()
    }

    override suspend fun delete(taskId: String): ZillitResult<String?> =
        when (val answer = call(HttpVerb.Delete, "/$taskId")) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> ZillitResult.Success(answer.data.message)
        }

    override suspend fun comment(taskId: String, text: String, mentions: List<String>): ZillitResult<Task> {
        val body = buildJsonObject {
            put("comment", text)
            put("mentions", JsonArray(mentions.map(::JsonPrimitive)))
        }
        return call(HttpVerb.Post, "/$taskId/comments", body = body).mapTask()
    }

    /** One answer from the service: its `data`, and the `message` it announced with it. */
    private class Reply(val data: JsonElement?, val message: String?)

    /** A call on the Tasks service; [path] is what follows `/tasks`. */
    private suspend fun call(
        verb: HttpVerb,
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<Reply> {
        // No Tasks host in this environment: the web answers its generic "something went wrong".
        val base = root ?: return ZillitResult.Failure(ZillitError.Validation(str(S.something_went_wrong)))
        return send(verb, base + path, body, query)
    }

    private suspend fun send(
        verb: HttpVerb,
        url: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<Reply> = when (
        val answer = apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body, queryParameters = query)
    ) {
        is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
        is ZillitResult.Success -> answer.data.asReply()
    }

    private fun ApiEnvelope.asReply(): ZillitResult<Reply> = if (status == 1) {
        ZillitResult.Success(Reply(data, message?.takeIf { it.isNotBlank() }))
    } else {
        ZillitResult.Failure(ZillitError.Http(status = OK, serverMessage = message))
    }

    private inline fun <T> ZillitResult<Reply>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> ZillitResult.Success(transform(data.data))
    }

    private fun ZillitResult<Reply>.mapTask(): ZillitResult<Task> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> (data.data as? JsonObject)?.toTask()
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("the task answer had no task in it"))
    }

    /** A write's answer: the task with the server's message, or why not. */
    private fun ZillitResult<Reply>.asWrite(): TaskWrite = when (val task = mapTask()) {
        is ZillitResult.Success -> TaskWrite.Saved(task.data, (this as? ZillitResult.Success)?.data?.message)
        is ZillitResult.Failure -> TaskWrite.Refused(task.error.serverMessageOrNull, task.error.httpStatus ?: 0, task.error)
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 20
        const val OK = 200
        const val CONFLICT = 409
    }
}

/** The HTTP status a failure carried, or null for a transport failure. */
val ZillitError.httpStatus: Int?
    get() = when (this) {
        is ZillitError.Http -> status
        is ZillitError.Unauthorized -> 401
        is ZillitError.Forbidden -> 403
        else -> null
    }

private val ZillitError.serverMessageOrNull: String? get() = (this as? ZillitError.Http)?.serverMessage
