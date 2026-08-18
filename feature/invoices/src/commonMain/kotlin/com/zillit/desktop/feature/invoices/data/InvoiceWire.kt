@file:Suppress("TooManyFunctions") // One reader per wire shape; one builder per body.

package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.TeamMember
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.UploadType
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

internal val invoicesJson = Json { ignoreUnknownKeys = true; isLenient = true }

// -- bodies -------------------------------------------------------------------

/** Every key the invoices service stores; `content_type` is the category, `content_subtype` the extension. */
internal fun attachmentWire(a: InvoiceAttachment): JsonObject = buildJsonObject {
    put("media", JsonPrimitive(a.media))
    put("bucket", JsonPrimitive(a.bucket))
    put("region", JsonPrimitive(a.region))
    put("name", JsonPrimitive(a.name))
    put("content_type", JsonPrimitive(a.contentType))
    put("content_subtype", JsonPrimitive(a.contentSubtype))
    put("caption", JsonPrimitive(a.caption))
}

/** `invoiceUploadPayload.js`: no `status`, no `vendor_id` — the server and the accountant decide those. */
internal fun departmentUploadBody(upload: DepartmentUpload): JsonObject = buildJsonObject {
    val x = upload.extraction ?: InvoiceExtraction()
    put("description", JsonPrimitive(uploadDescription(x.supplierName, upload.fileName)))
    putIfPresent("invoice_date", x.invoiceDate)
    putIfPresent("due_date", x.dueDate)
    put("gross_amount", JsonPrimitive(x.gross ?: 0.0))
    putIfPresent("po_number", x.poNumber)
    put("pay_method", JsonPrimitive(uploadPayMethod(upload.type, x.payMethod)))
    putIfPresent("department_id", upload.departmentId)
    put("currency", JsonPrimitive(x.currency.ifBlank { upload.projectCurrency.ifBlank { "GBP" } }))
    putIfPresent("upload_id", x.uploadId)
    put("attachments", buildJsonArray { upload.attachment?.let { add(attachmentWire(it)) } })
}

private fun uploadDescription(supplier: String, fileName: String): String = when {
    supplier.isNotBlank() -> "Invoice — ${supplier.trim()}"
    fileName.isNotBlank() -> fileName.substringBeforeLast('.')
    else -> "Uploaded invoice"
}

private fun uploadPayMethod(type: UploadType, extracted: String): String = when (type) {
    UploadType.Wire -> PayMethod.Wire.wire
    UploadType.Cheque -> PayMethod.Cheque.wire
    UploadType.Po -> PayMethod.normalise(extracted)
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let { put(key, JsonPrimitive(it)) }
}

/** `EnterInvoiceModal.jsx:275` — net/tax omitted when blank; lands in the inbox. */
internal fun enteredInvoiceBody(e: EnteredInvoice): JsonObject = buildJsonObject {
    put("attachments", buildJsonArray { add(attachmentWire(e.attachment)) })
    put("reference", JsonPrimitive(e.invoiceNumber))
    put("invoice_number", JsonPrimitive(e.invoiceNumber))
    put("vendor_id", JsonPrimitive(e.vendorId))
    put("company_id", e.companyId.orNull())
    put("bank_id", e.bankId.orNull())
    put("episode", e.episode.orNull())
    put("description", JsonPrimitive(e.description))
    e.netAmount?.let { put("net_amount", JsonPrimitive(it)) }
    e.taxAmount?.let { put("tax_amount", JsonPrimitive(it)) }
    put("gross_amount", JsonPrimitive(e.grossAmount))
    put("invoice_date", JsonPrimitive(e.invoiceDateMs))
    put("due_date", JsonPrimitive(e.dueDateMs))
    put("effective_date", e.effectiveDateMs?.let(::JsonPrimitive) ?: JsonNull)
    put("pay_method", JsonPrimitive(e.payMethod.wire))
    put("currency", JsonPrimitive(e.currency))
    put("department_id", e.departmentId.orNull())
    put("po_number", e.poNumber.orNull())
    e.uploadId?.takeIf { it.isNotBlank() }?.let { put("upload_id", JsonPrimitive(it)) }
    put("status", JsonPrimitive(InvoiceStatus.Inbox.wire))
}

internal fun approveBody(tierNumber: Int, totalTiers: Int): JsonObject = buildJsonObject {
    put("tier_number", JsonPrimitive(tierNumber))
    put("total_tiers", JsonPrimitive(totalTiers.coerceAtLeast(1)))
}

internal fun rejectBody(reason: String): JsonObject = buildJsonObject { put("reason", JsonPrimitive(reason.trim())) }

internal fun statusPatchBody(status: InvoiceStatus, approvalStatus: ApprovalStatus): JsonObject = buildJsonObject {
    put("status", JsonPrimitive(status.wire))
    put("approval_status", JsonPrimitive(approvalStatus.wire))
}

private fun String?.orNull(): JsonElement = this?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull

// -- readers ------------------------------------------------------------------

/** A list `data`: an array, `{}` for empty, or an object wrapping the array. */
internal fun rowsOf(data: JsonElement?): List<JsonObject> = when (data) {
    is JsonArray -> data.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf("data", "invoices", "rows", "items")
        .firstNotNullOfOrNull { data[it] as? JsonArray }
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    else -> emptyList()
}

internal fun parseInvoice(obj: JsonObject?): Invoice? {
    if (obj == null) return null
    val id = obj.text("id", "_id").takeIf { it.isNotBlank() } ?: return null
    return Invoice(
        id = id,
        invoiceNumber = obj.text("invoice_number"),
        reference = obj.text("reference"),
        vendorId = obj.text("vendor_id"),
        supplierName = obj.text("supplier_name"),
        description = obj.text("description"),
        grossAmount = obj.number("gross_amount") ?: 0.0,
        netAmount = obj.number("net_amount"),
        taxAmount = obj.number("tax_amount") ?: obj.number("vat_amount"),
        currency = obj.text("currency"),
        invoiceDateMs = obj.dateMs("invoice_date"),
        dueDateMs = obj.dateMs("due_date"),
        effectiveDateMs = obj.dateMs("effective_date"),
        payMethod = PayMethod.from(obj.text("pay_method")),
        status = InvoiceStatus.from(obj.text("status")),
        statusRaw = obj.text("status"),
        approvalStatus = ApprovalStatus.from(obj.text("approval_status")),
        departmentId = obj.text("department_id"),
        companyId = obj.text("company_id"),
        bankId = obj.text("bank_id"),
        episode = obj.text("episode"),
        poId = obj.text("po_id"),
        poNumber = obj.text("po_number"),
        linkedPos = obj.arrayField("linked_pos").mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val poId = o.text("po_id", "id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LinkedPo(
                poId,
                o.text("po_number"),
                o.text("po_vendor_id", "vendor_id"),
                o.number("po_gross_total", "gross_total"),
            )
        },
        attachments = obj.arrayField("attachments").mapNotNull { parseAttachment(it as? JsonObject) },
        approvals = obj.arrayField("approvals").mapNotNull { row ->
            (row as? JsonObject)?.let {
                Approval(it.text("user_id"), it.number("tier_number")?.toInt() ?: 0, it.dateMs("approved_at"))
            }
        },
        rejectionReason = obj.text("rejection_reason"),
        rejectedBy = obj.text("rejected_by"),
        rejectedAtMs = obj.dateMs("rejected_at"),
        holdReason = obj.text("hold_reason"),
        holdNote = obj.text("hold_note"),
        ocrConfidence = obj.number("ocr_confidence"),
        userId = obj.text("user_id", "created_by"),
        createdAtMs = obj.dateMs("created_at"),
        updatedBy = obj.text("updated_by"),
        updatedAtMs = obj.dateMs("updated_at"),
    )
}

internal fun parseAttachment(obj: JsonObject?): InvoiceAttachment? {
    if (obj == null) return null
    val media = obj.text("media", "stored_filename").takeIf { it.isNotBlank() } ?: return null
    val name = obj.text("name", "filename")
    return InvoiceAttachment(
        media = media,
        bucket = obj.text("bucket"),
        region = obj.text("region"),
        name = name,
        contentType = obj.text("content_type", "contentType"),
        contentSubtype = obj.text("content_subtype", "contentSubtype").lowercase()
            .ifBlank { name.substringAfterLast('.', "").lowercase() },
        caption = obj.text("caption"),
    )
}

internal fun parseHistory(data: JsonElement?): List<HistoryEntry> = rowsOf(data).map { row ->
    HistoryEntry(
        action = row.text("action", "event", "type"),
        actionBy = row.text("action_by", "user_id", "actor_id"),
        actionAtMs = row.dateMs("action_at", "created_at", "at"),
        note = row.text("note", "notes", "reason"),
    )
}.sortedByDescending { it.actionAtMs ?: Long.MIN_VALUE }

internal fun parseExtraction(data: JsonElement?): InvoiceExtraction {
    val obj = data as? JsonObject ?: return InvoiceExtraction()
    val supplier = when (val s = obj["supplier"]) {
        is JsonObject -> s.text("name")
        is JsonPrimitive -> s.content
        else -> obj.text("supplier_name", "vendor_name")
    }
    return InvoiceExtraction(
        uploadId = obj.text("upload_id", "id"),
        supplierName = supplier,
        invoiceNumber = obj.text("invoice_number"),
        invoiceDate = obj.text("invoice_date"),
        dueDate = obj.text("due_date"),
        gross = obj.number("gross", "gross_amount"),
        currency = obj.text("currency"),
        poNumber = obj.text("po_number"),
        payMethod = obj.text("pay_method"),
        confidence = obj.number("confidence"),
    )
}

internal fun parseSettings(data: JsonElement?): InvoiceSettings {
    val obj = data as? JsonObject ?: return InvoiceSettings()
    val me = obj["me"] as? JsonObject
    val members = obj.arrayField("team_members").mapNotNull { row ->
        (row as? JsonObject)?.let {
            TeamMember(
                userId = it.text("user_id", "id"),
                overrideAccess = it.flag("override_access") ?: false,
                isSenior = it.flag("is_senior") ?: false,
                runAccess = it.flag("run_access") ?: false,
            )
        }
    }
    val runApprovers = obj.arrayField("run_authorization").flatMap { tier ->
        val users = (tier as? JsonObject)?.arrayField("user").orEmpty()
        users.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }.toSet()
    return InvoiceSettings(
        canOverride = me?.flag("can_override"),
        isSenior = me?.flag("is_senior"),
        teamMembers = members,
        runApprovers = runApprovers,
    )
}

internal fun parseTierConfigs(data: JsonElement?): List<ApprovalTierConfig> = rowsOf(data).map { row ->
    ApprovalTierConfig(
        id = row.text("id", "_id"),
        scope = TierScope.from(row.text("scope")),
        departmentId = row.text("department_id").takeIf { it.isNotBlank() },
        tiers = row.arrayField("tiers").mapIndexedNotNull { index, tier ->
            val t = tier as? JsonObject ?: return@mapIndexedNotNull null
            TierLevel(
                order = t.number("order")?.toInt() ?: (index + 1),
                rules = t.arrayField("rules").mapNotNull { rule ->
                    (rule as? JsonObject)?.let {
                        TierRule(
                            type = it.text("type").ifBlank { TierRule.DEFAULT },
                            amountThreshold = it.number("amount_threshold", "threshold"),
                            userIds = it.arrayField("user_ids", "users").mapNotNull(::userIdOf),
                        )
                    }
                },
            )
        }.sortedBy { it.order },
    )
}

private fun userIdOf(e: JsonElement): String? = when (e) {
    is JsonPrimitive -> e.content.takeIf { it.isNotBlank() }
    is JsonObject -> e.text("user_id", "id", "_id").takeIf { it.isNotBlank() }
    else -> null
}

internal fun parseVendors(data: JsonElement?): List<Vendor> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    Vendor(
        id = id,
        name = row.text("name"),
        terms = row.text("terms", "payment_terms"),
        address = addressText(row["address"]),
        phone = phoneText(row["phone"]),
        email = row.text("email"),
    )
}

/** `{line1,line2,city,state,postal_code,country}`, the same as a JSON string, or free text. */
internal fun addressText(e: JsonElement?): String = when (e) {
    is JsonObject -> listOf("line1", "line2", "city", "state", "postal_code", "postalCode", "country")
        .map { e.text(it) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
    is JsonPrimitive -> e.content.trim().let { text ->
        (runCatching { invoicesJson.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let(::addressText) ?: text
    }
    else -> ""
}

/** `{country_code, number}`, the same as a string, or a bare number. */
internal fun phoneText(e: JsonElement?): String = when (e) {
    is JsonObject -> listOf(e.text("country_code", "countryCode"), e.text("number"))
        .filter { it.isNotBlank() }
        .joinToString(" ")
    is JsonPrimitive -> e.content.trim().let { text ->
        (runCatching { invoicesJson.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let(::phoneText) ?: text
    }
    else -> ""
}

/** `data` or `data.data`. */
internal fun parseBankAccounts(data: JsonElement?): List<BankAccount> = rowsOf(data).mapNotNull { row ->
    val id = row.text("id", "_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    BankAccount(
        id = id,
        name = row.text("name", "account_holder_name"),
        bankName = row.text("bank_name"),
        entityId = row.text("entity_id").takeIf { it.isNotBlank() },
    )
}

// -- lenient primitives ------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.text(vararg names: String): String = when (val e = firstOf(*names)) {
    is JsonPrimitive -> e.content
    else -> ""
}

/** A number, or a numeric string (with thousands separators tolerated). */
internal fun JsonObject.number(vararg names: String): Double? = (firstOf(*names) as? JsonPrimitive)?.let {
    it.doubleOrNull ?: it.content.replace(",", "").trim().toDoubleOrNull()
}

/** `1`, `true`, `"1"`, `"true"` are all set. */
internal fun JsonObject.flag(name: String): Boolean? {
    val e = this[name] as? JsonPrimitive ?: return null
    return e.booleanOrNull ?: e.longOrNull?.let { it != 0L } ?: e.content.lowercase().let { it == "1" || it == "true" }
}

/** An array, or an array JSON-encoded into a string; anything else is empty. */
internal fun JsonObject.arrayField(vararg names: String): List<JsonElement> = when (val e = firstOf(*names)) {
    is JsonArray -> e
    is JsonPrimitive ->
        runCatching { invoicesJson.parseToJsonElement(e.content) }.getOrNull() as? JsonArray ?: emptyList()
    else -> emptyList()
}

/** Epoch ms (number or numeric string), `YYYY-MM-DD` (UTC midnight), or an ISO instant. */
internal fun JsonObject.dateMs(vararg names: String): Long? = parseDateMs(firstOf(*names))

internal fun parseDateMs(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    val text = p.content.trim()
    val numeric = p.longOrNull ?: text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
    return when {
        numeric != null -> numeric.takeIf { it > 0 }
        text.length < DATE_LENGTH -> null
        else -> runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(DATE_LENGTH)) }.getOrNull()
                ?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
    }
}

private const val DATE_LENGTH = 10
