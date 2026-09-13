package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a board's chat calls go. Every board keeps its routes under
 * `<board>/chat/`; the production report's unit chat hangs them straight off
 * `production-report/` on the report service (the web's `productionReportApi`,
 * Android's `PRODUCTION_REPORT_CHAT_*`).
 */
class BoardChatRoutesTest {

    private val calls = mutableListOf<String>()

    private val plain = object : NoticeDecryptor {
        override fun decryptFromHex(cipherHex: String): ZillitResult<String> = ZillitResult.Success(cipherHex)
        override fun encryptToHex(plaintext: String): ZillitResult<String> = ZillitResult.Success(plaintext)
    }

    /** A null [chatSegment] leaves the parameter out, as every existing board's wiring does. */
    private fun repository(board: String, service: ZillitService, chatSegment: String? = null): HomeFeedRepositoryImpl {
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.host}${request.url.encodedPath}"
            respond(
                """{"status":1,"message":"ok","data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ RoutesEngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
        val config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        )
        return if (chatSegment == null) {
            HomeFeedRepositoryImpl(apiClient, config, plain, isAdmin = { false }, board = board, service = service)
        } else {
            HomeFeedRepositoryImpl(
                apiClient,
                config,
                plain,
                isAdmin = { false },
                board = board,
                service = service,
                chatSegment = chatSegment,
            )
        }
    }

    private suspend fun HomeFeedRepositoryImpl.touchEveryChatRoute() {
        loadNotices("u1", beforeMillis = 5)
        postNotice("u1", "hello", "local-1", attachment = null, location = null)
        postComment("n1", "u1", "reply")
        editComment("n1", "c1", "again")
        deleteComment("n1", "c1")
        editNotice("n1", "edited")
        deleteNotice("n1")
        readBy("n1")
    }

    @Test
    fun `an ordinary board keeps its chat segment`() = runTest {
        repository("info", ZillitService.Units).touchEveryChatRoute()
        val host = "units.test"
        assertEquals(
            listOf(
                "GET $host/api/v2/info/chat/u1/5/previous",
                "POST $host/api/v2/info/chat",
                "POST $host/api/v2/info/chat/comments/n1",
                "PUT $host/api/v2/info/chat/comments/n1/c1",
                "DELETE $host/api/v2/info/chat/comments/n1/c1",
                "PUT $host/api/v2/info/chat/n1",
                "PUT $host/api/v2/info/chat/delete/chats",
                "GET $host/api/v2/info/chat/readby/n1",
            ),
            calls,
        )
    }

    @Test
    fun `the production report's unit chat has no chat segment and lives on the report service`() = runTest {
        repository("production-report", ZillitService.ProductionReport, chatSegment = "").touchEveryChatRoute()
        val host = "productionreport.test"
        assertEquals(
            listOf(
                "GET $host/api/v2/production-report/u1/5/previous",
                "POST $host/api/v2/production-report",
                "POST $host/api/v2/production-report/comments/n1",
                "PUT $host/api/v2/production-report/comments/n1/c1",
                "DELETE $host/api/v2/production-report/comments/n1/c1",
                "PUT $host/api/v2/production-report/n1",
                "PUT $host/api/v2/production-report/delete/chats",
                "GET $host/api/v2/production-report/readby/n1",
            ),
            calls,
        )
    }
}

private class RoutesEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
