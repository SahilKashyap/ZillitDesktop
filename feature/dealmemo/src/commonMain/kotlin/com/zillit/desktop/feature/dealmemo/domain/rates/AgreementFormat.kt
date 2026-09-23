package com.zillit.desktop.feature.dealmemo.domain.rates

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.time.Instant

/** A trigger rendered for the rule tables: the condition, and its billing small print. */
data class TriggerText(val main: String, val meta: String)

/**
 * The agreement page's rule formatters (`DMConfigPage.jsx:59-233`), over the
 * CBA document as authored.
 *
 * These read the raw JSON rather than a model: a collective agreement's rule
 * rows carry whichever of a dozen trigger and rate shapes its union publishes,
 * and a typed reader would decide which of them exist.
 */
object AgreementFormat {

    private val MONTHS: List<String>
        get() = listOf(
            str(S.desktop_month_short_jan), str(S.desktop_month_short_feb), str(S.desktop_month_short_mar),
            str(S.desktop_month_short_apr), str(S.desktop_month_short_may), str(S.desktop_month_short_jun),
            str(S.desktop_month_short_jul), str(S.desktop_month_short_aug), str(S.desktop_month_short_sep),
            str(S.desktop_month_short_oct), str(S.desktop_month_short_nov), str(S.desktop_month_short_dec),
        )

    private val ORDINALS = listOf("", "1st", "2nd", "3rd", "4th", "5th", "6th", "7th")

    private val DAY_KINDS: Map<String, String>
        get() = mapOf(
            "bank_holiday" to str(S.desktop_bank_holiday),
            "public_holiday" to str(S.desktop_public_holiday),
            "sunday" to str(S.day_sunday),
            "idle" to str(S.desktop_idle_day),
        )

    private val BASIS_LABELS: Map<String, String>
        get() = mapOf(
            "hour" to str(S.desktop_unit_hour), "day" to str(S.day_label), "days" to str(S.dm_ds_days_label),
            "night" to str(S.desktop_unit_night), "event" to str(S.desktop_unit_event),
            "mile" to str(S.desktop_unit_mile), "meal" to str(S.desktop_unit_meal), "call" to str(S.desktop_unit_call),
            "penalty" to str(S.desktop_unit_penalty), "additional" to str(S.desktop_unit_additional),
            "30_minutes" to str(S.desktop_unit_30_min),
            "actuals" to str(S.desktop_actuals), "club_class" to str(S.desktop_dm_basis_class),
            "years" to str(S.desktop_dm_basis_years),
            "weekly_gross" to str(S.desktop_dm_basis_weekly_gross),
            "base_weekly" to str(S.desktop_dm_basis_base_weekly),
            "qualifying_earnings" to str(S.desktop_dm_basis_qualifying_earnings),
            "gross_invoice" to str(S.desktop_dm_basis_gross_invoice),
            "futa_wage_base" to str(S.desktop_dm_basis_futa_wage_base),
            "scale_wages" to str(S.desktop_dm_basis_scale_wages),
            "excess_threshold" to str(S.desktop_dm_basis_excess_over_threshold),
            "gross_fees" to str(S.desktop_dm_basis_gross_fees),
            "ordinary_time" to str(S.desktop_dm_basis_ordinary_time),
            "cpp_pensionable" to str(S.desktop_dm_basis_cpp_pensionable),
            "cpp2_band" to str(S.desktop_dm_basis_cpp2_band),
            "insurable_earnings" to str(S.desktop_dm_basis_insurable_earnings),
            "contracted_payment_capped_at_minimum_excl_aupp" to str(S.desktop_dm_basis_contracted_payment_excl_aupp),
            "minimum_payment_excl_sua" to str(S.desktop_dm_basis_minimum_payment_excl_sua),
            "established_writer_minimum" to str(S.desktop_dm_basis_established_writer_minimum),
        )

    /** `minsToHuman`: `90` → `1h 30m`, `120` → `2h`. */
    fun minutesToHuman(minutes: Double): String {
        val hours = floor(minutes / MINUTES_PER_HOUR)
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest == 0.0) "${Js.number(hours)}h" else "${Js.number(hours)}h ${Js.number(rest)}m"
    }

    /** `minsToClock`: `1380` → `23:00`. */
    fun minutesToClock(minutes: Double): String =
        Js.number(floor(minutes / MINUTES_PER_HOUR)).padStart(2, '0') + ":" +
            Js.number(minutes % MINUTES_PER_HOUR).padStart(2, '0')

    /**
     * `formatEffectiveRange`: `Apr 2024 – Mar 2026`, `from Apr 2024`,
     * `until Mar 2026`, or blank — months in the reader's own zone, as the web.
     */
    fun effectiveRange(from: Long?, to: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        fun month(millis: Long): String = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
            .let { "${MONTHS[it.month.ordinal]} ${it.year}" }
        return when {
            from != null && to != null -> "${month(from)} – ${month(to)}"
            from != null -> str(S.desktop_dm_range_from, month(from))
            to != null -> str(S.desktop_dm_range_until, month(to))
            else -> ""
        }
    }

    /** `primaryTrigger`: the SWD entry, else an untyped catch-all, else the first. */
    fun primaryTrigger(row: JsonObject): JsonObject? {
        val triggers = (row["triggers"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        val objects = triggers.map { it as? JsonObject }
        return objects.firstOrNull { it?.get("day_type")?.let(::stringOf) == "SWD" }
            ?: objects.firstOrNull { it == null || Js.isNullish(it["day_type"]) }
            ?: objects.first()
    }

    /** `formatTrigger` — when a rule applies, in the table's words. */
    @Suppress("CyclomaticComplexMethod") // One clause per trigger field, in the web's order.
    fun trigger(t: JsonObject?): TriggerText {
        if (t == null) return TriggerText(RateFormat.DASH, "")
        val parts = mutableListOf<String>()
        val after = number(t["after"])
        val before = number(t["before"])
        when {
            Js.truthy(t["meal"]) -> parts += str(S.desktop_dm_trig_meal_not_within, minutesToHuman(after ?: 0.0))
            Js.truthy(t["clock"]) -> {
                before?.let { parts += str(S.desktop_dm_before_x, minutesToClock(it)) }
                after?.let { parts += str(S.desktop_dm_after_x, minutesToClock(it)) }
            }
            Js.truthy(t["always"]) ->
                parts += before?.let { str(S.desktop_dm_trig_first, minutesToHuman(it)) } ?: str(S.desktop_always)
            after != null ->
                parts += str(S.desktop_dm_after_x, minutesToHuman(after)) +
                    (before?.let { " – ${minutesToHuman(it)}" } ?: "")
        }
        number(t["call_before"])?.let { parts += str(S.desktop_dm_trig_call_before, minutesToClock(it)) }
        number(t["less"])?.let { parts += str(S.desktop_dm_trig_rest_less, minutesToHuman(it)) }
        if (!Js.isNullish(t["more"])) parts += str(S.desktop_dm_trig_beyond_road_miles, Js.text(t["more"]))
        if (!Js.isNullish(t["day_number"])) {
            val day = number(t["day_number"])
            val ordinal = day?.takeIf { it >= 0 && it == floor(it) && it < ORDINALS.size }
                ?.let { ORDINALS[it.toInt()] } ?: "${Js.text(t["day_number"])}th"
            parts += if (Js.truthy(t["consecutive"])) {
                str(S.desktop_dm_trig_consecutive_day, ordinal)
            } else {
                str(S.desktop_dm_trig_day_of_week, ordinal)
            }
        }
        if (Js.truthy(t["day_kind"])) {
            val kind = Js.text(t["day_kind"])
            parts += DAY_KINDS[kind] ?: kind
        }
        if (Js.truthy(t["distant"])) parts += str(S.desktop_distant)
        if (Js.truthy(t["on_call"])) parts += str(S.desktop_dm_trig_as_called)
        if (Js.truthy(t["per_event"])) parts += str(S.desktop_per_event)
        if (Js.truthy(t["negotiated"])) parts += str(S.desktop_dm_trig_as_negotiated)

        val meta = buildList {
            number(t["guarantee"])?.let { add(str(S.desktop_dm_trig_guarantee, minutesToHuman(it))) }
            if (!Js.isNullish(t["max_occurrences"])) {
                add(str(S.desktop_dm_trig_max_per_week, Js.text(t["max_occurrences"])))
            }
            if (!Js.isNullish(t["increment"])) add(str(S.desktop_dm_trig_min_billing, Js.text(t["increment"])))
        }
        return TriggerText(parts.joinToString(" · ").ifEmpty { RateFormat.DASH }, meta.joinToString(" · "))
    }

    /**
     * `formatCompensation` — what a rule pays. A flat amount carries the raw
     * currency CODE (`GBP25/day`), unlike the caps beside it; a multiplier
     * reads `×1.5T`, or `+0.5T` when it enhances.
     */
    @Suppress("CyclomaticComplexMethod")
    fun compensation(row: JsonObject, currency: String?): String {
        if (Js.truthy(row["use_ot_rate"])) return str(S.desktop_dm_ot_rate)
        val rawType = row["rate_type"]?.let(::stringOf)
        val type = when {
            rawType == "fixed" -> "flat"
            !Js.isNullish(row["rate_type"]) -> Js.text(row["rate_type"])
            !Js.isNullish(row["multiplier"]) -> "multiplier"
            !Js.isNullish(row["flat"]) -> "flat"
            !Js.isNullish(row["percentage"]) -> "percentage"
            else -> null
        }
        val amount = listOf("rate_amount", "multiplier", "flat", "percentage")
            .firstNotNullOfOrNull { key -> row[key]?.takeUnless(Js::isNullish) }
        return when {
            type == "percentage" && amount != null ->
                str(S.desktop_dm_percent_of, Js.text(amount), basisLabel(row["basis"]))
            type == "flat" && amount != null ->
                "${currency.orEmpty()}${groupAmount(amount)}/${basisLabel(row["basis"])}"
            type == "multiplier" && amount != null ->
                "${if (Js.truthy(row["is_enhancement"])) "+" else "×"}${Js.text(amount)}T"
            rawType == "actuals" -> str(S.desktop_actuals)
            Js.truthy(row["basis"]) -> basisLabel(row["basis"])
            else -> RateFormat.DASH
        }
    }

    /** `formatCap`: `£40–£60/hr`, `≤ £60/hr`, `≥ £40/hr` — always per hour — or null. */
    fun cap(row: JsonObject, currency: String?): String? {
        val sym = RateFormat.symbolExact(currency)
        val min = row["min"]?.takeUnless(Js::isNullish)
        val max = row["max"]?.takeUnless(Js::isNullish)
        return when {
            min != null && max != null -> "$sym${groupAmount(min)}–$sym${groupAmount(max)}/hr"
            max != null -> "≤ $sym${groupAmount(max)}/hr"
            min != null -> "≥ $sym${groupAmount(min)}/hr"
            else -> null
        }
    }

    /** `basisLabel`: a missing basis reads `event`; an unknown one loses its underscores. */
    fun basisLabel(basis: JsonElement?): String {
        if (!Js.truthy(basis)) return str(S.desktop_unit_event)
        val key = Js.text(basis)
        return BASIS_LABELS[key] ?: key.replace('_', ' ')
    }

    /** `BASIS_LABELS[basis] ?? basis` — the fringe table's lookup, which keeps an unknown key as written. */
    fun basisLookup(basis: String): String = BASIS_LABELS[basis] ?: basis

    /** `basisLabel` for a basis already read as text. */
    fun basisLabel(basis: String?): String = basisLabel(basis?.let(::JsonPrimitive))

    /** `groupAmountAuto(v)` over a raw JSON value — `String(v)` when it is not a number. */
    fun groupAmount(value: JsonElement?): String =
        Js.toNumber(value)?.let(RateFormat::groupAmountAuto) ?: if (Js.isNullish(value)) "" else Js.text(value)

    private fun number(value: JsonElement?): Double? = if (Js.isNullish(value)) null else Js.toNumber(value)

    private fun stringOf(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MINUTES_PER_HOUR = 60.0
}
