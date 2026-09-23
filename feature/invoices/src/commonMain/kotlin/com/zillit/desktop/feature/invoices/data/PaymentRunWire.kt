package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.RunSignOff
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite
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
internal fun parseRuns(data: JsonElement?): List<PaymentRun> = rowsOf(data).mapNotNull(::parseRun)

/** One run row; null without an id. */
internal fun parseRun(row: JsonObject): PaymentRun? {
    val id = row.text("id", "_id").ifBlank { return null }
    return PaymentRun(
        id = id,
        number = row.text("number", "run_number"),
        name = row.text("name", "description"),
        payMethod = PayMethod.from(row.text("pay_method", "payMethod")),
        total = row.number("computed_total", "total_amount") ?: 0.0,
        currency = row.text("currency"),
        invoiceCount = row.number("invoice_count", "invoiceCount")?.toInt() ?: 0,
        status = PaymentRunStatus.from(row.text("status")),
        // An array, or the same array JSON-encoded into a string.
        approvals = row.arrayField("approval", "approvals").mapNotNull { entry ->
            val signed = entry as? JsonObject ?: return@mapNotNull null
            val tier = signed.number("tier_number", "tier")?.toInt() ?: return@mapNotNull null
            RunSignOff(tierNumber = tier, userId = signed.text("user_id", "userId"))
        },
        rejectionReason = row.text("rejection_reason"),
        rejectedBy = row.text("rejected_by"),
        rejectedAtMs = row.dateMs("rejected_at"),
    )
}

/**
 * `GET /active-runs/:id` — `{ run, invoices }`. A bare run row is tolerated
 * too, with no invoices, so an older shape still opens the dialog.
 */
internal fun parseRunDetail(data: JsonElement?): PaymentRunDetail {
    val obj = data as? JsonObject
    val runRow = (obj?.get("run") as? JsonObject) ?: obj
    val run = runRow?.let(::parseRun) ?: PaymentRun(id = "")
    val invoices = obj?.let { rowsOf(it["invoices"]) }.orEmpty().mapNotNull(::parseInvoice)
    return PaymentRunDetail(run = run, invoices = invoices)
}

/** Signing one tier: which tier, and how many the chain has (`PaymentsPage.jsx`). */
internal fun runApproveBody(tierNumber: Int, totalTiers: Int): JsonObject = buildJsonObject {
    put("tier_number", JsonPrimitive(tierNumber))
    put("total_tiers", JsonPrimitive(totalTiers))
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
        invoiceDateMs = row.dateMs("invoice_date", "invoiceDate"),
        lineItems = row.arrayField("line_items").mapIndexedNotNull { index, line ->
            (line as? JsonObject)?.let { parseCodedLine(it, index) }
        },
    )
}

/**
 * A new sales invoice — `SalesPage`'s `handleCreate`: raised as a draft, its
 * gross the lines' gross, the dates as the web's date inputs hold them, and
 * the lines in the stored record shape.
 */
internal fun salesInvoiceBody(invoice: SalesInvoiceWrite): JsonObject = buildJsonObject {
    put("client_name", JsonPrimitive(invoice.clientName.trim()))
    put("description", JsonPrimitive(invoice.description.trim()))
    put("gross_amount", JsonPrimitive(invoice.gross))
    put("currency", JsonPrimitive(invoice.currency))
    put("invoice_date", JsonPrimitive(invoice.invoiceDate))
    put("due_date", JsonPrimitive(invoice.dueDate))
    put("status", JsonPrimitive(SalesInvoiceStatus.Draft.wire))
    put("reference", JsonPrimitive(invoice.reference.trim()))
    put("line_items", recordLines(invoice.lines, ""))
}

private fun jsonArrayOf(values: List<String>) =
    kotlinx.serialization.json.buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
