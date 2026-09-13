package com.zillit.desktop.feature.dealmemo.domain.rates

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

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val ORDINALS = listOf("", "1st", "2nd", "3rd", "4th", "5th", "6th", "7th")

    private val DAY_KINDS = mapOf(
        "bank_holiday" to "Bank holiday",
        "public_holiday" to "Public holiday",
        "sunday" to "Sunday",
        "idle" to "Idle day",
    )

    private val BASIS_LABELS: Map<String, String> = mapOf(
        "hour" to "hour", "day" to "day", "days" to "days", "night" to "night", "event" to "event",
        "mile" to "mile", "meal" to "meal", "call" to "call", "penalty" to "penalty",
        "additional" to "additional", "30_minutes" to "30 min",
        "actuals" to "Actuals", "club_class" to "Class", "years" to "years",
        "weekly_gross" to "Weekly gross", "base_weekly" to "Base weekly",
        "qualifying_earnings" to "Qualifying earnings", "gross_invoice" to "Gross invoice",
        "futa_wage_base" to "FUTA wage base", "scale_wages" to "Scale wages",
        "excess_threshold" to "Excess over threshold", "gross_fees" to "Gross fees",
        "ordinary_time" to "Ordinary time", "cpp_pensionable" to "CPP pensionable",
        "cpp2_band" to "CPP2 band", "insurable_earnings" to "Insurable earnings",
        "contracted_payment_capped_at_minimum_excl_aupp" to "Contracted payment (excl. AUPP)",
        "minimum_payment_excl_sua" to "Minimum payment (excl. SUA)",
        "established_writer_minimum" to "Established writer minimum",
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
            from != null -> "from ${month(from)}"
            to != null -> "until ${month(to)}"
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
            Js.truthy(t["meal"]) -> parts += "Meal not within ${minutesToHuman(after ?: 0.0)}"
            Js.truthy(t["clock"]) -> {
                before?.let { parts += "Before ${minutesToClock(it)}" }
                after?.let { parts += "After ${minutesToClock(it)}" }
            }
            Js.truthy(t["always"]) -> parts += before?.let { "First ${minutesToHuman(it)}" } ?: "Always"
            after != null ->
                parts += "After ${minutesToHuman(after)}" + (before?.let { " – ${minutesToHuman(it)}" } ?: "")
        }
        number(t["call_before"])?.let { parts += "Call before ${minutesToClock(it)}" }
        number(t["less"])?.let { parts += "Rest < ${minutesToHuman(it)}" }
        if (!Js.isNullish(t["more"])) parts += "Beyond ${Js.text(t["more"])} road miles"
        if (!Js.isNullish(t["day_number"])) {
            val day = number(t["day_number"])
            val ordinal = day?.takeIf { it >= 0 && it == floor(it) && it < ORDINALS.size }
                ?.let { ORDINALS[it.toInt()] } ?: "${Js.text(t["day_number"])}th"
            parts += if (Js.truthy(t["consecutive"])) "$ordinal consecutive day" else "$ordinal day of week"
        }
        if (Js.truthy(t["day_kind"])) {
            val kind = Js.text(t["day_kind"])
            parts += DAY_KINDS[kind] ?: kind
        }
        if (Js.truthy(t["distant"])) parts += "Distant"
        if (Js.truthy(t["on_call"])) parts += "As called"
        if (Js.truthy(t["per_event"])) parts += "Per event"
        if (Js.truthy(t["negotiated"])) parts += "As negotiated"

        val meta = buildList {
            number(t["guarantee"])?.let { add("${minutesToHuman(it)} guarantee") }
            if (!Js.isNullish(t["max_occurrences"])) add("max ${Js.text(t["max_occurrences"])}/wk")
            if (!Js.isNullish(t["increment"])) add("${Js.text(t["increment"])}-min billing")
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
        if (Js.truthy(row["use_ot_rate"])) return "OT rate"
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
            type == "percentage" && amount != null -> "${Js.text(amount)}% of ${basisLabel(row["basis"])}"
            type == "flat" && amount != null ->
                "${currency.orEmpty()}${groupAmount(amount)}/${basisLabel(row["basis"])}"
            type == "multiplier" && amount != null ->
                "${if (Js.truthy(row["is_enhancement"])) "+" else "×"}${Js.text(amount)}T"
            rawType == "actuals" -> "Actuals"
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
        if (!Js.truthy(basis)) return "event"
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
