package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.InboxAccept
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * `POST /invoices/process` — inbox entries on to pre-approval.
 *
 * Bulk sends only the ids; a single review sends its edits as `updates`
 * (`InboxReviewModal`'s `proceedWithSubmit`): blank dates, number, vendor
 * and department are left off rather than cleared, Net and Tax only go when
 * they were typed, and a blank company, bank or episode is an explicit null.
 */
internal fun processBody(ids: List<String>, accept: InboxAccept? = null): JsonObject = buildJsonObject {
    put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    if (accept != null) put("updates", acceptWire(accept))
}

private fun acceptWire(a: InboxAccept): JsonObject = buildJsonObject {
    a.invoiceNumber.trim().takeIf { it.isNotEmpty() }?.let { put("invoice_number", JsonPrimitive(it)) }
    a.vendorId.takeIf { it.isNotBlank() }?.let { put("vendor_id", JsonPrimitive(it)) }
    put("description", JsonPrimitive(a.description))
    InvoiceFormat.parseDateInput(a.invoiceDate)?.let { put("invoice_date", JsonPrimitive(it)) }
    InvoiceFormat.parseDateInput(a.dueDate)?.let { put("due_date", JsonPrimitive(it)) }
    InvoiceFormat.parseDateInput(a.effectiveDate)?.let { put("effective_date", JsonPrimitive(it)) }
    if (a.net.isNotBlank()) put("net_amount", JsonPrimitive(InboxTriage.amount(a.net)))
    if (a.tax.isNotBlank()) put("tax_amount", JsonPrimitive(InboxTriage.amount(a.tax)))
    put("gross_amount", JsonPrimitive(InboxTriage.amount(a.gross)))
    put("po_ids", buildJsonArray { a.poIds.forEach { add(JsonPrimitive(it)) } })
    put("pay_method", JsonPrimitive(a.payMethod.wire))
    a.departmentId.takeIf { it.isNotBlank() }?.let { put("department_id", JsonPrimitive(it)) }
    put("currency", JsonPrimitive(a.currency))
    put("company_id", a.companyId.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put("bank_id", a.bankId.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put("episode", a.episode.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
}

/** A note stored on a matched order's link — `POST /:id/match {po_id, notes: [text]}`. */
internal fun matchNoteBody(poId: String, note: String): JsonObject = buildJsonObject {
    put("po_id", JsonPrimitive(poId))
    put("notes", buildJsonArray { add(JsonPrimitive(note.trim())) })
}

/**
 * One file of a bulk batch — `{batch_id, attachments: [attachment]}`, one
 * attachment per request, its size filled in and `paid` only when set.
 */
internal fun bulkUploadBody(batchId: String, attachment: InvoiceAttachment, size: Long, paid: Boolean): JsonObject =
    buildJsonObject {
        put("batch_id", JsonPrimitive(batchId))
        put(
            "attachments",
            buildJsonArray {
                add(
                    buildJsonObject {
                        attachmentWire(attachment).forEach { (key, value) -> put(key, value) }
                        put("file_size", JsonPrimitive(size))
                        if (paid) put("paid", JsonPrimitive(true))
                    },
                )
            },
        )
    }

/** `GET /invoices/bulk-upload/batches` — every batch the server is still tracking. */
internal fun parseBulkBatches(data: JsonElement?): List<ServerBatch> = rowsOf(data).mapNotNull { row ->
    val id = row.text("batch_id", "id").ifBlank { return@mapNotNull null }
    ServerBatch(
        batchId = id,
        total = row.number("total")?.toInt() ?: 0,
        completed = row.number("completed")?.toInt() ?: 0,
        failed = row.number("failed")?.toInt() ?: 0,
        pending = row.number("pending")?.toInt() ?: 0,
        invoiceIds = row.arrayField("invoice_ids").mapNotNull { (it as? JsonPrimitive)?.content },
        isComplete = row.flag("is_complete") == true,
        createdAtMs = row.dateMs("created_at"),
    )
}
