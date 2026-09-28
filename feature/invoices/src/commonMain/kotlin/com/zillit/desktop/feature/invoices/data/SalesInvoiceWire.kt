package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.ClientAddress
import com.zillit.desktop.feature.invoices.domain.ClientCountry
import com.zillit.desktop.feature.invoices.domain.PostcodeMatch
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite
import com.zillit.desktop.feature.invoices.domain.TrackingNode
import com.zillit.desktop.feature.invoices.domain.TrackingSet
import com.zillit.desktop.feature.invoices.domain.VendorPo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** `/invoices/sales-invoices` — money owed to the production. */
internal fun parseSalesInvoices(data: JsonElement?): List<SalesInvoice> = rowsOf(data).mapNotNull(::parseSalesRow)

/**
 * `GET /sales-invoices/:id` — the record itself, or wrapped as
 * `{sales_invoice}` / `{invoice}`; the web reads `full.data || full`.
 */
internal fun parseSalesInvoice(data: JsonElement?): SalesInvoice? {
    val obj = data as? JsonObject ?: return null
    return parseSalesRow(obj)
        ?: (obj["sales_invoice"] as? JsonObject)?.let(::parseSalesRow)
        ?: (obj["invoice"] as? JsonObject)?.let(::parseSalesRow)
}

private fun parseSalesRow(row: JsonObject): SalesInvoice? {
    val id = row.text("id", "_id").ifBlank { return null }
    val rawLines = row.arrayField("line_items").mapNotNull { it as? JsonObject }
    val lines = rawLines.mapIndexed { index, line -> parseRecordLine(line, index) }
    val address = row.firstOf("client_address", "clientAddress")
    return SalesInvoice(
        id = id,
        reference = row.text("reference", "invoice_number"),
        clientName = row.text("client_name", "clientName"),
        description = row.text("description"),
        grossAmount = row.number("gross_amount", "grossAmount") ?: 0.0,
        currency = row.text("currency"),
        dueDateMs = row.dateMs("due_date", "dueDate"),
        createdAtMs = row.dateMs("created_at", "createdAt"),
        status = SalesInvoiceStatus.from(row.text("status")),
        invoiceDateMs = row.dateMs("invoice_date", "invoiceDate"),
        lineItems = lines,
        clientAddress = clientAddressOf(address),
        clientAddressText = (address as? JsonPrimitive)?.content?.trim().orEmpty(),
        payTerms = row.text("pay_terms", "payTerms"),
        createdBy = row.text("user_id", "created_by"),
        updatedBy = row.text("updated_by"),
        updatedAtMs = row.dateMs("updated_at"),
        lineTaxAmounts = rawLines.mapIndexedNotNull { index, line ->
            line.number("tax_amount")?.let { lines[index].id to it }
        }.toMap(),
        lineItemsJson = rawLineItems(row),
    )
}

/** A stored line, layers and tags included — the coding screen's own reading. */
internal fun parseRecordLine(line: JsonObject, index: Int): CodedLine = parseCodedLine(line, index)

/** An address object, or a JSON string holding one; anything else is empty. */
private fun clientAddressOf(element: JsonElement?): ClientAddress {
    val obj = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> runCatching { invoicesJson.parseToJsonElement(element.content) }.getOrNull() as? JsonObject
        else -> null
    } ?: return ClientAddress()
    return ClientAddress(
        line1 = obj.text("line1"),
        line2 = obj.text("line2"),
        city = obj.text("city"),
        state = obj.text("state"),
        country = obj.text("country"),
        postalCode = obj.text("postal_code", "postalCode", "postcode"),
    )
}

/**
 * A new or edited sales invoice — `SalesPage`'s `handleCreate`
 * (`SalesPage.jsx:407-448`): the client and the structured address, the
 * gross the lines add up to, the dates as the form holds them, always a
 * draft, and the lines in the stored record shape. The reference goes on a
 * create only; there is no description, and `pay_terms` is not sent.
 */
internal fun salesInvoiceBody(invoice: SalesInvoiceWrite): JsonObject = buildJsonObject {
    put("client_name", JsonPrimitive(invoice.clientName.trim()))
    put(
        "client_address",
        buildJsonObject {
            put("line1", JsonPrimitive(invoice.clientAddress.line1))
            put("line2", JsonPrimitive(invoice.clientAddress.line2))
            put("city", JsonPrimitive(invoice.clientAddress.city))
            put("state", JsonPrimitive(invoice.clientAddress.state))
            put("country", JsonPrimitive(invoice.clientAddress.country))
            put("postal_code", JsonPrimitive(invoice.clientAddress.postalCode))
        },
    )
    put("gross_amount", JsonPrimitive(invoice.gross))
    put("currency", JsonPrimitive(invoice.currency))
    put("invoice_date", JsonPrimitive(invoice.invoiceDate))
    put("due_date", JsonPrimitive(invoice.dueDate))
    put("status", JsonPrimitive(SalesInvoiceStatus.Draft.wire))
    put("line_items", recordLines(invoice.lines, invoice.savedLinesJson))
    invoice.reference?.let { put("reference", JsonPrimitive(it)) }
}

/** `GET /account-hub/tracking-sets?include_nodes=true&active_only=true`. */
internal fun parseTrackingSets(data: JsonElement?): List<TrackingSet> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    TrackingSet(
        id = id,
        name = row.text("name"),
        prefix = row.text("prefix"),
        color = row.text("color", "colour"),
        active = row.flag("active") != false,
        nodes = row.arrayField("nodes").mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val nodeId = obj.text("id", "_id").ifBlank { return@mapNotNull null }
            TrackingNode(
                id = nodeId,
                code = obj.text("code"),
                label = obj.text("label", "name"),
                isHeader = obj.flag("is_header") == true,
                active = obj.flag("active") != false,
            )
        },
    )
}

/** `GET /api/v2/purchase-orders?per_page=200` — the vendor history's orders. */
internal fun parseVendorPos(data: JsonElement?): List<VendorPo> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    VendorPo(
        id = id,
        poNumber = row.text("po_number"),
        vendorId = row.text("vendor_id"),
        description = row.text("description"),
        grossTotal = row.number("gross_total") ?: 0.0,
        currency = row.text("currency"),
        status = row.text("status"),
    )
}

/** `preset/isd-codes` rows — `{name, dial_code, code}`; a row with no name is not offered. */
internal fun parseCountries(data: JsonElement?): List<ClientCountry> = rowsOf(data).mapNotNull { row ->
    val name = row.text("name").trim().ifBlank { return@mapNotNull null }
    ClientCountry(name = name, code = row.text("code").trim())
}

/** The first geonames match's city and state; blanks when there was none. */
internal fun parsePostcodeMatch(data: JsonElement?): PostcodeMatch {
    val first = rowsOf(data).firstOrNull() ?: return PostcodeMatch()
    return PostcodeMatch(city = first.text("city").trim(), state = first.text("state").trim())
}

/** One URL path segment, percent-encoded — a UK postcode carries a space ("SL0 0NH"). */
internal fun String.pathSegment(): String = buildString {
    for (byte in this@pathSegment.trim().encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (byte >= 0 && (char.isLetterOrDigit() || char in "-._~")) {
            append(char)
        } else {
            append('%').append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF]).append(HEX_DIGITS[byte.toInt() and 0xF])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
