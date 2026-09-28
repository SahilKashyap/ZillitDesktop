package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.parseInvoice
import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.BadgeTone
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PoPills
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.RegisterChip
import com.zillit.desktop.feature.invoices.ui.ReviewOverlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Invoice Register, Invoices Pre-approval and Approval Queue as the web's
 * `RegisterPage`, `MatchingPage` and `ApprovalPage` build their cells, counts
 * and badges.
 */
class AccountantQueuesParityTest {

    private val tiers = listOf(ResolvedTier(1, listOf("hod")), ResolvedTier(2, listOf("fc")))
    private val accountant = InvoiceViewer(userId = "me", departmentIdentifier = "accounts")

    private fun invoice(
        status: InvoiceStatus = InvoiceStatus.Approval,
        pay: PayMethod = PayMethod.Bacs,
        payRaw: String = pay.wire,
    ) = Invoice(id = "i1", invoiceNumber = "INV-1", status = status, payMethod = pay, payMethodRaw = payRaw)

    // -- the Approval Queue's badge, in the web's order ------------------------

    @Test
    fun `an override row paid faster names the faster payment, and no po only by linked pos`() {
        val row = invoice(InvoiceStatus.Override, PayMethod.Faster, "faster_payment").copy(poId = "p1")
        val badge = InvoiceRules.queueBadge(row, tiers)
        assertEquals("No PO · Faster Payment", badge.label)
        assertEquals(BadgeTone.Rejected, badge.tone)

        val linked = row.copy(linkedPos = listOf(LinkedPo("p1", "PO-1")))
        assertEquals("Faster Payment", InvoiceRules.queueBadge(linked, tiers).label)
    }

    @Test
    fun `an override row on bacs reads Override, in red`() {
        val badge = InvoiceRules.queueBadge(invoice(InvoiceStatus.Override), tiers)
        assertEquals("Override", badge.label)
        assertEquals(BadgeTone.Rejected, badge.tone)
    }

    @Test
    fun `an urgent row counts a po id but never a bare typed number`() {
        val typed = invoice(pay = PayMethod.Wire).copy(poNumber = "PO-TYPED")
        assertEquals("No PO · Urgent Wire Request", InvoiceRules.queueBadge(typed, tiers).label)
        val matched = invoice(pay = PayMethod.Cheque).copy(poId = "p1")
        assertEquals("Cheque Request", InvoiceRules.queueBadge(matched, tiers).label)
    }

    @Test
    fun `approved is the status alone, then rejected, then pending with or without a chain`() {
        assertEquals(BadgeTone.Success, InvoiceRules.queueBadge(invoice(InvoiceStatus.Approved), tiers).tone)
        // An approved flag on a row still in approval is not "Approved" here.
        val flagged = invoice().copy(
            approvalStatus = ApprovalStatus.Approved,
            approvals = listOf(Approval("hod", 1, 1L)),
        )
        assertEquals("Pending (1/2)", InvoiceRules.queueBadge(flagged, tiers).label)
        assertEquals("Rejected", InvoiceRules.queueBadge(invoice(InvoiceStatus.Rejected), tiers).label)
        val pending = InvoiceRules.queueBadge(invoice(), emptyList())
        assertEquals("Pending", pending.label)
        assertEquals(BadgeTone.Awaiting, pending.tone)
    }

    // -- isApproved on the queue ----------------------------------------------

    @Test
    fun `the queue's approved is status approved and nothing else`() {
        assertTrue(invoice(InvoiceStatus.Approved).isApprovedStatus)
        assertFalse(invoice(InvoiceStatus.Override).isApprovedStatus)
        assertFalse(invoice().copy(approvalStatus = ApprovalStatus.Approved).isApprovedStatus)
    }

    @Test
    fun `override and pay stays for an override row and an approved-flag urgent row`() {
        val viewer = accountant.copy(overrideFlag = true)
        val override = invoice(InvoiceStatus.Override)
        assertTrue(InvoiceRules.showOverrideAndPay(override, viewer) && !override.isApprovedStatus)
        val urgent = invoice(pay = PayMethod.Wire).copy(approvalStatus = ApprovalStatus.Approved)
        assertTrue(InvoiceRules.showOverrideAndPay(urgent, viewer) && !urgent.isApprovedStatus)
    }

    @Test
    fun `the panel counts every row not approved as awaiting, rejected ones included`() {
        val state = InvoicesUiState(
            viewer = accountant,
            page = AccountantPage.ApprovalQueue,
            invoices = listOf(
                invoice(),
                invoice(InvoiceStatus.Rejected).copy(id = "i2"),
                invoice(InvoiceStatus.Approved).copy(id = "i3"),
            ),
        )
        assertEquals(2, state.awaitingCount)
        assertEquals(1, state.approvedCount)
    }

    // -- the Register -----------------------------------------------------------

    @Test
    fun `the entry chip is entry exactly`() {
        assertTrue(RegisterChip.Entry.keeps(invoice(InvoiceStatus.Entry)))
        assertFalse(RegisterChip.Entry.keeps(invoice(InvoiceStatus.UnderReview)))
    }

    @Test
    fun `posted is settled, and a part day is not overdue`() {
        val now = 10 * DAY
        val due = invoice(InvoiceStatus.Entry).copy(dueDateMs = now - 3 * DAY - 1)
        assertEquals(3L, InvoiceRules.registerOverdueDays(due, now))
        assertNull(InvoiceRules.registerOverdueDays(due.copy(status = InvoiceStatus.Posted), now))
        assertNull(InvoiceRules.registerOverdueDays(due.copy(status = InvoiceStatus.Paid), now))
        assertNull(InvoiceRules.registerOverdueDays(due.copy(dueDateMs = now - DAY / 2), now), "0d is the due date")
    }

    @Test
    fun `the register's po cell wants a linked po, bar the urgent rows`() {
        val typed = invoice().copy(poNumber = "PO-TYPED", poId = "p9")
        assertEquals("No PO", PoPills.register(typed).label)
        assertEquals(BadgeTone.Rejected, PoPills.register(typed).tone)

        val linked = invoice().copy(linkedPos = listOf(LinkedPo("p1", "PO-0001")))
        assertEquals("PO-0001", PoPills.register(linked).label)
        assertEquals(BadgeTone.Info, PoPills.register(linked).tone)

        val urgent = invoice(pay = PayMethod.Wire).copy(poId = "p1", poNumber = "PO-7")
        assertEquals("PO-7", PoPills.register(urgent).label)
        assertEquals(BadgeTone.Info, PoPills.register(urgent).tone)
    }

    @Test
    fun `the queue's po cell is red without linked pos but keeps the typed number`() {
        val typed = invoice().copy(poNumber = "PO-TYPED")
        val pill = PoPills.queue(typed)
        assertEquals("PO-TYPED", pill.label)
        assertEquals(BadgeTone.Rejected, pill.tone)
    }

    @Test
    fun `the register falls back to the description before the dash for a vendor`() {
        val state = InvoicesUiState(viewer = accountant, page = AccountantPage.Register)
        val row = invoice().copy(description = "Acme Hire – camera kit")
        assertEquals("Acme Hire", state.pageVendorName(row))
        assertEquals("Unknown", state.copy(page = AccountantPage.Matching).pageVendorName(row))
    }

    // -- Invoices Pre-approval ----------------------------------------------------

    @Test
    fun `held rows come after the waiting ones`() {
        val held = invoice(InvoiceStatus.Held).copy(id = "held")
        val waiting = invoice(InvoiceStatus.Matching).copy(id = "waiting")
        val state = InvoicesUiState(
            viewer = accountant,
            page = AccountantPage.Matching,
            invoices = listOf(held, waiting),
        )
        assertEquals(listOf("waiting", "held"), state.shownInvoices.map { it.id })
    }

    @Test
    fun `a waiting row's typed number is amber, a held row's is red`() {
        val typed = invoice(InvoiceStatus.Matching).copy(poNumber = "PO-TYPED")
        assertEquals(BadgeTone.Pending, PoPills.matching(typed).tone)
        assertEquals("PO-TYPED", PoPills.matching(typed).label)
        assertEquals(BadgeTone.Rejected, PoPills.matching(typed.copy(status = InvoiceStatus.Held)).tone)

        val urgentHeld = invoice(InvoiceStatus.Held, PayMethod.Wire)
        assertEquals("No PO · Urgent Wire Request", PoPills.matching(urgentHeld).label)
        assertEquals("On Hold", PoPills.matchingStatus(urgentHeld).label)
    }

    @Test
    fun `po ids are read as bare ids or objects`() {
        val parsed = assertNotNull(
            parseInvoice(
                Json.parseToJsonElement(
                    """{"id":"x","po_ids":["p1",{"po_id":"p2"}],"pay_method":"Wire"}""",
                ) as JsonObject,
            ),
        )
        assertEquals(listOf("p1", "p2"), parsed.poIds)
        assertEquals("Wire", parsed.payMethodRaw)
        // The web compares the stored value: "Wire" is not the urgent "wire".
        assertFalse(parsed.isUrgentRaw)
    }

    // -- the PO review overlay -----------------------------------------------------

    @Test
    fun `the review's po is linked pos or a po id, never a typed number`() {
        val typed = ReviewOverlay(invoice(InvoiceStatus.Matching).copy(poNumber = "PO-TYPED"))
        assertFalse(typed.hasPo)
        assertNull(typed.activePo)

        val byId = ReviewOverlay(invoice(InvoiceStatus.Matching).copy(poId = "p1", poNumber = "PO-1"))
        assertTrue(byId.hasPo)
        assertEquals("p1", byId.activePo?.poId)
        assertTrue(byId.linkedPos.isEmpty(), "no synthetic linked row")
    }

    @Test
    fun `hold notes swap a known user id for the name`() {
        val id = "0123456789abcdef01234567"
        val text = InvoiceLabels.resolveUserIds("Dispute by $id and ffffffffffffffffffffffff") {
            if (it == id) "Jo Bloggs" else null
        }
        assertEquals("Dispute by Jo Bloggs and ffffffffffffffffffffffff", text)
        assertEquals("Awaiting Credit Note", InvoiceLabels.format("awaiting_credit_note"))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
