package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.bankrec.data.BankRecRepositoryImpl
import com.zillit.desktop.feature.bankrec.domain.ServerCounts
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What goes on the wire to take a match back, and what comes back from
 * `workspace-data`.
 *
 * The single route takes no body and answers a message key; the bulk route
 * takes `transaction_ids` (1–50) and names none. Both are the web client's
 * (`bank-reconciliation.js`), which is the authority for this host — the usual
 * 406 probe cannot tell a real path from an invented one here.
 */
class BankRecUnmatchWireTest {

    private class Call(val method: String, val path: String, val body: JsonObject?)

    private fun repository(reply: String): Pair<BankRecRepositoryImpl, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            calls += Call(request.method.value, request.url.encodedPath, body)
            respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = BankRecRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ UnmatchMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to calls
    }

    @Test
    fun `one line is unmatched with a bodiless post and answers the server's message`() = runTest {
        val (repo, calls) = repository("""{"status":1,"message":"br_transaction_unmatched","data":{}}""")

        val result = repo.unmatchTransaction("t3")

        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v2/bank-reconciliations/t3/unmatch", call.path)
        assertNull(call.body)
        assertEquals("br_transaction_unmatched", (result as ZillitResult.Success).data)
    }

    @Test
    fun `several lines go as transaction_ids and name no message`() = runTest {
        val (repo, calls) = repository(
            """{"status":1,"data":{"unmatched":2,"skipped":0,"unmatched_ids":["t3","t4"]}}""",
        )

        val result = repo.bulkUnmatchTransactions(listOf("t3", "t4"))

        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v2/bank-reconciliations/bulk-unmatch", call.path)
        assertEquals(listOf("t3", "t4"), (call.body!!["transaction_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertNull((result as ZillitResult.Success).data)
    }

    /** A 200 that says `status: 0` is a refusal, and must not be toasted as a success. */
    @Test
    fun `a refusal inside a 200 is a failure`() = runTest {
        val (repo, _) = repository("""{"status":0,"message":"br_transaction_not_matched"}""")

        assertTrue(repo.unmatchTransaction("t1") is ZillitResult.Failure)
    }

    @Test
    fun `workspace-data carries the tab counts`() = runTest {
        val (repo, _) = repository(
            """{"status":1,"data":{"transactions":[],"invoices":[],"counts":{"all":5,"matched":2,"suggested":1,""" +
                """"unmatched":1,"fraud":1,"fx":0}}}""",
        )

        val data = (repo.workspace("p1") as ZillitResult.Success).data

        val counts = data.counts
        assertIs<ServerCounts>(counts)
        assertEquals(5, counts.all)
        assertEquals(1, counts.fraud)
        assertEquals(0, counts.fx)
    }

    @Test
    fun `workspace-data without counts reads as none`() = runTest {
        val (repo, _) = repository("""{"status":1,"data":{"transactions":[],"invoices":[]}}""")

        assertNull((repo.workspace("p1") as ZillitResult.Success).data.counts)
    }
}

private class UnmatchMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
