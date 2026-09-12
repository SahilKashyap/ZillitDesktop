package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import kotlinx.serialization.json.JsonElement

/** `/invoices/credit-notes` — ordinary snake_case, like the rest of this service. */
internal fun parseCreditNotes(data: JsonElement?): List<CreditNote> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    CreditNote(
        id = id,
        reference = row.text("reference", "ref"),
        type = CreditNoteType.from(row.text("type")),
        vendorId = row.text("vendor_id", "vendorId"),
        vendorName = row.text("supplier_name", "vendor_name", "supplierName"),
        reason = row.text("reason"),
        description = row.text("description"),
        grossAmount = row.number("gross_amount", "grossAmount") ?: 0.0,
        currency = row.text("currency"),
        againstInvoice = row.text("invoice_reference", "invoiceReference"),
        effectiveDateMs = row.dateMs("effective_date", "effectiveDate"),
        status = CreditNoteStatus.from(row.text("status")),
        notes = row.text("notes"),
    )
}
