package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cardexpenses.domain.CardApproval
import com.zillit.desktop.feature.cardexpenses.domain.FixedLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessingFlag
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineDraft
import com.zillit.desktop.feature.cardexpenses.domain.TierConfig
import com.zillit.desktop.feature.cardexpenses.domain.TierRule
import com.zillit.desktop.feature.cardexpenses.domain.round2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The card service's looser JSON fields, read the way the web reads them.
 *
 * Every one of these arrives in more than one shape — an array or a JSON
 * *string* holding one, a boolean or `1`/`"true"`, an object or a legacy bare
 * number — depending on the row's age and which route served it. Each reader
 * takes whatever came and returns something sensible rather than failing the
 * decode, because a strict field here fails the **whole** response: that is
 * how one `request_cap` object blanked every card setting.
 */

private val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** An element as itself, or the element a JSON string holds. */
private fun JsonElement?.unwrapped(): JsonElement? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive && isString ->
        content.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { wireJson.parseToJsonElement(raw) }.getOrNull()
        }

    else -> this
}

private fun JsonElement?.objects(): List<JsonObject> =
    (unwrapped() as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.number(key: String): Double? = text(key).toAmountOrNull()

/** `true`, `1`, `"true"`, `"1"` — the four ways this service says yes. */
internal fun JsonElement?.truthy(): Boolean {
    val primitive = unwrappedPrimitive() ?: return false
    primitive.booleanOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it != 0.0 }
    return primitive.content.trim().lowercase() in setOf("true", "yes")
}

private fun JsonElement?.unwrappedPrimitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** A card's or receipt's sign-offs: `[{user_id, tier_number, approved_at}]`, maybe as a string. */
internal fun JsonElement?.readApprovals(): List<CardApproval> =
    objects().mapNotNull { row ->
        val user = row.text("user_id") ?: return@mapNotNull null
        val tier = row.number("tier_number")?.toInt() ?: return@mapNotNull null
        CardApproval(userId = user, tierNumber = tier, approvedAt = row.text("approved_at").toEpochMillisOrNull())
    }

/**
 * The processing rules a receipt tripped, as flag names.
 *
 * Legacy rows hold `["query"]`; current ones `[{flag, title, description}]`.
 */
internal fun JsonElement?.readFlags(): Set<String> =
    (unwrapped() as? JsonArray)?.mapNotNull { item ->
        when (item) {
            is JsonObject -> item.text("flag")
            is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() && item !is JsonNull }
            else -> null
        }
    }.orEmpty().toSet()

/**
 * The rules a receipt tripped, whole: `{flag, title, description,
 * threshold_value, threshold_type}` — or a legacy bare name, which reads as a
 * rule with nothing but its flag.
 */
internal fun JsonElement?.readFlagRules(): List<ProcessingFlag> =
    (unwrapped() as? JsonArray)?.mapNotNull { item ->
        when (item) {
            is JsonObject -> item.text("flag")?.let { flag ->
                ProcessingFlag(
                    flag = flag,
                    title = item.text("title"),
                    description = item.text("description"),
                    thresholdValue = item.number("threshold_value"),
                    thresholdType = item.text("threshold_type"),
                )
            }

            is JsonPrimitive -> item.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ProcessingFlag(flag = it) }

            else -> null
        }
    }.orEmpty()

/** What a receipt's `line_items` holds: the coded lines, the auto-deductions, and the saved tax row. */
internal data class LineItemsRead(
    val coded: List<ProcessLine>,
    val fixed: List<FixedLine>,
    val taxLine: TaxLineDraft?,
)

/**
 * A receipt's lines, split into the ones the editor codes, the ones the
 * server owns, and the reclaimable-tax row.
 *
 * The wire's `amount` is **gross**; the editor's net is backed out of it at the
 * line's rate rather than read off `unit_price`, which legacy and bulk rows
 * wrote tax-inclusive — the web's `toEditorLine` makes the same choice. The
 * saved `is_tax` row is not a line: it hydrates the tax row as an override,
 * so what was saved is what reopens (`ProcessReceiptModal.jsx:230-244`).
 */
internal fun JsonElement?.readLineItems(): LineItemsRead {
    val coded = mutableListOf<ProcessLine>()
    val fixed = mutableListOf<FixedLine>()
    var tax: TaxLineDraft? = null
    objects().forEach { row ->
        val gross = row.number("amount") ?: 0.0
        when {
            row.isAuto() -> fixed += FixedLine(row, gross, row.number("tax_amount") ?: 0.0, countsInTotal = true)
            row.isTax() -> if (tax == null) {
                tax = TaxLineDraft(
                    account = row.text("account").orEmpty(),
                    amount = row.number("amount") ?: row.number("unit_price") ?: 0.0,
                    overridden = true,
                    trackingCodes = row["tracking_codes"].readCodes(),
                    tags = row["tags"].readTags(),
                )
            }

            else -> coded += row.toProcessLine(gross)
        }
    }
    return LineItemsRead(coded, fixed, tax)
}

private fun JsonObject.isAuto(): Boolean {
    val meta = this["meta"].unwrapped() as? JsonObject ?: return false
    return meta["auto"].truthy()
}

private fun JsonObject.isTax(): Boolean = this["is_tax"].truthy() || this["isTax"].truthy()

private fun JsonObject.toProcessLine(gross: Double): ProcessLine {
    val rate = number("tax_rate")?.takeIf { it != 0.0 }
    val net = if (rate != null) gross / (1 + rate / PERCENT) else gross
    val quantity = number("quantity")?.takeIf { it > 0 } ?: 1.0
    return ProcessLine(
        id = text("id"),
        description = text("description").orEmpty(),
        account = text("account").orEmpty(),
        net = round2(net),
        taxRate = rate,
        quantity = quantity,
        raw = this,
        taxType = text("tax_type").orEmpty(),
        splitParentId = text("split_parent_id") ?: text("splitParentId"),
        trackingCodes = this["tracking_codes"].readCodes(),
        tags = this["tags"].readTags(),
    )
}

/** Layers picks: `{set_id: code}`, as an object or a JSON string holding one. */
private fun JsonElement?.readCodes(): Map<String, String> =
    (unwrapped() as? JsonObject)?.mapNotNull { (set, code) ->
        (code as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { set to it }
    }?.toMap().orEmpty()

/** Tags: an array or a JSON string of one — reading only arrays reset saved tags on every reopen. */
private fun JsonElement?.readTags(): List<String> =
    (unwrapped() as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim()?.takeIf(String::isNotEmpty)
    }.orEmpty()

/** `approval_tier_configs`: rows of `{scope, department_id, tiers}`. */
internal fun JsonElement?.readTierConfigs(): List<TierConfig> =
    objects().map { row ->
        TierConfig(
            scope = row.text("scope").orEmpty(),
            departmentId = row.text("department_id"),
            tiers = row["tiers"].objects().mapIndexed { index, tier ->
                ApprovalTier(
                    order = tier.number("order")?.toInt() ?: (index + 1),
                    rules = tier["rules"].objects().map { rule ->
                        TierRule(
                            type = rule.text("type").orEmpty(),
                            amountThreshold = rule.number("amount_threshold"),
                            userIds = (rule["user_ids"].unwrapped() as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                                .orEmpty(),
                        )
                    },
                )
            },
        )
    }

/**
 * The request ceiling: the object the web writes, or a legacy bare figure.
 *
 * `normalizeRequestCap` in `requestCap.js`: anything unreadable is the
 * default — no cap — rather than a failure.
 */
internal fun JsonElement?.readRequestCap(): RequestCap = when (val element = unwrapped()) {
    is JsonObject -> RequestCap(
        enabled = element["enabled"].truthy(),
        basis = RequestCapBasis.from(element.text("basis")),
        maxAmount = element.number("max_amount")?.takeIf { it >= 0 } ?: 0.0,
        salaryMultiplier = element.number("salary_multiplier")?.takeIf { it >= 0 } ?: 1.0,
    )

    is JsonPrimitive -> element.content.toAmountOrNull()
        ?.let { RequestCap(enabled = true, maxAmount = it.coerceAtLeast(0.0)) }
        ?: RequestCap()

    else -> RequestCap()
}

private const val PERCENT = 100.0
