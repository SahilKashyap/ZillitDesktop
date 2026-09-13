package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.floor

/**
 * The rule book's words for a trigger (`NonUnionPayBreakdownSection.jsx`
 * `summarizeEntry`, `formatBasis`) — what the read-only rules table prints
 * where the grid has its pickers.
 */
object RuleSummary {

    private val BASES = mapOf("hour" to "hr", "day" to "day", "week" to "wk", "event" to "event")

    /** `formatBasis`: `hr`, `day`, `wk`, `event`, or the basis as stored. */
    fun basis(basis: String): String = BASES[basis] ?: basis

    /** `summarizeEntry`: the trigger in words, `Always on` for none. */
    @Suppress("CyclomaticComplexMethod") // One clause per trigger key, in the web's order.
    fun entry(trigger: JsonObject?): String {
        if (trigger == null || trigger.isEmpty()) return "Always on"
        val t = trigger
        val parts = mutableListOf<String>()
        t["day_type"]?.takeIf(Js::truthy)?.let { parts += "day type ${Js.text(it)}" }
        if ("day_number" in t) {
            val consecutive = t["consecutive"]
            val day = Js.text(t["day_number"])
            parts += if (consecutive == null || isFalse(consecutive)) "Day $day" else "${day}th consecutive day"
        }
        when (val kind = t["day_kind"]) {
            is JsonArray -> if (kind.isNotEmpty()) parts += kind.joinToString(" / ") { Js.text(it).replace('_', ' ') }
            is JsonPrimitive -> if (kind.isString && kind.content.isNotEmpty()) parts += kind.content.replace('_', ' ')
            else -> Unit
        }
        val after = "after" in t
        val before = "before" in t
        when {
            isTrue(t["weekly"]) && (after || before) -> {
                if (after) parts += "Week total > ${hours(t["after"])} hrs"
                if (before) parts += "Week total < ${hours(t["before"])} hrs"
            }
            isTrue(t["clock"]) && (after || before) -> {
                if (after) parts += "After ${clock(t["after"])}"
                if (before) parts += "Before ${clock(t["before"])}"
            }
            isTrue(t["meal"]) -> parts += "Meal not provided within ${hours(t["after"])} hrs"
            isTrue(t["meal_curtailed"]) -> parts += "Meal break curtailed"
            after && before -> parts += "Worked ${hours(t["after"])}–${hours(t["before"])} hrs"
            after -> parts += "After ${hours(t["after"])} hrs worked"
            before -> parts += "Before ${hours(t["before"])} hrs worked"
        }
        if ("less" in t && "meal" !in t) parts += "Rest < ${hours(t["less"])} hrs"
        if ("more" in t) parts += "> ${Js.text(t["more"])}"
        if (isTrue(t["camera"])) parts += "(camera band)"
        if (Js.truthy(t["on_call"])) parts += "On call"
        if (Js.truthy(t["shoot_day"])) parts += "Shoot day"
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else "Custom: ${t.keys.joinToString(", ")}"
    }

    /** `fmtHrs`: minutes as hours to one place, a whole number bare. */
    private fun hours(minutes: JsonElement?): String {
        val value = if (Js.truthy(minutes)) Js.toNumber(minutes) else 0.0
        return Js.toFixed(value?.div(MINUTES_PER_HOUR) ?: return "NaN", 1).removeSuffix(".0")
    }

    /** `fmtClock`: minutes after midnight as `HH:MM`, clamped to the day. */
    private fun clock(minutes: JsonElement?): String {
        val value = (if (Js.truthy(minutes)) Js.toNumber(minutes) ?: 0.0 else 0.0).coerceIn(0.0, DAY_MINUTES)
        val hour = floor(value / MINUTES_PER_HOUR).toInt()
        val minute = value % MINUTES_PER_HOUR
        return "${hour.toString().padStart(2, '0')}:${Js.number(minute).padStart(2, '0')}"
    }

    private fun isTrue(value: JsonElement?) =
        (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    private fun isFalse(value: JsonElement?) =
        (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true

    private const val MINUTES_PER_HOUR = 60.0
    private const val DAY_MINUTES = 1440.0
}
