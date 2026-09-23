package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Which of the three lists a rule belongs to. */
enum class PayRuleKind(val wire: String, private val labelKey: String, private val helperKey: String) {
    Overtimes("overtimes", S.dm_rates_overtimes, S.desktop_hub_rates_that_fire_on_a_worked_hours_or_worked_days),
    Premiums("premiums", S.dm_rates_premiums, S.desktop_hub_additive_pay_for_a_shift_type_night_hazardous_holiday),
    Penalties(
        "penalties",
        S.dm_rates_penalties,
        S.desktop_hub_one_off_charges_for_forced_calls_missed_meals_broken_turnaround,
    ),
    ;

    val label: String get() = str(labelKey)
    val helper: String get() = str(helperKey)
}

/** How a rule's amount is applied to the base rate. */
enum class PayRateType(val wire: String, private val labelKey: String) {
    Multiplier("multiplier", S.desktop_multiplier_paren),
    Flat("flat", S.desktop_flat_amount),
    Percentage("percentage", S.desktop_percentage_paren),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PayRateType = entries.firstOrNull { it.wire == wire } ?: Multiplier
    }
}

/**
 * The rate tier an amount applies on top of.
 *
 * The wire values are unchanged from the union agreement files so the overtime
 * engine reads either source the same way; only the labels were rewritten to
 * read as a tier rather than a cadence.
 */
enum class PayRateBasis(val wire: String, private val labelKey: String) {
    Hour("hour", S.desktop_hourly_rate),
    Day("day", S.dm_rates_buyout_daily_rate),
    Week("week", S.dm_rates_weekly_rate),
    Event("event", S.desktop_per_event),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PayRateBasis = entries.firstOrNull { it.wire == wire } ?: Hour
    }
}

/** The day kinds the engine recognises. */
enum class PayDayKind(val wire: String, private val labelKey: String) {
    BankHoliday("bank_holiday", S.desktop_bank_holiday),
    StatutoryHoliday("statutory_holiday", S.desktop_statutory_holiday),
    PublicHoliday("public_holiday", S.desktop_public_holiday),
    Saturday("saturday", S.day_saturday),
    Sunday("sunday", S.day_sunday),
    Idle("idle", S.desktop_idle_day),
    Studio("studio_day", S.desktop_studio_day),
    Distant("distant_day", S.desktop_distant_day),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PayDayKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The condition that fires a rule.
 *
 * Every duration is **minutes**, including clock times, which are minutes from
 * midnight. That is the engine's unit, and converting at the edges is what
 * keeps a threshold typed as "8 hours" from arriving as eight minutes.
 *
 * The server persists each entry with the full canonical key set, most padded
 * to null or false. The padding is meaningless, and [meaningfulKeys] is what
 * template matching reads instead — with one exception that matters: **zero is
 * a real value**. A rest period of `less: 0` is not an absent rest period, and
 * dropping it once mis-tagged Broken Turnaround as a Meal Penalty on the web.
 *
 * [bdrMin] and [bdrMax] ride along on the same object but are rule-level
 * gates, not part of what the condition *is*, so they never take part in
 * matching. They are carried so a round trip does not lose them.
 */
data class PayTrigger(
    val afterMinutes: Int? = null,
    val beforeMinutes: Int? = null,
    val lessMinutes: Int? = null,
    val dayNumber: Int? = null,
    val consecutive: Boolean = false,
    val dayKinds: List<PayDayKind> = emptyList(),
    val clock: Boolean = false,
    val meal: Boolean = false,
    val mealCurtailed: Boolean = false,
    val camera: Boolean = false,
    val weekly: Boolean = false,
    val incrementMinutes: Int? = null,
    val bdrMin: Double? = null,
    val bdrMax: Double? = null,
) {
    /**
     * The keys that carry meaning, for template matching.
     *
     * A padded null or false is absent; a zero is present.
     */
    val meaningfulKeys: Set<String>
        get() = buildSet {
            if (afterMinutes != null) add("after")
            if (beforeMinutes != null) add("before")
            if (lessMinutes != null) add("less")
            if (dayNumber != null) add("day_number")
            if (consecutive) add("consecutive")
            if (dayKinds.isNotEmpty()) add("day_kind")
            if (clock) add("clock")
            if (meal) add("meal")
            if (mealCurtailed) add("meal_curtailed")
            if (camera) add("camera")
            if (weekly) add("weekly")
            if (incrementMinutes != null) add("increment")
        }

    val isEmpty: Boolean get() = meaningfulKeys.isEmpty()
}

/**
 * One pay rule.
 *
 * [triggers] is a list because the wire is: keys AND within an entry, entries
 * OR across them. This editor writes exactly one, which is what every template
 * produces — a stored rule with several is read and shown, and saving it
 * through a template replaces the lot, which is the same trade the web makes.
 */
data class PayRule(
    val id: String = "",
    val label: String = "",
    val rateType: PayRateType = PayRateType.Multiplier,
    /** Blank while empty — an unset rate is not a rate of zero. */
    val rateAmount: String = "",
    val basis: PayRateBasis = PayRateBasis.Hour,
    val triggers: List<PayTrigger> = emptyList(),
    /**
     * "Basic + OT on top": the amount is paid over the base rather than in
     * place of it. The web's rules editor starts a new rule at false.
     */
    val isEnhancement: Boolean = false,
    val nominalCode: String = "",
    val note: String = "",
    val appliesTo: String = "",
    /**
     * Clip the computed payout to [capAmount] per matched window.
     *
     * `cap_type` is the string `capped`/`uncapped` on the wire, as on a
     * rental; a rule read without the key is uncapped.
     */
    val capped: Boolean = false,
    val capAmount: String = "",
    /** The day type this rule reads against, by code; blank means any. */
    val dayType: String = "",
) {
    /** The single entry a template edits, or null for a rule with several. */
    val singleTrigger: PayTrigger? get() = triggers.singleOrNull()

    /** The first trigger's increment, which the grid shows as "OT Increment". */
    val incrementMinutes: Int? get() = triggers.firstOrNull()?.incrementMinutes

    val bdrMin: Double? get() = triggers.firstOrNull()?.bdrMin

    val bdrMax: Double? get() = triggers.firstOrNull()?.bdrMax

    /** The same rule with one trigger-level gate changed on every entry. */
    fun withTriggerGates(increment: Int?, bdrMin: Double?, bdrMax: Double?): PayRule = copy(
        triggers = triggers.ifEmpty { listOf(PayTrigger()) }
            .map { it.copy(incrementMinutes = increment, bdrMin = bdrMin, bdrMax = bdrMax) },
    )
}

/** The production's own overtime, premium and penalty rules. */
data class NonUnionPay(
    val overtimes: List<PayRule> = emptyList(),
    val premiums: List<PayRule> = emptyList(),
    val penalties: List<PayRule> = emptyList(),
    /**
     * Who these rules apply to.
     *
     * Section-level, not per rule: it scopes every overtime, premium and
     * penalty in the breakdown at once.
     */
    val applyMode: PayApplyMode = PayApplyMode.Unset,
    /** The departments, when [applyMode] is [PayApplyMode.Departments]. */
    val departmentIds: List<String> = emptyList(),
) {
    fun rulesFor(kind: PayRuleKind): List<PayRule> = when (kind) {
        PayRuleKind.Overtimes -> overtimes
        PayRuleKind.Premiums -> premiums
        PayRuleKind.Penalties -> penalties
    }

    fun withRules(kind: PayRuleKind, rules: List<PayRule>): NonUnionPay = when (kind) {
        PayRuleKind.Overtimes -> copy(overtimes = rules)
        PayRuleKind.Premiums -> copy(premiums = rules)
        PayRuleKind.Penalties -> copy(penalties = rules)
    }

    /** Applies these rules to the whole production, clearing any department list. */
    fun appliedToEveryone(): NonUnionPay =
        copy(applyMode = PayApplyMode.All, departmentIds = emptyList())

    /**
     * Applies them to named departments.
     *
     * Choosing departments is what sets the mode; it is never inferred from
     * the list being non-empty. A production that picked departments and then
     * removed them all has still chosen "departments", and re-reading that as
     * "everyone" would quietly widen who gets paid the rule.
     */
    fun appliedTo(departments: List<String>): NonUnionPay =
        copy(applyMode = PayApplyMode.Departments, departmentIds = departments.distinct())

    /** Whether the rules reach somebody. A department scope with none does not. */
    val appliesToNobody: Boolean
        get() = applyMode == PayApplyMode.Departments && departmentIds.isEmpty()
}

/**
 * Who a non-union breakdown's rules apply to.
 *
 * [Unset] is the pristine state — nobody has chosen yet — and it is *not* a
 * third behaviour: the engine treats it exactly as [All]. It exists so the
 * screen can show neither choice as picked rather than claiming a decision
 * nobody made.
 */
enum class PayApplyMode(val wire: String?, private val labelKey: String) {
    Unset(null, S.desktop_not_chosen),
    All("all", S.desktop_hub_everyone_on_the_production),
    Departments("departments", S.desktop_chosen_departments),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PayApplyMode =
            entries.firstOrNull { it.wire != null && it.wire == wire } ?: Unset
    }
}
