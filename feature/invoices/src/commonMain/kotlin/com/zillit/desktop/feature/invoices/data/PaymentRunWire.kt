package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.RunSignOff
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `/invoices/active-runs`.
 *
 * The total is the web's `run.total_amount || run.computed_total || 0`
 * (`PaymentsPage.jsx:2100`): the stored figure first, the server's own sum
 * only when that is missing or zero.
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
        total = row.number("total_amount")?.takeIf { it != 0.0 }
            ?: row.number("computed_total")?.takeIf { it != 0.0 }
            ?: 0.0,
        currency = row.text("currency"),
        invoiceCount = row.number("invoice_count", "invoiceCount")?.toInt() ?: 0,
        status = PaymentRunStatus.from(row.text("status")),
        statusRaw = row.text("status"),
        // An array, or the same array JSON-encoded into a string.
        approvals = row.arrayField("approval", "approvals").mapNotNull { entry ->
            val signed = entry as? JsonObject ?: return@mapNotNull null
            val tier = signed.number("tier_number", "tier")?.toInt() ?: return@mapNotNull null
            RunSignOff(tierNumber = tier, userId = signed.text("user_id", "userId"))
        },
        rejectionReason = row.text("rejection_reason"),
        rejectedBy = row.text("rejected_by"),
        rejectedAtMs = row.dateMs("rejected_at"),
        createdBy = row.text("created_by"),
        createdAtMs = row.dateMs("created_at"),
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

private fun jsonArrayOf(values: List<String>) =
    kotlinx.serialization.json.buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
