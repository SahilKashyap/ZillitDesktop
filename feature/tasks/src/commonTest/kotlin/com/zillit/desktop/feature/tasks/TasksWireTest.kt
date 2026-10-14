@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.data.toTask
import com.zillit.desktop.feature.tasks.data.toTaskPage
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val page = Json.parseToJsonElement("""{"tasks":[{"_id":"a"},{"_id":"b"},{"x":1}],"total":42}""").toTaskPage()
        val tasks = page.tasks
        val total = page.total

        assertEquals(listOf("a", "b"), tasks.map { it.id })
        assertEquals(42, total)
    }

    // -- what this deployment can keep (`data.features`) --------------------------------

    @Test
    fun `features are read only when the service says exactly true`() {
        fun features(body: String) = Json.parseToJsonElement(body).toTaskPage().features

        val on = features("""{"tasks":[],"features":{"visibility":true,"dpr":true}}""")
        assertTrue(on.visibility)
        assertTrue(on.dpr)

        // A service from before the fields sends no `features` at all.
        assertFalse(features("""{"tasks":[]}""").visibility)
        assertFalse(features("""{"tasks":[]}""").dpr)
        // And anything that is not exactly `true` is "no": it would otherwise
        // show a Private switch against a service that ignores the field.
        listOf(
            """{"tasks":[],"features":{"visibility":false,"dpr":false}}""",
            """{"tasks":[],"features":{"visibility":"true","dpr":"true"}}""",
            """{"tasks":[],"features":{"visibility":1,"dpr":1}}""",
            """{"tasks":[],"features":{}}""",
        ).forEach { body ->
            assertFalse(features(body).visibility, body)
            assertFalse(features(body).dpr, body)
        }
    }

    @Test
    fun `the two features are read apart`() {
        val one = Json.parseToJsonElement("""{"tasks":[],"features":{"visibility":true,"dpr":false}}""").toTaskPage().features
        assertTrue(one.visibility)
        assertFalse(one.dpr)
    }

    @Test
    fun `visibility, the DPR and who may switch it are read off a task`() {
        val task = parse(
            """
            {"_id":"t1","title":"x","visibility":"private","dpr":"DPR 12 Oct",
             "permissions":{"can_delete":false,"can_reassign":false,"can_change_visibility":true}}
            """.trimIndent(),
        )!!
        assertEquals(TaskVisibility.Private, task.visibility)
        assertEquals("DPR 12 Oct", task.dpr)
        assertTrue(task.canChangeVisibility)
    }

    @Test
    fun `a task that carries neither field reads as null, not as empty`() {
        // Null is "the service cannot keep one"; an empty string would be "no DPR".
        val task = parse("""{"_id":"t1","title":"x"}""")!!
        assertNull(task.visibility)
        assertNull(task.dpr)
        assertFalse(task.canChangeVisibility)
        // An empty `dpr` the service did send is "no DPR", which is not the same thing.
        assertEquals("", parse("""{"_id":"t1","title":"x","dpr":""}""")!!.dpr)
        // A word that is neither of the two is no visibility at all.
        assertNull(parse("""{"_id":"t1","title":"x","visibility":"secret"}""")!!.visibility)
    }
}
