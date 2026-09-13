package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateTierEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One tier as the Rates step merges it (`mergeTier`): the rate card's entry
 * over the agreement's `basic_rate_details`, field by field. Values stay raw —
 * the catalogue writes numbers as numbers or strings, and both print as written.
 */
data class MergedTier(
    val base: JsonElement?,
    val min: JsonElement?,
    val max: JsonElement?,
    val hours: JsonElement?,
    val baseFromUnion: Boolean,
    val envelopeFromUnion: Boolean,
) {
    val entry: RateTierEntry get() =
        RateTierEntry(Js.toNumber(base), Js.toNumber(min), Js.toNumber(max), Js.toNumber(hours))

    /** `pickFigure`: the base, else the contractual floor, else the cap. */
    val figure: JsonElement? get() = base ?: min ?: max
}

/** The published rates a deal is offered for its role, agreement, band and schedule. */
data class EffectiveRates(
    val hourly: MergedTier?,
    val daily: MergedTier?,
    val weekly: MergedTier?,
    val dayType: String?,
    val status: String?,
) {
    /** A tier borrowed its base or envelope from the agreement. */
    val usedUnionFallback: Boolean get() = listOfNotNull(hourly, daily, weekly).any {
        it.baseFromUnion || it.envelopeFromUnion
    }

    /** `daysPerWeek`: weekly hours over daily hours, else five. */
    val daysPerWeek: Double get() = RateResolve.hoursRatio(daily, weekly) ?: DEFAULT_DAYS_PER_WEEK

    private companion object {
        const val DEFAULT_DAYS_PER_WEEK = 5.0
    }
}

/**
 * The Rates step's rate resolution and its auto-apply (`Step5Rates.jsx`):
 * which schedule, which tier entry, how the agreement fills the gaps, and when
 * the published figure replaces what is in the form.
 */
object RateResolve {

    /** With several schedules, the picked one or the first; otherwise the only row. */
    fun chosenRate(resolvedRates: List<JsonObject>, form: DealForm): JsonObject? {
        if (resolvedRates.size <= 1) return resolvedRates.firstOrNull()
        val picked = form["selectedScheduleKey"]
        return resolvedRates.firstOrNull { strictEquals(it["schedule_key"], picked) } ?: resolvedRates.first()
    }

    /**
     * `pickTierEntryForAgreement`: the entry whose hours match the agreement's,
     * else an untyped entry, else the first. A non-array tier is nothing.
     */
    fun pickTierEntry(tier: JsonElement?, agreementHours: JsonElement?): JsonObject? {
        val entries = tier as? JsonArray ?: return null
        if (entries.isEmpty()) return null
        if (!Js.isNullish(agreementHours)) {
            val target = Js.toNumber(agreementHours)
            val match = entries.firstOrNull { entry ->
                val hours = Js.toNumber((entry as? JsonObject)?.get("work_hrs"))
                target != null && hours != null && hours == target
            }
            if (match is JsonObject) return match
        }
        val untyped = entries.firstOrNull { it !is JsonObject || Js.isNullish(it["day_type"]) }
        return untyped as? JsonObject ?: entries.first() as? JsonObject
    }

    fun merge(entry: JsonObject?, agreement: JsonObject?): MergedTier? {
        if (entry == null && agreement == null) return null
        fun of(source: JsonObject?, key: String) = source?.get(key)?.takeUnless { it is JsonNull }
        return MergedTier(
            base = of(entry, "base_rate") ?: of(agreement, "base_rate"),
            min = of(entry, "min_rate") ?: of(agreement, "min_rate"),
            max = of(entry, "max_rate") ?: of(agreement, "max_rate"),
            hours = of(entry, "work_hrs") ?: of(agreement, "work_hrs"),
            baseFromUnion = of(entry, "base_rate") == null && of(agreement, "base_rate") != null,
            envelopeFromUnion = of(entry, "min_rate") == null && of(entry, "max_rate") == null &&
                (of(agreement, "min_rate") != null || of(agreement, "max_rate") != null),
        )
    }

    fun effective(chosen: JsonObject?, agreement: JsonObject?): EffectiveRates {
        val basic = agreement?.get("basic_rate_details") as? JsonObject
        fun basicTier(key: String) = basic?.get(key) as? JsonObject
        fun picked(key: String) = pickTierEntry(chosen?.get(key), basicTier(key)?.get("work_hrs"))
        val daily = picked("daily")
        val dayType = listOf(daily, basic).firstNotNullOfOrNull { it?.get("day_type")?.takeUnless(Js::isNullish) }
        return EffectiveRates(
            hourly = merge(picked("hourly"), basicTier("hourly")),
            daily = merge(daily, basicTier("daily")),
            weekly = merge(picked("weekly"), basicTier("weekly")),
            dayType = dayType?.let(Js::text),
            status = chosen?.get("status")?.takeUnless(Js::isNullish)?.let(Js::text),
        )
    }

    /** Weekly hours over daily hours when both are known — the auto-apply's ratio, with no fallback. */
    fun hoursRatio(daily: MergedTier?, weekly: MergedTier?): Double? {
        if (!Js.truthy(weekly?.hours) || !Js.truthy(daily?.hours)) return null
        val week = Js.toNumber(weekly?.hours) ?: return null
        val day = Js.toNumber(daily?.hours) ?: return null
        return week / day
    }

    /** `_resolveApiRates`: the day and weekly figures to fill in, each "" when there is none. */
    fun apiRates(daily: MergedTier?, weekly: MergedTier?): Pair<String, String> {
        val day = daily?.figure
        val week = weekly?.figure
        if (day == null && week == null) return "" to ""
        val ratio = hoursRatio(daily, weekly)
        return if (day != null) {
            val weekText = when {
                week != null -> Js.text(week)
                ratio != null -> fixed2(Js.toNumber(day)?.times(ratio))
                else -> ""
            }
            Js.text(day) to weekText
        } else {
            (ratio?.let { fixed2(Js.toNumber(week)?.div(it)) } ?: "") to Js.text(week)
        }
    }

    /**
     * The auto-apply effect's write, or null when this tuple has been applied.
     * Typed rates survive while the agreement, designation, band and schedule
     * stay the same, and a tuple that publishes nothing never blanks them.
     */
    fun autoApplied(form: DealForm, effective: EffectiveRates, chosen: JsonObject?): DealForm? {
        val chosenId = chosen?.get("_id")?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
        val key = "${DealHydration.rateContextKey(form)}|$chosenId"
        if (form.text("rateAutoKey") == key) return null
        fun tuple(value: String) = value.split("|").take(CONTEXT_SEGMENTS).joinToString("|")
        val sameContext = tuple(form.text("rateAutoKey")) == tuple(key)
        val userTyped = typed(form["dayRate"]) || typed(form["weeklyRate"])
        val userOverrode = sameContext && userTyped && offScale(form)
        val (day, week) = apiRates(effective.daily, effective.weekly)
        val keep = userOverrode || (day.isEmpty() && week.isEmpty())
        val next = buildMap {
            if (!keep) {
                put("dayRate", JsonPrimitive(day))
                put("weeklyRate", JsonPrimitive(week))
            }
            put("rateAutoKey", JsonPrimitive(key))
            put("rateApiDay", JsonPrimitive(day))
            put("rateApiWeekly", JsonPrimitive(week))
        }
        return form.with(next)
    }

    /** `onDayRateChange`: the weekly rate follows as a two-place string. */
    fun withDayRate(form: DealForm, value: JsonElement, daysPerWeek: Double): DealForm = form.with(
        mapOf(
            "dayRate" to value,
            "weeklyRate" to JsonPrimitive(fixed2((Js.parseFloat(value) ?: 0.0) * daysPerWeek)),
            "rateAutoKey" to JsonPrimitive(DealHydration.rateContextKey(form)),
        ),
    )

    /** `onWeeklyRateChange`: the day rate follows. */
    fun withWeeklyRate(form: DealForm, value: JsonElement, daysPerWeek: Double): DealForm = form.with(
        mapOf(
            "weeklyRate" to value,
            "dayRate" to JsonPrimitive(fixed2((Js.parseFloat(value) ?: 0.0) / daysPerWeek)),
            "rateAutoKey" to JsonPrimitive(DealHydration.rateContextKey(form)),
        ),
    )

    /** The rates differ from the published scale they were filled from. */
    fun isManual(form: DealForm): Boolean {
        val published = !emptyString(form["rateApiDay"]) || !emptyString(form["rateApiWeekly"])
        return published && offScale(form)
    }

    /** Either figure differs, as a number, from the published one it was filled from. */
    private fun offScale(form: DealForm): Boolean =
        number(form["dayRate"]) != number(form["rateApiDay"]) ||
            number(form["weeklyRate"]) != number(form["rateApiWeekly"])

    /** "Reset to scale": the published figures back in, the key cleared so the next answer applies. */
    fun resetToScale(form: DealForm, effective: EffectiveRates): DealForm {
        val (day, week) = apiRates(effective.daily, effective.weekly)
        return form.with(
            mapOf(
                "dayRate" to JsonPrimitive(day),
                "weeklyRate" to JsonPrimitive(week),
                "rateApiDay" to JsonPrimitive(day),
                "rateApiWeekly" to JsonPrimitive(week),
                "rateAutoKey" to JsonPrimitive(""),
            ),
        )
    }

    /** `scheduleOptionLabel`: `10hr daily / 50hr weekly`, else the schedule key, else `Default schedule`. */
    fun scheduleLabel(rate: JsonObject): String {
        val parts = buildList {
            (rate["daily"] as? JsonObject)?.get("work_hrs")?.takeUnless(Js::isNullish)?.let {
                add("${Js.text(it)}hr daily")
            }
            (rate["weekly"] as? JsonObject)?.get("work_hrs")?.takeUnless(Js::isNullish)?.let {
                add("${Js.text(it)}hr weekly")
            }
        }
        if (parts.isNotEmpty()) return parts.joinToString(" / ")
        return rate["schedule_key"]?.takeIf(Js::truthy)?.let(Js::text) ?: "Default schedule"
    }

    /** The agreement publishes banded scales and no band is picked. */
    fun missingBand(form: DealForm, agreement: JsonObject?): Boolean {
        val bands = ((agreement?.get("pact") as? JsonObject)?.get("bands") as? JsonArray).orEmpty()
        return bands.isNotEmpty() && !form.flag("pactBand")
    }

    /** `parseFloat(x) || 0`. */
    fun number(value: JsonElement?): Double = Js.parseFloat(value)?.takeUnless { it.isNaN() } ?: 0.0

    /** `(x).toFixed(2)`, with JavaScript's `NaN` for what is not a number. */
    fun fixed2(value: Double?): String = if (value == null || !value.isFinite()) "NaN" else Js.toFixed(value, 2)

    /** `(x ?? "") !== ""`. */
    private fun typed(value: JsonElement?): Boolean = value != null && value !is JsonNull && !emptyString(value)

    private fun emptyString(value: JsonElement?): Boolean =
        value is JsonPrimitive && value.isString && value.content.isEmpty()

    /** `===` over JSON values; an absent key is `undefined`, apart from `null`. */
    @Suppress("CyclomaticComplexMethod")
    private fun strictEquals(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        a is JsonPrimitive && b is JsonPrimitive -> when {
            a.isString != b.isString -> false
            a.isString -> a.content == b.content
            else -> a.content == b.content || Js.toNumber(a)?.let { it == Js.toNumber(b) } == true
        }
        else -> false
    }

    private const val CONTEXT_SEGMENTS = 4
}
