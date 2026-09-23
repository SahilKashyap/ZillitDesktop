package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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

    private val BASES: Map<String, String>
        get() = mapOf(
            "hour" to str(S.desktop_unit_hr), "day" to str(S.day_label),
            "week" to str(S.dm_ds_weeks_label), "event" to str(S.desktop_unit_event),
        )

    /** `formatBasis`: `hr`, `day`, `wk`, `event`, or the basis as stored. */
    fun basis(basis: String): String = BASES[basis] ?: basis

    /** `summarizeEntry`: the trigger in words, `Always on` for none. */
    @Suppress("CyclomaticComplexMethod") // One clause per trigger key, in the web's order.
    fun entry(trigger: JsonObject?): String {
        if (trigger == null || trigger.isEmpty()) return str(S.desktop_always_on)
        val t = trigger
        val parts = mutableListOf<String>()
        t["day_type"]?.takeIf(Js::truthy)?.let { parts += str(S.desktop_dm_sum_day_type, Js.text(it)) }
        if ("day_number" in t) {
            val consecutive = t["consecutive"]
            val day = Js.text(t["day_number"])
            parts += if (consecutive == null || isFalse(consecutive)) {
                str(S.desktop_dm_sum_day_n, day)
            } else {
                str(S.desktop_dm_sum_nth_consecutive_day, day)
            }
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
                if (after) parts += str(S.desktop_dm_sum_week_total_over, hours(t["after"]))
                if (before) parts += str(S.desktop_dm_sum_week_total_under, hours(t["before"]))
            }
            isTrue(t["clock"]) && (after || before) -> {
                if (after) parts += str(S.desktop_dm_after_x, clock(t["after"]))
                if (before) parts += str(S.desktop_dm_before_x, clock(t["before"]))
            }
            isTrue(t["meal"]) -> parts += str(S.desktop_dm_sum_meal_not_provided, hours(t["after"]))
            isTrue(t["meal_curtailed"]) -> parts += str(S.desktop_meal_break_curtailed)
            after && before -> parts += str(S.desktop_dm_sum_worked_range, hours(t["after"]), hours(t["before"]))
            after -> parts += str(S.desktop_dm_sum_after_hours_worked, hours(t["after"]))
            before -> parts += str(S.desktop_dm_sum_before_hours_worked, hours(t["before"]))
        }
        if ("less" in t && "meal" !in t) parts += str(S.desktop_dm_sum_rest_less_hrs, hours(t["less"]))
        if ("more" in t) parts += "> ${Js.text(t["more"])}"
        if (isTrue(t["camera"])) parts += str(S.desktop_dm_sum_camera_band)
        if (Js.truthy(t["on_call"])) parts += str(S.desktop_dm_sum_on_call)
        if (Js.truthy(t["shoot_day"])) parts += str(S.cs_shoot_day)
        return parts.joinToString(" · ").ifEmpty { str(S.desktop_dm_sum_custom, t.keys.joinToString(", ")) }
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
