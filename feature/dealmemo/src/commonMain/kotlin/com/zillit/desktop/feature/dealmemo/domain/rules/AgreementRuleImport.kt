package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleLines
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/**
 * "Import union rules" (`agreementRuleImport.js`): an agreement's overtime,
 * premium and turnaround rows as non-union rules — each re-classified onto a
 * rule template with its one canonical trigger, multiplicative, fresh ids.
 * Turnarounds and the overtime block's penalties land as penalties.
 */
object AgreementRuleImport {

    private val IGNORED_TRIGGER_KEYS = setOf("bdr_min", "bdr_max")

    fun project(agreement: JsonObject?, newId: (RuleList) -> String): RuleLists {
        if (agreement == null) return RuleLists(emptyMap())
        val overtimeRows = rows(agreement["overtimes"])
        return RuleLists(
            linkedMapOf(
                RuleList.Overtimes to overtimeRows.filterNot(RuleLines::isPenaltyRow).map {
                    row(it, RuleList.Overtimes, newId)
                },
                RuleList.Premiums to rows(agreement["premiums"]).map { row(it, RuleList.Premiums, newId) },
                RuleList.Penalties to overtimeRows.filter(RuleLines::isPenaltyRow)
                    .map { row(it, RuleList.Penalties, newId) } +
                    rows(agreement["turnaround"]).map { row(it, RuleList.Penalties, newId) },
            ),
        )
    }

    fun count(lists: RuleLists): Int =
        listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Penalties).sumOf { lists.lists[it].orEmpty().size }

    private fun row(source: JsonObject, list: RuleList, newId: (RuleList) -> String): JsonObject {
        val (rateType, rateAmount) = RuleLines.normalizeRate(source)
        val capped = Js.text(source["cap_type"] ?: JsonNull) == "capped" && !Js.isNullish(source["cap_amount"])
        val label = (source["label"]?.takeUnless(Js::isNullish) ?: source["raw_label"]?.takeUnless(Js::isNullish))
            ?.let(Js::text)?.trim().orEmpty()
        return buildJsonObject {
            put("id", newId(list))
            put("label", label.ifEmpty { "Untitled rule" })
            put("rate_type", rateType)
            put("rate_amount", PayloadParts.number(rateAmount ?: 0.0))
            put(
                "basis",
                source["basis"]?.takeIf(Js::truthy)?.let(Js::text) ?: if (list == RuleList.Premiums) "day" else "hour",
            )
            put("triggers", buildJsonArray { add(classify(source, list)) })
            put("is_enhancement", false)
            put("cap_type", if (capped) "capped" else "uncapped")
            put("cap_amount", if (capped) PayloadParts.number(Js.toNumber(source["cap_amount"])) else JsonNull)
            source["nominal_code"]?.takeIf(Js::truthy)?.let { put("nominal_code", Js.text(it)) }
            source["note"]?.takeIf(Js::truthy)?.let { put("note", Js.text(it)) }
        }
    }

    /**
     * `classifyAgreementRow`'s trigger: the first trigger's meaningful keys
     * decide it — meal curtailed, meal, broken turnaround, camera OT, 7th / 6th
     * day, day kind, night work, early call, OT — else the list's default. The
     * template the grid shows is matched from this trigger later.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per template, in the web's order.
    fun classify(source: JsonObject, list: RuleList): JsonObject {
        val first = ((source["triggers"] as? JsonArray)?.firstOrNull() as? JsonObject).orEmpty()
        val t = first.filter { (key, value) -> key !in IGNORED_TRIGGER_KEYS && meaningful(value) }
        val after = t["after"] != null
        val before = t["before"] != null
        return when {
            isTrue(t["meal_curtailed"]) -> trigger("meal_curtailed" to JsonPrimitive(true))
            isTrue(t["meal"]) -> trigger("meal" to JsonPrimitive(true), "after" to minutes(t["after"]))
            t["less"] != null && !after && !isTrue(t["clock"]) && t["day_number"] == null ->
                trigger("less" to minutes(t["less"]))
            isTrue(t["camera"]) && after ->
                trigger(
                    "after" to minutes(t["after"]),
                    "camera" to JsonPrimitive(true),
                    "increment" to JsonPrimitive(CAMERA_INCREMENT),
                )
            isDay(t["day_number"], SEVENTH) -> dayTrigger(SEVENTH, if (after) t["after"] else null)
            isDay(t["day_number"], SIXTH) -> dayTrigger(SIXTH, if (after) t["after"] else null)
            t["day_kind"] != null -> trigger(
                "day_kind" to ((t["day_kind"] as? JsonArray) ?: buildJsonArray { add(t.getValue("day_kind")) }),
            )
            isTrue(t["clock"]) && after -> trigger("clock" to JsonPrimitive(true), "after" to minutes(t["after"]))
            isTrue(t["clock"]) && before -> trigger("clock" to JsonPrimitive(true), "before" to minutes(t["before"]))
            after -> trigger("after" to minutes(t["after"]))
            list == RuleList.Premiums -> dayTrigger(SIXTH, null)
            list == RuleList.Penalties -> trigger("meal" to JsonPrimitive(true), "after" to JsonPrimitive(0))
            else -> trigger("after" to JsonPrimitive(0))
        }
    }

    private fun dayTrigger(day: Int, after: JsonElement?): JsonObject = buildJsonObject {
        put("day_number", day)
        put("consecutive", true)
        after?.let { put("after", minutes(it)) }
    }

    private fun trigger(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    /** `Math.round(Number(v)) || 0` — agreement times are minutes already. */
    private fun minutes(value: JsonElement?): JsonPrimitive =
        JsonPrimitive(Js.toNumber(value)?.takeIf { it.isFinite() }?.roundToLong() ?: 0L)

    private fun rows(block: JsonElement?): List<JsonObject> =
        ((block as? JsonObject)?.get("rows") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** Dropped by `meaningfulTrigger`: null, false and "". */
    private fun meaningful(value: JsonElement): Boolean = when {
        value is JsonNull -> false
        value is JsonPrimitive && !value.isString && value.booleanOrNull == false -> false
        value is JsonPrimitive && value.isString && value.content.isEmpty() -> false
        else -> true
    }

    private fun isTrue(value: JsonElement?) =
        (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    /** `t.day_number === n` — a number, not a numeric string. */
    private fun isDay(value: JsonElement?, day: Int) =
        (value as? JsonPrimitive)
            ?.let { !it.isString && it.booleanOrNull == null && Js.toNumber(it) == day.toDouble() } == true

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private const val SIXTH = 6
    private const val SEVENTH = 7
    private const val CAMERA_INCREMENT = 15
}
