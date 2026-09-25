package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.PoLine
import com.zillit.desktop.feature.invoices.domain.PurchaseOrderRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `GET /purchase-orders/:id` — the deployed shape is flat (`data` is the PO),
 * and `data.po` is the older documented envelope; both are read, as
 * `LinkedPoViewer.fetchPoCached` does.
 */
internal fun parsePurchaseOrder(data: JsonElement?): PurchaseOrderRecord? {
    val outer = data as? JsonObject ?: return null
    val obj = (outer["po"] as? JsonObject) ?: outer
    val id = obj.text("id", "_id").takeIf { it.isNotBlank() } ?: return null
    return PurchaseOrderRecord(
        id = id,
        poNumber = obj.text("po_number", "poNumber"),
        description = obj.text("description"),
        vendorId = obj.text("vendor_id"),
        vendorName = obj.text("vendor_name", "vendor"),
        vendorAddress = addressText(obj["vendor_address"]),
        status = obj.text("status"),
        currency = obj.text("currency"),
        grossTotal = obj.number("gross_total", "gross_amount"),
        departmentId = obj.text("department_id"),
        effectiveDateMs = obj.dateMs("effective_date"),
        deliveryDateMs = obj.dateMs("delivery_date"),
        createdBy = obj.text("user_id", "created_by", "raised_by"),
        episode = obj.text("episode"),
        notes = obj.text("notes"),
        lines = obj.arrayField("line_items").mapNotNull { row ->
            val line = row as? JsonObject ?: return@mapNotNull null
            // The reclaimable-tax line is not a line of the order.
            if (line.flag("is_tax") == true || line.flag("isTax") == true) return@mapNotNull null
            PoLine(
                description = line.text("description"),
                quantity = line.number("quantity", "qty"),
                unitPrice = line.number("unit_price", "unitPrice"),
                total = line.number("total"),
                id = line.text("id"),
                account = line.text("account"),
                taxRate = parseTaxRate(line["tax_rate"] ?: line["taxRate"]),
                taxType = line.text("tax_type", "taxType"),
                expenditureType = line.text("expenditure_type", "expenditureType"),
                splitParentId = line.text("split_parent_id", "splitParentId").ifBlank { null },
            )
        },
    )
}

/**
 * `POST /purchase-orders/:id/pdf` takes the production's display names; the
 * invoice screens pass none (`usePoPdfPreview` → `getPdfBlobUrl(po, access, {})`),
 * so every one goes as an empty string.
 */
internal fun poPdfBody(): JsonObject = buildJsonObject {
    listOf("projectName", "companyName", "companyAddress", "companyPhone", "companyEmail").forEach {
        put(it, JsonPrimitive(""))
    }
}

/** `data.attachment` of the PDF route — refused when the S3 keys are missing, as the web refuses it. */
internal fun parsePoPdfAttachment(data: JsonElement?): InvoiceAttachment? {
    val attachment = (data as? JsonObject)?.get("attachment") as? JsonObject ?: return null
    val parsed = parseAttachment(attachment) ?: return null
    if (parsed.bucket.isBlank() || parsed.region.isBlank()) return null
    // Always a PDF, whatever the stored category says (`getPdfBlobUrl` forces the mime).
    return parsed.copy(contentType = "document", contentSubtype = "pdf")
}
