package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.roundToLong

/** The storage list a rule lives in. */
enum class RuleList(val wire: String) {
    Overtimes("overtimes"),
    Premiums("premiums"),
    Turnarounds("turnarounds"),
    Penalties("penalties"),
    ;

    companion object {
        fun from(wire: String?): RuleList? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a rule template's trigger column edits. */
enum class TriggerField { Hours, Time, DayKinds, None }

/**
 * The grid's editable trigger value: hours as typed, a `HH:MM` clock, or the
 * chosen day kinds.
 */
data class TriggerForm(val hours: String = "", val time: String = "", val dayKinds: List<String> = emptyList())

/**
 * One of the non-union rule templates (`NonUnionPayBreakdownSection.jsx:280-520`)
 * — the industry-vocabulary scaffolds whose trigger shapes the OT engine reads.
 */
@Suppress("MagicNumber") // Each template's default amount, as the web seeds it.
enum class RuleTemplate(
    private val labelKey: String,
    val list: RuleList,
    val rateType: String,
    val rateAmount: Double,
    val basis: String,
    val field: TriggerField,
    val defaultForm: TriggerForm,
) {
    OtSixth(
        S.desktop_dm_tpl_ot_6th_day, RuleList.Overtimes, "multiplier", 1.5, "hour", TriggerField.Hours,
        TriggerForm(hours = "0"),
    ),
    OtSeventh(
        S.desktop_dm_tpl_ot_7th_day,
        RuleList.Overtimes,
        "multiplier",
        2.0,
        "hour",
        TriggerField.Hours,
        TriggerForm(hours = "0"),
    ),
    Ot(
        S.desktop_dm_tpl_ot_after_hours,
        RuleList.Overtimes,
        "multiplier",
        1.5,
        "hour",
        TriggerField.Hours,
        TriggerForm(hours = "8"),
    ),
    CameraOt(
        S.desktop_dm_tpl_camera_ot_after_hours, RuleList.Overtimes, "multiplier", 2.0, "hour", TriggerField.Hours,
        TriggerForm(hours = "11"),
    ),
    SixthDay(S.desktop_dm_tpl_6th_day, RuleList.Premiums, "multiplier", 1.5, "day", TriggerField.None, TriggerForm()),
    SeventhDay(S.desktop_dm_tpl_7th_day, RuleList.Premiums, "multiplier", 2.0, "day", TriggerField.None, TriggerForm()),
    BankHoliday(
        S.desktop_dm_tpl_bank_holiday, RuleList.Premiums, "multiplier", 1.5, "day", TriggerField.DayKinds,
        TriggerForm(dayKinds = listOf("bank_holiday")),
    ),
    PreDawn(
        S.desktop_dm_tpl_pre_dawn,
        RuleList.Premiums,
        "multiplier",
        2.0,
        "hour",
        TriggerField.Time,
        TriggerForm(time = "06:00"),
    ),
    NightWorkEarly(
        S.desktop_dm_tpl_night_work_early, RuleList.Premiums, "flat", 25.0, "event", TriggerField.Time,
        TriggerForm(time = "05:00"),
    ),
    NightWork(
        S.desktop_dm_tpl_night_work,
        RuleList.Premiums,
        "multiplier",
        1.5,
        "hour",
        TriggerField.Time,
        TriggerForm(time = "22:00"),
    ),
    BrokenTurnaround(
        S.desktop_dm_tpl_broken_turnaround, RuleList.Penalties, "flat", 900.0, "event", TriggerField.Hours,
        TriggerForm(hours = "10"),
    ),
    MealPenalty(
        S.desktop_dm_tpl_meal_penalty, RuleList.Penalties, "flat", 9.5, "event", TriggerField.Hours,
        TriggerForm(hours = "6"),
    ),
    MealCurtailed(
        S.desktop_dm_tpl_meal_curtailed,
        RuleList.Penalties,
        "multiplier",
        2.0,
        "hour",
        TriggerField.None,
        TriggerForm(),
    ),
    ;

    val id: String get() = IDS.getValue(this)

    val label: String get() = str(labelKey)

    /** The rule-type menu's groups: Overtime, Premium, Penalty. */
    val group: String
        get() = when (list) {
            RuleList.Overtimes -> str(S.overtime)
            RuleList.Premiums -> str(S.desktop_premium)
            else -> str(S.desktop_penalty)
        }

    /** `fromTrigger`: the editable form a stored trigger reads as. */
    fun fromTrigger(trigger: JsonObject): TriggerForm = when (this) {
        OtSixth, OtSeventh, Ot, CameraOt, MealPenalty -> TriggerForm(hours = minutesAsHours(trigger["after"]))
        BrokenTurnaround -> TriggerForm(hours = minutesAsHours(trigger["less"]))
        BankHoliday -> TriggerForm(
            dayKinds = when (val kinds = trigger["day_kind"]) {
                is JsonArray -> kinds.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOfNotNull(kinds.takeIf { Js.truthy(it) }?.content)
                else -> emptyList()
            },
        )
        PreDawn, NightWorkEarly ->
            TriggerForm(time = RuleClock.format(Js.toNumber(trigger["before"] ?: JsonPrimitive(0))))
        NightWork -> TriggerForm(time = RuleClock.format(Js.toNumber(trigger["after"] ?: JsonPrimitive(0))))
        SixthDay, SeventhDay, MealCurtailed -> TriggerForm()
    }

    /** `toTrigger`: the canonical single trigger for a form. */
    @Suppress("CyclomaticComplexMethod")
    fun toTrigger(form: TriggerForm): JsonObject = buildJsonObject {
        val minutes = ((form.hours.toDoubleOrNull() ?: 0.0) * MINUTES).roundToLong()
        when (this@RuleTemplate) {
            OtSixth, OtSeventh -> {
                put("day_number", if (this@RuleTemplate == OtSixth) SIXTH else SEVENTH)
                put("consecutive", true)
                put("after", minutes)
            }
            Ot -> put("after", minutes)
            CameraOt -> {
                put("after", minutes)
                put("camera", true)
                put("increment", CAMERA_INCREMENT)
            }
            SixthDay, SeventhDay -> {
                put("day_number", if (this@RuleTemplate == SixthDay) SIXTH else SEVENTH)
                put("consecutive", true)
            }
            BankHoliday -> put("day_kind", buildJsonArray { form.dayKinds.forEach { add(JsonPrimitive(it)) } })
            PreDawn, NightWorkEarly -> {
                put("clock", true)
                put("before", RuleClock.parse(form.time))
            }
            NightWork -> {
                put("clock", true)
                put("after", RuleClock.parse(form.time))
            }
            BrokenTurnaround -> put("less", minutes)
            MealPenalty -> {
                put("meal", true)
                put("after", minutes)
            }
            MealCurtailed -> put("meal_curtailed", true)
        }
    }

    companion object {
        private const val MINUTES = 60.0
        private const val SIXTH = 6
        private const val SEVENTH = 7
        private const val SIXTH_DAY = 6.0
        private const val SEVENTH_DAY = 7.0
        private const val CAMERA_INCREMENT = 15

        private val IDS = mapOf(
            OtSixth to "ot_6th", OtSeventh to "ot_7th", Ot to "ot", CameraOt to "camera_ot",
            SixthDay to "sixth_day", SeventhDay to "seventh_day", BankHoliday to "bank_holiday",
            PreDawn to "pre_dawn", NightWorkEarly to "night_work_early", NightWork to "night_work",
            BrokenTurnaround to "broken_turnaround", MealPenalty to "meal_penalty", MealCurtailed to "meal_curtailed",
        )

        fun byId(id: String?): RuleTemplate? = entries.firstOrNull { it.id == id }

        /** `t.after ? t.after / 60 : ""` — as JavaScript prints the quotient. */
        private fun minutesAsHours(value: JsonElement?): String =
            value.takeIf { Js.truthy(it) }?.let { Js.toNumber(it) }?.let { Js.number(it / MINUTES) }.orEmpty()

        /**
         * `matchTemplate`: exactly one trigger, its padding (null, false, "")
         * and BDR bounds dropped, matched by each template's `detect`. Null when
         * nothing matches — which is not the same as the list default.
         */
        fun match(triggers: JsonElement?): RuleTemplate? {
            val list = triggers as? JsonArray ?: return null
            if (list.size != 1) return null
            val trigger = meaningful(list[0] as? JsonObject)
            if (trigger.isEmpty()) return null
            return entries.firstOrNull { detects(it, trigger) }
        }

        @Suppress("CyclomaticComplexMethod")
        private fun detects(template: RuleTemplate, t: Map<String, JsonElement>): Boolean {
            fun flag(key: String) = Js.truthy(t[key])
            fun isTrue(key: String) = (t[key] as? JsonPrimitive)?.booleanOrNull == true
            fun number(key: String) = (t[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val plain = !flag("weekly") && !flag("clock") && !flag("meal") && !flag("meal_curtailed")
            return when (template) {
                OtSixth -> number("day_number") == SIXTH_DAY && isTrue("consecutive") && "after" in t && plain
                OtSeventh -> number("day_number") == SEVENTH_DAY && isTrue("consecutive") && "after" in t && plain
                Ot -> "after" in t && plain && t.keys.all { it == "after" }
                CameraOt -> isTrue("camera") && "after" in t && plain && "less" !in t && "day_number" !in t
                SixthDay -> number("day_number") == SIXTH_DAY && isTrue("consecutive") && "after" !in t && plain
                SeventhDay -> number("day_number") == SEVENTH_DAY && isTrue("consecutive") && "after" !in t && plain
                BankHoliday -> "day_kind" in t && t.keys.all { it == "day_kind" }
                PreDawn -> isTrue("clock") && "before" in t && t.keys.all { it == "clock" || it == "before" }
                NightWorkEarly -> false
                NightWork -> isTrue("clock") && "after" in t && t.keys.all { it == "clock" || it == "after" }
                BrokenTurnaround -> t.size == 1 && "less" in t
                MealPenalty -> isTrue("meal")
                MealCurtailed -> isTrue("meal_curtailed")
            }
        }

        /**
         * `classifyAgreementRow`: a union agreement's padded, per-day-type
         * trigger read by meaning — penalties first, the camera flag, day
         * numbers, day kinds, clocks, then plain OT; else the list's default.
         */
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun classify(row: JsonObject, list: RuleList?): RuleTemplate {
            val first = ((row["triggers"] as? JsonArray)?.firstOrNull() as? JsonObject)
            val t = meaningful(first)
            fun isTrue(key: String) = (t[key] as? JsonPrimitive)?.booleanOrNull == true
            val hasAfter = t["after"] != null
            val hasBefore = t["before"] != null
            val dayNumber = (t["day_number"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (isTrue("meal_curtailed")) return MealCurtailed
            if (isTrue("meal")) return MealPenalty
            val turnaround = t["less"] != null && t["day_number"] == null
            if (turnaround && !hasAfter && !isTrue("clock")) return BrokenTurnaround
            if (isTrue("camera") && hasAfter) return CameraOt
            if (dayNumber == SEVENTH_DAY) return if (hasAfter) OtSeventh else SeventhDay
            if (dayNumber == SIXTH_DAY) return if (hasAfter) OtSixth else SixthDay
            if (t["day_kind"] != null) return BankHoliday
            if (isTrue("clock") && hasAfter) return NightWork
            if (isTrue("clock") && hasBefore) return clockBefore(row)
            if (hasAfter) return Ot
            return when (list) {
                RuleList.Premiums -> SixthDay
                RuleList.Penalties -> MealPenalty
                else -> Ot
            }
        }

        /** Pre-dawn and early night work share a trigger; only the label or a flat rate tells them apart. */
        private fun clockBefore(row: JsonObject): RuleTemplate {
            val words = listOf("id", "raw_label", "label")
                .joinToString(" ") { key -> (row[key] as? JsonPrimitive)?.content.orEmpty() }.lowercase()
            return when {
                "night" in words -> NightWorkEarly
                Regex("pre|dawn").containsMatchIn(words) -> PreDawn
                (row["rate_type"] as? JsonPrimitive)?.content == "flat" -> NightWorkEarly
                else -> PreDawn
            }
        }

        /** Engine padding stripped: null, false, "" and the BDR bounds. Zero stays. */
        private fun meaningful(trigger: JsonObject?): Map<String, JsonElement> =
            trigger.orEmpty().filter { (key, value) ->
                key != "bdr_min" && key != "bdr_max" && value !is JsonNull &&
                    !((value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } ?: false) &&
                    !((value as? JsonPrimitive)?.let { it.isString && it.content.isEmpty() } ?: false)
            }
    }
}

/** `HH:MM` ↔ minutes past midnight, clamped to the day. */
object RuleClock {
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")
    private const val DAY_MINUTES = 1440
    private const val HOUR = 60

    fun format(minutes: Double?): String {
        val clamped = (minutes ?: 0.0).coerceIn(0.0, DAY_MINUTES.toDouble())
        val whole = floor(clamped).toInt()
        return "${(whole / HOUR).toString().padStart(2, '0')}:${(whole % HOUR).toString().padStart(2, '0')}"
    }

    fun parse(text: String): Int {
        val match = CLOCK.matchEntire(text.trim()) ?: return 0
        return (match.groupValues[1].toInt() * HOUR + match.groupValues[2].toInt()).coerceIn(0, DAY_MINUTES)
    }
}

/** A name preset: one pick sets the label, and optionally the rate and trigger. */
data class NamePreset(
    val label: String,
    val amount: Double? = null,
    val rateType: String? = null,
    val form: TriggerForm? = null,
)

/** The Name column's presets per rule type (`BulkRulesEditor.jsx:454-514`). */
object RuleNamePresets {

    private val OT_LADDER = listOf(1.5 to 8, 1.5 to 10, 2.0 to 11, 2.0 to 12, 3.0 to 13)
    private val TIMED_MULTIPLIERS = listOf(1.5, 2.0)

    private fun ladder(prefix: String) = OT_LADDER.map { (amount, hours) ->
        NamePreset(
            "$prefix ×${Js.number(amount)} after $hours hrs",
            amount,
            form = TriggerForm(hours = hours.toString()),
        )
    }

    private fun timed(times: List<String>, label: (String, String) -> String) = times.flatMap { time ->
        TIMED_MULTIPLIERS.map { amount ->
            NamePreset(label(Js.number(amount), time), amount, form = TriggerForm(time = time))
        }
    }

    private val PRESETS: Map<RuleTemplate, List<NamePreset>> = mapOf(
        RuleTemplate.Ot to ladder("OT"),
        RuleTemplate.CameraOt to ladder("Camera OT"),
        RuleTemplate.OtSixth to ladder("6th Day OT"),
        RuleTemplate.OtSeventh to ladder("7th Day OT"),
        RuleTemplate.SixthDay to listOf(NamePreset("6th Day Premium ×1.5"), NamePreset("6th Day Premium ×2", 2.0)),
        RuleTemplate.SeventhDay to listOf(NamePreset("7th Day Premium ×2"), NamePreset("7th Day Premium ×1.5", 1.5)),
        RuleTemplate.BankHoliday to listOf(NamePreset("Bank Holiday ×2", 2.0), NamePreset("Bank Holiday ×1.5")),
        RuleTemplate.PreDawn to timed(listOf("05:00", "05:30", "06:00", "06:30")) { a, t ->
            "Pre-Dawn Call ×$a (before $t)"
        },
        RuleTemplate.NightWorkEarly to listOf(NamePreset("Night Work — Early Call £25")),
        RuleTemplate.NightWork to timed(listOf("21:00", "22:00", "23:00")) { a, t -> "Night Work ×$a (from $t)" },
        RuleTemplate.BrokenTurnaround to listOf(
            NamePreset("Broken Turnaround £20", 20.0),
            NamePreset("Broken Turnaround £30", 30.0),
            NamePreset("Broken Turnaround 20%", 20.0, "percentage"),
            NamePreset("Broken Turnaround 30%", 30.0, "percentage"),
            NamePreset("Broken Turnaround ×0.5", 0.5, "multiplier"),
            NamePreset("Broken Turnaround ×0.25", 0.25, "multiplier"),
        ),
        RuleTemplate.MealPenalty to listOf(5.0, 5.5, 6.0).flatMap { hours ->
            val h = Js.number(hours)
            val form = TriggerForm(hours = h)
            listOf(
                NamePreset("Meal Penalty £9.50 ($h hrs)", 9.5, form = form),
                NamePreset("Meal Penalty ×1.5 ($h hrs)", 1.5, "multiplier", form),
                NamePreset("Meal Penalty ×2 ($h hrs)", 2.0, "multiplier", form),
            )
        },
        RuleTemplate.MealCurtailed to listOf(NamePreset("Meal Break Curtailed ×2")),
    )

    fun of(template: RuleTemplate?): List<NamePreset> = template?.let { PRESETS[it] }.orEmpty()
}

/** The grid's option lists. */
object RuleOptions {
    val RATE_TYPES: List<Pair<String, String>>
        get() = listOf(
            "multiplier" to str(S.desktop_multiplier_paren),
            "flat" to str(S.desktop_flat_amount),
            "percentage" to str(S.desktop_percentage_paren),
        )
    val BASES: List<Pair<String, String>>
        get() = listOf(
            "hour" to str(S.desktop_dm_hourly_rate),
            "day" to str(S.dm_rates_buyout_daily_rate),
            "week" to str(S.dm_rates_weekly_rate),
            "event" to str(S.desktop_per_event),
        )
    val DAY_TYPES: List<Pair<String, String>>
        get() = listOf("" to str(S.dm_rule_any_day), "SWD" to "SWD", "CWD" to "CWD", "SCWD" to "SCWD")
    val INCREMENTS = listOf(5, 10, 15, 30, 45)
    val DAY_KINDS: List<Pair<String, String>>
        get() = listOf(
            "bank_holiday" to str(S.desktop_bank_holiday),
            "statutory_holiday" to str(S.desktop_statutory_holiday),
            "public_holiday" to str(S.desktop_public_holiday),
            "saturday" to str(S.day_saturday),
            "sunday" to str(S.day_sunday),
            "idle" to str(S.desktop_idle_day),
            "studio_day" to str(S.desktop_studio_day),
            "distant_day" to str(S.desktop_distant_day),
        )
}
