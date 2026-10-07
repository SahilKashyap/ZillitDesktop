package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.feature.accounthub.domain.CardProvider
import com.zillit.desktop.feature.accounthub.domain.SpendCoordinator
import com.zillit.desktop.feature.accounthub.domain.SpendDeductionRule
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendQuickCode
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.domain.SpendTeamMember
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Writing [SpendSettings] back — the web's `denormalize`. Each row starts from the fields it was
 * read with, so a key this modal does not know survives a save.
 */

/** The keys this modal writes for the kind, as they would go on the wire. */
internal fun SpendSettings.wire(): Map<String, JsonElement> = buildMap {
    put("team_members", JsonArray(team.map { it.normalised().toJson() }))
    put("department_coordinators", JsonArray(coordinators.map { it.toJson(kind) }))
    put("approval_override", approvalJson())
    put("deduction_rules", JsonArray(deductionRules.map { it.toJson() }))
    put("quick_codes", JsonArray(quickCodes.map { it.toJson() }))
    when (kind) {
        // The global custodian and BS range are superseded by the providers on the card side.
        SpendKind.Cards -> {
            put("card_providers", JsonArray(providers.filterNot { it.isBlank }.map { it.toJson() }))
        }
        SpendKind.Cash -> {
            put("float_custodian_account", JsonPrimitive(custodianAccount))
            put("bs_code_from", JsonPrimitive(bsCodeFrom))
            put("bs_code_to", JsonPrimitive(bsCodeTo))
        }
    }
}

private fun SpendSettings.approvalJson(): JsonObject = JsonObject(
    approvalExtra + kind.approvalToggles.associate { it.key to JsonPrimitive(approvalOn(it.key)) },
)

private fun SpendTeamMember.toJson(): JsonObject = JsonObject(
    extra + mapOf(
        "user_id" to JsonPrimitive(userId),
        // Explicit null, never absent: null is unlimited and zero is "may post nothing".
        "posting_limit" to (postingLimit?.let(::number) ?: JsonNull),
        "can_override" to JsonPrimitive(canOverride),
        "is_senior" to JsonPrimitive(isSenior),
    ),
)

private fun SpendCoordinator.toJson(kind: SpendKind): JsonObject = JsonObject(
    extra + buildMap {
        put("department_id", JsonPrimitive(departmentId))
        put("user_ids", JsonArray(userIds.map(::JsonPrimitive)))
        put("coding_required", JsonPrimitive(codingRequired))
        if (kind == SpendKind.Cash) put("view_department_floats", JsonPrimitive(viewDepartmentFloats))
    },
)

private fun SpendDeductionRule.toJson(): JsonObject = JsonObject(
    extra + mapOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive(type),
        "title" to JsonPrimitive(title),
        "description" to JsonPrimitive(description),
        "process_type" to JsonPrimitive(processType),
        "threshold_type" to JsonPrimitive(thresholdType),
        "threshold_value" to number(thresholdValue),
        "enable" to JsonPrimitive(enable),
        "trigger_codes" to JsonArray(triggerCodes.map(::JsonPrimitive)),
        "system_default" to JsonPrimitive(systemDefault),
    ),
)

private fun SpendQuickCode.toJson(): JsonObject = JsonObject(
    extra + mapOf(
        "name" to JsonPrimitive(name),
        "nominal_code" to JsonPrimitive(nominalCode),
        "keywords" to JsonArray(keywords.map(::JsonPrimitive)),
        "vat" to number(vat),
    ),
)

/** The web's `sanitizeCardProviders`: trimmed, and an empty optional field is null. */
private fun CardProvider.toJson(): JsonObject = JsonObject(
    extra + mapOf(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(name.trim()),
        "bank_id" to nullable(bankId),
        "company_id" to nullable(companyId),
        "custodian_account" to nullable(custodianAccount),
        "float_min" to nullable(floatMin),
        "float_max" to nullable(floatMax),
    ),
)

private fun nullable(text: String): JsonElement = text.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull

/** A whole number goes as `500`, not `500.0`, as the web's `parseFloat` sends it. */
private fun number(value: Double): JsonPrimitive =
    if (value == value.toLong().toDouble()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
