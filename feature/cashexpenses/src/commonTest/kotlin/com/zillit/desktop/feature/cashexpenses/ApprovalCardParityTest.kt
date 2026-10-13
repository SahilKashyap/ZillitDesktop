package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.BatchDto
import com.zillit.desktop.feature.cashexpenses.data.ClaimDto
import com.zillit.desktop.feature.cashexpenses.data.cashLenient
import com.zillit.desktop.feature.cashexpenses.data.toRequesterDeal
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrency
import com.zillit.desktop.feature.cashexpenses.domain.CashTrackingNode
import com.zillit.desktop.feature.cashexpenses.domain.CashTrackingSet
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.CrewRequestCap
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatFlow
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.OopRoutingFilter
import com.zillit.desktop.feature.cashexpenses.domain.OopStatusFilter
import com.zillit.desktop.feature.cashexpenses.domain.ProcessingRule
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.RequesterDeal
import com.zillit.desktop.feature.cashexpenses.domain.filterOop
import com.zillit.desktop.feature.cashexpenses.domain.layersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Approval Queue's receipt card, the OOP overview's filters and the float
 * request cap — the rules behind three web-parity fixes.
 */
class ApprovalCardParityTest {

    private fun claim(json: String) =
        cashLenient.decodeFromJsonElement(ClaimDto.serializer(), Json.parseToJsonElement(json)).toDomain()!!

    // -- processing rules -----------------------------------------------------------

    @Test
    fun `a processing flag object keeps its rule's title, threshold and description`() {
        val rules = claim(
            """{"id":"c1","processing_flags":[{"flag":"review","title":"Big spend","threshold_value":"250",
               "threshold_type":"amount","description":"Over the senior limit"},"query"]}""",
        ).processingRules

        assertEquals(
            listOf(
                ProcessingRule("review", "Big spend", 250.0, "amount", "Over the senior limit"),
                ProcessingRule("query"),
            ),
            rules,
        )
    }

    @Test
    fun `processing flags held in a string still read, and the bare flags are derived`() {
        val parsed = claim(
            """{"id":"c1","processing_flags":"[{\"flag\":\"deduct\",\"threshold_value\":10,
               \"threshold_type\":\"percentage\"}]"}""",
        )

        assertEquals(listOf("deduct"), parsed.processingFlags)
        assertEquals(10.0, parsed.processingRules.single().thresholdValue)
        assertTrue(parsed.processingRules.single().isPercentage)
    }

    @Test
    fun `a rule is named with rule on the end once, and a missing title has no name`() {
        assertEquals("Petty cash rule", ProcessingRule("query", title = " Petty cash ").ruleName)
        assertEquals("Big spend RULE", ProcessingRule("query", title = "Big spend RULE").ruleName)
        assertNull(ProcessingRule("query", title = "  ").ruleName)
    }

    // -- layers -----------------------------------------------------------------------

    private val sets = listOf(
        CashTrackingSet("loc", nodes = listOf(CashTrackingNode("n1", "LOC-LON"), CashTrackingNode("n2", "LOC-PAR"))),
        CashTrackingSet("ep", nodes = listOf(CashTrackingNode("n3", "EP-1"))),
        CashTrackingSet("old", active = false, nodes = listOf(CashTrackingNode("n4", "OLD-1"))),
    )

    @Test
    fun `a line's layers resolve by code or by a legacy node id, in set order`() {
        val codes = Json.parseToJsonElement("""{"ep":"n3","loc":"LOC-PAR","old":"OLD-1","gone":"X"}""")

        assertEquals(listOf("LOC-PAR", "EP-1"), sets.layersOf(codes))
    }

    @Test
    fun `layers held in a string read, and a pick matching no node is dropped`() {
        val codes = Json.parseToJsonElement("\"{\\\"loc\\\":\\\"NOPE\\\",\\\"ep\\\":\\\"EP-1\\\"}\"")

        assertEquals(listOf("EP-1"), sets.layersOf(codes))
        assertEquals(emptyList(), sets.layersOf(null))
    }

    // -- OOP overview ------------------------------------------------------------------

    private fun batch(id: String, status: BatchStatus, method: String?) = ClaimBatch(
        id = id, reference = id, userId = "u", holderName = "", departmentId = null, status = status,
        expenseType = ExpenseType.OutOfPocket, claimCount = 1, totalGross = 10.0, reimbursementAmount = 10.0,
        currency = "GBP", settlementType = null, paymentMethod = method, notes = null, assignedTo = null,
        assignedBy = null, assignmentReason = null, createdAt = null,
    )

    private val batches = listOf(
        batch("coding", BatchStatus.Coding, "BACS"),
        batch("ready", BatchStatus.ReadyToPost, "BACS"),
        batch("override", BatchStatus.AcctOverride, "PAYROLL"),
        batch("posted", BatchStatus.Posted, "PAYROLL"),
        batch("unrouted", BatchStatus.AwaitingApproval, null),
    )

    private fun ids(status: OopStatusFilter, routing: OopRoutingFilter = OopRoutingFilter.All) =
        batches.filterOop(status, routing).map { it.id }

    @Test
    fun `the status filters partition the batches without overlap`() {
        assertEquals(listOf("coding", "unrouted"), ids(OopStatusFilter.Pending))
        assertEquals(listOf("ready", "override"), ids(OopStatusFilter.Approved))
        assertEquals(listOf("posted"), ids(OopStatusFilter.Paid))
        val all = OopStatusFilter.entries.filter { it != OopStatusFilter.All }.flatMap { ids(it) }
        assertEquals(batches.map { it.id }.sorted(), all.sorted())
    }

    @Test
    fun `the routing filter follows the payment method and combines with the status`() {
        assertEquals(listOf("coding", "ready"), ids(OopStatusFilter.All, OopRoutingFilter.Bacs))
        assertEquals(listOf("override", "posted"), ids(OopStatusFilter.All, OopRoutingFilter.Payroll))
        assertEquals(listOf("posted"), ids(OopStatusFilter.Paid, OopRoutingFilter.Payroll))
        assertEquals(emptyList(), ids(OopStatusFilter.Paid, OopRoutingFilter.Bacs))
    }

    @Test
    fun `net is total_net when there is one, else gross less vat`() {
        fun net(json: String): Double {
            val batch = cashLenient.decodeFromJsonElement(BatchDto.serializer(), Json.parseToJsonElement(json))
            return batch.toDomain()!!.netAmount
        }

        assertEquals(80.0, net("""{"id":"b","total_gross":"100","total_net":"80","total_vat":"20"}"""))
        assertEquals(80.0, net("""{"id":"b","total_gross":"100","total_net":"0","total_vat":"20"}"""))
        assertEquals(100.0, net("""{"id":"b","total_gross":"100"}"""))
    }

    // -- request cap -----------------------------------------------------------------

    private val currencies = CashCurrencies(
        currencies = listOf(CashCurrency("GBP", "£"), CashCurrency("USD", "$"), CashCurrency("EUR", "€")),
        defaultCode = "GBP",
        // exr is foreign per default: 1 GBP = 1.25 USD. EUR has no rate.
        rates = mapOf("USD" to 1.25),
    )

    private fun verdict(
        cap: RequestCap?,
        deal: RequesterDeal? = null,
        currency: String = "GBP",
        applies: Boolean = true,
    ) = CrewRequestCap.verdict(cap, deal, currency, currencies, applies)

    @Test
    fun `a flat cap is the project default and only the request is converted`() {
        val v = verdict(RequestCap(enabled = true, maxAmount = 400.0), currency = "USD")

        assertEquals(400.0, v.capInDefault)
        assertEquals(CrewRequestCap.Source.Max, v.source)
        assertTrue(v.enforceable)
        // $500 is £400 exactly: not over. $501 is.
        assertFalse(CrewRequestCap.exceeds(v, 500.0, "USD", currencies))
        assertTrue(CrewRequestCap.exceeds(v, 501.0, "USD", currencies))
    }

    @Test
    fun `a weekly cap is the deal's rate times the multiplier, converted to the default`() {
        val cap = RequestCap(
            enabled = true,
            basis = RequestCap.WEEKLY_SALARY,
            maxAmount = 100.0,
            salaryMultiplier = 2.0,
        )
        val v = verdict(cap, RequesterDeal(weeklyRate = 500.0, currency = "USD"))

        assertEquals(CrewRequestCap.Source.Weekly, v.source)
        assertEquals(1_000.0, v.amount)
        assertEquals("USD", v.currency)
        assertEquals(800.0, v.capInDefault!!, 0.001)
        assertTrue(CrewRequestCap.exceeds(v, 800.01, "GBP", currencies))
        assertFalse(CrewRequestCap.exceeds(v, 800.0, "GBP", currencies))
    }

    @Test
    fun `a weekly cap with no usable rate falls back to the flat ceiling`() {
        val cap = RequestCap(enabled = true, basis = RequestCap.WEEKLY_SALARY, maxAmount = 300.0)

        for (deal in listOf(null, RequesterDeal(0.0, "GBP"))) {
            val v = verdict(cap, deal)
            assertEquals(CrewRequestCap.Source.Fallback, v.source)
            assertEquals(300.0, v.capInDefault)
        }
        // And with no ceiling either there is no cap at all, never a cap of nothing.
        assertNull(verdict(cap.copy(maxAmount = 0.0), null).amount)
    }

    @Test
    fun `an unrated currency on either side stands the cap down and names the code`() {
        val flat = RequestCap(enabled = true, maxAmount = 100.0)
        val eur = verdict(flat, currency = "EUR")
        assertFalse(eur.enforceable)
        assertEquals("EUR", eur.unrated)
        assertFalse(CrewRequestCap.exceeds(eur, 1_000_000.0, "EUR", currencies))

        val weekly = RequestCap(enabled = true, basis = RequestCap.WEEKLY_SALARY, maxAmount = 100.0)
        val capEur = verdict(weekly, RequesterDeal(500.0, "EUR"))
        assertFalse(capEur.enforceable)
        assertEquals("EUR", capEur.unrated)
    }

    @Test
    fun `no cap, a disabled cap and a request on someone's behalf never enforce`() {
        assertNull(verdict(null).amount)
        assertNull(verdict(RequestCap(enabled = false, maxAmount = 100.0)).amount)
        val onBehalf = verdict(RequestCap(enabled = true, maxAmount = 100.0), applies = false)
        assertEquals(100.0, onBehalf.amount)
        assertFalse(onBehalf.enforceable)
        assertFalse(CrewRequestCap.exceeds(onBehalf, 5_000.0, "GBP", currencies))
    }

    @Test
    fun `a deal reads its weekly rate in the pay currency, else the contract currency`() {
        fun deal(json: String) = (Json.parseToJsonElement(json) as JsonObject).toRequesterDeal()

        assertEquals(
            RequesterDeal(750.0, "USD"),
            deal("""{"rates":{"weekly":{"rate":"750"},"pay_currency":"USD","contract_currency":"GBP"}}"""),
        )
        assertEquals(
            RequesterDeal(750.0, "GBP"),
            deal("""{"rates":{"weekly":{"rate":750},"contract_currency":{"code":"GBP"}}}"""),
        )
        // A buy-out carries no weekly rate; that must read as 0, not as a missing deal.
        assertEquals(RequesterDeal(0.0, null), deal("""{"rates":{"daily":{"rate":100}}}"""))
        assertNull((null as JsonObject?).toRequesterDeal())
    }

    // -- float journey ---------------------------------------------------------------

    private fun FloatStatus.journey() = FloatFlow.steps(this).map {
        when {
            it.rejected -> 'x'
            it.done -> 'd'
            it.active -> 'a'
            else -> '.'
        }
    }.joinToString("")

    @Test
    fun `a float request's journey advances with its status`() {
        assertEquals("da....", FloatStatus.AwaitingApproval.journey())
        assertEquals("da....", FloatStatus.Approved.journey(), "approved is still waiting for the cash")
        assertEquals("da....", FloatStatus.AcctOverride.journey())
        assertEquals("dda...", FloatStatus.ReadyToCollect.journey())
        assertEquals("ddda..", FloatStatus.Collected.journey())
        assertEquals("ddda..", FloatStatus.Active.journey())
        assertEquals("dddda.", FloatStatus.Spending.journey())
        assertEquals("ddddda", FloatStatus.Spent.journey())
        assertEquals("ddddda", FloatStatus.PendingReturn.journey())
    }

    @Test
    fun `a rejected request stops at approval and a closed float shows no progress`() {
        assertEquals("dx....", FloatStatus.Rejected.journey())
        assertEquals("......", FloatStatus.Closed.journey())
        assertEquals("......", FloatStatus.Cancelled.journey())
    }
}
