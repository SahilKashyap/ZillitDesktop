package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleLists
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A deal's own pay rules (`Step5Rates.jsx` rule authoring): what the rules
 * grid opens on, how its lists fold back into edits, removals and custom rows
 * against the agreement, and the two settling passes a loaded deal gets.
 * Rows are identified only by occurrence keys (`overtime`, `overtime#1`).
 */
object RuleAuthoring {

    private val LISTS = listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Turnarounds, RuleList.Penalties)
    private val GROUPS = mapOf(
        RuleList.Overtimes to "Overtime",
        RuleList.Premiums to "Premium",
        RuleList.Turnarounds to "Turnaround",
        RuleList.Penalties to "Penalty",
    )
    private val OVERRIDE_NUMBERS = listOf("rate_amount", "multiplier", "min", "max", "cap_amount")
    private val OVERRIDE_DIFFS = listOf("rate_amount", "min", "max", "cap_amount")
    private val UNAUTHORED: Map<String, JsonElement> = mapOf(
        "ruleRowEdits" to JsonObject(emptyMap()),
        "ruleRowRemovals" to JsonArray(emptyList()),
        "ruleCustomRows" to JsonArray(emptyList()),
    )

    /** `ruleEditorData`: the deal's rule lists as they stand, legacy numeric overrides laid in. */
    fun editorLists(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject): RuleLists {
        val lines = project(form, agreement, paybreakdown)
        val overrides = form.obj("ruleOverrides")
        return RuleLists(LISTS.associateWith { list -> lines.of(list).map { withOverrides(it, overrides) } })
    }

    /**
     * `commitRuleRows`: each committed row against the un-authored rules by
     * occurrence key — a changed row becomes an edit, a missing one a
     * removal, an unknown one a custom row — and the switch follows.
     */
    fun committed(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject, committed: RuleLists): DealForm {
        val base = project(form, agreement, paybreakdown, UNAUTHORED)
        val edits = linkedMapOf<String, JsonElement>()
        val customs = mutableListOf<JsonElement>()
        val seen = mutableSetOf<String>()
        val baseKeys = linkedSetOf<String>()
        LISTS.forEach { list ->
            val baseRows = base.of(list)
            val baseRowKeys = RuleLines.rowKeys(baseRows)
            val baseByKey = baseRowKeys.zip(baseRows).toMap()
            baseKeys += baseRowKeys
            val rows = committed.lists[list].orEmpty()
            val keys = RuleLines.rowKeys(rows)
            rows.forEachIndexed { index, row ->
                if (!Js.truthy(row["id"])) return@forEachIndexed
                val key = keys[index]
                val original = baseByKey[key]
                if (original == null) {
                    val id = Js.text(row["id"])
                    val customId = if (id.startsWith(CUSTOM_PREFIX)) {
                        row["id"] ?: JsonNull
                    } else {
                        JsonPrimitive("$CUSTOM_PREFIX$id")
                    }
                    customs += buildJsonObject {
                        put("group", GROUPS.getValue(list))
                        put("row", JsonObject(row + ("id" to customId)))
                    }
                } else {
                    seen += key
                    if (RuleLines.rowDiffers(row, original)) edits[key] = row
                }
            }
        }
        val removals = baseKeys.filterNot { it in seen }
        val overrides = form.obj("ruleOverrides").orEmpty().filterKeys { it !in edits && it !in removals }
        val customised = edits.isNotEmpty() || customs.isNotEmpty() || removals.isNotEmpty() || overrides.isNotEmpty()
        return form.with(
            mapOf(
                "ruleRowEdits" to JsonObject(edits),
                "ruleRowRemovals" to JsonArray(removals.map { JsonPrimitive(it) }),
                "ruleCustomRows" to JsonArray(customs),
                "ruleOverrides" to JsonObject(overrides),
                "rulesCustomized" to JsonPrimitive(customised),
            ),
        )
    }

    /**
     * A freshly loaded deal's switch (`null`) settles once there are rules to
     * compare against: on when an override really differs, or any edit or
     * custom row exists. Null while there is nothing to settle.
     */
    fun materialised(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject): Boolean? {
        if (form["rulesCustomized"] !is JsonNull) return null
        val rows = customisableRows(form, agreement, paybreakdown)
        if (rows.isEmpty()) return null
        val overrides = form.obj("ruleOverrides")
        return rows.any { isCustomised(it, overrides) } ||
            form.list("ruleCustomRows").isNotEmpty() ||
            form.obj("ruleRowEdits").orEmpty().isNotEmpty()
    }

    /**
     * Edits that no longer differ from the rule they replace — a reload hands
     * every row back as an edit — dropped. Null when nothing was dropped.
     */
    fun pruned(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject): JsonObject? {
        val edits = form.obj("ruleRowEdits")?.takeIf { it.isNotEmpty() } ?: return null
        if (customisableRows(form, agreement, paybreakdown).isEmpty()) return null
        val base = project(form, agreement, paybreakdown, UNAUTHORED)
        val baseByKey = mutableMapOf<String, JsonObject>()
        LISTS.forEach { list ->
            val rows = base.of(list)
            RuleLines.rowKeys(rows).forEachIndexed { index, key -> baseByKey[key] = rows[index] }
        }
        val kept = edits.filter { (key, row) ->
            val original = baseByKey[key]
            original == null || RuleLines.rowDiffers(row as? JsonObject, original)
        }
        return if (kept.size < edits.size) JsonObject(kept) else null
    }

    /** Reset is offered once the rule fields moved from what was last loaded or saved. */
    fun changedSinceSave(form: DealForm, saved: Map<String, JsonElement>): Boolean =
        saved.keys.any { key -> (form[key] ?: JsonNull) != (saved[key] ?: JsonNull) }

    /** The rows a customisation is measured on, one per (group, id). */
    private fun customisableRows(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject): List<JsonObject> {
        val base: List<Pair<JsonObject, String>> = if (isNonUnionId(form.text("union"))) {
            objects(paybreakdown["overtimes"]).map { it to "Overtime" } +
                objects(paybreakdown["premiums"]).map { it to "Premium" } +
                objects(paybreakdown["penalties"]).map { it to "Penalty" }
        } else {
            val sections = RateTables.ruleSections(form, agreement, paybreakdown)
            sections.overtime?.rows.orEmpty().map { it to "Overtime" } +
                sections.premiums?.rows.orEmpty().map { it to "Premium" } +
                sections.turnaround?.rows.orEmpty().map { it to "Turnaround" }
        }
        val custom = form.objects("ruleCustomRows").mapNotNull { entry ->
            (entry["row"] as? JsonObject)?.let { it to Js.text(entry["group"]) }
        }
        return (base + custom)
            .filter { (row, _) -> Js.truthy(row["id"]) }
            .distinctBy { (row, group) -> group to Js.text(row["id"]) }
            .map { it.first }
    }

    private fun isCustomised(row: JsonObject, overrides: JsonObject?): Boolean {
        val override = overrides?.get(Js.text(row["id"])) as? JsonObject ?: return false
        return OVERRIDE_DIFFS.any { field ->
            val value = override[field]?.takeUnless(Js::isNullish) ?: return@any false
            val original = if (field == "rate_amount") {
                row["rate_amount"]?.takeUnless(Js::isNullish) ?: row["multiplier"]
            } else {
                row[field]
            }
            val a = Js.toNumber(value)
            val b = Js.toNumber(original)
            a == null || b == null || a != b
        }
    }

    private fun withOverrides(row: JsonObject, overrides: JsonObject?): JsonObject {
        val override = overrides?.get(Js.text(row["id"])) as? JsonObject ?: return row
        val patch = OVERRIDE_NUMBERS.mapNotNull { field ->
            val value = override[field]?.takeUnless(Js::isNullishOrEmpty)
            value?.let { field to PayloadParts.number(Js.toNumber(it)) }
        }
        return if (patch.isEmpty()) row else JsonObject(row + patch)
    }

    /** `projectRuleLines` with the customiser on, and whatever [over] replaces. */
    private fun project(
        form: DealForm,
        agreement: JsonObject?,
        paybreakdown: JsonObject,
        over: Map<String, JsonElement> = emptyMap(),
    ) = RuleLines.project(form.with(mapOf("rulesCustomized" to JsonPrimitive(true)) + over), agreement, paybreakdown)

    private fun RuleLineSet.of(list: RuleList): List<JsonObject> = when (list) {
        RuleList.Overtimes -> overtimes
        RuleList.Premiums -> premiums
        RuleList.Turnarounds -> turnarounds
        RuleList.Penalties -> penalties
    }

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private const val CUSTOM_PREFIX = "custom-"
}
