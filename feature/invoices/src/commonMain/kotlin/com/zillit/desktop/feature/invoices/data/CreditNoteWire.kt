package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.CreditNoteWrite
import com.zillit.desktop.feature.invoices.domain.CreditNotes
import com.zillit.desktop.feature.invoices.domain.LineItems
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** `/invoices/credit-notes` — ordinary snake_case, like the rest of this service. */
internal fun parseCreditNotes(data: JsonElement?): List<CreditNote> = rowsOf(data).mapNotNull(::parseCreditNote)

internal fun parseCreditNote(row: JsonObject): CreditNote? {
    val id = row.text("id", "_id").ifBlank { return null }
    return CreditNote(
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
        invoiceId = row.text("invoice_id", "invoiceId"),
        effectiveDateMs = row.dateMs("effective_date", "effectiveDate"),
        status = CreditNoteStatus.from(row.text("status")),
        notes = row.text("notes"),
        lineItems = row.arrayField("line_items").mapIndexedNotNull { index, line ->
            (line as? JsonObject)?.let { parseRecordLine(it, index) }
        },
        attachments = row.arrayField("attachments").mapNotNull { (it as? JsonObject)?.let(::parseCreditAttachment) },
        createdBy = row.text("created_by", "user_id"),
        updatedBy = row.text("updated_by"),
        createdAtMs = row.dateMs("created_at"),
        updatedAtMs = row.dateMs("updated_at"),
        lineItemsJson = rawLineItems(row),
        typeRaw = row.text("type"),
        lineTaxAmounts = row.arrayField("line_items").mapIndexedNotNull { index, line ->
            val obj = line as? JsonObject ?: return@mapIndexedNotNull null
            obj.number("tax_amount")?.let { obj.text("id").ifBlank { "line-$index" } to it }
        }.toMap(),
    )
}

/** Only a complete storage model is kept — the web drops one without media, bucket or region. */
private fun parseCreditAttachment(obj: JsonObject): CreditAttachment? {
    val stored = parseAttachment(obj) ?: return null
    if (stored.bucket.isBlank() || stored.region.isBlank()) return null
    return CreditAttachment(
        name = obj.text("original_filename", "name", "filename").ifBlank { stored.name },
        sizeBytes = obj.number("size", "file_size")?.toLong(),
        stored = stored,
        storedJson = obj.toString(),
    )
}

/**
 * A credit note or dispute save — `handleCreate`'s payload. A dispute has no
 * lines: its gross is the amount typed, its status `disputed`, and its
 * description the reason. Attachments go inline, stored ones exactly as they
 * came back.
 */
internal fun creditNoteBody(write: CreditNoteWrite): JsonObject = buildJsonObject {
    val dispute = write.type == CreditNoteType.Dispute
    put("supplier_name", write.vendorName.nullIfEmpty())
    put("vendor_id", write.vendorId.nullIfEmpty())
    put("invoice_reference", write.invoiceReference.nullIfEmpty())
    put("invoice_id", write.invoiceId.nullIfEmpty())
    put("type", JsonPrimitive(write.type.wire))
    put("reason", JsonPrimitive(write.reason))
    val described = write.lines.map { it.description }.filter { it.isNotBlank() }.joinToString(", ")
    val description = if (dispute) write.reason.ifBlank { "Dispute" } else described.ifBlank { "Credit note" }
    put("description", JsonPrimitive(description))
    put("gross_amount", JsonPrimitive(CreditNotes.gross(write)))
    put("currency", JsonPrimitive(write.currency))
    put("effective_date", JsonPrimitive(write.effectiveDate))
    put("status", JsonPrimitive(if (dispute) CreditNoteStatus.Disputed.wire else CreditNoteStatus.Pending.wire))
    put("notes", JsonPrimitive(write.notes))
    put("attachments", buildJsonArray { write.attachments.mapNotNull(::creditAttachmentWire).forEach { add(it) } })
    if (!dispute) put("line_items", recordLines(write.lines, write.savedLinesJson))
}

/** A stored attachment's own JSON, unchanged; a fresh upload in the storage model's keys. */
internal fun creditAttachmentWire(attachment: CreditAttachment): JsonObject? {
    if (attachment.storedJson.isNotBlank()) {
        runCatching { invoicesJson.parseToJsonElement(attachment.storedJson) as? JsonObject }.getOrNull()?.let {
            return it
        }
    }
    val stored = attachment.stored ?: return null
    return buildJsonObject {
        attachmentWire(stored).forEach { (key, value) -> put(key, value) }
        put("original_filename", JsonPrimitive(attachment.name))
        attachment.sizeBytes?.let { put("size", JsonPrimitive(it)) }
    }
}

/**
 * Lines in the shape credit notes and sales invoices store — the web's
 * `line_items` map: `amount` (not `total`), a `tax_amount` at the line's rate,
 * `sort_order`, and no ids. Layers, tags and rental dates a saved line
 * already had are carried over; a new split child takes its parent's.
 */
internal fun recordLines(lines: List<CodedLine>, savedJson: String): JsonArray {
    val saved = runCatching { invoicesJson.parseToJsonElement(savedJson) as? JsonArray }.getOrNull()
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    return buildJsonArray {
        lines.forEachIndexed { index, line ->
            val own = saved.firstOrNull { it.text("id") == line.id }
            val inherited = own ?: line.splitParentId?.let { parent -> saved.firstOrNull { it.text("id") == parent } }
            add(
                buildJsonObject {
                    put("description", JsonPrimitive(line.description))
                    put("quantity", JsonPrimitive(line.quantity))
                    put("unit_price", JsonPrimitive(line.unitPrice))
                    put("amount", JsonPrimitive(line.amount))
                    put("tax_type", line.taxType.nullIfEmpty())
                    put("tax_rate", line.taxRate?.let(::JsonPrimitive) ?: JsonNull)
                    put("tax_amount", JsonPrimitive(LineItems.taxOf(line)))
                    put("account", line.account.trim().nullIfEmpty())
                    put("expenditure_type", line.expenditureType.nullIfEmpty())
                    put("rental_start", own?.get("rental_start") ?: JsonNull)
                    put("rental_end", own?.get("rental_end") ?: JsonNull)
                    put("split_parent_id", line.splitParentId.nullIfEmpty())
                    put("tracking_codes", layersOf(line, inherited))
                    put("tags", inherited?.get("tags") as? JsonArray ?: JsonArray(emptyList()))
                    put("sort_order", JsonPrimitive(index))
                },
            )
        }
    }
}

/**
 * A line's Layers: what the picker holds for a line read back from the server
 * or picked on — a read line's picks stand even when cleared — else the saved
 * row's own, or for a new split child its parent's, as the web's split copies
 * them (the same rule as the entry writer's).
 */
private fun layersOf(line: CodedLine, inherited: JsonObject?): JsonObject = when {
    line.carried != null || line.trackingCodes.isNotEmpty() ->
        JsonObject(line.trackingCodes.mapValues { (_, code) -> JsonPrimitive(code) })
    else -> inherited?.get("tracking_codes") as? JsonObject ?: JsonObject(emptyMap())
}

private fun String?.nullIfEmpty(): JsonElement = this?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull
