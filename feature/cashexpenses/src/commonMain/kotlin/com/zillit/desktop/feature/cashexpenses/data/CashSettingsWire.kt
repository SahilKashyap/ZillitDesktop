package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * One Settings section's PATCH body — only its own keys, as the web's
 * `saveCustodian` / `saveCoordinators` / `saveApproval` / `saveReimbursement`
 * / `saveDeduction` / `saveQuickCodes` send them (`PCSettingsPage.jsx:359-388`).
 */
internal fun CashSettings.sectionBody(section: CashSettingsSection): JsonObject = buildJsonObject {
    when (section) {
        CashSettingsSection.Custodian -> {
            // `value || null` on the web: a cleared code is no code, not "".
            put("float_custodian_account", custodianAccount.orNullJson())
            put("bs_code_from", bsCodeFrom.orNullJson())
            put("bs_code_to", bsCodeTo.orNullJson())
        }
        CashSettingsSection.Coordinators -> put(
            "department_coordinators",
            buildJsonArray { departmentCoordinators.forEach { add(CoordinatorDto.of(it)) } },
        )
        // A nested object, which is how the column is stored; flattened, the
        // four flags silently do nothing.
        CashSettingsSection.Approval -> put(
            "approval_override",
            buildJsonObject {
                put("override_float_req", JsonPrimitive(overrideFloatRequest))
                put("override_receipt_batch", JsonPrimitive(overrideReceiptBatch))
                put("require_coord_code", JsonPrimitive(requireCoordinatorCoding))
                put("require_senior_sign_off", JsonPrimitive(requireSeniorSignOff))
            },
        )
        CashSettingsSection.Reimbursement -> put("reimburse_to_payroll", JsonPrimitive(reimburseToPayroll))
        CashSettingsSection.Deduction -> put(
            "deduction_rules",
            buildJsonArray { deductionRules.forEach { add(DeductionRuleDto.of(it)) } },
        )
        CashSettingsSection.QuickCodes -> put(
            "quick_codes",
            buildJsonArray { quickCodes.forEach { add(QuickCodeDto.of(it)) } },
        )
    }
}

private fun String.orNullJson() = trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
