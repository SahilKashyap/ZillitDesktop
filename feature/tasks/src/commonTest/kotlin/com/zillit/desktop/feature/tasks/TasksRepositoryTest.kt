@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.tasks.data.TasksRepositoryImpl
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What this client sends `zillit_tasks`, and what it makes of the answers. */
class TasksRepositoryTest {

    private class Sent(val method: HttpMethod, val url: String, val body: JsonObject?)

    private val sent = mutableListOf<Sent>()

    private fun repository(
        services: Map<ZillitService, String> = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        answer: (Sent) -> Pair<HttpStatusCode, String>,
    ): TasksRepositoryImpl {
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            val call = Sent(request.method, request.url.toString(), body)
            sent += call
            val (status, text) = answer(call)
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return TasksRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ TasksMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(environment = Environment.Develop, services = services, realtime = emptyMap()),
        )
    }

    private fun ok(data: String) = HttpStatusCode.OK to """{"status":1,"data":$data}"""

    @Test
    fun `a create names its client id and is a subtask only when it has a parent`() = runTest {
        val repo = repository { ok("""{"_id":"t1","title":"x"}""") }

        repo.create(TaskDraft(title = "  Order paint  ", isSelf = true, dueDate = "2026-10-02"), "client-1")
        repo.create(TaskDraft(title = "Buy brushes", parentId = "m1"), "client-2")

        val plain = sent[0]
        assertEquals("https://tasks.test/api/v2/tasks", plain.url)
        assertEquals("client-1", plain.body!!["unique_id"]!!.jsonPrimitive.content)
        assertEquals("Order paint", plain.body["title"]!!.jsonPrimitive.content)
        assertTrue(plain.body["is_self"]!!.jsonPrimitive.boolean)
        // A null parent_id would be refused by the service as a malformed id.
        assertFalse("parent_id" in plain.body)
        assertEquals("m1", sent[1].body!!["parent_id"]!!.jsonPrimitive.content)
        // A field with no value is sent as null, so "no assignee" is stated, not left to a merge.
        assertEquals(JsonNull, plain.body["assignee_id"])
    }

    @Test
    fun `an update carries only its changes and the version it started from`() = runTest {
        val repo = repository { ok("""{"_id":"t1","title":"x","status":"done"}""") }

        val write = repo.update("t1", mapOf("status" to "done", "assignee_id" to null), expectedUpdated = 123)

        assertIs<TaskWrite.Saved>(write)
        assertEquals(TaskStatus.Done, write.task.status)
        val call = sent.single()
        assertEquals(HttpMethod.Put, call.method)
        assertEquals("https://tasks.test/api/v2/tasks/t1", call.url)
        assertEquals("done", call.body!!["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, call.body["assignee_id"])
        assertEquals(JsonPrimitive(123), call.body["expected_updated"])
    }

    /** The 409's body does not survive the client's error path, so their version is read again. */
    @Test
    fun `a 409 is answered with the other person's version`() = runTest {
        val repo = repository { call ->
            if (call.method == HttpMethod.Put) {
                HttpStatusCode.Conflict to """{"status":0,"message":"task_conflict"}"""
            } else {
                ok("""{"_id":"t1","title":"theirs","updated":99,"history":[]}""")
            }
        }

        val write = repo.update("t1", mapOf("title" to "mine"), expectedUpdated = 1)

        assertIs<TaskWrite.Conflict>(write)
        assertEquals("theirs", write.latest.title)
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Get), sent.map { it.method })
    }

    @Test
    fun `a refusal carries the service's message and status`() = runTest {
        val repo = repository { HttpStatusCode.UnprocessableEntity to """{"status":0,"message":"task_open_subtasks"}""" }

        val write = repo.update("t1", mapOf("status" to "done"))

        assertIs<TaskWrite.Refused>(write)
        assertEquals("task_open_subtasks", write.message)
        assertEquals(422, write.status)
    }

    @Test
    fun `a gone task is a 404 the caller can act on`() = runTest {
        val repo = repository { HttpStatusCode.NotFound to """{"status":0,"message":"task_not_exists"}""" }

        val write = repo.update("t1", mapOf("title" to "x"))

        assertTrue((write as TaskWrite.Refused).isGone)
    }

    @Test
    fun `the list is read page by page until the total is reached`() = runTest {
        val repo = repository { call ->
            val page = Regex("page=(\\d+)").find(call.url)!!.groupValues[1]
            ok(if (page == "1") """{"tasks":[{"_id":"a"},{"_id":"b"}],"total":3}""" else """{"tasks":[{"_id":"c"}],"total":3}""")
        }

        val result = repo.tasks()

        assertEquals(listOf("a", "b", "c"), (result as ZillitResult.Success).data.map { it.id })
        assertEquals(2, sent.size)
        assertTrue("limit=1000" in sent[0].url)
    }

    @Test
    fun `assignees and heads of department are read as sets`() = runTest {
        val repo = repository { ok("""{"user_ids":["u1","u2"],"hod_user_ids":["u2"]}""") }

        val answer = (repo.assignees() as ZillitResult.Success).data

        assertEquals(setOf("u1", "u2"), answer.userIds)
        assertEquals(setOf("u2"), answer.hodIds)
    }

    @Test
    fun `departments are named, de-duplicated and sorted`() = runTest {
        val repo = repository {
            ok("""[{"_id":"d2","department_name":"Zebra"},{"_id":"d1","department_name":"Art"},{"_id":"d1","department_name":"Art"},{"department_name":"No id"}]""")
        }

        val departments = (repo.departments() as ZillitResult.Success).data

        assertEquals(listOf("d1" to "Art", "d2" to "Zebra"), departments.map { it.id to it.name })
        // The core service, not the tasks one.
        assertEquals("https://core.test/api/v2/departments?designations=true", sent.single().url)
    }

    @Test
    fun `a comment carries its mentions`() = runTest {
        val repo = repository { ok("""{"_id":"t1","comments":[{"_id":"c1","comment":"hi"}]}""") }

        val answer = repo.comment("t1", "hi @Una", listOf("u1"))

        assertEquals(1, (answer as ZillitResult.Success).data.comments!!.size)
        assertEquals("https://tasks.test/api/v2/tasks/t1/comments", sent.single().url)
        assertEquals("hi @Una", sent.single().body!!["comment"]!!.jsonPrimitive.content)
    }

    /** `AppConfig.apiV2` throws for a missing service, and a throw in a view model's coroutine ends the app. */
    @Test
    fun `an environment without the service fails instead of throwing`() = runTest {
        val repo = repository(services = mapOf(ZillitService.Core to "https://core.test")) { ok("{}") }

        assertIs<ZillitResult.Failure>(repo.tasks())
        assertIs<TaskWrite.Refused>(repo.create(TaskDraft(title = "x"), "id"))
        assertTrue(sent.isEmpty())
    }
}

private class TasksMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
