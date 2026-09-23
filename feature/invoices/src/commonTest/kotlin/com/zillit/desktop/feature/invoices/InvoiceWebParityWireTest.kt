package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.invoices.data.InvoicesRepositoryImpl
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryWrite
import com.zillit.desktop.feature.invoices.domain.HoldReason
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
import kotlin.test.assertTrue

/**
 * The routes and bodies the web sends, through the app's own HTTP stack.
 *
 * Each of these was a place the desktop wrote something the web does not:
 * two status PATCHes for an override, `/dispute` to resolve a dispute, a
 * translated hold reason, and a run approval with no tier.
 */
class InvoiceWebParityWireTest {

    @Test
    fun `override is one POST to the override route with an empty body`() = runTest {
        val (repo, sent) = repository()

        repo.override("inv1")

        val call = sent.single()
        assertEquals(HttpMethod.Post, call.method)
        assertTrue(call.path.endsWith("/api/v2/invoices/inv1/override"), call.path)
        assertEquals(JsonObject(emptyMap()), call.body)
    }

    @Test
    fun `the hold reason goes as the web's fixed English, camelCase`() = runTest {
        val (repo, sent) = repository()

        repo.hold("inv1", HoldReason.AwaitingHodConfirmation, "  chasing the HoD  ")

        val call = sent.single()
        assertTrue(call.path.endsWith("/invoices/inv1/hold"), call.path)
        assertEquals("Awaiting HoD Confirmation", call.body?.get("holdReason")?.jsonPrimitive?.content)
        assertEquals("chasing the HoD", call.body?.get("notes")?.jsonPrimitive?.content)
    }

    @Test
    fun `every hold reason has the web's wire value`() {
        assertEquals(
            listOf(
                "Invoice Adjustment Required",
                "Awaiting Credit Note",
                "PO Amendment Needed",
                "Querying Amount with Vendor",
                "Missing Supporting Documentation",
                "Tax Query",
                "Duplicate Invoice Check",
                "Awaiting HoD Confirmation",
                "Other (specify in notes)",
            ),
            HoldReason.entries.map { it.wire },
        )
    }

    @Test
    fun `applying a credit note posts to apply`() = runTest {
        val (repo, sent) = repository()

        repo.applyCreditNote("cn1")

        assertTrue(sent.single().path.endsWith("/invoices/credit-notes/cn1/apply"))
    }

    @Test
    fun `approving a run sends the tier being signed and the chain's length`() = runTest {
        val (repo, sent) = repository()

        repo.approvePaymentRun("run1", tierNumber = 2, totalTiers = 3)

        val call = sent.single()
        assertTrue(call.path.endsWith("/invoices/active-runs/run1/approve"), call.path)
        assertEquals(2, call.body?.get("tier_number")?.jsonPrimitive?.content?.toInt())
        assertEquals(3, call.body?.get("total_tiers")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `a run's detail reads the run and the invoices it pays`() = runTest {
        val (repo, sent) = repository(
            answer = {
                """{"status":1,"data":{"run":{"id":"run1","number":"PR-004","status":"pending",
                "approval":"[{\"tier_number\":1,\"user_id\":\"u2\"}]"},
                "invoices":[{"_id":"i1","invoice_number":"INV-1","gross_amount":120}]}}"""
            },
        )

        val detail = repo.paymentRun("run1")

        assertTrue(sent.single().path.endsWith("/invoices/active-runs/run1"))
        val data = (detail as com.zillit.desktop.core.common.ZillitResult.Success).data
        assertEquals("PR-004", data.run.number)
        assertEquals(1, data.run.approvals.single().tierNumber)
        assertEquals("u2", data.run.approvals.single().userId)
        assertEquals(listOf("i1"), data.invoices.map { it.id })
    }

    @Test
    fun `save writes the entry with a PATCH on the invoice`() = runTest {
        val (repo, sent) = repository()

        repo.saveEntry("inv1", EntryWrite(header = EntryHeader(invoiceNumber = "INV-1"), lines = emptyList()))

        val call = sent.single()
        assertEquals(HttpMethod.Patch, call.method)
        assertTrue(call.path.endsWith("/api/v2/invoices/inv1"), call.path)
        assertEquals("INV-1", call.body?.get("invoice_number")?.jsonPrimitive?.content)
    }

    @Test
    fun `a query thread is read by entity, opened, then added to - on the hub`() = runTest {
        val (repo, sent) = repository(
            answer = { """{"status":1,"data":{"id":"q1","queries":[{"query":"Hi","queried_by":"u1"}]}}""" },
        )

        val thread = repo.queryThread("inv1")
        repo.openQuery("inv1", " Which PO? ")
        repo.addQuery("q1", "Thanks")

        assertEquals("q1", (thread as com.zillit.desktop.core.common.ZillitResult.Success).data.id)
        assertTrue(sent[0].path.endsWith("/account-hub/queries/entity/invoice/inv1"), sent[0].path)
        assertTrue(sent[0].url.startsWith("https://accounthub.test"), sent[0].url)
        assertTrue(sent[1].path.endsWith("/account-hub/queries"), sent[1].path)
        assertEquals("invoice", sent[1].body?.get("entity_type")?.jsonPrimitive?.content)
        assertEquals("inv1", sent[1].body?.get("entity_id")?.jsonPrimitive?.content)
        assertEquals("Which PO?", sent[1].body?.get("query")?.jsonPrimitive?.content)
        assertTrue(sent[2].path.endsWith("/account-hub/queries/q1/add"), sent[2].path)
        assertEquals("Thanks", sent[2].body?.get("query")?.jsonPrimitive?.content)
    }

    /** The web's `useCrLock`: the lock route and the settings document, the later date winning. */
    @Test
    fun `the close boundary is the later of the lock route and the settings document`() = runTest {
        val (repo, sent) = repository(
            answer = { path ->
                if (path.endsWith("/lock-period")) {
                    """{"status":1,"data":{"lockedDate":"2026-09-13","tz":"Europe/London"}}"""
                } else {
                    """{"status":1,"data":{"settings":{"last_cr_locked_date":"2026-09-20"}}}"""
                }
            },
        )

        val lock = (repo.periodLock() as com.zillit.desktop.core.common.ZillitResult.Success).data

        assertEquals("2026-09-20", lock.lockedThrough)
        assertEquals("Europe/London", lock.timeZone)
        assertTrue(
            sent.any { it.url.startsWith("https://costreport.test") && it.path.endsWith("/cost-reports/lock-period") },
        )
        assertTrue(sent.any { it.path.endsWith("/account-hub/project-settings") })
    }

    private data class Sent(val method: HttpMethod, val path: String, val body: JsonObject?, val url: String = "")

    private fun repository(
        answer: (String) -> String = { """{"status":1,"data":{}}""" },
    ): Pair<InvoicesRepositoryImpl, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            sent += Sent(request.method, request.url.encodedPath, body, request.url.toString())
            respond(
                answer(request.url.encodedPath),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = InvoicesRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ InvoicesMockEngineFactory(engine) }),
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

private class InvoicesMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
