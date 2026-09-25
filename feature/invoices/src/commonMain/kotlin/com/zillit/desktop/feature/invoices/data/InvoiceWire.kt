@file:Suppress("TooManyFunctions") // One reader per wire shape; one builder per body.

package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.TeamMember
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    // Never both: the server derives ready_to_pay from `paid`, and a status
    // sent beside it would win and leave a settled invoice in the pipeline.
    if (e.paid) put("paid", JsonPrimitive(true)) else put("status", JsonPrimitive(InvoiceStatus.Inbox.wire))
}

internal fun approveBody(tierNumber: Int, totalTiers: Int): JsonObject = buildJsonObject {
    put("tier_number", JsonPrimitive(tierNumber))
    put("total_tiers", JsonPrimitive(totalTiers.coerceAtLeast(1)))
}

/** The reason as typed — the web sends it untrimmed (`ApprovalPage.jsx` `rejectOne`). */
internal fun rejectBody(reason: String): JsonObject = buildJsonObject { put("reason", JsonPrimitive(reason)) }

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
    val (lines, taxLine) = parseCodedLines(obj)
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
        payMethodRaw = obj.text("pay_method"),
        status = InvoiceStatus.from(obj.text("status")),
        statusRaw = obj.text("status"),
        approvalStatus = ApprovalStatus.from(obj.text("approval_status")),
        departmentId = obj.text("department_id"),
        companyId = obj.text("company_id"),
        bankId = obj.text("bank_id"),
        episode = obj.text("episode"),
        poId = obj.text("po_id"),
        poNumber = obj.text("po_number"),
        linkedPos = parseLinkedPos(obj),
        poIds = obj.arrayField("po_ids").mapNotNull(::poIdOf),
        attachments = obj.arrayField("attachments").mapNotNull { parseAttachment(it as? JsonObject) },
        approvals = parseApprovals(obj),
        rejectionReason = obj.text("rejection_reason"),
        rejectedBy = obj.text("rejected_by"),
        rejectedAtMs = obj.dateMs("rejected_at"),
        holdReason = obj.text("hold_reason"),
        holdNote = obj.text("hold_note"),
        ocrConfidence = obj.number("ocr_confidence"),
        userId = obj.text("user_id", "created_by"),
        assignedTo = obj.text("assigned_to", "assignedTo"),
        createdAtMs = obj.dateMs("created_at"),
        updatedBy = obj.text("updated_by"),
        updatedAtMs = obj.dateMs("updated_at"),
        lineItems = lines,
        taxLine = taxLine,
        lineItemsJson = rawLineItems(obj),
        nominalCode = obj.text("nominal_code"),
        activeRunId = obj.text("active_run_id"),
        paidAtMs = obj.dateMs("paid_at"),
        wireAttachments = parseWireAttachments(obj),
        cis = obj.flag("cisApplies") == true || obj.flag("cis") == true,
        paymentTerms = obj.text("paymentTerms", "terms"),
    )
}

private fun parseLinkedPos(obj: JsonObject): List<LinkedPo> = obj.arrayField("linked_pos").mapNotNull { row ->
    val o = row as? JsonObject ?: return@mapNotNull null
    val poId = o.text("po_id", "id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    LinkedPo(
        poId,
        o.text("po_number"),
        o.text("po_vendor_id", "vendor_id"),
        o.number("po_gross_total", "gross_total"),
        notes = linkNotes(o["notes"]),
    )
}

/**
 * A link's `notes` as the review reads them: an array, a JSON string holding
 * one, or a plain string — trimmed, blanks dropped (`InboxReviewModal`).
 */
internal fun linkNotes(element: JsonElement?): List<String> {
    val raw: List<JsonElement> = when (element) {
        is JsonArray -> element
        is JsonPrimitive -> if (!element.isString || element.content.isBlank()) {
            emptyList()
        } else {
            (runCatching { invoicesJson.parseToJsonElement(element.content) }.getOrNull() as? JsonArray)
                ?: listOf(element)
        }
        else -> emptyList()
    }
    return raw.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
}

/** One `po_ids` entry: a bare id, or an object carrying one. */
private fun poIdOf(e: JsonElement): String? = when (e) {
    is JsonPrimitive -> e.content.trim().takeIf { it.isNotBlank() && e !is JsonNull }
    is JsonObject -> e.text("po_id", "id", "_id").takeIf { it.isNotBlank() }
    else -> null
}

private fun parseApprovals(obj: JsonObject): List<Approval> = obj.arrayField("approvals").mapNotNull { row ->
    (row as? JsonObject)?.let {
        Approval(it.text("user_id"), it.number("tier_number")?.toInt() ?: 0, it.dateMs("approved_at"))
    }
}

/** A record's saved `line_items` as sent — an array, or the array JSON-encoded in a string. */
internal fun rawLineItems(obj: JsonObject): String = when (val raw = obj["line_items"]) {
    is JsonArray -> raw.toString()
    is JsonPrimitive -> if (raw is JsonNull) "" else raw.content
    else -> ""
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
                postingRight = it.hasPostingRight(),
            )
        }
    }
    // `tier` falls back to the level's position, as the Settings page reads it.
    val chain = obj.arrayField("run_authorization").mapIndexedNotNull { index, tier ->
        val level = tier as? JsonObject ?: return@mapIndexedNotNull null
        RunAuthLevel(
            tier = level.number("tier")?.toInt() ?: (index + 1),
            userIds = level.arrayField("user")
                .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) },
        )
    }
    return InvoiceSettings(
        canOverride = me?.flag("can_override"),
        isSenior = me?.flag("is_senior"),
        teamMembers = members,
        runApprovers = chain.flatMap { it.userIds }.toSet(),
        runAuthorisation = chain,
        hasMe = me != null,
    )
}

/** `posting_limit`: null or `"unlimited"` is unlimited, a positive number a cap; absent is nothing. */
private fun JsonObject.hasPostingRight(): Boolean {
    val limit = this["posting_limit"] ?: return false
    if (limit is JsonNull) return true
    val text = (limit as? JsonPrimitive)?.content?.trim().orEmpty()
    return text.equals("unlimited", ignoreCase = true) || (text.toDoubleOrNull() ?: 0.0) > 0.0
}

internal fun parseTierConfigs(data: JsonElement?): List<ApprovalTierConfig> =
    legacyTierConfig(data)?.let(::listOf) ?: rowsOf(data).map { row ->
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
                            // As stored: a rule with no type is neither default nor amount on the web.
                            type = it.text("type"),
                            amountThreshold = it.number("amount_threshold", "threshold"),
                            userIds = it.arrayField("user_ids", "users").mapNotNull(::userIdOf),
                        )
                    }
                },
            )
        },
    )
}

/**
 * The legacy shape the web still accepts (`resolveConfigForDepartment`,
 * `approval-helpers.js:132-138`): `{ "1": [{ user_id, … }], "2": […] }`, one
 * chain for everybody, each numbered tier a default rule.
 */
private fun legacyTierConfig(data: JsonElement?): ApprovalTierConfig? {
    val obj = data as? JsonObject ?: return null
    if (obj.isEmpty() || !obj.keys.all { key -> key.isNotEmpty() && key.all(Char::isDigit) }) return null
    return ApprovalTierConfig(
        scope = TierScope.All,
        tiers = obj.entries.sortedBy { it.key.toInt() }.map { (key, users) ->
            TierLevel(
                order = key.toInt(),
                rules = listOf(
                    TierRule(
                        type = TierRule.DEFAULT,
                        userIds = ((users as? JsonArray) ?: JsonArray(emptyList())).mapNotNull(::userIdOf),
                    ),
                ),
            )
        },
    )
}

/** An id as the server stored it — the web keeps even a blank one (`r.user_ids || []`). */
private fun userIdOf(e: JsonElement): String? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> e.content
    is JsonObject -> e.text("user_id", "id", "_id")
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
        country = countryOf(row["address"]).ifBlank { row.text("country") },
        taxNumber = row.text("vat_number", "tax_number", "tax_id"),
        type = row.text("vendor_type", "type", "supplier_type"),
        bankName = row.text("bank_name", "bankName"),
        defaultNominalCode = row.text("default_nominal_code", "nominal_code", "default_code"),
        currency = row.text("currency"),
        bankId = row.text("bank_id", "bankId"),
        contactPerson = row.text("contact_person", "contactPerson"),
        city = (row["address"] as? JsonObject)?.text("city").orEmpty(),
    )
}

/** The country out of an address object, which is where this service keeps it. */
internal fun countryOf(e: JsonElement?): String = (e as? JsonObject)?.text("country").orEmpty()

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

internal fun JsonObject.firstOf(vararg names: String): JsonElement? =
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
