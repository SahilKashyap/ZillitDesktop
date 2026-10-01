@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.data.toTask
import com.zillit.desktop.feature.tasks.data.toTaskPage
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `zillit_tasks` says, read tolerantly. */
class TasksWireTest {

    private fun parse(body: String) = (Json.parseToJsonElement(body) as JsonObject).toTask()

    @Test
    fun `a task reads in the service's own shape`() {
        val task = parse(
            """
            {"_id":"t1","title":"Dress the set","description":"Hero room","department_id":"d1","scenes":"12, 14A",
             "due_date":"2026-10-06","priority":"high","status":"progress","assignee_id":"u1","is_self":false,
             "created_by":"u2","assigned_by":"u2","assigned_at":1790000000000,"created":1780000000000,
             "updated":1790000000001,"comment_count":3,"permissions":{"can_delete":true,"can_reassign":false}}
            """.trimIndent(),
        )!!

        assertEquals("Dress the set", task.title)
        assertEquals(TaskPriority.High, task.priority)
        assertEquals(TaskStatus.Progress, task.status)
        assertEquals("2026-10-06", task.dueDate)
        assertEquals(1790000000001, task.updatedMillis)
        assertEquals(3, task.commentCount)
        assertTrue(task.canDelete)
        assertEquals(false, task.canReassign)
        // A list row carries neither; only a single-task read does.
        assertNull(task.comments)
        assertNull(task.history)
    }

    @Test
    fun `a due date that carries a time is cut to the day`() {
        assertEquals("2026-10-06", parse("""{"_id":"t","due_date":"2026-10-06T00:00:00.000Z"}""")!!.dueDate)
    }

    @Test
    fun `a status or priority this build does not know reads as the default`() {
        val task = parse("""{"_id":"t","status":"archived","priority":"urgent"}""")!!

        assertEquals(TaskStatus.Todo, task.status)
        assertEquals(TaskPriority.Med, task.priority)
    }

    @Test
    fun `an id-less row is dropped, not guessed at`() {
        assertNull(parse("""{"title":"Ghost"}"""))
    }

    @Test
    fun `a subtask carries its parent`() {
        val task = parse("""{"_id":"s","parent_id":"m","parent_title":"Main"}""")!!

        assertEquals("m", task.parentId)
        assertEquals("Main", task.parentTitle)
        assertTrue(task.isSubtask)
    }

    @Test
    fun `a single-task read brings comments and history`() {
        val task = parse(
            """
            {"_id":"t","comments":[{"_id":"c1","sender":"u1","comment":"hi @Ravi","mentions":["u3"],"created":5}],
             "history":[{"_id":"h1","user_id":"u1","user_name":"Sam","action":"updated",
               "changes":[{"field":"status","from":"todo","to":"done"}],"meta":{"reason":"subtask_open"},"created":6},
               {"_id":"h2","action":"created","meta":{"assignee_id":"u9"}}]}
            """.trimIndent(),
        )!!

        assertEquals(listOf("u3"), task.comments!!.single().mentions)
        assertEquals(1, task.commentCount)
        val history = task.history.orEmpty()
        assertEquals("subtask_open", history[0].reason)
        assertEquals("done", history[0].changes.single().to)
        assertEquals("u9", history[1].assigneeId)
    }

    @Test
    fun `a list page reports its total`() {
        val (tasks, total) = Json.parseToJsonElement("""{"tasks":[{"_id":"a"},{"_id":"b"},{"x":1}],"total":42}""").toTaskPage()

        assertEquals(listOf("a", "b"), tasks.map { it.id })
        assertEquals(42, total)
    }
}
