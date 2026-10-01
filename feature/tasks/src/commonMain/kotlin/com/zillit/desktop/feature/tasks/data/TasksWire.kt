package com.zillit.desktop.feature.tasks.data

import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskChange
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskHistoryEntry
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The service's own shape (snake_case, `_id`), read tolerantly: a field that is
 * missing or of an unexpected type reads as empty rather than failing the whole
 * list. Parsed by hand rather than into a DTO because the booleans, counts and
 * epoch fields arrive as numbers or strings depending on the row's age.
 */

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.millis(key: String): Long =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L

private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.list(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/** An empty string is "none" on this wire. */
private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

internal fun JsonObject.toTask(): Task? {
    val id = text("_id")?.takeIf { it.isNotBlank() } ?: return null
    val permissions = obj("permissions")
    val comments = if (this["comments"] is JsonArray) list("comments").mapNotNull { it.toComment() } else null
    val history = if (this["history"] is JsonArray) list("history").mapNotNull { it.toHistoryEntry() } else null
    return Task(
        id = id,
        title = text("title").orEmpty(),
        description = text("description").orEmpty(),
        departmentId = text("department_id").blankToNull(),
        scenes = text("scenes").orEmpty(),
        dueDate = text("due_date").blankToNull()?.take(DAY_LENGTH),
        priority = TaskPriority.fromWire(text("priority")),
        status = TaskStatus.fromWire(text("status")),
        assigneeId = text("assignee_id").blankToNull(),
        isSelf = flag("is_self"),
        parentId = text("parent_id").blankToNull(),
        parentTitle = text("parent_title").orEmpty(),
        createdBy = text("created_by").blankToNull(),
        assignedBy = text("assigned_by").blankToNull(),
        assignedAtMillis = millis("assigned_at"),
        createdMillis = millis("created"),
        updatedMillis = millis("updated"),
        commentCount = (this["comment_count"] as? JsonPrimitive)?.longOrNull?.toInt() ?: comments?.size ?: 0,
        canDelete = permissions?.flag("can_delete") ?: false,
        canReassign = permissions?.flag("can_reassign") ?: false,
        comments = comments,
        history = history,
    )
}

private fun JsonObject.toComment(): TaskComment? {
    val id = text("_id") ?: return null
    return TaskComment(
        id = id,
        senderId = text("sender"),
        text = text("comment").orEmpty(),
        mentions = (this["mentions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        createdMillis = millis("created"),
    )
}

private fun JsonObject.toHistoryEntry(): TaskHistoryEntry? {
    val id = text("_id") ?: return null
    val meta = obj("meta")
    return TaskHistoryEntry(
        id = id,
        userId = text("user_id"),
        userName = text("user_name").orEmpty(),
        action = text("action").orEmpty(),
        changes = list("changes").map { TaskChange(it.text("field").orEmpty(), it.text("from"), it.text("to")) },
        assigneeId = meta?.text("assignee_id").blankToNull(),
        subtaskTitle = meta?.text("title").orEmpty(),
        reason = meta?.text("reason"),
        createdMillis = millis("created"),
    )
}

/** `{ tasks: [...], total }` — a list read. */
internal fun JsonElement?.toTaskPage(): Pair<List<Task>, Int?> {
    val data = this as? JsonObject
    val tasks = data?.list("tasks").orEmpty().mapNotNull { it.toTask() }
    val total = (data?.get("total") as? JsonPrimitive)?.longOrNull?.toInt()
    return tasks to total
}

private const val DAY_LENGTH = 10
