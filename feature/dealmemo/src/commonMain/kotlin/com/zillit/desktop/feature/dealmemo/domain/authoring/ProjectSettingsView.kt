package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Production Setup's document (`GET /account-hub/project-settings` → `data.settings`)
 * read the way `useProjectSettings` and its per-section selector hooks read it.
 *
 * Deployed servers nest the Deal Memo Setup sections under `production`; older
 * ones keep them at the top level. Every section is read through [slice], both
 * shapes — guessing one reads a configured project as empty.
 */
data class ProjectSettingsView(val json: JsonObject? = null) {

    /** `productionSlice(settings, key)`: `settings.production[key] ?? settings[key]`. */
    fun slice(key: String): JsonElement? =
        (json?.get("production") as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
            ?: json?.get(key)?.takeUnless { it is JsonNull }

    /** The production entities — top level only, as `useProjectCompanies` reads them. */
    val companies: List<JsonObject> get() = objects(json?.get("companies"))

    val allowances: List<JsonObject> get() = objects((slice(ALLOWANCES_RENTALS) as? JsonObject)?.get("allowances"))

    val rentals: List<JsonObject> get() = objects((slice(ALLOWANCES_RENTALS) as? JsonObject)?.get("rentals"))

    /** Sorted by `order`, the curated sequence; a missing order reads as 0. */
    val standardConditions: List<JsonObject>
        get() = objects(slice(STANDARD_DEAL_CONDITIONS)).sortedBy { Js.toNumber(it["order"]) ?: 0.0 }

    /** `{currencies: [...]}`, the legacy `{codes: [...]}`, or a bare list of codes or rows. */
    val currencies: List<JsonObject>
        get() = when (val value = json?.get("project_currencies")) {
            is JsonObject -> when {
                value["currencies"] is JsonArray -> objects(value["currencies"])
                value["codes"] is JsonArray -> (value["codes"] as JsonArray).map { code ->
                    JsonObject(mapOf("code" to code))
                }
                else -> emptyList()
            }
            is JsonArray -> value.mapNotNull { row ->
                when {
                    row is JsonPrimitive && row.isString -> JsonObject(mapOf("code" to row))
                    else -> row as? JsonObject
                }
            }
            else -> emptyList()
        }

    /** The project's default currency — `project_currencies.default || null`. */
    val defaultCurrency: String?
        get() = (json?.get("project_currencies") as? JsonObject)?.get("default")?.takeIf(Js::truthy)?.let(Js::text)

    /** The three payroll toggles over their seed (`auto_sync`, `notify_payroll` on; `include_pdf` off). */
    val payrollDefaults: JsonObject
        get() {
            val seed = mapOf(
                "auto_sync" to JsonPrimitive(true),
                "notify_payroll" to JsonPrimitive(true),
                "include_pdf" to JsonPrimitive(false),
            )
            val stored = json?.get("payroll_defaults") as? JsonObject
            return JsonObject(seed + stored.orEmpty())
        }

    val dayTypes: List<JsonObject> get() = objects(slice(DAY_TYPES))

    /** `{overtimes, premiums, penalties, apply_mode, department_ids}` over the empty breakdown. */
    val nonUnionPaybreakdown: JsonObject
        get() {
            val empty = mapOf(
                "overtimes" to JsonArray(emptyList()),
                "premiums" to JsonArray(emptyList()),
                "penalties" to JsonArray(emptyList()),
                "apply_mode" to JsonNull,
                "department_ids" to JsonArray(emptyList()),
            )
            return JsonObject(empty + (slice(NON_UNION_PAYBREAKDOWN) as? JsonObject).orEmpty())
        }

    /** Whether any of the project's non-union overtime, premium or penalty lists has a rule. */
    val hasNonUnionRules: Boolean
        get() = listOf("overtimes", "premiums", "penalties").any { key ->
            (nonUnionPaybreakdown[key] as? JsonArray)?.isNotEmpty() == true
        }

    val payrollBureaus: List<JsonObject> get() = objects(slice(PAYROLL_BUREAU))

    /** A list, or the `{rows: [...]}` some servers wrap it in. */
    val agreementDocuments: List<JsonObject>
        get() = when (val value = slice(AGREEMENTS_DOCUMENTS)) {
            is JsonArray -> objects(value)
            is JsonObject -> objects(value["rows"])
            else -> emptyList()
        }

    val productionSchedule: JsonObject? get() = slice(PRODUCTION_SCHEDULE) as? JsonObject

    /** `exrForCode`: a positive exchange rate for [code] among the project's currencies, else null. */
    fun exchangeRate(code: String?): Double? {
        val want = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val row = currencies.firstOrNull { row ->
            row["code"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty().trim().uppercase() == want
        }
        return row?.get("exr")?.let(Js::toNumber)?.takeIf { it > 0 }
    }

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    companion object {
        const val ALLOWANCES_RENTALS = "allowances_rentals"
        const val STANDARD_DEAL_CONDITIONS = "standard_deal_conditions"
        const val PAYROLL_BUREAU = "payroll_bureau"
        const val PRODUCTION_SCHEDULE = "production_schedule"
        const val AGREEMENTS_DOCUMENTS = "agreements_documents"
        const val NON_UNION_PAYBREAKDOWN = "non_union_paybreakdown"
        const val DAY_TYPES = "day_types"
    }
}
