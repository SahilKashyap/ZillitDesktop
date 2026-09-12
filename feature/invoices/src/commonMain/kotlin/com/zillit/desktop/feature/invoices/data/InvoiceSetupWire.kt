package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.InvoiceSetup
import com.zillit.desktop.feature.invoices.domain.InvoiceSetupBundle
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.LinkedPoDetail
import com.zillit.desktop.feature.invoices.domain.PoLine
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The Settings page's shapes: the module's own `/settings` document, and the
 * hub's assignment rules filed under module `invoices`.
 *
 * Three of the document's fields — `alerts`, `team_members`, `run_authorization`
 * — arrive either as arrays or as a JSON string holding one (an unparsed jsonb
 * column), which is why each is read through [arrayOrEncoded] rather than cast.
 */
internal fun parseSetup(data: JsonElement?): InvoiceSetupBundle {
    val obj = data as? JsonObject ?: return InvoiceSetupBundle()
    val setup = InvoiceSetup(
        teamMembers = obj.arrayOrEncoded("team_members").mapNotNull { row ->
            (row as? JsonObject)?.let {
                val id = it.text("user_id", "id")
                if (id.isBlank()) return@let null
                val senior = it.flag("is_senior") ?: false
                InvoiceTeamRow(
                    userId = id,
                    postingLimit = it.postingLimit(senior),
                    runAccess = it.flag("run_access") ?: false,
                    overrideAccess = it.flag("override_access") ?: false,
                    isSenior = senior,
                )
            }
        },
        alerts = obj.arrayOrEncoded("alerts")
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            .toSet(),
        runAuthorisation = obj.arrayOrEncoded("run_authorization").mapIndexedNotNull { index, level ->
            val tier = level as? JsonObject ?: return@mapIndexedNotNull null
            RunAuthLevel(
                tier = tier.number("tier")?.toInt() ?: (index + 1),
                userIds = tier.arrayOrEncoded("user")
                    .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) },
            )
        },
    )
    return InvoiceSetupBundle(setup, obj.arrayField("assignment_rules").mapNotNull(::parseRule))
}

/** `unlimited`, a blank, an absent key and a senior all mean no ceiling. */
private fun JsonObject.postingLimit(senior: Boolean): Double? {
    if (senior) return null
    val text = (this["posting_limit"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    return if (text.isBlank() || text.equals("unlimited", ignoreCase = true)) null else text.toDoubleOrNull()
}

internal fun parseRule(row: JsonElement?): InvoiceAssignmentRule? {
    val obj = row as? JsonObject ?: return null
    val id = obj.text("id", "_id").takeIf { it.isNotBlank() } ?: return null
    return InvoiceAssignmentRule(
        id = id,
        departments = obj.arrayOrEncoded("departments").mapNotNull { (it as? JsonPrimitive)?.content },
        vendors = obj.arrayOrEncoded("vendors").mapNotNull { (it as? JsonPrimitive)?.content },
        nominalCodes = obj.arrayOrEncoded("nominal_codes").mapNotNull { (it as? JsonPrimitive)?.content },
        amountMin = (obj["amount_min"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }.orEmpty(),
        assignTo = obj.text("target_user_id"),
        isActive = obj.flag("is_active") != false,
        priority = obj.number("priority")?.toInt() ?: 0,
        persisted = true,
    )
}

/** The web's `computeLeafRows`: with typed rows, categories and sub-categories are the postable leaves. */
internal fun parseNominals(data: JsonElement?): List<InvoiceNominal> {
    val rows = rowsOf(data).filter { it.flag("is_active") != false && it.text("code").isNotBlank() }
    val typed = rows.any { it.text("line_type").isNotBlank() }
    val leaves = if (typed) rows.filter { it.text("line_type") in LEAF_TYPES } else rows
    return leaves.map { InvoiceNominal(code = it.text("code"), name = it.text("name")) }
}

private val LEAF_TYPES = setOf("category", "sub_category")

// -- writes ------------------------------------------------------------------

internal fun teamBody(rows: List<InvoiceTeamRow>): JsonObject = buildJsonObject {
    put(
        "team_members",
        buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonObject {
                        put("user_id", JsonPrimitive(row.userId))
                        // Null, not the string "unlimited": the web sends null
                        // for a senior and for an unlimited member alike.
                        put("posting_limit", row.postingLimit?.let(::JsonPrimitive) ?: JsonNull)
                        put("run_access", JsonPrimitive(row.isSenior || row.runAccess))
                        put("override_access", JsonPrimitive(row.isSenior || row.overrideAccess))
                        put("is_senior", JsonPrimitive(row.isSenior))
                    },
                )
            }
        },
    )
}

internal fun alertsBody(alerts: Set<String>): JsonObject = buildJsonObject {
    put("alerts", buildJsonArray { alerts.forEach { add(JsonPrimitive(it)) } })
}

internal fun runAuthBody(levels: List<RunAuthLevel>): JsonObject = buildJsonObject {
    put(
        "run_authorization",
        buildJsonArray {
            levels.forEach { level ->
                add(
                    buildJsonObject {
                        put("tier", JsonPrimitive(level.tier))
                        put("user", buildJsonArray { level.userIds.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        },
    )
}

/** The web's `mapRuleToApi`. */
internal fun ruleBody(rule: InvoiceAssignmentRule, module: String? = null): JsonObject = buildJsonObject {
    put("departments", buildJsonArray { rule.departments.forEach { add(JsonPrimitive(it)) } })
    put("vendors", buildJsonArray { rule.vendors.forEach { add(JsonPrimitive(it)) } })
    put("nominal_codes", buildJsonArray { rule.nominalCodes.forEach { add(JsonPrimitive(it)) } })
    put("amount_min", rule.amountMinValue?.let(::JsonPrimitive) ?: JsonNull)
    put("target_user_id", JsonPrimitive(rule.assignTo))
    put("is_active", JsonPrimitive(rule.isActive))
    put("priority", JsonPrimitive(rule.priority))
    module?.let { put("module", JsonPrimitive(it)) }
}

/**
 * A list field that may arrive as an array or as a string holding one.
 *
 * The hub stores these in jsonb columns some drivers hand back unparsed; the
 * web guards every read with the same `try { JSON.parse } catch { [] }`.
 */
private fun JsonObject.arrayOrEncoded(key: String): List<JsonElement> {
    val raw = this[key] ?: return emptyList()
    (raw as? JsonArray)?.let { return it }
    val text = (raw as? JsonPrimitive)?.content?.trim().orEmpty()
    if (!text.startsWith("[")) return emptyList()
    return runCatching { invoicesJson.parseToJsonElement(text) as? JsonArray }.getOrNull().orEmpty().toList()
}

// -- PO matching -------------------------------------------------------------

/** The two lists `po-suggestions` answers; either may be absent. */
internal fun parsePoSuggestions(data: JsonElement?): PoSuggestions {
    val obj = data as? JsonObject ?: return PoSuggestions()
    return PoSuggestions(
        vendorPos = obj.arrayField("vendor_pos").mapNotNull(::parsePoSuggestion),
        userPos = obj.arrayField("user_pos").mapNotNull(::parsePoSuggestion),
    )
}

private fun parsePoSuggestion(row: JsonElement?): PoSuggestion? {
    val obj = row as? JsonObject ?: return null
    val id = obj.text("po_id", "id", "_id").takeIf { it.isNotBlank() } ?: return null
    return PoSuggestion(
        poId = id,
        poNumber = obj.text("po_number", "number"),
        reference = obj.text("po_reference", "reference"),
        vendorName = obj.text("vendor_name", "supplier_name"),
        grossAmount = obj.number("gross_amount", "total", "amount"),
        currency = obj.text("currency"),
        score = obj.number("score", "match_score"),
        confidence = obj.text("confidence", "match_confidence"),
    )
}

/** The web's match payload — the order, its reference, and the server's own judgement of the fit. */
internal fun matchBody(suggestion: PoSuggestion): JsonObject = buildJsonObject {
    put("po_id", JsonPrimitive(suggestion.poId))
    if (suggestion.reference.isNotBlank()) put("po_reference", JsonPrimitive(suggestion.reference))
    suggestion.score?.let { put("match_score", JsonPrimitive(it)) }
    if (suggestion.confidence.isNotBlank()) put("match_confidence", JsonPrimitive(suggestion.confidence))
}

/** `GET /:id/linked-pos` — the orders themselves, not the stubs the invoice carries. */
internal fun parseLinkedPos(data: JsonElement?): List<LinkedPoDetail> = rowsOf(data).mapNotNull { row ->
    val id = row.text("po_id", "id", "_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    LinkedPoDetail(
        poId = id,
        poNumber = row.text("po_number", "number", "reference"),
        vendorName = row.text("vendor_name", "supplier_name"),
        description = row.text("description", "title"),
        status = row.text("status"),
        currency = row.text("currency"),
        grossTotal = row.number("gross_total", "gross_amount", "total"),
        netTotal = row.number("net_total", "net_amount"),
        raisedBy = row.text("user_id", "created_by", "raised_by"),
        raisedAtMs = row.number("created_at", "createdAt")?.toLong(),
        lines = row.arrayField("line_items", "lines").mapNotNull { line ->
            (line as? JsonObject)?.let {
                PoLine(
                    description = it.text("description", "item", "name"),
                    quantity = it.number("quantity", "qty"),
                    unitPrice = it.number("unit_price", "rate", "price"),
                    total = it.number("total", "line_total", "amount"),
                )
            }
        },
    )
}
