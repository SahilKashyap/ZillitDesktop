package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scope travels with every save of the pay breakdown.
 *
 * It was left out of the body entirely until 2026-09-10: the desktop read
 * three rule lists and wrote three rule lists, so a production that had scoped
 * its overtime to two departments on the web had that scoping dropped by
 * anybody who edited an unrelated rule here. There is no error in that path —
 * only a payroll run that pays the wrong people.
 */
class PayScopeWireTest {

    @Test
    fun `a department scope is sent with the rules`() = runTest {
        val (repo, sent) = repository()

        repo.saveNonUnionPay(NonUnionPay().appliedTo(listOf("d1", "d2")))

        val body = sent.single()
        assertEquals("departments", body["apply_mode"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("d1", "d2"),
            (body["department_ids"] as JsonArray).map { it.jsonPrimitive.content },
        )
        // The three rule lists still go, of course.
        assertTrue(listOf("overtimes", "premiums", "penalties").all { it in body })
    }

    /** Everyone is sent as itself, with the department list emptied. */
    @Test
    fun `applying to everyone is sent, not simply omitted`() = runTest {
        val (repo, sent) = repository()

        repo.saveNonUnionPay(NonUnionPay().appliedTo(listOf("d1")).appliedToEveryone())

        val body = sent.single()
        assertEquals("all", body["apply_mode"]?.jsonPrimitive?.content)
        assertTrue((body["department_ids"] as JsonArray).isEmpty())
    }

    /**
     * The pristine state is sent as null rather than left out.
     *
     * Omitting the key would let a merge on the server keep whatever scope was
     * there before, which is the opposite of what "nobody has chosen" means.
     */
    @Test
    fun `an unset scope is sent as null`() = runTest {
        val (repo, sent) = repository()

        repo.saveNonUnionPay(NonUnionPay())

        assertEquals(JsonNull, sent.single()["apply_mode"])
    }

    private fun repository(): Pair<AccountHubRepositoryImpl, MutableList<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request: HttpRequestData ->
            (request.body as? TextContent)?.text?.let {
                sent += Json.parseToJsonElement(it) as JsonObject
            }
            respond(
                """{"status":1,"data":{"value":{"overtimes":[],"premiums":[],"penalties":[]}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ScopeMockEngineFactory(engine) }),
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

private class ScopeMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
