package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `applyPayloadToForm` and the "Use a setup" strip (`DMTemplateBuilderPage.jsx`
 * B:1348-1470): a saved deal or setup payload becomes a whole form.
 */
object DealHydration {

    /** The rule fields Step 5's Reset restores — banked from the last load or save. */
    val RULE_FIELDS = listOf("rulesCustomized", "ruleOverrides", "ruleRowEdits", "ruleRowRemovals", "ruleCustomRows")

    /** The form a payload hydrates, rate context stamped so the rate card never re-seeds saved rates. */
    fun form(payload: JsonObject, departments: DepartmentCatalogue): DealForm {
        val reloaded = DealPayloadRead.read(payload, departments)
        val merged = DealForm.INITIAL.with(reloaded).with(
            mapOf(
                "allowances" to savedRows(reloaded["allowances"]),
                "rentals" to savedRows(reloaded["rentals"]),
            ),
        )
        return merged.with("rateAutoKey", rateContextKey(merged))
    }

    /** The reloaded rule fields, for Step 5's Reset. */
    fun savedRules(form: DealForm): Map<String, JsonElement> = RULE_FIELDS.associateWith { form[it] ?: JsonNull }

    /** `mergePresets([], saved)`: the saved rows that carry an id. */
    private fun savedRows(value: JsonElement?): JsonArray =
        JsonArray((value as? JsonArray).orEmpty().filter { (it as? JsonObject)?.get("id")?.let(Js::truthy) == true })

    /** `rateContextKey`: the (agreement × designation × band × schedule) tuple the rates were authored for. */
    fun rateContextKey(form: DealForm): String = listOf("union", "designation", "pactBand", "selectedScheduleKey")
        .mapIndexed { index, key ->
            val value = form[key]
            when {
                value == null -> if (index < 2) "undefined" else ""
                value is JsonNull -> if (index < 2) "null" else ""
                else -> Js.text(value)
            }
        }
        .joinToString("|")

    /**
     * The "Use" strip: a setup starts a deal without anyone's person or bank —
     * only the crew member's employment status and crew type survive.
     */
    fun stripForUse(payload: JsonObject): JsonObject {
        val kept = payload.filterKeys { it !in STRIPPED }
        val crew = payload["crew_details"] as? JsonObject
        return JsonObject(
            kept + ("is_external" to JsonPrimitive(false)) + listOfNotNull(
                crew?.let {
                    "crew_details" to buildJsonObject {
                        put("emp_status", crew["emp_status"] ?: JsonNull)
                        put("crew_type", crew["crew_type"] ?: JsonNull)
                    }
                },
            ),
        )
    }

    private val STRIPPED = setOf("user_id", "is_external", "bank", "department_id", "designation_id")
}
