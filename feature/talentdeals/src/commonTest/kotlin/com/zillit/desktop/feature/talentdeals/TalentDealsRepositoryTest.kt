@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.talentdeals.data.TalentDealsRepositoryImpl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every route the tool calls, over a mock server that records what it is sent:
 * which host, which verb, which path — and that no project id rides the URL or
 * the body (the encrypted `moduledata` header carries the project).
 */
class TalentDealsRepositoryTest {

    private data class Call(val method: HttpMethod, val url: String, val path: String, val query: String,
        val body: JsonElement?)

    private val calls = mutableListOf<Call>()
    private var answer: (Call) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"status":1,"data":[]}""" }

    private val engine = MockEngine { request ->
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) }
        val call = Call(request.method, request.url.toString(), request.url.encodedPath, request.url.encodedQuery, body)
        calls += call
        val (status, text) = answer(call)
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private val repository = TalentDealsRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ MockFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = config,
    )

    private val listBody = """{"status":1,"data":[
        {"_id":"c1","talent_reference":"TC-1","person":"Jane Doe","role":"Cast","status":"issued","fee":100,"contract_currency":"GBP"},
        {"person":"No id"},
        {"_id":"c2","talent_reference":"TC-2","person":"Sam Poe","role":"Director","status":"issued","fee":200}]}"""

    @Test
    fun `the register reads the list on the deal-memo host with no project id anywhere`() = runTest {
        answer = { HttpStatusCode.OK to listBody }
        val result = repository.contracts()

        val rows = assertIs<ZillitResult.Success<*>>(result).data as List<*>
        assertEquals(2, rows.size)
        val call = calls.single()
        assertEquals(HttpMethod.Get, call.method)
        assertEquals("https://dealmemo.test/api/v2/deal-memo/talent-contracts", call.url)
        assertEquals("", call.query)
    }

    @Test
    fun `a list the backend serialises as an empty object is an empty list`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"data":{}}""" }
        val rows = repository.contracts()
        assertEquals(emptyList(), (rows as ZillitResult.Success).data)
    }

    @Test
    fun `one contract is read by id, encoded`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"data":{"_id":"c 1","person":"Jane"}}""" }
        val result = repository.contract("c 1")
        assertEquals("Jane", (result as ZillitResult.Success).data.person)
        assertEquals("/api/v2/deal-memo/talent-contracts/c%201", calls.single().path)
    }

    @Test
    fun `a contract the server answers with nothing is not found`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"data":null}""" }
        val result = repository.contract("gone")
        assertEquals("Contract not found.", assertIs<ZillitResult.Failure>(result).error.userMessage)
    }

    @Test
    fun `a create posts the body and answers the new id and the server's words`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"message":"talent_contract_created","data":{"_id":"new1"}}""" }
        val body = buildJsonObject { put("person", "Jane"); put("status", "issued") }
        val saved = (repository.create(body) as ZillitResult.Success).data

        assertEquals("new1", saved.id)
        assertEquals("talent_contract_created", saved.message)
        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/v2/deal-memo/talent-contracts", call.path)
        assertEquals(body, call.body)
    }

    @Test
    fun `an update patches the whole body to the contract's own route`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"data":{"_id":"c1"}}""" }
        val body = buildJsonObject { put("person", "Jane Doe") }
        repository.update("c1", body)

        val call = calls.single()
        assertEquals(HttpMethod.Patch, call.method)
        assertEquals("/api/v2/deal-memo/talent-contracts/c1", call.path)
        assertEquals(body, call.body)
        assertTrue("project_id" !in call.body!!.jsonObject)
    }

    @Test
    fun `a remove is a soft delete on the contract's route`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"message":"talent_contract_deleted"}""" }
        val message = (repository.remove("c1") as ZillitResult.Success).data
        assertEquals("talent_contract_deleted", message)
        assertEquals(HttpMethod.Delete, calls.single().method)
        assertEquals("/api/v2/deal-memo/talent-contracts/c1", calls.single().path)
    }

    @Test
    fun `a 200 that states status 0 is a refusal, not a save`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":0,"message":"talent_contract_validation"}""" }
        val failure = assertIs<ZillitResult.Failure>(repository.create(buildJsonObject { put("person", "x") }))
        assertEquals("talent_contract_validation", assertIs<ZillitError.Http>(failure.error).serverMessage)
    }

    @Test
    fun `an HTTP failure carries the server's message key`() = runTest {
        answer = { HttpStatusCode.BadRequest to """{"status":0,"message":"talent_contract_validation"}""" }
        val failure = assertIs<ZillitResult.Failure>(repository.update("c1", buildJsonObject { put("person", "x") }))
        assertEquals("talent_contract_validation", assertIs<ZillitError.Http>(failure.error).serverMessage)
    }

    @Test
    fun `unions are asked per crew type and territory`() = runTest {
        answer = {
            HttpStatusCode.OK to """{"status":1,"data":[
              {"union_id":"1","union_identifier":"sag-aftra","union_name":"Screen Actors Guild - AFTRA"},
              {"union_name":"no slug"}]}"""
        }
        val unions = (repository.unions("cast", "us") as ZillitResult.Success).data
        assertEquals(listOf("sag-aftra"), unions.map { it.identifier })
        assertEquals("/api/v2/deal-memo/talent-deals/unions/cast/us", calls.single().path)

        repository.unions("", "uk")
        assertEquals("/api/v2/deal-memo/talent-deals/unions/all/uk", calls.last().path)
    }

    @Test
    fun `Exhibit G is read from the AD dashboard host by the typed name`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1,"data":{"reports":[{"report_id":"r1","shoot_date":1767571200000,"rows":[{"row_id":"a","row_index":0,"name":"Jane Doe","signed":true}]}]}}""" }
        val days = (repository.exhibitG("Jane Doe") as ZillitResult.Success).data

        assertEquals(1, days.size)
        assertEquals("Jane Doe", days[0].rows[0].name)
        val call = calls.single()
        assertEquals("https://addashboard.test/api/v2/ad-report/exhibit-g-reports/signing-rows",
            call.url.substringBefore('?'))
        assertEquals("username=Jane%20Doe", call.query.replace("+", "%20"))
    }

    @Test
    fun `a name found nowhere is a 404 whose message the panel shows`() = runTest {
        answer = { HttpStatusCode.NotFound to """{"status":0,"message":"exhibit_g_person_not_found"}""" }
        val failure = assertIs<ZillitResult.Failure>(repository.exhibitG("Nobody"))
        assertEquals("exhibit_g_person_not_found", assertIs<ZillitError.Http>(failure.error).serverMessage)
    }

    @Test
    fun `no id is a null message on a quiet success`() = runTest {
        answer = { HttpStatusCode.OK to """{"status":1}""" }
        val saved = (repository.create(buildJsonObject { put("person", "x") }) as ZillitResult.Success).data
        assertNull(saved.id)
        assertNull(saved.message)
        assertEquals("x", calls.single().body!!.jsonObject["person"]!!.jsonPrimitive.content)
    }

    private class MockFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }
}
