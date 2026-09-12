package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.taxfiling.data.TaxFilingRepositoryImpl
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.RegistrationRequest
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The repository against a mock server — the envelope, the paths and the
 * verbs, which a DTO test cannot see.
 */
class TaxFilingRepositoryTest {

    private data class Sent(val method: HttpMethod, val path: String, val query: String, val body: String)

    private val sent = mutableListOf<Sent>()

    private fun repository(answer: (path: String) -> String): TaxFilingRepositoryImpl {
        val engine = MockEngine { request ->
            sent += Sent(
                method = request.method,
                path = request.url.encodedPath,
                query = request.url.encodedQuery,
                body = (request.body as? TextContent)?.text.orEmpty(),
            )
            respond(
                content = answer(request.url.encodedPath),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return TaxFilingRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ MockFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private fun <T> ZillitResult<T>.orFail(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> fail("expected success, got $error")
    }

    /**
     * `box-map` answers its rows one level down, at `data.rows` — the web's
     * `res.data.rows`. Read as a bare list, the whole answer is refused and the
     * company's saved mapping never appears.
     */
    @Test
    fun `the saved box map is read from data rows`() = runTest {
        val repo = repository {
            """{"status":1,"data":{"rows":[{"box":"box1","codes":["4000"]},{"box":"box8","mark_zero":true}]}}"""
        }

        val rows = repo.boxMap("co-1").orFail()

        assertEquals(listOf("box1", "box8"), rows.map { it.box })
        val call = sent.single()
        assertEquals("/api/v2/tax-filing/box-map", call.path)
        assertEquals("companyId=co-1", call.query)
    }

    /** Saving replaces the whole map with a PUT, under the company. */
    @Test
    fun `saving the map is a put of every configured row`() = runTest {
        val repo = repository { """{"status":1,"data":{"rows":[]}}""" }

        repo.saveBoxMap(
            "co-1",
            listOf(
                BoxMapping(box = "box1", codes = listOf("4000")),
                BoxMapping(box = "box2"),
            ),
        ).orFail()

        val call = sent.single()
        assertEquals(HttpMethod.Put, call.method)
        val rows = Json.parseToJsonElement(call.body).jsonObject["rows"].toString()
        assertTrue(rows.contains("box1") && !rows.contains("box2"))
    }

    @Test
    fun `a registration is posted with the fields the web sends`() = runTest {
        val repo = repository { """{"status":1,"data":{"id":"r1"}}""" }

        repo.createRegistration(
            RegistrationRequest(
                companyId = "co-1",
                registrationNumber = "123456789",
                filingFrequency = "quarterly",
                registrationDate = "2026-04-01",
            ),
        ).orFail()

        val call = sent.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/v2/tax-filing/registrations", call.path)
        val body = Json.parseToJsonElement(call.body).jsonObject
        assertEquals("GB", body["country_code"]?.jsonPrimitive?.content)
        assertEquals("VAT", body["regime"]?.jsonPrimitive?.content)
        assertEquals("2026-04-01", body["registration_date"]?.jsonPrimitive?.content)
    }

    /** The data export is saved as the web saves it: `JSON.stringify(data, null, 2)`. */
    @Test
    fun `the data export comes back as two-space pretty JSON`() = runTest {
        val repo = repository { """{"status":1,"data":{"registration":{"id":"r1"},"returns":[]}}""" }

        val text = repo.exportRegistration("r1").orFail()

        assertEquals("/api/v2/tax-filing/registrations/r1/export", sent.single().path)
        assertEquals("{\n  \"registration\": {\n    \"id\": \"r1\"\n  },\n  \"returns\": []\n}", text)
    }

    /** The per-country routes carry the adapter's country, and the draft asks for one period. */
    @Test
    fun `the return routes are under the country adapter`() = runTest {
        val repo = repository { path ->
            when {
                path.endsWith("/obligations") -> """{"status":1,"data":[{"period_key":"18A1","status":"O"}]}"""
                path.endsWith("/returns/draft") -> """{"status":1,"data":{"payload":{"vatDueSales":"10.00"}}}"""
                path.endsWith("/oauth/authorize") -> """{"status":1,"data":{"url":"https://hmrc.test/consent"}}"""
                else -> """{"status":1,"data":[]}"""
            }
        }

        assertEquals("18A1", repo.obligations("r1").orFail().single().periodKey)
        assertEquals(10.0, repo.buildDraft("r1", "18A1").orFail().vatReturn.values.values.single())
        assertEquals("https://hmrc.test/consent", repo.connectUrl("r1").orFail())

        assertEquals(
            listOf(
                "/api/v2/tax-filing/GB/registrations/r1/obligations",
                "/api/v2/tax-filing/GB/registrations/r1/returns/draft",
                "/api/v2/tax-filing/GB/oauth/authorize",
            ),
            sent.map { it.path },
        )
        assertTrue(sent[1].body.contains("\"periodKey\":\"18A1\""))
        assertEquals("registrationId=r1", sent[2].query)
    }

    /** The pickers' lists come from the account hub, in the shapes it answers with. */
    @Test
    fun `the picker lists are read from the account hub`() = runTest {
        val repo = repository { path ->
            when {
                path.endsWith("/chart-of-accounts") ->
                    """{"status":1,"data":[{"code":"4000","name":"Sales","line_type":"category"}]}"""
                path.endsWith("/tracking-sets") ->
                    """{"status":1,"data":[{"id":"s1","name":"Locations","nodes":[{"id":"n1","code":"LOC"}]}]}"""
                path.endsWith("/asset-tags") -> """{"status":1,"data":{"value":["VFX","CAMERA","VFX"," "]}}"""
                else -> """{"status":1,"data":[]}"""
            }
        }

        assertEquals(listOf("4000"), repo.coaCodes().orFail().map { it.code })
        assertEquals(listOf("LOC"), repo.layerSets().orFail().single().codes.map { it.code })
        assertEquals(listOf("VFX", "CAMERA"), repo.assetTags().orFail())
        assertTrue(sent.first().query.contains("active_only=true"))
        assertTrue(sent[1].query.contains("include_nodes=true"))
    }
}

private class MockFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
