package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.payroll.data.PayrollBinaryTransport
import com.zillit.desktop.feature.payroll.data.PayrollDocumentsImpl
import com.zillit.desktop.feature.payroll.data.PayrollRepositoryImpl
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.JournalLine
import com.zillit.desktop.feature.payroll.domain.JournalSubmission
import com.zillit.desktop.feature.payroll.domain.ManualClaim
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routes and bodies payroll sends, pinned against the web's API modules.
 *
 * The earlier port called `/payroll/timecards/accountant-payroll/{ws}/crew/{id}/nominal`
 * and `/payslip` — routes the web declares but never calls, and which answer
 * 404 on every verb — so every test here also checks nothing reaches that
 * prefix.
 */
class PayrollWireTest {

    private class Sent(val method: HttpMethod, val url: String, val body: JsonObject?)

    private fun repository(
        response: String = """{"status":1,"data":{}}""",
    ): Pair<PayrollRepositoryImpl, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            sent += Sent(
                request.method,
                request.url.toString(),
                (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject },
            )
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = PayrollRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ PayrollMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = CONFIG,
        )
        return repo to sent
    }

    private fun List<Sent>.noAccountantPayrollRoutes() =
        assertTrue(none { "accountant-payroll" in it.url }, "no call may reach the 404 accountant-payroll routes")

    @Test
    fun `mark paid, final approve and lock send the ids the web sends`() = runTest {
        val (repo, sent) = repository("""{"status":1,"message":"ok","data":{"marked":["a"],"skipped":[]}}""")
        repo.markPaidBatch(listOf("a", "b"))
        repo.finalApprove(listOf("c"))
        repo.lock(listOf("d"))
        repo.markPaid("e")
        repo.markUnpaid("f")
        repo.unlock("g")

        assertEquals("https://payroll.test/api/v2/payroll/timecards/weekly/batch/mark-paid", sent[0].url)
        assertEquals(listOf("a", "b"), sent[0].body!!["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(sent[1].url.endsWith("/timecards/weekly/batch/final-approve"))
        assertTrue(sent[2].url.endsWith("/timecards/weekly/batch/lock"))
        assertTrue(sent[3].url.endsWith("/timecards/weekly/e/mark-paid"))
        assertTrue(sent[4].url.endsWith("/timecards/weekly/f/mark-unpaid"))
        // Unlock carries an empty object, as the web's `api.post(url, {})`.
        assertTrue(sent[5].url.endsWith("/timecards/weekly/g/unlock"))
        assertEquals(JsonObject(emptyMap()), sent[5].body)
        sent.noAccountantPayrollRoutes()
    }

    @Test
    fun `a batch reports the ids the server actually moved`() = runTest {
        val (repo, _) = repository(
            """{"status":1,"message":"final_approved","data":{"marked":["a"],"skipped":[{"id":"b"}]}}""",
        )
        val outcome = (repo.finalApprove(listOf("a", "b")) as ZillitResult.Success).data
        assertEquals(listOf("a"), outcome.marked)
        assertEquals(1, outcome.skipped)
        assertEquals("final_approved", outcome.message)
    }

    @Test
    fun `a stated status zero is a refusal, not a quiet success`() = runTest {
        val (repo, _) = repository("""{"status":0,"message":"timecard_not_locked"}""")
        assertIs<ZillitResult.Failure>(repo.markPaid("x"))
    }

    @Test
    fun `override sends its reason only when there is one`() = runTest {
        val (repo, sent) = repository()
        repo.override("t1", "  HOD away  ")
        repo.override("t2", "   ")
        assertEquals("HOD away", sent[0].body!!["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), sent[1].body)
    }

    @Test
    fun `a history post carries the account and the effective date the server requires`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":{"marked":2,"skipped":1}}""")
        val outcome = (repo.markPosted(listOf("a", "b", "c"), "bank-1", 1_785_715_200_000) as ZillitResult.Success).data
        val body = sent.single().body!!
        assertTrue(sent.single().url.endsWith("/timecards/weekly/batch/mark-posted"))
        assertEquals(3, body["ids"]!!.jsonArray.size)
        assertEquals("bank-1", body["bank_id"]!!.jsonPrimitive.content)
        assertEquals(1_785_715_200_000, body["effective_date"]!!.jsonPrimitive.long)
        assertFalse("company_id" in body)
        assertEquals(2, outcome.marked)
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `the history queue is the week's paid list and the run is the week's processing list`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":[]}""")
        repo.paidCrew(1_785_715_200_000)
        repo.runQueue(1_785_715_200_000)
        repo.processingQueue()
        repo.outstanding()
        repo.outstandingFor("u1")
        repo.timecard("t1")
        assertEquals(
            listOf(
                "/api/v2/payroll/weekly/1785715200000/paid",
                "/api/v2/payroll/weekly/1785715200000/processing",
                "/api/v2/payroll/weekly/processing",
                "/api/v2/payroll/timecards/weekly/payroll-processing/outstanding",
                "/api/v2/payroll/outstanding/u1",
                "/api/v2/payroll/timecards/weekly/t1",
            ),
            sent.map { it.url.removePrefix("https://payroll.test") },
        )
        assertTrue(sent.all { it.method == HttpMethod.Get })
        sent.noAccountantPayrollRoutes()
    }

    /**
     * Captured from dev: `/weekly/processing` answers an object naming its
     * week, `/weekly/{ws}/processing` a bare array. Same route name, two
     * shapes — reading either as the other empties the screen silently.
     */
    @Test
    fun `the processing queue is an object and the week's queue a bare array`() = runTest {
        val row = """{"_id":"tc1","user_id":"u1","status":"approved","basic_pay":1400,"deductions":[{"amount":60}]}"""
        val (objectRepo, _) = repository(
            """{"status":1,"data":{"week_starting":1785715200000,"timezone":"UTC","timecards":[$row]}}""",
        )
        val queue = (objectRepo.processingQueue() as ZillitResult.Success).data
        assertEquals(1_785_715_200_000, queue.weekStarting)
        assertEquals("tc1", queue.timecards.single().id)

        val (arrayRepo, _) = repository("""{"status":1,"data":[$row]}""")
        val week = (arrayRepo.runQueue(1_785_715_200_000) as ZillitResult.Success).data
        assertEquals("u1", week.single().userId)
    }

    @Test
    fun `bank accounts are the production's, one page of two hundred`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":[]}""")
        repo.settings.bankAccounts()
        val url = sent.single().url
        assertTrue(url.startsWith("https://accounthub.test/api/v2/account-hub/bank-accounts"))
        assertTrue("entity_type=production" in url && "per_page=200" in url)
    }

    @Test
    fun `the lock is read from the cost report and the project settings`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":{"lockedDate":"2026-05-14"}}""")
        val locked = (repo.settings.lockedDate() as ZillitResult.Success).data
        assertEquals("2026-05-14", locked)
        assertTrue(sent.any { it.url == "https://costreport.test/api/v2/cost-reports/lock-period" })
        assertTrue(sent.any { it.url == "https://accounthub.test/api/v2/account-hub/project-settings" })
    }

    @Test
    fun `the journal is fetched by timecard ids with the week under both names`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":{"timecards":[],"run_lines":[]}}""")
        repo.journal.coding(listOf("t1", "t2"), 1_785_715_200_000)
        val call = sent.single()
        assertEquals("https://payroll.test/api/v2/payroll/runs/journal-ledger/fetch", call.url)
        assertEquals(HttpMethod.Post, call.method)
        assertEquals(listOf("t1", "t2"), call.body!!["timecard_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1_785_715_200_000, call.body["week_starting"]!!.jsonPrimitive.long)
        assertEquals(1_785_715_200_000, call.body["posting_week"]!!.jsonPrimitive.long)
    }

    /**
     * The journal's wire line: both sides always present, the side that does
     * not apply null; a pay break sends no amount at all.
     */
    @Test
    fun `a journal post groups lines by timecard and sends the run's account lines apart`() = runTest {
        val (repo, sent) = repository("""{"status":1,"data":{"journal_display":"PR-0007"}}""")
        val payBreak = JournalLine(
            id = "t1-rates_ots::basic|Basic",
            src = "rates_ots",
            groupKey = "basic|Basic",
            nominalCode = "5100",
        )
        val account = JournalLine(
            id = "acct::acct:2100",
            src = "payroll_account",
            groupKey = "acct:2100",
            credit = 1_250.0,
        )
        val posted = repo.journal.submit(
            JournalSubmission(
                post = true,
                weekStarting = 1_785_715_200_000,
                effectiveDate = 1_786_000_000_000,
                timecards = mapOf("t1" to listOf(payBreak)),
                runLines = listOf(account),
            ),
        )
        assertEquals("PR-0007", (posted as ZillitResult.Success).data.journalDisplay)
        val body = sent.single().body!!
        assertEquals("post", body["action"]!!.jsonPrimitive.content)
        assertEquals(1_785_715_200_000, body["posting_week"]!!.jsonPrimitive.long)
        assertEquals(1_786_000_000_000, body["effective_date"]!!.jsonPrimitive.long)
        val line = body["timecards"]!!.jsonArray.single().jsonObject["lines"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, line["debit"])
        assertEquals(JsonNull, line["credit"])
        assertEquals(JsonNull, line["split_parent_id"])
        assertEquals("5100", line["nominal_code"]!!.jsonPrimitive.content)
        assertIs<JsonObject>(line["tracking_codes"])
        assertIs<JsonArray>(line["tags"])
        val run = body["run_lines"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, run["debit"])
        assertEquals(1_250.0, run["credit"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `a journal save sends no effective date of its own`() = runTest {
        val (repo, sent) = repository()
        repo.journal.submit(JournalSubmission(false, 1L, null, emptyMap(), emptyList()))
        assertEquals("save", sent.single().body!!["action"]!!.jsonPrimitive.content)
        assertFalse("effective_date" in sent.single().body!!)
    }

    @Test
    fun `claims and deductions use their own row routes with the web's bodies`() = runTest {
        val (repo, sent) = repository()
        val adjustments = repo.adjustments
        adjustments.attachBatch("t1", "batch-9")
        adjustments.attachManual("t1", ManualClaim(" Kit delivery ", 40.0, "GBP", " 5300 "))
        adjustments.detachClaim("t1", ClaimLine("claim-1", "Kit", 40.0, "GBP", null, null))
        adjustments.detachClaim("t1", ClaimLine(null, "Batch", 10.0, "GBP", null, "batch-9"))
        adjustments.addDeduction("t1", ManualClaim("Advance", 100.0, "GBP", ""))
        adjustments.removeDeduction("t1", "ded-1")

        assertTrue(sent[0].url.endsWith("/timecards/weekly/t1/attach-claim"))
        assertEquals("batch-9", sent[0].body!!["cash_expense_batch_id"]!!.jsonPrimitive.content)
        assertEquals("Kit delivery", sent[1].body!!["claim_name"]!!.jsonPrimitive.content)
        assertEquals("GBP", sent[1].body!!["claim_currency"]!!.jsonPrimitive.content)
        assertEquals("5300", sent[1].body!!["nominal_code"]!!.jsonPrimitive.content)
        assertEquals("claim-1", sent[2].body!!["attached_claim_id"]!!.jsonPrimitive.content)
        assertEquals("batch-9", sent[3].body!!["cash_expense_batch_id"]!!.jsonPrimitive.content)
        assertTrue(sent[4].url.endsWith("/timecards/weekly/t1/add-deduction"))
        assertEquals("flat", sent[4].body!!["rate_type"]!!.jsonPrimitive.content)
        assertFalse("actual_amount" in sent[4].body!!)
        assertEquals("ded-1", sent[5].body!!["deduction_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pending claims come from cash expenses for the crew member`() = runTest {
        val (repo, sent) = repository(
            """{"status":1,"data":{"payroll_batches":[{"id":"b1","batch_reference":"CE-1","expense_type":"pc",
            "claim_count":2,"reimbursement_amount":"45.50","currency":"GBP"}]}}""",
        )
        val pending = (repo.adjustments.pendingClaims("u1") as ZillitResult.Success).data.single()
        val url = sent.single().url
        assertTrue(url.startsWith("https://cashexpenses.test/api/v2/cash-expenses/claims/pending-for-payroll"))
        assertTrue("user_id=u1" in sent.single().url)
        assertEquals(45.5, pending.amount)
        assertTrue(pending.isCash)
    }

    @Test
    fun `the payslip and the run summary are the run routes, posted`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject?>>()
        val documents = PayrollDocumentsImpl(
            CONFIG,
            object : PayrollBinaryTransport {
                override suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray> {
                    calls += url to body
                    return ZillitResult.Success(byteArrayOf(1))
                }

                override suspend fun get(url: String): ZillitResult<ByteArray> {
                    calls += url to null
                    return ZillitResult.Success(byteArrayOf(2))
                }
            },
        )
        documents.payslip(1_785_715_200_000, "u1")
        documents.runSummary(1_785_715_200_000, ExportFormat.Excel)
        documents.weekWorkbook(1_785_715_200_000)
        documents.outstandingWorkbook()

        assertEquals("https://payroll.test/api/v2/payroll/runs/payslip", calls[0].first)
        assertEquals("u1", calls[0].second!!["user_id"]!!.jsonPrimitive.content)
        assertEquals(1_785_715_200_000, calls[0].second!!["week_starting"]!!.jsonPrimitive.long)
        assertEquals("https://payroll.test/api/v2/payroll/runs/export-summary", calls[1].first)
        assertEquals("xlsx", calls[1].second!!["format"]!!.jsonPrimitive.content)
        assertEquals(
            "https://payroll.test/api/v2/payroll/timecards/weekly/payroll-processing/1785715200000/csv",
            calls[2].first,
        )
        assertNull(calls[2].second)
        assertEquals(
            "https://payroll.test/api/v2/payroll/timecards/weekly/payroll-processing/outstanding/csv",
            calls[3].first,
        )
        assertTrue(calls.none { "accountant-payroll" in it.first })
    }

    private companion object {
        val CONFIG = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        )
    }
}

private class PayrollMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
