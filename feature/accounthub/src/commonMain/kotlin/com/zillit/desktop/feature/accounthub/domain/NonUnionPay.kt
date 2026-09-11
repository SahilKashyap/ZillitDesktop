package com.zillit.desktop.feature.accounthub.domain

/** Which of the three lists a rule belongs to. */
enum class PayRuleKind(val wire: String, val label: String, val helper: String) {
    Overtimes("overtimes", "Overtimes", "Rates that fire on a worked-hours or worked-days condition."),
    Premiums("premiums", "Premiums", "Additive pay for a shift type — night, hazardous, holiday."),
    Penalties("penalties", "Penalties", "One-off charges for forced calls, missed meals, broken turnaround."),
}

/** How a rule's amount is applied to the base rate. */
enum class PayRateType(val wire: String, val label: String) {
    Multiplier("multiplier", "Multiplier (×)"),
    Flat("flat", "Flat amount"),
    Percentage("percentage", "Percentage (%)"),
    ;

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
enum class PayRateBasis(val wire: String, val label: String) {
    Hour("hour", "Hourly rate"),
    Day("day", "Daily rate"),
    Week("week", "Weekly rate"),
    Event("event", "Per event"),
    ;

    companion object {
        fun from(wire: String?): PayRateBasis = entries.firstOrNull { it.wire == wire } ?: Hour
    }
}

/** The day kinds the engine recognises. */
enum class PayDayKind(val wire: String, val label: String) {
    BankHoliday("bank_holiday", "Bank holiday"),
    StatutoryHoliday("statutory_holiday", "Statutory holiday"),
    PublicHoliday("public_holiday", "Public holiday"),
    Saturday("saturday", "Saturday"),
    Sunday("sunday", "Sunday"),
    Idle("idle", "Idle day"),
    Studio("studio_day", "Studio day"),
    Distant("distant_day", "Distant day"),
    ;

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
    val isEnhancement: Boolean = false,
    val nominalCode: String = "",
    val note: String = "",
    val appliesTo: String = "",
) {
    /** The single entry a template edits, or null for a rule with several. */
    val singleTrigger: PayTrigger? get() = triggers.singleOrNull()
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
enum class PayApplyMode(val wire: String?, val label: String) {
    Unset(null, "Not chosen"),
    All("all", "Everyone on the production"),
    Departments("departments", "Chosen departments"),
    ;

    companion object {
        fun from(wire: String?): PayApplyMode =
            entries.firstOrNull { it.wire != null && it.wire == wire } ?: Unset
    }
}
