package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.chat.data.ChatExportRepositoryImpl
import com.zillit.desktop.feature.chat.data.belongsTo
import com.zillit.desktop.feature.chat.data.toChatExportUpdate
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import com.zillit.desktop.feature.chat.domain.chatExportSizeLabel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/v2/chat-export` on the wire — the contract in
 * `~/Downloads/chat-export-frontend.md`.
 *
 * The things this file is really guarding: `include_media` reaches the server
 * as the user set it, a stated `status: 0` inside a 200 is a refusal rather
 * than an empty answer, `cnc_chat_export_already_in_progress` is a 200 that
 * must not read as one, and a socket event for another production never
 * reaches this window.
 */
class ChatExportWireTest {

    @Test
    fun `every route is the documented verb and path`() = runTest {
        val (repo, requests) = repository { anyEnvelope() }

        repo.start()
        repo.exports()
        repo.export("e1")
        repo.downloadLink("e1")
        repo.delete("e1")

        assertEquals(
            listOf("POST", "GET", "GET", "GET", "DELETE"),
            requests.map { it.method.value },
        )
        assertEquals(
            listOf(
                "/api/v2/chat-export",
                "/api/v2/chat-export",
                "/api/v2/chat-export/e1",
                "/api/v2/chat-export/e1/download",
                "/api/v2/chat-export/e1",
            ),
            requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun `the media choice is the one thing POST carries, and it carries it either way`() = runTest {
        val (withMedia, media) = repository { anyEnvelope() }
        withMedia.start(includeMedia = true)
        assertEquals("""{"include_media":true}""", media.single().bodyText())

        val (textOnly, text) = repository { anyEnvelope() }
        textOnly.start(includeMedia = false)
        assertEquals(
            """{"include_media":false}""",
            text.single().bodyText(),
            "a text-only export is a tenth of the size — it must not silently include media",
        )
    }

    @Test
    fun `a new export and an already-running one are told apart`() = runTest {
        val (fresh, _) = repository {
            envelope("cnc_chat_export_requested", """{"export":${export("queued")}}""")
        }
        val started = (fresh.start() as ZillitResult.Success).data
        assertFalse(started.alreadyRunning)
        assertEquals("cnc_chat_export_requested", started.message)
        assertEquals(ChatExportStatus.Queued, started.export.status)

        val (busy, _) = repository {
            envelope("cnc_chat_export_already_in_progress", """{"export":${export("processing")}}""")
        }
        val running = (busy.start() as ZillitResult.Success).data
        assertTrue(running.alreadyRunning, "a running export is an answer, not a failure")
        assertEquals(ChatExportStatus.Processing, running.export.status)
    }

    @Test
    fun `a stated status zero inside a 200 is a refusal, not an empty list`() = runTest {
        val (repo, _) = repository {
            """{"status":0,"message":"cnc_chat_export_not_ready","messageElements":[],"data":{}}"""
        }
        val error = (repo.exports() as ZillitResult.Failure).error as ZillitError.Http
        assertEquals("cnc_chat_export_not_ready", error.serverMessage, "the screen has to be able to say why")
    }

    @Test
    fun `the list is read newest-first and capped at the route's own ten`() = runTest {
        val rows = (1..14).joinToString(",") { """{"export_id":"e$it","status":"completed"}""" }
        val (repo, _) = repository { envelope("cnc_chat_export_list", """{"exports":[$rows]}""") }

        val exports = (repo.exports() as ZillitResult.Success).data
        assertEquals(10, exports.size)
        assertEquals("e1", exports.first().exportId, "the server's order is kept")
    }

    @Test
    fun `numbers and flags are read however this backend spells them`() = runTest {
        val (repo, _) = repository {
            envelope(
                "cnc_chat_export_fetched",
                """
                {"export":{"_id":"e9","status":"PROCESSING","include_media":"false",
                 "total_conversations":"42","exported_conversations":17,
                 "exported_messages":"8190","exported_media":312,"skipped_media":"1",
                 "size_bytes":"48213377","requested_on":"1791975262243"}}
                """.trimIndent(),
            )
        }
        val export = (repo.export("e9") as ZillitResult.Success).data

        assertEquals("e9", export.exportId, "an `_id` is an export id too")
        assertEquals(ChatExportStatus.Processing, export.status)
        assertFalse(export.includeMedia, """a quoted "false" still means no media""")
        assertEquals(42, export.totalConversations)
        assertEquals(17, export.exportedConversations)
        assertEquals(8190, export.exportedMessages)
        assertEquals(1, export.skippedMedia)
        assertEquals(48_213_377, export.sizeBytes)
        assertEquals(1_791_975_262_243, export.requestedOnMillis)
    }

    @Test
    fun `an absent media flag means the contract's default, not off`() = runTest {
        val (repo, _) = repository { envelope("ok", """{"export":{"export_id":"e1","status":"completed"}}""") }
        assertTrue(
            (repo.export("e1") as ZillitResult.Success).data.includeMedia,
            "include_media defaults to true, and an export made by another client most likely had it",
        )
    }

    @Test
    fun `an unknown status is read as queued rather than dropping the export`() = runTest {
        val (repo, _) = repository { envelope("ok", """{"export":{"export_id":"e1","status":"paused"}}""") }
        val export = (repo.export("e1") as ZillitResult.Success).data
        assertEquals(ChatExportStatus.Queued, export.status)
        assertTrue(export.isInProgress, "a word we do not know still means the server is working")
    }

    @Test
    fun `the download answer is the link, the name and the size`() = runTest {
        val (repo, _) = repository {
            envelope(
                "cnc_chat_export_download_link",
                """
                {"export_id":"e1","file_name":"zillit-chats-night-shoot-2-2026-10-14.zip",
                 "size_bytes":48213377,"expires_in":3600,"url":"https://bucket.s3/x?X-Amz-Signature=y"}
                """.trimIndent(),
            )
        }
        val link = (repo.downloadLink("e1") as ZillitResult.Success).data

        assertEquals("https://bucket.s3/x?X-Amz-Signature=y", link.url)
        assertEquals("zillit-chats-night-shoot-2-2026-10-14.zip", link.fileName)
        assertEquals(3600, link.expiresInSeconds)
        assertFalse(link.toString().contains("X-Amz-Signature"), "a signed link is a credential, never logged")
    }

    @Test
    fun `a download answer with no link is a failure, not a button that does nothing`() = runTest {
        val (repo, _) = repository {
            envelope("cnc_chat_export_download_link", """{"export_id":"e1","expires_in":3600,"url":""}""")
        }
        val error = (repo.downloadLink("e1") as ZillitResult.Failure).error as ZillitError.Http
        assertNull(error.serverMessage, "the success key would print as the error text")
        assertTrue(error.technical?.contains("cnc_chat_export_download_link") == true, "kept for the log")
    }

    @Test
    fun `an export read from the socket carries the production it belongs to`() {
        val update = toChatExportUpdate(
            message(
                """
                {"project_id":"p1","user_id":"u1",
                 "data":{"export_id":"e1","status":"processing","exported_conversations":17}}
                """.trimIndent(),
            ),
        )

        assertEquals("e1", update?.export?.exportId)
        assertEquals(17, update?.export?.exportedConversations)
        assertEquals("p1", update?.projectId)
    }

    @Test
    fun `an event for another production is not this window's export`() {
        val update = toChatExportUpdate(message("""{"project_id":"p2","data":{"export_id":"e1","status":"failed"}}"""))

        assertFalse(update!!.belongsTo("p1"), "one socket serves every production this device is on")
        assertTrue(update.belongsTo("p2"))
    }

    @Test
    fun `an event that names no production is taken rather than stalling the dialog`() {
        val update = toChatExportUpdate(message("""{"data":{"export_id":"e1","status":"completed"}}"""))
        assertTrue(update!!.belongsTo("p1"))
    }

    @Test
    fun `a data payload sent as a JSON string is still an export`() {
        val update = toChatExportUpdate(
            message("""{"project_id":"p1","data":"{\"export_id\":\"e1\",\"status\":\"completed\"}"}"""),
        )
        assertEquals(ChatExportStatus.Completed, update?.export?.status)
    }

    @Test
    fun `a frame with no export is dropped`() {
        assertNull(toChatExportUpdate(message("""{"project_id":"p1","data":{"status":"completed"}}""")))
        assertNull(toChatExportUpdate(message("""{"project_id":"p1"}""")))
    }

    @Test
    fun `sizes read in the units an export reaches`() {
        assertEquals("", chatExportSizeLabel(0))
        assertEquals("512 B", chatExportSizeLabel(512))
        assertEquals("2 KB", chatExportSizeLabel(2048))
        assertEquals("45 MB", chatExportSizeLabel(48_213_377))
        assertEquals("1.3 GB", chatExportSizeLabel(1_400_000_000))
    }

    // -- plumbing ------------------------------------------------------------

    private fun repository(
        answer: (HttpRequestData) -> String,
    ): Pair<ChatExportRepositoryImpl, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return ChatExportRepositoryImpl(apiClient(engine), config()) to requests
    }

    private fun HttpRequestData.bodyText(): String =
        (body as io.ktor.http.content.TextContent).text

    private fun apiClient(engine: MockEngine) = ApiClient(
        httpClient = HttpClientFactory.create({ ChatExportMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private fun config() = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private fun message(payload: String) =
        SocketMessage(ZillitSocketEvents.ChatExport.Updated, Json.parseToJsonElement(payload))

    private fun envelope(message: String, data: String) =
        """{"status":1,"message":"$message","messageElements":[],"data":$data}"""

    private fun export(status: String) =
        """{"export_id":"e1","status":"$status","include_media":true,"requested_on":1791975262243}"""

    /** An envelope carrying every shape the five routes read, so one answer serves them all. */
    private fun anyEnvelope() = envelope(
        "ok",
        """{"export":${export("queued")},"exports":[${export("queued")}],"url":"https://s3/x"}""",
    )
}

private class ChatExportMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
