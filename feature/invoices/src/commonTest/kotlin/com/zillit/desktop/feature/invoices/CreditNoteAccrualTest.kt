package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.parseAccrualDetail
import com.zillit.desktop.feature.invoices.data.parseAccruals
import com.zillit.desktop.feature.invoices.domain.AccrualSort
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccrualsUi
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** `High Value (>£50k)` keeps what is accrued above fifty thousand. */
    @Test
    fun `the high value chip keeps big accruals`() {
        assertTrue(AccrualFilter.HighValue.keeps(Accrual(id = "a", accrualAmount = 50_000.01)))
        assertFalse(AccrualFilter.HighValue.keeps(Accrual(id = "b", accrualAmount = 50_000.0)))
    }

    /** The Used column is not clamped: an over-invoiced order reads past 100%. */
    @Test
    fun `used reads to one decimal and runs past a hundred`() {
        assertEquals("132.4%", Accrual(id = "a", poTotal = 1000.0, invoicedAmount = 1324.0).usedLabel)
        assertEquals("0.0%", Accrual(id = "a", poTotal = 0.0, invoicedAmount = 5.0).usedLabel)
        assertEquals("33.3%", Accrual(id = "a", poTotal = 3.0, invoicedAmount = 1.0).usedLabel)
    }

    /**
     * The page's own order: search (formatted amounts too), chip, the Accruals
     * page's own department, then the Sort — the vendor named from the
     * directory, "Unknown" without one.
     */
    @Test
    fun `accruals filter by their own department and sort as asked`() {
        val rows = listOf(
            Accrual(id = "a1", vendorId = "v1", departmentId = "d1", accrualAmount = 100.0, poTotal = 400.0, invoicedAmount = 300.0),
            Accrual(id = "a2", vendorId = "v2", departmentId = "d1", accrualAmount = 900.0, poTotal = 1000.0, invoicedAmount = 100.0),
            Accrual(id = "a3", vendorId = "", departmentId = "d2", accrualAmount = 500.0),
        )
        val vendors = mapOf("v1" to Vendor("v1", "Zed Trucks"), "v2" to Vendor("v2", "Acme"))
        val state = InvoicesUiState(accruals = rows, vendors = vendors)
        assertEquals(listOf("a2", "a3", "a1"), state.shownAccruals.map { it.id }, "Accrual ↓ is the default")
        assertEquals("Unknown", state.accrualVendorName(rows[2]))

        val d1 = state.copy(accrualsPage = AccrualsUi(departmentId = "d1", sort = AccrualSort.VendorAZ))
        assertEquals(listOf("a2", "a1"), d1.shownAccruals.map { it.id })
        assertEquals(null, d1.registerDepartment, "the Register's filter is its own")

        val used = state.copy(accrualsPage = AccrualsUi(sort = AccrualSort.UsedDesc))
        assertEquals("a1", used.shownAccruals.first().id)

        val asc = state.copy(accrualsPage = AccrualsUi(sort = AccrualSort.AccrualAsc), search = "900")
        assertEquals(listOf("a2"), asc.shownAccruals.map { it.id }, "the formatted accrual is searched")
    }

    @Test
    fun `an accrual detail unwraps the accrual, its order, vendor and invoices`() {
        val detail = parseAccrualDetail(
            json.parseToJsonElement(
                """
                {"accrual":{"id":"a1","po_number":"PO-9","description":"","po_total":1000,"invoiced_amount":250,
                            "accrual_amount":750,"status":"accrued"},
                 "po":{"po_number":"PO-9","status":"POSTED","currency":"USD","description":"Grip truck",
                       "vat_treatment":"standard","notes":"Night work"},
                 "vendor":{"name":"Movietech","address":"{\"line1\":\"1 Pinewood\",\"postcode\":\"SL0\"}",
                           "contact_person":"Sam","email":"s@m.co","phone":{"isd":"+44","number":"7700"}},
                 "invoices":[{"id":"i1","invoice_number":"INV-1","gross_amount":250,"status":"approved"}]}
                """.trimIndent(),
            ),
        )
        assertNotNull(detail)
        assertEquals("PO-9 — Grip truck", detail.title)
        assertTrue(detail.po!!.isPosted)
        assertEquals("1 Pinewood, SL0", detail.vendor!!.address)
        assertEquals("Sam · s@m.co · +44 7700", detail.vendor!!.contactLine)
        assertEquals(listOf("INV-1"), detail.invoices.map { it.invoiceNumber })
        assertNull(parseAccrualDetail(json.parseToJsonElement("""{"accrual":null}""")))
    }
}
