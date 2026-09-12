package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `/invoices/active-runs`.
 *
 * `computed_total` is the server's own sum over the run's invoices and wins
 * over the stored `total_amount`, which can lag an invoice being pulled out.
 */
internal fun parseRuns(data: JsonElement?): List<PaymentRun> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    PaymentRun(
        id = id,
        number = row.text("number", "run_number"),
        name = row.text("name", "description"),
        payMethod = PayMethod.from(row.text("pay_method", "payMethod")),
        total = row.number("computed_total", "total_amount") ?: 0.0,
        currency = row.text("currency"),
        invoiceCount = row.number("invoice_count", "invoiceCount")?.toInt() ?: 0,
        status = PaymentRunStatus.from(row.text("status")),
    )
}

/** A run is created from the invoices it pays, by one method, under a name and number. */
internal fun runBody(
    name: String,
    number: String,
    payMethod: PayMethod,
    invoiceIds: List<String>,
): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name.trim()))
    put("number", JsonPrimitive(number.trim()))
    put("pay_method", JsonPrimitive(payMethod.wire))
    put("invoice_ids", jsonArrayOf(invoiceIds))
}

/** `POST /invoices/bulk-update` — the same change applied to many rows at once. */
internal fun bulkUpdateBody(ids: List<String>, field: String, value: String): JsonObject = buildJsonObject {
    put("ids", jsonArrayOf(ids))
    put("data", buildJsonObject { put(field, JsonPrimitive(value)) })
}

/** `/invoices/sales-invoices` — money owed to the production. */
internal fun parseSalesInvoices(data: JsonElement?): List<SalesInvoice> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").ifBlank { return@mapNotNull null }
    SalesInvoice(
        id = id,
        reference = row.text("reference", "invoice_number"),
        clientName = row.text("client_name", "clientName"),
        description = row.text("description"),
        grossAmount = row.number("gross_amount", "grossAmount") ?: 0.0,
        currency = row.text("currency"),
        dueDateMs = row.dateMs("due_date", "dueDate"),
        createdAtMs = row.dateMs("created_at", "createdAt"),
        status = SalesInvoiceStatus.from(row.text("status")),
    )
}

/** What a new sales invoice needs: who it is for, for how much, and by when. */
internal fun salesInvoiceBody(invoice: SalesInvoice): JsonObject = buildJsonObject {
    put("client_name", JsonPrimitive(invoice.clientName.trim()))
    put("description", JsonPrimitive(invoice.description.trim()))
    put("gross_amount", JsonPrimitive(invoice.grossAmount))
    put("currency", JsonPrimitive(invoice.currency))
    invoice.dueDateMs?.let { put("due_date", JsonPrimitive(it)) }
    put("reference", JsonPrimitive(invoice.reference.trim()))
}

private fun jsonArrayOf(values: List<String>) =
    kotlinx.serialization.json.buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
