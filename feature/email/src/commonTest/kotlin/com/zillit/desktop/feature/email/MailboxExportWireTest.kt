package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.data.MAILBOX_FLAG
import com.zillit.desktop.feature.email.data.MailboxExportRepositoryImpl
import com.zillit.desktop.feature.email.data.toMailboxExportUpdate
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.exportSizeLabel
import com.zillit.desktop.feature.email.ui.settings.MailboxExportEvent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/v2/email-export` on the wire — the contract in
 * `~/Downloads/email-export-api.md`.
 *
 * The three things this file is really guarding: the shared-mailbox flag
 * rides the **query** on every route (no route takes a body), a stated
 * `status: 0` inside a 200 is a refusal rather than an empty answer, and
 * `email_export_already_in_progress` is a 200 that must not read as one.
 */
class MailboxExportWireTest {

    private val accounts = MailboxScope { true }

    @Test
    fun `every route is the documented verb and path`() = runTest {
        val (repo, requests) = repository(MailboxScope.Personal) { anyEnvelope() }

        repo.start()
        repo.exports()
        repo.export("e1")
        repo.downloadLinks("e1")
        repo.delete("e1")

        assertEquals(
            listOf("POST", "GET", "GET", "GET", "DELETE"),
            requests.map { it.method.value },
        )
        assertEquals(
            listOf(
                "/api/v2/email-export",
                "/api/v2/email-export",
                "/api/v2/email-export/e1",
                "/api/v2/email-export/e1/download",
                "/api/v2/email-export/e1",
            ),
            requests.map { it.url.encodedPath },
        )
        assertTrue(
            requests.all { it.body.contentLength == null || it.body.contentLength == 0L },
            "no route takes a body",
        )
    }

    @Test
    fun `the accounts mailbox is named in the query even on POST and DELETE`() = runTest {
        val (repo, requests) = repository(accounts) { anyEnvelope() }

        repo.start()
        repo.exports()
        repo.delete("e1")

        assertTrue(requests.all { it.url.parameters[MAILBOX_FLAG] == "true" }, "the flag rides every route")
        // There is nowhere else to put it: the flag cannot be a body key here.
        assertTrue(requests.all { it.body.contentLength == null || it.body.contentLength == 0L })
    }

    @Test
    fun `the personal mailbox adds nothing`() = runTest {
        val (repo, requests) = repository(MailboxScope.Personal) { anyEnvelope() }
        repo.exports()
        assertNull(requests.single().url.parameters[MAILBOX_FLAG])
    }

    @Test
    fun `a new export and an already-running one are told apart`() = runTest {
        val (fresh, _) = repository(MailboxScope.Personal) {
            envelope("email_export_requested_success", """{"export":${export("queued")}}""")
        }
        val started = (fresh.start() as ZillitResult.Success).data
        assertTrue(!started.alreadyRunning)
        assertEquals("email_export_requested_success", started.message)
        assertEquals(MailboxExportStatus.Queued, started.export.status)

        val (busy, _) = repository(MailboxScope.Personal) {
            envelope("email_export_already_in_progress", """{"export":${export("processing")}}""")
        }
        val running = (busy.start() as ZillitResult.Success).data
        assertTrue(running.alreadyRunning, "a running export is an answer, not a failure")
        assertEquals(MailboxExportStatus.Processing, running.export.status)
    }

    @Test
    fun `a stated status zero inside a 200 is a refusal, not an empty list`() = runTest {
        val (repo, _) = repository(MailboxScope.Personal) {
            """{"status":0,"message":"email_export_not_ready","messageElements":[],"data":{}}"""
        }
        val failure = repo.downloadLinks("e1") as ZillitResult.Failure
        assertEquals("email_export_not_ready", (failure.error as ZillitError.Http).serverMessage)

        val listed = repo.exports() as ZillitResult.Failure
        assertEquals("email_export_not_ready", (listed.error as ZillitError.Http).serverMessage)
    }

    @Test
    fun `the export object is read whole, numbers in either spelling`() = runTest {
        val (repo, _) = repository(MailboxScope.Personal) {
            envelope(
                "email_export_list_success",
                """{"exports":[{"export_id":"e1","status":"processing","format":"mbox",
                    "mailbox_email":"jane@prod.com","is_accounts_mailbox":false,
                    "total_folders":"7","total_messages":"6087","exported_messages":1200,
                    "skipped_messages":"3","total_size_bytes":"1012334592",
                    "parts":[{"part_no":2,"file_name":"b.zip","size_bytes":"398120448","message_count":1877},
                             {"part_no":1,"file_name":"a.zip","size_bytes":1012334592,"message_count":4210}],
                    "error":"","requested_on":"1760000000000","completed_on":0,"expires_on":0}]}""",
            )
        }
        val export = (repo.exports() as ZillitResult.Success).data.single()
        assertEquals("e1", export.exportId)
        assertEquals(MailboxExportStatus.Processing, export.status)
        assertEquals(7, export.totalFolders)
        assertEquals(6087, export.totalMessages)
        assertEquals(3, export.skippedMessages)
        assertEquals(1_012_334_592L, export.totalSizeBytes)
        assertEquals(1_760_000_000_000L, export.requestedOnMillis)
        assertEquals(listOf(1, 2), export.parts.map { it.partNo }, "parts are ordered")
        val progress = requireNotNull(export.progress)
        assertTrue(progress > 0.19f && progress < 0.20f, "1200 of 6087, as a fraction")
        assertTrue(export.parts.all { it.url.isEmpty() }, "a listed export carries no links")
    }

    @Test
    fun `the download route gives one link per part and drops the linkless`() = runTest {
        val (repo, _) = repository(MailboxScope.Personal) {
            envelope(
                "email_export_download_success",
                """{"export_id":"e1","expires_in":3600,
                    "parts":[{"part_no":1,"file_name":"a.zip","size_bytes":10,"url":"https://s3/a?sig=1"},
                             {"part_no":2,"file_name":"b.zip","size_bytes":20,"url":""}]}""",
            )
        }
        val links = (repo.downloadLinks("e1") as ZillitResult.Success).data
        assertEquals("e1", links.exportId)
        assertEquals(3600L, links.expiresInSeconds)
        assertEquals(listOf("https://s3/a?sig=1"), links.parts.map { it.url })
    }

    @Test
    fun `a 404 for an unknown export keeps its message key`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"status":0,"message":"email_export_not_found","messageElements":[],"data":{}}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = MailboxExportRepositoryImpl(apiClient(engine), config(), MailboxScope.Personal)
        val failure = repo.export("nope") as ZillitResult.Failure
        assertEquals("email_export_not_found", (failure.error as ZillitError.Http).serverMessage)
    }

    @Test
    fun `the socket update carries the export and which mailbox it belongs to`() {
        val payload = """{"project_id":"p1","user_id":"u1","isAccountsMailbox":true,
            "data":{"export_id":"e1","status":"completed","exported_messages":4210,"total_messages":4210,
                    "parts":[{"part_no":1,"file_name":"a.zip","size_bytes":10,"message_count":4210}]}}"""
        val update = toMailboxExportUpdate(message(payload))
        assertEquals("e1", update?.export?.exportId)
        assertEquals(MailboxExportStatus.Completed, update?.export?.status)
        assertEquals(true, update?.isAccountsMailbox)
        assertEquals(1f, update?.export?.progress)

        // The same event with `data` as a JSON *string*, which this backend sends.
        val stringed = """{"isAccountsMailbox":false,"data":"{\"export_id\":\"e2\",\"status\":\"failed\"}"}"""
        val second = toMailboxExportUpdate(message(stringed))
        assertEquals("e2", second?.export?.exportId)
        assertEquals(MailboxExportStatus.Failed, second?.export?.status)
        assertEquals(false, second?.isAccountsMailbox)

        assertNull(toMailboxExportUpdate(message("""{"isAccountsMailbox":false,"data":{}}""")), "no id, no update")
    }

    @Test
    fun `the export event is not one of the folder events`() {
        assertTrue(
            ZillitSocketEvents.Email.ExportUpdated !in ZillitSocketEvents.Email.All,
            "routing it with the mail events would make every progress push refresh a folder",
        )
        assertTrue(ZillitSocketEvents.Email.ExportUpdated in ZillitSocketEvents.all)
    }

    @Test
    fun `a part with no number is numbered by its position`() = runTest {
        // The sentinel for "every part" used to be 0, so a part the server
        // numbered 0 turned one row's Download into the whole export.
        val (repo, _) = repository(MailboxScope.Personal) {
            envelope(
                "ok",
                """{"export":{"export_id":"e1","status":"completed","parts":[
                    {"file_name":"first.zip","size_bytes":10},
                    {"part_no":0,"file_name":"second.zip","size_bytes":20}]}}""",
            )
        }
        val parts = (repo.export("e1") as ZillitResult.Success).data.parts
        assertEquals(listOf(1, 2), parts.map { it.partNo })
        assertEquals(listOf("first.zip", "second.zip"), parts.map { it.fileName })
        assertTrue(parts.none { it.partNo == MailboxExportEvent.ALL_PARTS }, "never the all-parts sentinel")
    }

    @Test
    fun `a null parts array costs the parts, not the whole export`() = runTest {
        val (repo, _) = repository(MailboxScope.Personal) {
            envelope("ok", """{"export":{"export_id":"e1","status":"queued","parts":null}}""")
        }
        val export = (repo.export("e1") as ZillitResult.Success).data
        assertEquals("e1", export.exportId, "an uninitialised list must not empty the page")
        assertTrue(export.parts.isEmpty())
    }

    @Test
    fun `an unreadable payload is not reported in the server's success words`() = runTest {
        val (repo, _) = repository(MailboxScope.Personal) {
            // 200, status 1, and a download answer with no usable link.
            envelope("email_export_download_success", """{"export_id":"e1","expires_in":3600,"parts":[]}""")
        }
        val error = (repo.downloadLinks("e1") as ZillitResult.Failure).error as ZillitError.Http
        assertNull(error.serverMessage, "the success key would print as the error text")
        assertTrue(error.technical?.contains("email_export_download_success") == true, "kept for the log")
    }

    @Test
    fun `sizes read in the units an export reaches`() {
        assertEquals("", exportSizeLabel(0))
        assertEquals("512 B", exportSizeLabel(512))
        assertEquals("2 KB", exportSizeLabel(2048))
        assertEquals("965 MB", exportSizeLabel(1_012_334_592))
        assertEquals("1.3 GB", exportSizeLabel(1_400_000_000))
    }

    // -- plumbing ------------------------------------------------------------

    private fun repository(
        scope: MailboxScope,
        answer: (HttpRequestData) -> String,
    ): Pair<MailboxExportRepositoryImpl, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return MailboxExportRepositoryImpl(apiClient(engine), config(), scope) to requests
    }

    private fun apiClient(engine: MockEngine) = ApiClient(
        httpClient = HttpClientFactory.create({ ExportMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private fun config() = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private fun message(payload: String) =
        SocketMessage(SocketEventName("email:export:updated"), Json.parseToJsonElement(payload))

    private fun envelope(message: String, data: String) =
        """{"status":1,"message":"$message","messageElements":[],"data":$data}"""

    private fun export(status: String) =
        """{"export_id":"e1","status":"$status","mailbox_email":"jane@prod.com","requested_on":1760000000000}"""

    /** An envelope carrying every shape the five routes read, so one answer serves them all. */
    private fun anyEnvelope() = envelope(
        "ok",
        """{"export":${export("queued")},"exports":[${export("queued")}]}""",
    )
}

private class ExportMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
