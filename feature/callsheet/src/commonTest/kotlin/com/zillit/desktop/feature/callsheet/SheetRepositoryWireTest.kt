package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.callsheet.data.CallSheetRepositoryImpl
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.ReminderRequest
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.ReviewAssignee
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the report service is asked, in the web's exact spellings — and a `status:0` read as the refusal it is. */
class SheetRepositoryWireTest {

    private class Sent(val method: String, val path: String, val query: String, val body: JsonObject?)

    private val sent = mutableListOf<Sent>()

    private fun repository(
        answer: (HttpRequestData) -> String = { """{"status":1,"message":"ok","data":{}}""" },
    ): CallSheetRepositoryImpl {
        val engine = MockEngine { request ->
            val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            sent += Sent(
                method = request.method.value,
                path = request.url.encodedPath,
                query = request.url.encodedQuery,
                body = text?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject },
            )
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CallSheetRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ SheetMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    @Test
    fun `lists ask by project, status and approver`() = runTest {
        val repo = repository {
            """{"status":1,"data":{"call_sheets":[{"_id":"r1","name":"Day 1","status":"DRAFT"}]}}"""
        }
        val rows = repo.sheets(
            SheetQuery(
                projectId = "p1",
                statuses = listOf(CallSheetStatus.Draft, CallSheetStatus.PendingInternalApproval),
                approverId = "u1",
            ),
        )
        assertEquals(listOf("r1"), (rows as ZillitResult.Success).data.map { it.id })
        val request = sent.single()
        assertTrue(request.path.endsWith("/api/v2/call-sheets"))
        assertTrue("project_id=p1" in request.query)
        assertTrue("approver_id=u1" in request.query)
        assertTrue(
            "status=DRAFT%2CPENDING_INTERNAL_APPROVAL" in request.query ||
                "status=DRAFT,PENDING_INTERNAL_APPROVAL" in request.query,
        )
    }

    @Test
    fun `an approval with a drawn signature sends the stored image the web's way`() = runTest {
        val repo = repository()
        repo.approve("q1", ApprovalDecision.Signature(media = "p/sig.png", thumbnail = "", bucket = "b", region = "eu"))
        val body = sent.single().body!!
        assertTrue(sent.single().path.endsWith("/approval-requests/q1/approve"))
        val image = body["signature_image"]!!.jsonObject
        assertEquals("p/sig.png", image["media"]!!.jsonPrimitive.content)
        assertEquals("p/sig.png", image["thumbnail"]!!.jsonPrimitive.content, "a missing thumbnail is the image itself")
        assertEquals("png", image["content_subtype"]!!.jsonPrimitive.content)
        assertEquals("signature.png", image["name"]!!.jsonPrimitive.content)

        repo.approve("q2", ApprovalDecision.WithoutSignature)
        assertTrue(sent.last().body!!["without_signature"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `sends, reminders, rejections and publishing use the web's keys`() = runTest {
        val repo = repository()
        repo.submitForApproval("r1")
        repo.submitForInternalApproval("r1", listOf(ReviewAssignee("u2", "Uma", "Producer")))
        repo.sendReminder(
            "r1",
            ReminderRequest(
                sentBy = "u1",
                sentById = "",
                sentByRole = "second_ad_label",
                assigneeIds = listOf("u2"),
                message = "Please",
            ),
        )
        repo.reject("q1", "Wrong call time")
        repo.publish("r1", publishedBy = "Author", publishedById = "u1", continuation = true, notes = "Fixed")

        assertEquals(
            listOf("submit-for-approval", "submit-for-internal-approval", "send-reminder", "reject", "publish"),
            sent.map { it.path.substringAfterLast('/') },
        )
        assertTrue(sent[0].body!!.isEmpty(), "Send for Signature takes an EMPTY body")
        val approver = sent[1].body!!["approvers"]!!.jsonArray.single().jsonObject
        assertEquals("u2", approver["assignee_id"]!!.jsonPrimitive.content)
        assertEquals("Uma", approver["assignee_name"]!!.jsonPrimitive.content)
        assertEquals("Producer", approver["role"]!!.jsonPrimitive.content)
        assertEquals("null", sent[2].body!!["sent_by_id"].toString(), "a blank id goes out as null")
        assertEquals("u1", sent[2].body!!["sent_by"]!!.jsonPrimitive.content, "sent_by is the member id")
        assertEquals("second_ad_label", sent[2].body!!["sent_by_role"]!!.jsonPrimitive.content, "the designation KEY")
        assertEquals("Wrong call time", sent[3].body!!["reason"]!!.jsonPrimitive.content)
        assertEquals("CONTINUATION", sent[4].body!!["continuation_type"]!!.jsonPrimitive.content)
        assertEquals("Fixed", sent[4].body!!["publish_notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `metadata writes only what changed`() = runTest {
        val repo = repository { """{"status":1,"data":{"day_types":["SWD","CWD","SCWD","NIGHT"]}}""" }
        val saved = repo.saveMetadata("p1", MetadataUpdate(dayTypeAdd = "NIGHT"))
        assertEquals(listOf("SWD", "CWD", "SCWD", "NIGHT"), (saved as ZillitResult.Success).data?.dayTypes)
        assertEquals(setOf("day_type_add"), sent.single().body!!.keys)
        assertEquals("PUT", sent.single().method)

        repo.saveMetadata("p1", MetadataUpdate(internalReceiverIds = listOf("u2"), revokeAccessOnRemoval = true))
        assertEquals(setOf("internal_distribution_receivers", "revoke_access_on_removal"), sent.last().body!!.keys)
    }

    @Test
    fun `a comment names its author, and the unknown author reads Unknown`() = runTest {
        val repo = repository {
            """{"status":1,"data":{"comment":{"_id":"c1","text":"Looks good","author_id":"u1"}}}"""
        }
        val added = repo.addComment("r1", SheetMember("u1", "Amy", designation = "Producer"), "Me", "Looks good")
        assertEquals("c1", (added as ZillitResult.Success).data?.id)
        assertEquals("Producer", sent.single().body!!["author_role"]!!.jsonPrimitive.content)

        repo.addComment("r1", null, "Me", "Hi")
        assertEquals("Me", sent.last().body!!["author_name"]!!.jsonPrimitive.content)
        assertEquals("null", sent.last().body!!["author_id"].toString())
    }

    @Test
    fun `a status zero answer is a failure carrying the server's sentence`() = runTest {
        val repo = repository { """{"status":0,"message":"Call sheet is locked"}""" }
        val result = repo.delete("r1")
        val failure = assertIs<ZillitResult.Failure>(result)
        val error = assertIs<ZillitError.Http>(failure.error)
        assertEquals("Call sheet is locked", error.serverMessage)
    }
}

private class SheetMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
