package com.zillit.desktop.feature.email

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.email.data.DraftRepositoryImpl
import com.zillit.desktop.feature.email.domain.OutgoingEmail
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * What the draft store is actually sent.
 *
 * The idempotent create is a wire contract: `unique_id` in the POST body, the
 * created row's `_id` in the answer. A key that never left the client, or an
 * id read from the wrong field, would each look fine in the ViewModel tests
 * and still duplicate drafts in production.
 */
class DraftWireTest {

    private val message = OutgoingEmail(to = listOf("crew@prod.com"), subject = "Hi", body = "<p>Hi</p>")

    @Test
    fun `a create posts the key and reads the id back`() = runTest {
        val (repo, sent) = repository()

        val result = repo.saveDraft(message, "6d48f7f5-9ee7-4d21-ab56-2ce2a92d3804")

        assertIs<ZillitResult.Success<String>>(result)
        assertEquals("SERVER_DRAFT_ID", result.data)
        val (request, body) = sent.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v2/email-draft", request.url.encodedPath)
        assertEquals("6d48f7f5-9ee7-4d21-ab56-2ce2a92d3804", body["unique_id"]?.jsonPrimitive?.content)
        // The rest of the draft still travels alongside it.
        assertEquals("Hi", body["subject"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an update names its draft in the URL and sends no key`() = runTest {
        val (repo, sent) = repository()

        repo.updateDraft("SERVER_DRAFT_ID", message)

        val (request, body) = sent.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v2/email-draft/SERVER_DRAFT_ID", request.url.encodedPath)
        assertFalse("unique_id" in body)
    }

    private fun repository(): Pair<DraftRepositoryImpl, MutableList<Pair<HttpRequestData, JsonObject>>> {
        val sent = mutableListOf<Pair<HttpRequestData, JsonObject>>()
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
            if (body != null) sent += request to body
            val key = body?.get("unique_id")?.jsonPrimitive?.content
            respond(
                """{"status":1,"message":"email_draft_save_success","messageElements":[],""" +
                    """"data":{"_id":"SERVER_DRAFT_ID","unique_id":"$key","subject":"Hi","body":"<p>Hi</p>"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = DraftRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ DraftMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to sent
    }
}

private class DraftMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
