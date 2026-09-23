package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val CAMERA_INCREMENT = 15

/** What a template asks for, beyond the rate itself. */
enum class PayRuleField {
    /** Nothing — the condition is fully described by the template. */
    None,

    /** A number of hours, stored as minutes. */
    Hours,

    /** A clock time, stored as minutes from midnight. */
    Clock,

    /** One or more day kinds. */
    DayKinds,
}

/**
 * The conditions a non-union rule can fire on.
 *
 * A closed catalogue, not a free trigger editor. The web shipped a raw-JSON
 * "custom" option and withdrew it as too easy to get wrong — multi-entry
 * arrays of engine-specific keys — and the same reasoning applies here with
 * more force, because a wrong condition here silently pays the wrong amount.
 *
 * Each template knows three things: how to recognise a stored condition
 * ([matches]), how to read its form value back out ([hoursFrom] and friends),
 * and how to build one ([trigger]). Durations are minutes throughout.
 */
@Suppress("LongParameterList") // A catalogue row; every field is data.
enum class PayRuleTemplate(
    val id: String,
    private val groupKey: String,
    private val labelKey: String,
    private val helperKey: String,
    val field: PayRuleField,
    val defaultRateType: PayRateType,
    val defaultRateAmount: String,
    val defaultBasis: PayRateBasis,
) {
    OvertimeSixthDay(
        "ot_6th", S.overtime, S.desktop_hub_overtime_on_the_6th_day,
        S.desktop_hub_an_hours_threshold_on_the_6th_consecutive_worked_day,
        PayRuleField.Hours, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    OvertimeSeventhDay(
        "ot_7th", S.overtime, S.desktop_hub_overtime_on_the_7th_day,
        S.desktop_hub_an_hours_threshold_on_the_7th_consecutive_worked_day,
        PayRuleField.Hours, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    Overtime(
        "ot", S.overtime, S.desktop_hub_overtime_after_a_number_of_hours,
        S.desktop_hub_the_plain_hours_threshold_on_any_shift,
        PayRuleField.Hours, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    CameraOvertime(
        "camera_ot", S.overtime, S.desktop_hub_camera_overtime_after_a_number_of_hours,
        S.desktop_hub_as_above_but_for_camera_crew_only,
        PayRuleField.Hours, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    SixthDay(
        "sixth_day", S.desktop_day_premiums, S.desktop_6th_day,
        S.desktop_hub_fires_on_the_6th_consecutive_worked_day_with_no_hours,
        PayRuleField.None, PayRateType.Multiplier, "1.5", PayRateBasis.Day,
    ),
    SeventhDay(
        "seventh_day", S.desktop_day_premiums, S.desktop_7th_day,
        S.desktop_hub_fires_on_the_7th_consecutive_worked_day,
        PayRuleField.None, PayRateType.Multiplier, "2", PayRateBasis.Day,
    ),
    BankHoliday(
        "bank_holiday", S.desktop_day_premiums, S.desktop_holiday_or_weekend,
        S.desktop_hub_fires_on_the_day_kinds_you_choose,
        PayRuleField.DayKinds, PayRateType.Multiplier, "1.5", PayRateBasis.Day,
    ),
    PreDawn(
        "pre_dawn", S.desktop_time_premiums, S.desktop_hub_pre_dawn_or_early_call,
        S.desktop_hub_fires_when_the_shift_starts_before_a_clock_time,
        PayRuleField.Clock, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    NightWorkEarlyCall(
        "night_work_early", S.desktop_time_premiums, S.desktop_hub_night_work_early_unit_call,
        S.desktop_hub_the_same_condition_as_pre_dawn_usually_paired_with_a,
        PayRuleField.Clock, PayRateType.Flat, "25", PayRateBasis.Event,
    ),
    NightWork(
        "night_work", S.desktop_time_premiums, S.desktop_night_work,
        S.desktop_hub_fires_when_work_runs_past_a_clock_time,
        PayRuleField.Clock, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    BrokenTurnaround(
        "broken_turnaround", S.dm_rates_penalties, S.desktop_broken_turnaround,
        S.desktop_hub_fires_when_the_rest_between_shifts_is_shorter_than_this,
        PayRuleField.Hours, PayRateType.Flat, "900", PayRateBasis.Event,
    ),
    MealPenalty(
        "meal_penalty", S.dm_rates_penalties, S.desktop_meal_penalty,
        S.desktop_hub_fires_when_a_meal_is_not_provided_within_this_long,
        PayRuleField.Hours, PayRateType.Flat, "9.5", PayRateBasis.Event,
    ),
    MealCurtailed(
        "meal_curtailed", S.dm_rates_penalties, S.desktop_meal_break_curtailed,
        S.desktop_hub_fires_when_the_break_taken_was_shorter_than_the_contract,
        PayRuleField.None, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    ;

    val group: String get() = str(groupKey)
    val label: String get() = str(labelKey)
    val helper: String get() = str(helperKey)

    /**
     * Whether [trigger] is this template's shape.
     *
     * Written against the meaningful keys only — see [PayTrigger.meaningfulKeys].
     * Order matters when several could match, which is why [of] walks the
     * entries in declaration order, most specific first.
     */
    @Suppress("CyclomaticComplexMethod")
    // One branch per template, deliberately in one place: these conditions
    // only make sense read against each other — several overlap, and the
    // order they are tried in is what separates them. Split across thirteen
    // functions, the next person to add a template cannot see what it has to
    // be narrower than.
    fun matches(trigger: PayTrigger): Boolean {
        // `increment` is a billing gate like the BDR pair — "Bill in
        // increments" on any rule — not part of what the condition is. Matched
        // on, an overtime billed in 15-minute steps stopped reading as
        // overtime, and the editor fell back to the list's default template.
        val keys = trigger.meaningfulKeys - "increment"
        val plainShift = !trigger.weekly && !trigger.clock && !trigger.meal && !trigger.mealCurtailed
        return when (this) {
            OvertimeSixthDay ->
                trigger.dayNumber == SIXTH && trigger.consecutive && "after" in keys && plainShift
            OvertimeSeventhDay ->
                trigger.dayNumber == SEVENTH && trigger.consecutive && "after" in keys && plainShift
            // Nothing but a bare hours threshold: any other key means one of
            // the more specific templates above owns this condition.
            Overtime -> keys == setOf("after")
            CameraOvertime ->
                trigger.camera && "after" in keys && plainShift &&
                    "less" !in keys && "day_number" !in keys
            SixthDay -> trigger.dayNumber == SIXTH && trigger.consecutive && "after" !in keys && plainShift
            SeventhDay -> trigger.dayNumber == SEVENTH && trigger.consecutive && "after" !in keys && plainShift
            BankHoliday -> keys == setOf("day_kind")
            PreDawn -> keys == setOf("clock", "before")
            // The same shape as pre-dawn, so it can never be recognised from a
            // stored rule — it is offered when writing one, and reads back as
            // pre-dawn. The web has the same overlap and resolves it the same
            // way; the rate is what tells the two apart in practice.
            NightWorkEarlyCall -> false
            NightWork -> keys == setOf("clock", "after")
            BrokenTurnaround -> keys == setOf("less")
            MealPenalty -> trigger.meal
            MealCurtailed -> trigger.mealCurtailed
        }
    }

    /** The hours in this template's field, as typed. */
    fun hoursFrom(trigger: PayTrigger): String {
        val minutes = when (this) {
            BrokenTurnaround -> trigger.lessMinutes
            else -> trigger.afterMinutes
        }
        return minutes?.let { asHoursText(it) }.orEmpty()
    }

    /** The clock time in this template's field, as `HH:MM`. */
    fun clockFrom(trigger: PayTrigger): String {
        val minutes = when (this) {
            PreDawn, NightWorkEarlyCall -> trigger.beforeMinutes
            else -> trigger.afterMinutes
        }
        return asClockText(minutes ?: 0)
    }

    /**
     * The condition this template builds.
     *
     * [carrying] keeps the rule-level BDR gates, which are stored on the same
     * object but are not part of the condition.
     */
    fun trigger(hours: String, clock: String, dayKinds: List<PayDayKind>, carrying: PayTrigger): PayTrigger {
        // The billing increment is carried too (the web's `modelFromBulkRow`
        // keeps it on the trigger): editing a threshold used to drop it, and
        // the rule then billed actual minutes.
        val kept = PayTrigger(
            bdrMin = carrying.bdrMin,
            bdrMax = carrying.bdrMax,
            incrementMinutes = carrying.incrementMinutes,
        )
        val minutes = hoursToMinutes(hours)
        val time = clockToMinutes(clock)
        return when (this) {
            OvertimeSixthDay -> kept.copy(dayNumber = SIXTH, consecutive = true, afterMinutes = minutes)
            OvertimeSeventhDay -> kept.copy(dayNumber = SEVENTH, consecutive = true, afterMinutes = minutes)
            Overtime -> kept.copy(afterMinutes = minutes)
            CameraOvertime -> kept.copy(
                afterMinutes = minutes,
                camera = true,
                incrementMinutes = kept.incrementMinutes ?: CAMERA_INCREMENT,
            )
            SixthDay -> kept.copy(dayNumber = SIXTH, consecutive = true)
            SeventhDay -> kept.copy(dayNumber = SEVENTH, consecutive = true)
            BankHoliday -> kept.copy(dayKinds = dayKinds)
            PreDawn, NightWorkEarlyCall -> kept.copy(clock = true, beforeMinutes = time)
            NightWork -> kept.copy(clock = true, afterMinutes = time)
            BrokenTurnaround -> kept.copy(lessMinutes = minutes)
            MealPenalty -> kept.copy(meal = true, afterMinutes = minutes)
            MealCurtailed -> kept.copy(mealCurtailed = true)
        }
    }

    /**
     * The condition's field as the web's `defaultForm` fills it for a new rule
     * or a switched type — 8 h overtime, 06:00 pre-dawn, a bank holiday.
     *
     * Blank would build a zero threshold, and an overtime "after 0 hours"
     * pays every hour worked.
     */
    val defaultText: String
        get() = when (this) {
            OvertimeSixthDay, OvertimeSeventhDay -> "0"
            Overtime -> "8"
            CameraOvertime -> "11"
            PreDawn -> "06:00"
            NightWorkEarlyCall -> "05:00"
            NightWork -> "22:00"
            BrokenTurnaround -> "10"
            MealPenalty -> "6"
            SixthDay, SeventhDay, BankHoliday, MealCurtailed -> ""
        }

    /** The day kinds a new rule of this type starts with — a bank holiday, for that template. */
    val defaultDayKinds: List<PayDayKind>
        get() = if (this == BankHoliday) listOf(PayDayKind.BankHoliday) else emptyList()

    /**
     * This template's condition at its defaults, carrying [carrying]'s gates
     * (increment, BDR) across — what a new rule or a type switch starts from.
     */
    fun defaultTrigger(carrying: PayTrigger = PayTrigger()): PayTrigger =
        conditionFrom(defaultText, defaultDayKinds, carrying)

    /** The condition built from the one text field this template shows. */
    fun conditionFrom(text: String, dayKinds: List<PayDayKind>, carrying: PayTrigger): PayTrigger = when (field) {
        PayRuleField.Clock -> trigger(hours = "", clock = text, dayKinds = dayKinds, carrying = carrying)
        else -> trigger(hours = text, clock = "", dayKinds = dayKinds, carrying = carrying)
    }

    /** The text the condition field shows for [trigger] — hours, a clock time, or nothing. */
    fun conditionText(trigger: PayTrigger): String = when (field) {
        PayRuleField.Hours -> hoursFrom(trigger)
        PayRuleField.Clock -> clockFrom(trigger)
        PayRuleField.DayKinds, PayRuleField.None -> ""
    }

    /**
     * Whether [text] still describes [trigger] — the field is left alone while
     * it does, so "5." and "06:0" survive the keystroke that typed them.
     */
    fun textDescribes(text: String, trigger: PayTrigger): Boolean = when (field) {
        PayRuleField.Hours -> hoursToMinutes(text) == (fieldMinutes(trigger) ?: 0)
        PayRuleField.Clock -> clockToMinutes(text) == (fieldMinutes(trigger) ?: 0)
        PayRuleField.DayKinds, PayRuleField.None -> true
    }

    /** The one stored number this template's field edits. */
    private fun fieldMinutes(trigger: PayTrigger): Int? = when (this) {
        BrokenTurnaround -> trigger.lessMinutes
        PreDawn, NightWorkEarlyCall -> trigger.beforeMinutes
        else -> trigger.afterMinutes
    }

    /**
     * Why the typed condition cannot be saved, or null. A clock must read as
     * `HH:MM` and hours as a number: both used to fall back to zero, and a
     * half-typed "06:0" saved a midnight premium.
     */
    fun conditionProblem(text: String): String? = when (field) {
        PayRuleField.Hours ->
            str(S.desktop_hub_pay_rule_hours_invalid).takeIf { text.trim().toDoubleOrNull()?.let { it < 0 } != false }
        PayRuleField.Clock -> str(S.desktop_hub_pay_rule_time_invalid).takeIf { !CLOCK.matches(text.trim()) }
        PayRuleField.DayKinds, PayRuleField.None -> null
    }

    companion object {
        private const val SIXTH = 6
        private const val SEVENTH = 7

        /** `H:MM` or `HH:MM`, 00:00–24:00. */
        private val CLOCK = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$|^24:00$")

        /**
         * Which template a stored condition came from, or null when none fits.
         *
         * Null and "the list's default" are different facts: a rule nothing
         * recognises keeps its condition on screen but would lose it if saved
         * through a template, so the caller has to be able to tell.
         */
        fun of(trigger: PayTrigger?): PayRuleTemplate? {
            if (trigger == null || trigger.isEmpty) return null
            return entries.firstOrNull { it.matches(trigger) }
        }

        /** What a new rule in [kind] starts as. */
        fun defaultFor(kind: PayRuleKind): PayRuleTemplate = when (kind) {
            PayRuleKind.Overtimes -> Overtime
            PayRuleKind.Premiums -> SixthDay
            PayRuleKind.Penalties -> MealPenalty
        }

        fun hoursToMinutes(hours: String): Int =
            ((hours.trim().toDoubleOrNull() ?: 0.0) * MINUTES_PER_HOUR).toInt()

        /** Trailing `.0` dropped: eight hours reads as 8, not 8.0. */
        fun asHoursText(minutes: Int): String {
            val hours = minutes.toDouble() / MINUTES_PER_HOUR
            return if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()
        }

        /** `HH:MM` to minutes from midnight; anything unparseable is midnight. */
        fun clockToMinutes(text: String): Int {
            val parts = text.trim().split(':')
            if (parts.size != 2) return 0
            val hours = parts[0].toIntOrNull() ?: return 0
            val minutes = parts[1].takeIf { it.length == 2 }?.toIntOrNull() ?: return 0
            return (hours * MINUTES_PER_HOUR + minutes).coerceIn(0, MINUTES_PER_DAY)
        }

        fun asClockText(minutes: Int): String {
            val clamped = minutes.coerceIn(0, MINUTES_PER_DAY)
            val hours = clamped / MINUTES_PER_HOUR
            val rest = clamped % MINUTES_PER_HOUR
            return "${hours.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        }
    }
}
