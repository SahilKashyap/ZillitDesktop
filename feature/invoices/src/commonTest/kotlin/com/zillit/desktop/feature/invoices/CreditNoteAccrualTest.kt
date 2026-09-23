package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.parseAccruals
import com.zillit.desktop.feature.invoices.data.parseCreditNotes
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualFilter
import com.zillit.desktop.feature.invoices.domain.AccrualStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteFilter
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Credit notes and accruals: their wire shapes, and the rules on their rows. */
class CreditNoteAccrualTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `credit notes decode, and a row without an id is dropped`() {
        val notes = parseCreditNotes(
            json.parseToJsonElement(
                """
                [{"id":"c1","reference":"CN-001","type":"dispute","vendor_id":"v1",
                  "supplier_name":"Panavision","reason":"Damaged on arrival","gross_amount":250.5,
                  "currency":"GBP","invoice_reference":"INV-12","effective_date":"2026-03-03",
                  "status":"disputed"},
                 {"reference":"CN-002"}]
                """.trimIndent(),
            ),
        )
        val note = notes.single()
        assertEquals("CN-001", note.reference)
        assertEquals(CreditNoteType.Dispute, note.type)
        assertEquals(CreditNoteStatus.Disputed, note.status)
        assertEquals(250.5, note.grossAmount)
        assertEquals("INV-12", note.againstInvoice)
    }

    /** The button on a row is what its status allows — the web's `STATUS_MAP.action`. */
    @Test
    fun `each status offers the web's action`() {
        assertEquals("Apply", CreditNoteStatus.Pending.action)
        assertEquals("Resolve", CreditNoteStatus.Disputed.action)
        assertEquals("View", CreditNoteStatus.Applied.action)
        assertEquals("View", CreditNoteStatus.Resolved.action)
        assertTrue(CreditNoteStatus.Pending.isActionable)
        assertFalse(CreditNoteStatus.Applied.isActionable, "an applied note only opens")
    }

    @Test
    fun `an unknown type or status falls back rather than failing the load`() {
        val notes = parseCreditNotes(json.parseToJsonElement("""[{"id":"c1","type":"what","status":"whenever"}]"""))
        assertEquals(CreditNoteType.CreditNote, notes.single().type)
        assertEquals(CreditNoteStatus.Pending, notes.single().status)
    }

    @Test
    fun `accruals decode and know how much of the order is used`() {
        val accruals = parseAccruals(
            json.parseToJsonElement(
                """
                [{"id":"a1","po_number":"QW-PO-0042","supplier":"Movietech","description":"Grip truck",
                  "department_id":"d1","po_total":1000,"invoiced_amount":250,"accrual_amount":750,
                  "currency":"GBP","status":"accrued"}]
                """.trimIndent(),
            ),
        )
        val accrual = accruals.single()
        assertEquals("QW-PO-0042", accrual.poNumber)
        assertEquals(750.0, accrual.accrualAmount)
        assertEquals(0.25, accrual.used)
        assertEquals(AccrualStatus.Accrued, accrual.status)
    }

    /** A zero-total order cannot be part-used, and an over-invoiced one is capped. */
    @Test
    fun `used is a fraction between none and all`() {
        assertEquals(0.0, Accrual(id = "a", poTotal = 0.0, invoicedAmount = 100.0).used)
        assertEquals(1.0, Accrual(id = "a", poTotal = 100.0, invoicedAmount = 250.0).used)
        assertEquals(0.5, Accrual(id = "a", poTotal = 100.0, invoicedAmount = 50.0).used)
    }

    @Test
    fun `the filters keep what they name`() {
        val accrued = Accrual(id = "a1", status = AccrualStatus.Accrued)
        val reversed = Accrual(id = "a2", status = AccrualStatus.Reversed)
        assertTrue(AccrualFilter.All.keeps(accrued) && AccrualFilter.All.keeps(reversed))
        assertTrue(AccrualFilter.Active.keeps(accrued))
        assertFalse(AccrualFilter.Active.keeps(reversed))
        assertTrue(AccrualFilter.Reversed.keeps(reversed))

        assertEquals(
            listOf("All", "Pending", "Applied", "Disputed", "Resolved"),
            CreditNoteFilter.entries.map { it.label },
        )
    }
}
