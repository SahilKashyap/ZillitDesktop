package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualDetail
import com.zillit.desktop.feature.invoices.domain.AccrualPo
import com.zillit.desktop.feature.invoices.domain.AccrualStatus
import com.zillit.desktop.feature.invoices.domain.AccrualVendor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `/invoices/accruals` — snake_case, and every amount a number. */
internal fun parseAccruals(data: JsonElement?): List<Accrual> = rowsOf(data).mapNotNull(::parseAccrual)

private fun parseAccrual(row: JsonObject): Accrual? {
    val id = row.text("id", "_id").ifBlank { return null }
    return Accrual(
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
        statusRaw = row.text("status"),
    )
}

/**
 * `GET /invoices/accruals/:id` — the envelope's `data` is
 * `{accrual, po, vendor, invoices}` (`AccrualsPage.jsx:88-100`); null when
 * there is no accrual in it ("Accrual not found").
 */
internal fun parseAccrualDetail(data: JsonElement?): AccrualDetail? {
    val obj = data as? JsonObject ?: return null
    val accrual = (obj["accrual"] as? JsonObject)?.let(::parseAccrual) ?: return null
    val po = (obj["po"] as? JsonObject)?.let { row ->
        AccrualPo(
            poNumber = row.text("po_number"),
            status = row.text("status"),
            currency = row.text("currency"),
            effectiveDateMs = row.dateMs("effective_date"),
            deliveryDateMs = row.dateMs("delivery_date"),
            vatTreatment = row.text("vat_treatment"),
            description = row.text("description"),
            notes = row.text("notes"),
        )
    }
    val vendor = (obj["vendor"] as? JsonObject)?.let { row ->
        AccrualVendor(
            name = row.text("name", "vendor_name"),
            address = vendorAddress(row["address"]),
            contactLine = listOf(
                row.text("contact_person"),
                row.text("email"),
                vendorPhone(row["phone"]),
            ).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }
    return AccrualDetail(
        accrual = accrual,
        po = po,
        vendor = vendor,
        invoices = rowsOf(obj["invoices"]).mapNotNull(::parseInvoice),
    )
}

/** `line1, line2, city, state, postcode` — the web's own join for this block, which has no country. */
private fun vendorAddress(element: JsonElement?): String {
    val obj = objectOf(element) ?: return ""
    return listOf(
        obj.text("line1"),
        obj.text("line2"),
        obj.text("city"),
        obj.text("state"),
        obj.text("postcode", "postalCode"),
    ).filter { it.isNotBlank() }.joinToString(", ")
}

/** `{isd, number}` as `isd number`. */
private fun vendorPhone(element: JsonElement?): String {
    val obj = objectOf(element) ?: return ""
    val number = obj.text("number")
    if (number.isBlank()) return ""
    return "${obj.text("isd")} $number".trim()
}

private fun objectOf(element: JsonElement?): JsonObject? = when (element) {
    is JsonObject -> element
    is JsonPrimitive -> runCatching { invoicesJson.parseToJsonElement(element.content) }.getOrNull() as? JsonObject
    else -> null
}
