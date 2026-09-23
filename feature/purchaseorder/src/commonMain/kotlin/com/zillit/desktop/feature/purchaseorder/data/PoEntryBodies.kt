package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.feature.purchaseorder.domain.PoEntryUpdate
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoPostRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The processing page's two writes, key for key as the web sends them.
 *
 * Save is `handleSavePOEntry`'s PATCH — snake_case, the header the page edits,
 * the VAT figures and the whole `line_items` array. Post is `handlePostPO`'s
 * body — **camelCase** at the top (`vatTreatment`, `lineItems`, `poDetails`)
 * with snake_case inside each line and inside `poDetails`. Both casings are the
 * web's, and both are pinned by `PoEntryWireTest`; "tidying" either one sends a
 * body the server reads as empty.
 */
internal fun PoEntryUpdate.body(): JsonObject = buildJsonObject {
    put("vat_treatment", JsonPrimitive(vat.treatment))
    put("vat_amount", JsonPrimitive(vat.amount))
    put("gross_total", JsonPrimitive(vat.gross))
    put("line_items", lines.entryLines())
    put("vendor_id", header.vendorId.orNull())
    put("company_id", header.companyId.orNull())
    put("department_id", header.departmentId.orNull())
    put("nominal_code", header.nominalCode.orNull())
    put("currency", JsonPrimitive(header.currency))
    put("delivery_date", header.deliveryDate?.let { JsonPrimitive(it) } ?: JsonNull)
    // Only when set, as the web does: a PATCH without it leaves the date alone.
    effectiveDate?.let { put("effective_date", JsonPrimitive(it)) }
}

internal fun PoPostRequest.body(): JsonObject = buildJsonObject {
    put("vatTreatment", JsonPrimitive(vat.treatment))
    put("vatAmount", JsonPrimitive(vat.amount))
    put("grossTotal", JsonPrimitive(vat.gross))
    put("effectiveDate", effectiveDate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("lineItems", lines.entryLines())
    put(
        "poDetails",
        buildJsonObject {
            put("description", JsonPrimitive(description))
            put("vendor_id", header.vendorId.orNull())
            put("department_id", header.departmentId.orNull())
            put("nominal_code", header.nominalCode.orNull())
            put("currency", JsonPrimitive(header.currency))
            put("notes", notes.orNull())
            put("company_id", header.companyId.orNull())
            put("episode", episode.orNull())
            put("delivery_address", deliveryAddress ?: JsonPrimitive(""))
            put("delivery_address_id", deliveryAddressId.orNull())
            put("delivery_date", header.deliveryDate?.let { JsonPrimitive(it) } ?: JsonNull)
        },
    )
}

/**
 * One line as both processing-page writes send it — the web's
 * `buildLineItemsPayload`. Every key, every time, nulls included: the server
 * replaces the array, so a key left out is a value lost. `id` rides along so
 * the backend can remap the children's `split_parent_id`.
 */
internal fun PoLine.entryBody(): JsonObject = buildJsonObject {
    put("id", id.orNull())
    put("description", JsonPrimitive(description))
    put("quantity", JsonPrimitive(quantity))
    put("unit_price", JsonPrimitive(unitPrice))
    put("total", JsonPrimitive(total))
    put("account", JsonPrimitive(nominalCode.orEmpty()))
    put("expenditure_type", JsonPrimitive(expenditureType.orEmpty()))
    put("tax_type", taxType.orNull())
    put("tax_rate", vatRate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("rental_start", rentalStart.orNull())
    put("rental_end", rentalEnd.orNull())
    put("split_parent_id", splitParentId.orNull())
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
    put("custom_fields", customFields ?: JsonArray(emptyList()))
    put("tracking_codes", trackingCodes ?: JsonObject(emptyMap()))
    put("is_tax", JsonPrimitive(isTax))
    departmentId?.takeIf { it.isNotBlank() }?.let { putDepartment(it) }
}

/** A line's own department, when it has one — kept so an entry save cannot blank it. */
private fun JsonObjectBuilder.putDepartment(id: String) = put("department", JsonPrimitive(id))

private fun List<PoLine>.entryLines(): JsonArray = buildJsonArray { forEach { add(it.entryBody()) } }

private fun String?.orNull(): JsonElement =
    this?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull

/**
 * `delivery_address` as one line of text, whichever shape it came in: a string
 * as it is, an object as its filled parts joined in reading order.
 */
internal fun JsonElement?.addressText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonObject -> ADDRESS_ORDER
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
        .joinToString(", ")
        .takeIf { it.isNotEmpty() }

    else -> null
}

private val ADDRESS_ORDER = listOf("name", "line1", "line2", "city", "state", "postalCode", "country")
