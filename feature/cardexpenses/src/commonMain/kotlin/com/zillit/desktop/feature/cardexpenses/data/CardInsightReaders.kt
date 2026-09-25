package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.feature.cardexpenses.domain.AlertTxn
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Tolerant readers for the two lists the insight pages read off loose JSON:
 * an alert's `relatedTxns` and the settings document's `assignment_rules`.
 * Either may arrive as an array or as a string holding one — the hub keeps
 * both in jsonb columns some drivers hand back unparsed.
 */
internal fun JsonElement?.readAlertTxns(): List<AlertTxn> =
    listIn(this).mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        AlertTxn(
            merchant = row.text("merchant"),
            ref = row.text("ref"),
            holder = row.text("holder"),
            amount = row.text("amount").toAmountOrNull(),
            currency = row.text("currency"),
        )
    }

/** The web's `mapRuleFromDb` (`SettingsPage.jsx:94-108`). */
internal fun JsonElement?.readAssignmentRules(): List<CardAssignmentRule> =
    listIn(this).mapNotNull { item -> (item as? JsonObject)?.toAssignmentRule() }

internal fun JsonObject.toAssignmentRule(): CardAssignmentRule? {
    val id = text("id") ?: text("_id") ?: return null
    return CardAssignmentRule(
        id = id,
        departments = listIn(this["departments"]).mapNotNull { (it as? JsonPrimitive)?.content },
        nominalCodes = listIn(this["nominal_codes"]).mapNotNull { (it as? JsonPrimitive)?.content },
        amountMin = text("amount_min")?.toAmountOrNull()?.let(::plainAmount).orEmpty(),
        assignTo = text("target_user_id").orEmpty(),
        isActive = (this["is_active"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "true") } != false,
        priority = text("priority")?.toDoubleOrNull()?.toInt() ?: 0,
        persisted = true,
    )
}

private fun plainAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private val loose = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun listIn(element: JsonElement?): List<JsonElement> = when (element) {
    is JsonArray -> element
    is JsonPrimitive -> if (element.isString) {
        runCatching { loose.parseToJsonElement(element.content) as? JsonArray }.getOrNull().orEmpty()
    } else {
        emptyList()
    }

    else -> emptyList()
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
