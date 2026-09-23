package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashReturn
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.PostBatchRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.TierStep
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keys the cash service reads on a write, as the web sends them.
 *
 * Every body here was wrong on the desktop and failed without an error: a
 * post with no `effective_date` (the server refuses it), an escalation whose
 * reason went out as `note`, a cash return whose amount went out as
 * `amount`, approvals with no level, a reconciliation of `counted_balance`.
 */
class CashWriteWireTest {

    // -- post & ledger --------------------------------------------------------

    @Test
    fun `a post sends the ledger date and the claims, lines as stored`() = runTest {
        val (repo, sent) = repository()
        val claim = claim().copy(
            rawLines = listOf(
                line("l1", "5010", isTax = false),
                line("tax", "2200", isTax = true),
            ),
        )

        repo.postBatch("b1", PostBatchRequest(effectiveDate = DAY, claims = listOf(claim)))

        val body = sent.single().body
        assertEquals(DAY, body["effective_date"]!!.jsonPrimitive.content.toLong())
        assertFalse("note" in body, "the old `{note}` body")
        val sentClaim = body["claims"]!!.jsonArray.single().jsonObject
        assertEquals("c1", sentClaim["id"]!!.jsonPrimitive.content)
        assertEquals("5010", sentClaim["cost_code"]!!.jsonPrimitive.content)
        val lines = sentClaim["line_items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("l1", "tax"), lines.map { it["id"]!!.jsonPrimitive.content })
        // The tax line keeps what makes it one; the DB-only key does not travel.
        assertEquals("true", lines[1]["is_tax"]!!.jsonPrimitive.content)
        assertFalse("claim_id" in lines[0], "a stored-row key the save does not take")
    }

    @Test
    fun `the senior's post sends notes and the date, not the claims`() = runTest {
        val (repo, sent) = repository()

        repo.postBatch("b1", PostBatchRequest(effectiveDate = DAY, seniorNotes = "Checked the fuel split"))

        val body = sent.single().body
        assertEquals("Checked the fuel split", body["senior_notes"]!!.jsonPrimitive.content)
        assertEquals(DAY, body["effective_date"]!!.jsonPrimitive.content.toLong())
        assertFalse("claims" in body)
    }

    /** `stripAutoDeductionLines`: the engine's rows never go back, tagged or de-tagged. */
    @Test
    fun `engine deduction rows are left out of the lines sent back`() {
        val element = Json.parseToJsonElement(
            """
            {"id":"c1","line_items":[
              {"id":"l1","account":"5010","total":"80"},
              {"id":"d1","account":"DEDUCT","total":"-20"},
              {"id":"d2","account":"5010","total":"-20","meta":{"auto":true}}
            ]}
            """.trimIndent(),
        )
        val dto = Json { ignoreUnknownKeys = true }
            .decodeFromJsonElement(com.zillit.desktop.feature.cashexpenses.data.ClaimDto.serializer(), element)
        assertEquals(listOf("l1"), dto.toDomain()!!.rawLines.map { it["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `an escalation sends escalation_reason`() = runTest {
        val (repo, sent) = repository()

        repo.escalateBatch("b1", "Over my limit")

        assertEquals("Over my limit", sent.single().body["escalation_reason"]!!.jsonPrimitive.content)
        assertFalse("note" in sent.single().body)
    }

    @Test
    fun `return to accounts is the deescalate route`() = runTest {
        val (repo, sent) = repository()
        repo.deescalateBatch("b1")
        assertTrue(sent.single().url.endsWith("/claims/b1/deescalate"))
    }

    // -- approvals ------------------------------------------------------------

    @Test
    fun `an approval signs its level`() = runTest {
        val (repo, sent) = repository()

        repo.approveFloat("f1", TierStep(tierNumber = 2, totalTiers = 3))
        repo.approveBatch("b1", TierStep(1, 2), claimIds = listOf("c2"))
        repo.approveBatch("b2", TierStep(1, 2), claimIds = null)

        val float = sent[0].body
        assertEquals("2", float["tier_number"]!!.jsonPrimitive.content)
        assertEquals("3", float["total_tiers"]!!.jsonPrimitive.content)
        val partial = sent[1].body
        assertEquals("approve", partial["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("c2"), partial["claim_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1", partial["tier_number"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, sent[2].body["claim_ids"], "the whole batch sends null, as the web does")
    }

    // -- floats and top-ups --------------------------------------------------------

    @Test
    fun `ready to collect carries the company and the bs code`() = runTest {
        val (repo, sent) = repository()

        repo.markFloatReadyToCollect("f1", "co-1", "1145")
        repo.markFloatReadyToCollect("f2", "co-1", " ")

        assertEquals("co-1", sent[0].body["company_id"]!!.jsonPrimitive.content)
        assertEquals("1145", sent[0].body["bs_code"]!!.jsonPrimitive.content)
        assertFalse("bs_code" in sent[1].body, "never an empty code")
    }

    @Test
    fun `a cash return sends return_amount and its reason`() = runTest {
        val (repo, sent) = repository()

        repo.recordCashReturn("f1", CashReturn(40.0, DAY, "close_full_return", "Handed in"))

        val body = sent.single().body
        assertEquals("40.0", body["return_amount"]!!.jsonPrimitive.content)
        assertEquals(DAY, body["received_date"]!!.jsonPrimitive.content.toLong())
        assertEquals("close_full_return", body["return_reason"]!!.jsonPrimitive.content)
        assertEquals("Handed in", body["reason_notes"]!!.jsonPrimitive.content)
        assertFalse("amount" in body)
    }

    @Test
    fun `a partial top-up sends its note`() = runTest {
        val (repo, sent) = repository()
        repo.partialTopUp("t1", 50.0, "Only £50 in the safe")
        assertEquals("Only £50 in the safe", sent.single().body["note"]!!.jsonPrimitive.content)
    }

    // -- reconciliation ---------------------------------------------------------------

    @Test
    fun `a new reconciliation opens a period with its count`() = runTest {
        val (repo, sent) = repository(response = """{"status":1,"data":{"id":"r1","status":"DRAFT"}}""")
        val draft = ReconDraft(
            id = null,
            currency = "GBP",
            openingBalance = "500",
            year = 2026,
            month = 9,
            denominations = ReconDraft.seedDenominations(1L),
        )

        val created = repo.createReconciliation(draft)

        assertIs<ZillitResult.Success<*>>(created)
        val body = sent.single().body
        assertEquals("500.0", body["opening_safe_balance"]!!.jsonPrimitive.content)
        assertEquals("GBP", body["currency"]!!.jsonPrimitive.content)
        assertTrue("period_start" in body && "period_end" in body)
        assertEquals(12, body["denominations"]!!.jsonArray.size)
        assertFalse("counted_balance" in body, "the old body")
    }

    @Test
    fun `a saved reconciliation sends the whole count`() = runTest {
        val (repo, sent) = repository(response = """{"status":1,"data":{"id":"r1","status":"DRAFT"}}""")
        val draft = ReconDraft(
            id = "r1",
            currency = "GBP",
            openingBalance = "100",
            year = 2026,
            month = 9,
            denominations = ReconDraft.seedDenominations(1L).map { if (it.value == "50") it.copy(count = "2") else it },
            computedBook = 110.0,
        )

        repo.updateReconciliation(draft)

        val body = sent.single().body
        assertEquals("100.0", body["physical_cash"]!!.jsonPrimitive.content)
        assertEquals("110.0", body["book_balance"]!!.jsonPrimitive.content)
        assertEquals("-10.0", body["variance"]!!.jsonPrimitive.content)
        assertTrue(sent.single().url.endsWith("/reconciliations/r1"))
    }

    // -- reads ---------------------------------------------------------------------

    /** `OOPPaymentPage.jsx:191-195` — the route's own names. */
    @Test
    fun `payment routing reads stats and the two lists`() = runTest {
        val (repo, _) = repository(
            response = """
                {"status":1,"data":{
                  "stats":{"bacs_ready":"1200.50","bacs_count":3,"payroll_total":400,"payroll_count":1},
                  "bacs_batches":[{"id":"b1","status":"POSTED"}],
                  "payroll_batches":[{"id":"b2","status":"POSTED"},{"id":"b3","status":"POSTED"}]
                }}
            """.trimIndent(),
        )

        val routing = (repo.paymentRouting() as ZillitResult.Success).data

        assertEquals(1200.5, routing.bacsReady)
        assertEquals(3, routing.bacsCount)
        assertEquals(400.0, routing.payrollTotal)
        assertEquals(listOf("b1"), routing.bacsBatches.map { it.id })
        assertEquals(2, routing.payrollBatches.size)
    }

    /** A null posting limit is unlimited; an absent one is no grant — the web's two answers. */
    @Test
    fun `metadata tells an unlimited limit from a missing one`() = runTest {
        val (unlimited, _) = repository(
            response = """{"status":1,"data":{"is_team_member":true,"posting_limit":null}}""",
        )
        val (missing, _) = repository(response = """{"status":1,"data":{"is_team_member":true}}""")

        assertTrue((unlimited.metadata() as ZillitResult.Success).data.postingLimitUnlimited)
        val absent = (missing.metadata() as ZillitResult.Success).data
        assertFalse(absent.postingLimitUnlimited)
        assertNull(absent.postingLimit)
    }

    @Test
    fun `the approval chains ride on the metadata`() = runTest {
        val (repo, _) = repository(
            response = """
                {"status":1,"data":{"approval_tier_configs":[
                  {"scope":"all","tiers":"[{\"rules\":[{\"type\":\"default\",\"user_ids\":[\"u1\"]}]}]"}
                ]}}
            """.trimIndent(),
        )

        val configs = (repo.metadata() as ZillitResult.Success).data.approvalTierConfigs

        assertEquals("all", configs.single().scope)
        assertEquals(listOf("u1"), configs.single().tiers.single().rules.single().userIds)
    }

    @Test
    fun `a reconciliation reads the web's fields`() = runTest {
        val (repo, _) = repository(
            response = """
                {"status":1,"data":[{"id":"r1","status":"draft","opening_safe_balance":"500","physical_cash":"480",
                  "book_balance":"500","variance":"-20","notes":"Short",
                  "denominations":"[{\"id\":\"n_50\",\"type\":\"note\",\"value\":\"50\",\"count\":\"2\"}]"}]}
            """.trimIndent(),
        )

        val recon = (repo.reconciliations() as ZillitResult.Success).data.single()

        assertEquals(480.0, recon.countedBalance)
        assertEquals(500.0, recon.openingBalance)
        assertEquals(-20.0, recon.variance)
        assertEquals("Short", recon.note)
        assertEquals("2", recon.denominations.single().count)
    }

    /** A 200 with `status: 0` is a refusal: "Posted" over it would be a lie about money. */
    @Test
    fun `a stated refusal is a failure`() = runTest {
        val (repo, _) = repository(response = """{"status":0,"message":"period_locked"}""")
        val result = repo.postBatch("b1", PostBatchRequest(effectiveDate = DAY, claims = emptyList()))
        assertIs<ZillitResult.Failure>(result)
    }

    // -- harness ---------------------------------------------------------------------

    private data class Sent(val method: String, val url: String, val body: JsonObject)

    private fun repository(response: String = """{"status":1,"data":{}}"""): Pair<CashRepositoryImpl, List<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            sent += Sent(request.method.value, request.url.toString(), body ?: buildJsonObject { })
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = CashRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CashMockEngineFactory(engine) }),
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

    private fun line(id: String, account: String, isTax: Boolean) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("account", JsonPrimitive(account))
        put("total", JsonPrimitive(if (isTax) 20.0 else 80.0))
        put("claim_id", JsonPrimitive("c1"))
        if (isTax) put("is_tax", JsonPrimitive(true))
        put("tags", JsonArray(emptyList()))
    }

    private fun claim() = Claim(
        id = "c1", batchId = "b1", description = "Tape", supplier = null, category = "materials",
        costCode = "5010", codedDescription = null, episode = null, receiptDate = DAY, grossAmount = 100.0,
        netAmount = 80.0, vatAmount = 20.0, taxRate = 20.0, taxType = null, settlementType = null,
        status = BatchStatus.ReadyToPost, receiptUrl = null,
    )

    private companion object {
        /** 2026-09-01T00:00:00Z. */
        const val DAY = 1_788_220_800_000L
    }
}

private class CashMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
