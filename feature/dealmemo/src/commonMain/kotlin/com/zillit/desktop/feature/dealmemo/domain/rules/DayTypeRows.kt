package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayload
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor

/** One row of the Day Types table, as typed. */
data class DayTypeRow(
    val id: String,
    val code: String,
    val workMin: String,
    val mealBreakMin: String,
    val label: String,
    /** SWD, CWD and SCWD: code fixed, never removed, values retunable. */
    val isDefault: Boolean,
)

/**
 * The project's Day Types (`DayTypesEditor.jsx`): the three defaults first —
 * each replaced by a saved row with its code — then the saved custom codes;
 * validated whole before the bare-array save.
 */
object DayTypeRows {

    const val MAX_ROWS = 100
    const val TOO_MANY = "A project can have at most $MAX_ROWS day types."
    private const val DAY_MINUTES = 1440
    private const val MINUTES_PER_HOUR = 60

    private val DEFAULT_CODES: Set<String> = DealPayload.defaultDayTypes.map { Js.text(it["day_type"]) }.toSet()

    fun rows(saved: List<JsonObject>, newId: () -> String): List<DayTypeRow> {
        val coded = saved.filter { Js.truthy(it["day_type"]) }
        val byCode = coded.associateBy { Js.text(it["day_type"]) }
        val defaults = DealPayload.defaultDayTypes.map {
            row(byCode[Js.text(it["day_type"])] ?: it, isDefault = true, newId)
        }
        return defaults + coded.filterNot { Js.text(it["day_type"]) in DEFAULT_CODES }.map {
            row(it, isDefault = false, newId)
        }
    }

    fun blank(id: String) = DayTypeRow(id, code = "", workMin = "", mealBreakMin = "", label = "", isDefault = false)

    /** The save body: codes trimmed, minutes as numbers, a blank meal break and label as null. */
    fun wire(rows: List<DayTypeRow>): JsonArray = JsonArray(
        rows.map { row ->
            buildJsonObject {
                put("day_type", row.code.trim())
                put("work_min", PayloadParts.number(number(row.workMin)))
                put(
                    "meal_break_min",
                    if (row.mealBreakMin.isEmpty()) JsonNull else PayloadParts.number(number(row.mealBreakMin)),
                )
                put("label", row.label.trim().ifEmpty { null })
                put("note", JsonNull)
            }
        },
    )

    /** The first problem, in the web's words, or null. */
    @Suppress("ReturnCount") // The first failing check is the message, in the web's order.
    fun validate(rows: List<DayTypeRow>): String? {
        if (rows.size > MAX_ROWS) return TOO_MANY
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            val code = row.code.trim()
            if (code.isEmpty()) return "Every day type needs a code (e.g. CWD, SWD)."
            if (!seen.add(code)) return "Duplicate day type \"$code\" — codes must be unique."
            if (row.workMin.isEmpty()) return "\"$code\": working minutes is required."
            if (!isWholeMinutes(row.workMin)) {
                return "\"$code\": working minutes must be a whole number between 0 and 1440."
            }
            if (row.mealBreakMin.isNotEmpty() && !isWholeMinutes(row.mealBreakMin)) {
                return "\"$code\": meal break must be a whole number of minutes (or blank)."
            }
        }
        return null
    }

    /** `= 10h`, `= 9h 30m`, `= 45m`; nothing for a blank or non-positive value. */
    fun minutesHint(value: String): String {
        val minutes = number(value)
        if (value.isEmpty() || minutes == null || minutes <= 0) return ""
        val hours = floor(minutes / MINUTES_PER_HOUR).toInt()
        val rest = minutes % MINUTES_PER_HOUR
        val hourPart = if (hours != 0) "${hours}h" else ""
        val gap = if (hours != 0 && rest != 0.0) " " else ""
        val minutePart = when {
            rest != 0.0 -> "${Js.number(rest)}m"
            hours != 0 -> ""
            else -> "${Js.number(rest)}m"
        }
        return "= $hourPart$gap$minutePart".trim()
    }

    private fun row(source: JsonObject, isDefault: Boolean, newId: () -> String) = DayTypeRow(
        id = source["id"]?.takeIf(Js::truthy)?.let(Js::text) ?: newId(),
        code = source["day_type"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
        workMin = source["work_min"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
        mealBreakMin = source["meal_break_min"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
        label = source["label"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
        isDefault = isDefault,
    )

    /** `Number(text)`: trimmed, blank as zero, anything else not a number as null. */
    private fun number(text: String): Double? = Js.toNumber(JsonPrimitive(text))

    private fun isWholeMinutes(text: String): Boolean {
        val value = number(text) ?: return false
        return value == floor(value) && value in 0.0..DAY_MINUTES.toDouble()
    }
}
