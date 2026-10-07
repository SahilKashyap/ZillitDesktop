package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.feature.accounthub.domain.CardProvider
import com.zillit.desktop.feature.accounthub.domain.LocalIds
import com.zillit.desktop.feature.accounthub.domain.SpendCoordinator
import com.zillit.desktop.feature.accounthub.domain.SpendDeductionRule
import com.zillit.desktop.feature.accounthub.domain.SpendDefaults
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendQuickCode
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.domain.SpendTeamMember
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Reading a Card or Petty Cash settings document into [SpendSettings] — the web's `normalize`.
 * Tolerant of the shapes these services send (a list or a string holding one, a flag or its
 * text), and every row keeps the fields this modal does not edit.
 */

/** The stored document as the modal edits it, with the web's defaults for what is missing. */
internal fun parseSpend(kind: SpendKind, document: JsonObject?): SpendSettings {
    val doc = document ?: JsonObject(emptyMap())
    val overrides = (doc["approval_override"].asObject() ?: doc["approval"].asObject())
    val toggles = kind.approvalToggles.map { it.key }
    val rules = doc["deduction_rules"].asArray()
    val codes = doc["quick_codes"].asArray()
    return SpendSettings(
        kind = kind,
        custodianAccount = when (kind) {
            SpendKind.Cards -> doc.text("card_custodian_account") ?: doc.text("custodian_account")
            SpendKind.Cash -> doc.text("float_custodian_account") ?: doc.text("custodian_account")
        }.orEmpty(),
        bsCodeFrom = doc.text("bs_code_from").orEmpty(),
        bsCodeTo = doc.text("bs_code_to").orEmpty(),
        team = (doc["team_members"].asArray() ?: doc["team_posting_rights"].asArray()).orEmpty()
            .objects().map(::teamMember),
        coordinators = (doc["department_coordinators"].asArray() ?: doc["coordinators"].asArray()).orEmpty()
            .objects().map(::coordinator),
        approval = toggles.associateWith { overrides?.get(it).asBoolean() == true },
        approvalExtra = JsonObject(overrides.orEmpty().filterKeys { it !in toggles }),
        deductionRules = rules?.objects()?.map(::deductionRule)?.takeIf { it.isNotEmpty() }
            ?: SpendDefaults.deductionRules(kind),
        quickCodes = codes?.objects()?.map(::quickCode)?.takeIf { it.isNotEmpty() }
            ?: SpendDefaults.quickCodes(kind),
        providers = if (kind == SpendKind.Cards) {
            doc["card_providers"].asArray().orEmpty().objects().map(::provider)
        } else {
            emptyList()
        },
    )
}

private fun teamMember(row: JsonObject): SpendTeamMember = SpendTeamMember(
    userId = row.text("user_id").orEmpty(),
    // An absent key is zero (submit only), a null is unlimited — the web's `normalize`.
    postingLimit = when {
        !row.containsKey("posting_limit") -> 0.0
        row["posting_limit"] is JsonNull -> null
        else -> row["posting_limit"].asNumber() ?: 0.0
    },
    canOverride = row["can_override"].asBoolean() == true,
    isSenior = row["is_senior"].asBoolean() == true,
    extra = row.without("user_id", "posting_limit", "can_override", "is_senior"),
)

private fun coordinator(row: JsonObject): SpendCoordinator = SpendCoordinator(
    departmentId = row.text("department_id").orEmpty(),
    userIds = row["user_ids"].strings(),
    codingRequired = row["coding_required"].asBoolean() == true,
    viewDepartmentFloats = row["view_department_floats"].asBoolean() == true,
    extra = row.without("department_id", "user_ids", "coding_required", "view_department_floats"),
)

private fun deductionRule(row: JsonObject): SpendDeductionRule = SpendDeductionRule(
    id = row.text("id").orEmpty(),
    type = row.text("type") ?: SpendDeductionRule.CUSTOM,
    title = row.text("title").orEmpty(),
    description = row.text("description").orEmpty(),
    processType = row.text("process_type") ?: SpendDeductionRule.DEDUCT_AMOUNT,
    thresholdType = row.text("threshold_type") ?: SpendDeductionRule.PERCENTAGE,
    thresholdValue = row["threshold_value"].asNumber() ?: 0.0,
    // The flag is `enable`, and an absent one means off.
    enable = row["enable"].asBoolean() == true,
    triggerCodes = row["trigger_codes"].strings(),
    systemDefault = row["system_default"].asBoolean() == true,
    extra = row.without(
        "id", "type", "title", "description", "process_type", "threshold_type", "threshold_value",
        "enable", "trigger_codes", "system_default",
    ),
)

private fun quickCode(row: JsonObject): SpendQuickCode = SpendQuickCode(
    // Older rows spell these `label` and `code`; the web writes `name` and `nominal_code`.
    name = row.text("name") ?: row.text("label").orEmpty(),
    nominalCode = row.text("nominal_code") ?: row.text("code").orEmpty(),
    keywordsText = row["keywords"].strings().joinToString(", "),
    vat = row["vat"].asNumber() ?: 0.0,
    extra = row.without("name", "nominal_code", "keywords", "vat"),
)

private fun provider(row: JsonObject): CardProvider = CardProvider(
    id = row.text("id") ?: LocalIds.next("prov"),
    name = row.text("name").orEmpty(),
    bankId = row.text("bank_id").orEmpty(),
    companyId = row.text("company_id").orEmpty(),
    custodianAccount = row.text("custodian_account").orEmpty(),
    floatMin = row.text("float_min").orEmpty(),
    floatMax = row.text("float_max").orEmpty(),
    extra = row.without("id", "name", "bank_id", "company_id", "custodian_account", "float_min", "float_max"),
)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })

private fun List<JsonElement>.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** An array, or a string holding one — the web's `parseJsonField`. */
private fun JsonElement?.asArray(): JsonArray? = when (this) {
    is JsonArray -> this
    is JsonPrimitive -> parsed() as? JsonArray
    else -> null
}

/** The text of every string in an array, or a string holding one. */
private fun JsonElement?.strings(): List<String> =
    asArray().orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonElement?.asObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> parsed() as? JsonObject
    else -> null
}

private fun JsonPrimitive.parsed(): JsonElement? =
    if (isString) runCatching { Json.parseToJsonElement(content) }.getOrNull() else null

private fun JsonElement?.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.toBooleanStrictOrNull() }

private fun JsonElement?.asNumber(): Double? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
