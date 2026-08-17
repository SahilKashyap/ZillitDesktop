package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.FloatSettlement
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reimburse/reduce decision, pinned.
 *
 * This is the one computation in the cash module where being wrong moves
 * money: understating the headroom over-reimburses, overstating it leaves a
 * crew member out of pocket. The web arrived at these rules through several
 * incidents; the tests here are what stop the desktop client rediscovering
 * them.
 */
class FloatSettlementTest {

    private fun float(
        issued: Double = 1_000.0,
        balance: Double = 1_000.0,
        commits: Double? = null,
        requested: Double = 1_000.0,
    ) = CashFloat(
        id = "float-1",
        requestNumber = "PC-001",
        userId = "user-1",
        holderName = "Ada",
        departmentId = null,
        status = FloatStatus.Spending,
        currency = "GBP",
        requestedAmount = requested,
        issuedAmount = issued,
        balance = balance,
        receiptsAmount = 0.0,
        receiptsCommits = commits,
        returnAmount = 0.0,
        bsCode = null,
        companyId = null,
        duration = null,
        durationType = null,
        purpose = null,
        createdAt = null,
    )

    @Test
    fun `a batch inside the headroom reduces the float`() {
        val settlement = FloatSettlement.of(float(), newBatchTotal = 400.0)

        assertFalse(settlement.reimburses)
        assertEquals(400.0, settlement.floatConsumed)
        assertEquals(0.0, settlement.overdraft)
        assertEquals(600.0, settlement.returnAmount)
    }

    @Test
    fun `a batch exactly meeting the headroom is still a reduce`() {
        // Strictly greater, not greater-or-equal. Spending the float to zero is
        // the ordinary case and must not route through reimbursement.
        val settlement = FloatSettlement.of(float(), newBatchTotal = 1_000.0)

        assertFalse(settlement.reimburses)
        assertEquals(1_000.0, settlement.floatConsumed)
        assertEquals(0.0, settlement.returnAmount)
    }

    @Test
    fun `spend beyond the headroom is reimbursed rather than refused`() {
        val settlement = FloatSettlement.of(float(), newBatchTotal = 1_250.0)

        assertTrue(settlement.reimburses)
        assertEquals(1_000.0, settlement.floatConsumed)
        assertEquals(250.0, settlement.overdraft)
        // Negative: the holder is out of pocket rather than holding cash back.
        assertEquals(-250.0, settlement.returnAmount)
    }

    @Test
    fun `overdraft and consumed always sum to the batch`() {
        listOf(0.0, 250.0, 1_000.0, 1_000.01, 5_000.0).forEach { batch ->
            val settlement = FloatSettlement.of(float(), newBatchTotal = batch)
            assertEquals(
                batch,
                settlement.floatConsumed + settlement.overdraft,
                absoluteTolerance = 1e-9,
                message = "batch $batch split incorrectly",
            )
        }
    }

    @Test
    fun `pending batches reduce the headroom`() {
        // 400 already submitted and unposted, so only 600 of the 1000 remains
        // spendable — a second 700 batch tips into reimbursement.
        val settlement = FloatSettlement.of(
            float(),
            newBatchTotal = 700.0,
            pendingBatchesTotal = 400.0,
        )

        assertEquals(600.0, settlement.headroom)
        assertTrue(settlement.reimburses)
        assertEquals(100.0, settlement.overdraft)
    }

    @Test
    fun `a stale commitment figure cannot overstate the headroom`() {
        // The server says only 100 is committed, but the balance and the live
        // pending total say otherwise. The floor wins, so the client never
        // promises headroom the cash cannot back.
        val settlement = FloatSettlement.of(
            float(balance = 500.0, commits = 100.0),
            newBatchTotal = 0.0,
            pendingBatchesTotal = 200.0,
        )

        assertEquals(300.0, settlement.headroom)
    }

    @Test
    fun `a commitment larger than the float floors the headroom at zero`() {
        val settlement = FloatSettlement.of(float(balance = 50.0, commits = 1_500.0))

        assertEquals(0.0, settlement.headroom)
        // Any spend at all is therefore a reimbursement.
        assertTrue(FloatSettlement.of(float(balance = 50.0, commits = 1_500.0), 10.0).reimburses)
    }

    @Test
    fun `no commitment figure falls back to the live balance`() {
        // The behaviour on a production running a service that predates the
        // field: headroom is exactly the spendable balance.
        val settlement = FloatSettlement.of(float(balance = 750.0, commits = null))

        assertEquals(750.0, settlement.headroom)
    }

    @Test
    fun `an unissued float falls back to what was requested`() {
        val settlement = FloatSettlement.of(float(issued = 0.0, balance = 0.0, requested = 800.0))

        assertEquals(800.0, settlement.issued)
    }

    @Test
    fun `no float at all yields a settlement that reimburses everything`() {
        val settlement = FloatSettlement.of(activeFloat = null, newBatchTotal = 120.0)

        assertEquals(0.0, settlement.headroom)
        assertTrue(settlement.reimburses)
        assertEquals(120.0, settlement.overdraft)
    }

    @Test
    fun `a negative batch total cannot produce a negative consumption`() {
        // Defensive: the UI only ever sums positive amounts, but a negative
        // would otherwise break the consumed-plus-overdraft identity.
        val settlement = FloatSettlement.of(float(), newBatchTotal = -50.0)

        assertEquals(0.0, settlement.floatConsumed)
        assertEquals(0.0, settlement.overdraft)
    }
}
