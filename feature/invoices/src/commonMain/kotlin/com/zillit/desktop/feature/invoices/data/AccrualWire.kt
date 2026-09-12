package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualStatus
import kotlinx.serialization.json.JsonElement

/** `/invoices/accruals` — snake_case, and every amount a number. */
internal fun parseAccruals(data: JsonElement?): List<Accrual> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    Accrual(
        id = id,
        poNumber = row.text("po_number", "poNumber"),
        vendorId = row.text("vendor_id", "vendorId"),
        vendorName = row.text("supplier", "supplier_name", "vendor_name"),
        description = row.text("description"),
        departmentId = row.text("department_id", "departmentId"),
        poTotal = row.number("po_total", "poTotal") ?: 0.0,
        invoicedAmount = row.number("invoiced_amount", "invoicedAmount") ?: 0.0,
        accrualAmount = row.number("accrual_amount", "accrualAmount") ?: 0.0,
        currency = row.text("currency"),
        status = AccrualStatus.from(row.text("status")),
    )
}
