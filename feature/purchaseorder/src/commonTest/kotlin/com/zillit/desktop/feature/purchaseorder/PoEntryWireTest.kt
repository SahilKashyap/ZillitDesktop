package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.purchaseorder.data.PurchaseOrderRepositoryImpl
import com.zillit.desktop.feature.purchaseorder.domain.PoEntryHeader
import com.zillit.desktop.feature.purchaseorder.domain.PoEntryUpdate
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoPostRequest
import com.zillit.desktop.feature.purchaseorder.domain.PoVat
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the order workflow puts on the wire, through the repository and the
 * app's own client stack over a mock engine — every body key for key as the
 * web sends it (`PurchaseOrdersModule.jsx`, `POEntry.jsx`, `QueryPanel.jsx`).
 */
class PoEntryWireTest {

    private class Sent(val method: String, val path: String, val query: String, val body: JsonObject?)

    private val sent = mutableListOf<Sent>()

    private fun repository(answer: (String) -> String = { """{"status":1,"data":{}}""" }): PurchaseOrderRepositoryImpl {
        val engine = MockEngine { request: HttpRequestData ->
            val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            sent += Sent(
                method = request.method.value,
                path = request.url.encodedPath,
                query = request.url.encodedQuery,
                body = text?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject },
            )
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            respond(answer(request.url.encodedPath), HttpStatusCode.OK, json)
        }
        return PurchaseOrderRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ PoMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private val header = PoEntryHeader(
        vendorId = "v-1",
        companyId = null,
        departmentId = "dept-cam",
        nominalCode = "2400",
        currency = "GBP",
        deliveryDate = null,
    )

    private val lines = listOf(
        PoLine(
            id = "l1",
            description = "Camera body",
            quantity = 2.0,
            unitPrice = 50.0,
            nominalCode = "2400",
            vatRate = 20.0,
            taxType = "GB_vat_standard",
            trackingCodes = JsonObject(mapOf("set-1" to JsonPrimitive("node-9"))),
            customFields = JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Serial"))))),
        ),
        PoLine(id = "tax-po-1", description = "", quantity = 1.0, unitPrice = 20.0, nominalCode = "2200",
            vatRate = null, amount = 20.0, isTax = true),
    )

    @Test
    fun `approve sends the tier it decides and the chain's length`() = runTest {
        repository().approve("po-1", tierNumber = 2, totalTiers = 3)
        val call = sent.single()
        assertEquals("POST", call.method)
        assertTrue(call.path.endsWith("/api/v2/purchase-orders/po-1/approve"))
        assertEquals(2, call.body!!["tier_number"]!!.jsonPrimitive.int)
        assertEquals(3, call.body["total_tiers"]!!.jsonPrimitive.int)
        assertEquals(setOf("tier_number", "total_tiers"), call.body.keys)
    }

    @Test
    fun `close sends the reason and the effective date, both always`() = runTest {
        repository().close("po-1", reason = "Wrapped", effectiveDate = 1_788_134_400_000L)
        val body = sent.single().body!!
        assertEquals("Wrapped", body["reason"]!!.jsonPrimitive.content)
        assertEquals(1_788_134_400_000L, body["effective_date"]!!.jsonPrimitive.long)

        sent.clear()
        repository().close("po-1", reason = "", effectiveDate = null)
        val bare = sent.single().body!!
        assertEquals("", bare["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, bare["effective_date"])
    }

    /** The web's `handleSavePOEntry` PATCH: snake_case, the whole array, the tax row included. */
    @Test
    fun `the processing page saves the web's PATCH, not the create body`() = runTest {
        val update = PoEntryUpdate(header, lines, PoVat.of(100.0, "pending"), effectiveDate = 1_788_134_400_000L)
        repository().saveEntry("po-1", update)
        val call = sent.single()
        assertEquals("PATCH", call.method)
        assertTrue(call.path.endsWith("/api/v2/purchase-orders/po-1"))
        val body = call.body!!
        assertEquals("pending", body["vat_treatment"]!!.jsonPrimitive.content)
        assertEquals("v-1", body["vendor_id"]!!.jsonPrimitive.content)
        assertEquals("2400", body["nominal_code"]!!.jsonPrimitive.content)
        assertEquals(1_788_134_400_000L, body["effective_date"]!!.jsonPrimitive.long)
        // Nothing the page does not edit: a partial copy must not overwrite them.
        listOf("description", "attachments", "custom_fields", "net_amount", "status").forEach { key ->
            assertTrue(key !in body, "$key rode along")
        }
        val items = body["line_items"]!!.jsonArray
        assertEquals(2, items.size)
        val coded = items[0].jsonObject
        assertEquals("node-9", coded["tracking_codes"]!!.jsonObject["set-1"]!!.jsonPrimitive.content)
        assertEquals("Serial", coded["custom_fields"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("2400", coded["account"]!!.jsonPrimitive.content)
        val tax = items[1].jsonObject
        assertTrue(tax["is_tax"]!!.jsonPrimitive.boolean)
        assertEquals("tax-po-1", tax["id"]!!.jsonPrimitive.content)
        assertEquals("2200", tax["account"]!!.jsonPrimitive.content)
    }

    /** The web's `handlePostPO` body — camelCase outside, snake_case inside. */
    @Test
    fun `post carries the lines and the header in the web's envelope`() = runTest {
        val request = PoPostRequest(
            header = header,
            lines = lines,
            vat = PoVat.of(100.0, "standard_20"),
            effectiveDate = 1_788_134_400_000L,
            description = "Camera package",
            notes = null,
            episode = "Ep 1",
            deliveryAddress = JsonObject(mapOf("line1" to JsonPrimitive("Pinewood"))),
            deliveryAddressId = "addr-1",
        )
        repository().post("po-1", request)
        val call = sent.single()
        assertTrue(call.path.endsWith("/api/v2/purchase-orders/po-1/post"))
        val body = call.body!!
        assertEquals("standard_20", body["vatTreatment"]!!.jsonPrimitive.content)
        assertEquals(20.0, body["vatAmount"]!!.jsonPrimitive.content.toDouble())
        assertEquals(120.0, body["grossTotal"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1_788_134_400_000L, body["effectiveDate"]!!.jsonPrimitive.long)
        assertEquals(2, body["lineItems"]!!.jsonArray.size)
        val details = body["poDetails"]!!.jsonObject
        assertEquals("Camera package", details["description"]!!.jsonPrimitive.content)
        assertEquals("dept-cam", details["department_id"]!!.jsonPrimitive.content)
        assertEquals("Pinewood", details["delivery_address"]!!.jsonObject["line1"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, details["notes"])
    }

    @Test
    fun `a bulk reassign is one PATCH of the bulk route`() = runTest {
        repository().bulkReassign(listOf("a", "b"), userId = "acc-2", reason = "Workload")
        val call = sent.single()
        assertEquals("PATCH", call.method)
        assertTrue(call.path.endsWith("/api/v2/purchase-orders/bulk"))
        assertEquals(listOf("a", "b"), call.body!!["po_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        val data = call.body["data"]!!.jsonObject
        assertEquals("acc-2", data["assigned_to"]!!.jsonPrimitive.content)
        assertEquals("Workload", data["reassignment_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the tier configuration is read from the hub for purchase orders`() = runTest {
        val tiers = repository { """{"status":1,"data":$TIERS_JSON}""" }.approvalTiers()
        val call = sent.single()
        assertTrue(call.path.endsWith("/api/v2/account-hub/approval-tiers"))
        assertTrue("module=purchase_orders" in call.query)
        assertEquals(2, (tiers as ZillitResult.Success).data.resolve("dept-art", 10.0).size)
    }

    /** The web's `QueryPanel.handleSend`: the first message opens, the rest append. */
    @Test
    fun `a query opens its thread once and appends after`() = runTest {
        val thread = """{"status":1,"data":{"id":"q-1","queries":""" +
            """[{"query":"Why £20?","queried_by":"u","queried_at":1}]}}"""
        val repository = repository { thread }
        val opened = (repository.sendQuery("po-1", threadId = null, text = "Why £20?") as ZillitResult.Success).data
        val first = sent.single()
        assertTrue(first.path.endsWith("/api/v2/account-hub/queries"))
        assertEquals("purchase_order", first.body!!["entity_type"]!!.jsonPrimitive.content)
        assertEquals("po-1", first.body["entity_id"]!!.jsonPrimitive.content)
        assertEquals("q-1", assertNotNull(opened).id)

        sent.clear()
        repository.sendQuery("po-1", threadId = "q-1", text = "Answered")
        val second = sent.single()
        assertTrue(second.path.endsWith("/api/v2/account-hub/queries/q-1/add"))
        assertEquals(setOf("query"), second.body!!.keys)
    }

    @Test
    fun `an order nobody has queried reads as no thread, not an error`() = runTest {
        val answer = repository { """{"status":1,"data":null}""" }.queryThread("po-1")
        assertTrue(sent.single().path.endsWith("/api/v2/account-hub/queries/entity/purchase_order/po-1"))
        assertEquals(null, (answer as ZillitResult.Success).data)
    }

    @Test
    fun `the lock is the later of the lock route and the settings document`() = runTest {
        val repository = repository { path ->
            if (path.endsWith("/lock-period")) {
                """{"status":1,"data":{"lockedDate":"2026-08-23"}}"""
            } else {
                """{"status":1,"data":{"settings":{"last_cr_locked_date":"2026-08-30"}}}"""
            }
        }
        val lock = (repository.periodLock() as ZillitResult.Success).data
        assertEquals("2026-08-30", lock.lockedThrough)
    }
}

private class PoMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
