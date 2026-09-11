package com.zillit.desktop.feature.accounthub.domain

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
    val group: String,
    val label: String,
    val helper: String,
    val field: PayRuleField,
    val defaultRateType: PayRateType,
    val defaultRateAmount: String,
    val defaultBasis: PayRateBasis,
) {
    OvertimeSixthDay(
        "ot_6th", "Overtime", "Overtime on the 6th day",
        "An hours threshold on the 6th consecutive worked day.",
        PayRuleField.Hours, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    OvertimeSeventhDay(
        "ot_7th", "Overtime", "Overtime on the 7th day",
        "An hours threshold on the 7th consecutive worked day.",
        PayRuleField.Hours, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    Overtime(
        "ot", "Overtime", "Overtime after a number of hours",
        "The plain hours threshold, on any shift.",
        PayRuleField.Hours, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    CameraOvertime(
        "camera_ot", "Overtime", "Camera overtime after a number of hours",
        "As above, but for camera crew only.",
        PayRuleField.Hours, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    SixthDay(
        "sixth_day", "Day premiums", "6th day",
        "Fires on the 6th consecutive worked day, with no hours threshold.",
        PayRuleField.None, PayRateType.Multiplier, "1.5", PayRateBasis.Day,
    ),
    SeventhDay(
        "seventh_day", "Day premiums", "7th day",
        "Fires on the 7th consecutive worked day.",
        PayRuleField.None, PayRateType.Multiplier, "2", PayRateBasis.Day,
    ),
    BankHoliday(
        "bank_holiday", "Day premiums", "Holiday or weekend",
        "Fires on the day kinds you choose.",
        PayRuleField.DayKinds, PayRateType.Multiplier, "1.5", PayRateBasis.Day,
    ),
    PreDawn(
        "pre_dawn", "Time premiums", "Pre-dawn or early call",
        "Fires when the shift starts before a clock time.",
        PayRuleField.Clock, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    NightWorkEarlyCall(
        "night_work_early", "Time premiums", "Night work — early unit call",
        "The same condition as pre-dawn, usually paired with a flat amount per event.",
        PayRuleField.Clock, PayRateType.Flat, "25", PayRateBasis.Event,
    ),
    NightWork(
        "night_work", "Time premiums", "Night work",
        "Fires when work runs past a clock time.",
        PayRuleField.Clock, PayRateType.Multiplier, "1.5", PayRateBasis.Hour,
    ),
    BrokenTurnaround(
        "broken_turnaround", "Penalties", "Broken turnaround",
        "Fires when the rest between shifts is shorter than this.",
        PayRuleField.Hours, PayRateType.Flat, "900", PayRateBasis.Event,
    ),
    MealPenalty(
        "meal_penalty", "Penalties", "Meal penalty",
        "Fires when a meal is not provided within this long of the call.",
        PayRuleField.Hours, PayRateType.Flat, "9.5", PayRateBasis.Event,
    ),
    MealCurtailed(
        "meal_curtailed", "Penalties", "Meal break curtailed",
        "Fires when the break taken was shorter than the contract allows.",
        PayRuleField.None, PayRateType.Multiplier, "2", PayRateBasis.Hour,
    ),
    ;

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
        val keys = trigger.meaningfulKeys
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
        val kept = PayTrigger(bdrMin = carrying.bdrMin, bdrMax = carrying.bdrMax)
        val minutes = hoursToMinutes(hours)
        val time = clockToMinutes(clock)
        return when (this) {
            OvertimeSixthDay -> kept.copy(dayNumber = SIXTH, consecutive = true, afterMinutes = minutes)
            OvertimeSeventhDay -> kept.copy(dayNumber = SEVENTH, consecutive = true, afterMinutes = minutes)
            Overtime -> kept.copy(afterMinutes = minutes)
            CameraOvertime ->
                kept.copy(afterMinutes = minutes, camera = true, incrementMinutes = CAMERA_INCREMENT)
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

    companion object {
        private const val SIXTH = 6
        private const val SEVENTH = 7

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
