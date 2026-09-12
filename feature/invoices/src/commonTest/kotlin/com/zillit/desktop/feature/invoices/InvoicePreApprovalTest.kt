package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.HoldRequest
import com.zillit.desktop.feature.invoices.ui.PostedFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pre-approval queue's rules, and the posted page's filter. */
class InvoicePreApprovalTest {

    private fun invoice(id: String, status: InvoiceStatus) = Invoice(
        id = id,
        reference = id,
        supplierName = "Panavision",
        status = status,
    )

    /** A reason is required, and the web refuses "Other" with nothing written down. */
    @Test
    fun `a hold needs a reason, and Other needs its notes`() {
        val rows = listOf(invoice("i1", InvoiceStatus.Matching))
        assertFalse(HoldRequest(rows).isReady, "no reason picked")
        assertTrue(HoldRequest(rows, reason = HoldReason.TaxQuery).isReady)
        assertFalse(HoldRequest(rows, reason = HoldReason.Other).isReady, "Other without notes")
        assertTrue(HoldRequest(rows, reason = HoldReason.Other, notes = "chasing the PO").isReady)
    }

    /** The reasons are the web's, in its order, and only the last one demands notes. */
    @Test
    fun `the hold reasons are the web's`() {
        assertEquals("Invoice Adjustment Required", HoldReason.entries.first().label)
        assertEquals("Other (specify in notes)", HoldReason.entries.last().label)
        assertEquals(1, HoldReason.entries.count { it.needsNotes() })
    }

    /** Pre-approval is both statuses at once, and it lists invoices like the others. */
    @Test
    fun `pre-approval is a list page over two statuses`() {
        assertTrue(AccountantPage.Matching.isInvoiceList)
        assertTrue(AccountantPage.Posted.isInvoiceList)
        assertFalse(AccountantPage.Overview.isInvoiceList)
        assertFalse(AccountantPage.Reports.isInvoiceList, "the web shows its own coming-soon here")
    }

    @Test
    fun `the posted filter keeps what it names`() {
        val ready = invoice("i1", InvoiceStatus.ReadyToPay)
        val paid = invoice("i2", InvoiceStatus.Paid)
        assertTrue(PostedFilter.All.keeps(ready) && PostedFilter.All.keeps(paid))
        assertTrue(PostedFilter.ReadyToPay.keeps(ready))
        assertFalse(PostedFilter.ReadyToPay.keeps(paid))
        assertTrue(PostedFilter.Paid.keeps(paid))
    }
}
