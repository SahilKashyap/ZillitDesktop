package com.zillit.desktop.feature.tasks.data

import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskChange
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskHistoryEntry
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
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
        // Absent where the service cannot keep one; an empty string is "no DPR".
        dpr = if ("dpr" in this) text("dpr").orEmpty() else null,
        dueDate = text("due_date").blankToNull()?.take(DAY_LENGTH),
        priority = TaskPriority.fromWire(text("priority")),
        status = TaskStatus.fromWire(text("status")),
        assigneeId = text("assignee_id").blankToNull(),
        isSelf = flag("is_self"),
        // Only the two words count: anything else is "the task carries none".
        visibility = TaskVisibility.fromWire(text("visibility")),
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
        canChangeVisibility = permissions?.flag("can_change_visibility") ?: false,
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

/**
 * What this deployment can keep, from the list's `data.features`.
 *
 * **Exactly `true` and nothing else.** No `features` at all (an older
 * service), `false`, the string `"true"` or `1` all mean off: a service that
 * does not keep the field ignores it in a request without a word, and a
 * Private switch shown against one would call a task private that everybody
 * can see.
 */
private fun JsonObject.strictFlag(key: String): Boolean =
    // `isString` matters: booleanOrNull reads the *content* of `"true"` as true,
    // and a quoted string is exactly one of the shapes that must mean "no".
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

internal fun JsonElement?.toFeatures(): TasksFeatures {
    val features = (this as? JsonObject)?.obj("features") ?: return TasksFeatures()
    return TasksFeatures(visibility = features.strictFlag("visibility"), dpr = features.strictFlag("dpr"))
}

/** `{ tasks: [...], total, features }` — one page of a list read. */
internal class TaskPage(val tasks: List<Task>, val total: Int?, val features: TasksFeatures)

internal fun JsonElement?.toTaskPage(): TaskPage {
    val data = this as? JsonObject
    return TaskPage(
        tasks = data?.list("tasks").orEmpty().mapNotNull { it.toTask() },
        total = (data?.get("total") as? JsonPrimitive)?.longOrNull?.toInt(),
        features = toFeatures(),
    )
}

private const val DAY_LENGTH = 10
