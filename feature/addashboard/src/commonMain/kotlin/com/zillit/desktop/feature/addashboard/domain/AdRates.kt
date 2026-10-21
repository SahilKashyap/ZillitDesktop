package com.zillit.desktop.feature.addashboard.domain

/**
 * Rates & Config: the production-wide setup the AD does once.
 *
 * One document per project, with one Save — the non-union document plus one
 * document per union agreement, all written by a single `PATCH
 * /ad-rate-config`. The server's pay engine reads these to compute each
 * artiste's day gross, and the agency list decides which agencies can receive
 * a purchase order when a day is submitted.
 */

/** How a pay rule's amount is expressed. */
enum class RateKind(val wire: String, val label: String) {
    Multiplier("multiplier", "Multiplier (×)"),
    Flat("flat", "Flat amount"),
    Percentage("percentage", "Percentage (%)"),
    ;

    companion object {
        fun from(wire: String?): RateKind {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Multiplier
        }
    }
}

/** What a rule's amount is a rate *of*. */
enum class RateBasis(val wire: String, val label: String, val short: String) {
    Hour("hour", "Hourly rate", "hr"),
    Day("day", "Daily rate", "day"),
    Week("week", "Weekly rate", "wk"),
    Event("event", "Per event", "event"),
    ;

    companion object {
        fun from(wire: String?): RateBasis {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Hour
        }
    }
}

/** Which of the three lists a rule belongs to. */
enum class PayRuleGroup(val wire: String, val label: String) {
    Overtimes("overtimes", "Overtimes"),
    Premiums("premiums", "Premiums"),
    Penalties("penalties", "Penalties"),
    ;

    /** The template a freshly added rule starts on, per the web's `defaultTemplateForList`. */
    val defaultTemplate: String
        get() = when (this) {
            Overtimes -> "ot"
            Premiums -> "sixth_day"
            Penalties -> "meal_penalty"
        }
}

/**
 * One of the twelve rule templates, which is what turns a short form into the
 * `triggers` array the pay engine reads.
 *
 * The template's id rides in the saved rule's `source.id`, so a stored rule
 * round-trips without an extra key: [PayRuleTemplates.triggerFor] writes the
 * trigger and [PayRuleTemplates.formFrom] reads it back.
 */
data class PayRuleTemplate(
    val id: String,
    val group: PayRuleGroup,
    val label: String,
    val helper: String,
    /** Which single field the form shows, if any. */
    val field: PayRuleField = PayRuleField.None,
    val defaultValue: String = "",
)

/** The one input a rule template asks for. */
enum class PayRuleField { None, Hours, Clock, DayKinds }

/** One holiday or weekend kind a day-based premium may fire on. */
enum class DayKind(val wire: String, val label: String) {
    BankHoliday("bank_holiday", "Bank holiday"),
    StatutoryHoliday("statutory_holiday", "Statutory holiday"),
    PublicHoliday("public_holiday", "Public holiday"),
    Saturday("saturday", "Saturday"),
    Sunday("sunday", "Sunday"),
    Idle("idle", "Idle day"),
    StudioDay("studio_day", "Studio day"),
    DistantDay("distant_day", "Distant day"),
    ;

    companion object {
        fun from(wire: String?): DayKind? {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value }
        }
    }
}

/**
 * The rule catalogue, and the trigger codec either way.
 *
 * Ported verbatim from the web's `RULE_TEMPLATES` / `FROM_TRIGGER`, which
 * themselves mirror the production-setup section — the two must agree or a
 * rule saved in one place reads as a different rule in the other.
 */
object PayRuleTemplates {

    private const val MINUTES_PER_HOUR = 60
    private const val MAX_MINUTES_OF_DAY = 1440

    val all: List<PayRuleTemplate> = listOf(
        PayRuleTemplate(
            id = "ot_6th",
            group = PayRuleGroup.Overtimes,
            label = "OT 6th Day",
            helper = "Overtime threshold on the 6th consecutive worked day.",
            field = PayRuleField.Hours,
            defaultValue = "0",
        ),
        PayRuleTemplate(
            id = "ot_7th",
            group = PayRuleGroup.Overtimes,
            label = "OT 7th Day",
            helper = "Overtime threshold on the 7th consecutive worked day.",
            field = PayRuleField.Hours,
            defaultValue = "0",
        ),
        PayRuleTemplate(
            id = "ot",
            group = PayRuleGroup.Overtimes,
            label = "OT — after N hours worked",
            helper = "Generic overtime after a worked-hours threshold per shift.",
            field = PayRuleField.Hours,
            defaultValue = "8",
        ),
        PayRuleTemplate(
            id = "sixth_day",
            group = PayRuleGroup.Premiums,
            label = "6th Day",
            helper = "Fires whenever the 6th consecutive worked day occurs (no hours threshold).",
        ),
        PayRuleTemplate(
            id = "seventh_day",
            group = PayRuleGroup.Premiums,
            label = "7th Day",
            helper = "Fires whenever the 7th consecutive worked day occurs.",
        ),
        PayRuleTemplate(
            id = "bank_holiday",
            group = PayRuleGroup.Premiums,
            label = "Bank Holiday",
            helper = "Fires on selected holiday / weekend types.",
            field = PayRuleField.DayKinds,
            defaultValue = DayKind.BankHoliday.wire,
        ),
        PayRuleTemplate(
            id = "pre_dawn",
            group = PayRuleGroup.Premiums,
            label = "Pre-Dawn / Early Call",
            helper = "Fires when the shift starts before a given clock time.",
            field = PayRuleField.Clock,
            defaultValue = "06:00",
        ),
        PayRuleTemplate(
            id = "night_work_early",
            group = PayRuleGroup.Premiums,
            label = "Night Work — Early Unit Call",
            helper = "Premium for unit calls before a given clock time.",
            field = PayRuleField.Clock,
            defaultValue = "05:00",
        ),
        PayRuleTemplate(
            id = "night_work",
            group = PayRuleGroup.Premiums,
            label = "Night Work",
            helper = "Fires when working past a given clock time (e.g. after 22:00).",
            field = PayRuleField.Clock,
            defaultValue = "22:00",
        ),
        PayRuleTemplate(
            id = "broken_turnaround",
            group = PayRuleGroup.Penalties,
            label = "Broken Turnaround",
            helper = "Fires when rest period between shifts is less than N hours.",
            field = PayRuleField.Hours,
            defaultValue = "10",
        ),
        PayRuleTemplate(
            id = "meal_penalty",
            group = PayRuleGroup.Penalties,
            label = "Meal Penalty",
            helper = "Fires when a meal isn't provided within N hours of call.",
            field = PayRuleField.Hours,
            defaultValue = "6",
        ),
        PayRuleTemplate(
            id = "meal_curtailed",
            group = PayRuleGroup.Penalties,
            label = "Meal Break Curtailed",
            helper = "Fires when the meal break taken was shorter than the contractual minimum.",
        ),
    )

    /** An unknown template id falls to the first, as the web's `templateById` does. */
    fun byId(id: String?): PayRuleTemplate =
        all.firstOrNull { it.id == id } ?: all.first()

    fun inGroup(group: PayRuleGroup): List<PayRuleTemplate> = all.filter { it.group == group }

    /** `"HH:mm"` → minutes of the day; anything unparseable reads as 0. */
    fun parseClock(text: String?): Int {
        val parts = text?.trim()?.split(':')?.takeIf { it.size == 2 } ?: return 0
        val hours = parts[0].toIntOrNull() ?: return 0
        val minutes = parts[1].toIntOrNull() ?: return 0
        return (hours * MINUTES_PER_HOUR + minutes).coerceIn(0, MAX_MINUTES_OF_DAY)
    }

    /** Minutes of the day back to `"HH:mm"`. */
    fun formatClock(minutes: Int?): String {
        val total = (minutes ?: 0).coerceIn(0, MAX_MINUTES_OF_DAY)
        val hh = (total / MINUTES_PER_HOUR).toString().padStart(2, '0')
        val mm = (total % MINUTES_PER_HOUR).toString().padStart(2, '0')
        return "$hh:$mm"
    }

    /** Minutes → hours, trimmed: 90 reads "1.5" and 600 reads "10". */
    fun formatHours(minutes: Int?): String {
        val tenths = ((minutes ?: 0) * TENTHS_PER_HOUR + MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR
        val whole = tenths / TENTHS
        val fraction = tenths % TENTHS
        return if (fraction == 0) whole.toString() else "$whole.$fraction"
    }

    private const val TENTHS = 10
    private const val TENTHS_PER_HOUR = 10
}

/**
 * One pay rule as the editor holds it, flattened out of the wire's
 * `rateRowOverrideSchema { row_id, source, nominal_code }`.
 *
 * The flattening is deliberate: the wire nests a whole agreement rate row
 * inside `source`, and every field the AD can actually change is one level
 * down inside it.
 */
@Suppress("LongParameterList")
data class PayRule(
    val id: String,
    val templateId: String = "ot",
    val label: String = "",
    val rateKind: RateKind = RateKind.Multiplier,
    val amount: String = "",
    val basis: RateBasis = RateBasis.Hour,
    /** The template's single form value: hours, a clock time, or day kinds. */
    val formValue: String = "",
    val dayKinds: List<DayKind> = emptyList(),
    /** Round a matched window up to a multiple of N minutes. */
    val increments: String = "",
    /** Clip the computed payout per matched window. */
    val cap: String = "",
    val bdrMin: String = "",
    val bdrMax: String = "",
    val nominalCode: String = "",
    val note: String = "",
) {
    val template: PayRuleTemplate get() = PayRuleTemplates.byId(templateId)

    /** The rate as the collapsed row shows it: `×1.5`, `10%` or a plain amount. */
    fun formatRate(): String = when (rateKind) {
        RateKind.Multiplier -> "×${amount.ifBlank { "0" }}"
        RateKind.Percentage -> "${amount.ifBlank { "0" }}%"
        RateKind.Flat -> amount.ifBlank { "0" }
    }
}

/** The project's distribution list and report approvers — one row each. */
data class DistributionEntry(
    /** `user` for a project member; the server also accepts a bare address. */
    val kind: String = "user",
    val ref: String? = null,
    val name: String = "",
    val email: String = "",
)

/** The non-union document: what a non-union artiste is paid. */
data class NonUnionRates(
    /** Day rate per category, keyed by the category's **label** as the wire stores it. */
    val dayRates: Map<String, String> = emptyMap(),
    val dayTypes: List<DayTypeOption> = emptyList(),
    val overtimes: List<PayRule> = emptyList(),
    val premiums: List<PayRule> = emptyList(),
    val penalties: List<PayRule> = emptyList(),
    val allowances: List<RateLine> = emptyList(),
    val additionalFees: List<RateLine> = emptyList(),
    /** Who the server e-mails a published report's PDF to. */
    val distribution: List<DistributionEntry> = emptyList(),
    val reportApprovers: List<DistributionEntry> = emptyList(),
) {
    fun rules(group: PayRuleGroup): List<PayRule> = when (group) {
        PayRuleGroup.Overtimes -> overtimes
        PayRuleGroup.Premiums -> premiums
        PayRuleGroup.Penalties -> penalties
    }

    fun withRules(group: PayRuleGroup, rows: List<PayRule>): NonUnionRates = when (group) {
        PayRuleGroup.Overtimes -> copy(overtimes = rows)
        PayRuleGroup.Premiums -> copy(premiums = rows)
        PayRuleGroup.Penalties -> copy(penalties = rows)
    }
}

/** A union agreement the project has saved a pay breakdown for. */
data class UnionAgreementRef(
    val id: String,
    val label: String,
    val territory: String = "",
)

/**
 * One union agreement's pay breakdown, as the editor holds it.
 *
 * [document] is the agreement itself — read from the deal-memo catalogue while
 * the agreement is unsaved, and from this project's own snapshot once it has
 * been saved. The AD service cannot read the agreement, which is why the save
 * snapshots it rather than referencing it.
 */
data class UnionPayBreakdown(
    val agreementId: String,
    val label: String = "",
    val currency: String = "",
    /** The production's chosen day rate per category label, floored at the agreement's. */
    val dayRates: Map<String, String> = emptyMap(),
    /** Chosen allowance amounts, keyed by the agreement row's id. */
    val allowanceAmounts: Map<String, String> = emptyMap(),
    val feeAmounts: Map<String, String> = emptyMap(),
    val document: UnionAgreementDocument? = null,
)

/** The agreement's own rules, as either the catalogue or a saved snapshot gives them. */
data class UnionAgreementDocument(
    val currency: String = "",
    val overtimes: List<AgreementRateRow> = emptyList(),
    val premiums: List<AgreementRateRow> = emptyList(),
    val penalties: List<AgreementRateRow> = emptyList(),
    val turnaround: List<AgreementRateRow> = emptyList(),
    val allowances: List<AgreementAllowanceRow> = emptyList(),
    val extraFees: List<AgreementAllowanceRow> = emptyList(),
    val fringes: List<AgreementAllowanceRow> = emptyList(),
    val dayTypes: List<DayTypeOption> = emptyList(),
    val holidayPayKind: String = "",
    val holidayPayAmount: Double? = null,
)

/** One of the agreement's own pay rows, carried through the save verbatim. */
data class AgreementRateRow(
    val id: String = "",
    val label: String = "",
    val rateKind: RateKind = RateKind.Multiplier,
    val rateAmount: Double? = null,
    val basis: RateBasis = RateBasis.Hour,
    val nominalCode: String = "",
    val note: String = "",
    /** Everything else the agreement row carries, kept so the snapshot round-trips. */
    val extras: Map<String, String> = emptyMap(),
)

/** One of the agreement's allowance / fee rows, with the floor it sets. */
data class AgreementAllowanceRow(
    val id: String = "",
    val label: String = "",
    val rateKind: RateKind = RateKind.Flat,
    /** The agreement's amount: the floor the production may raise but not undercut. */
    val floor: Double? = null,
    val basis: String = "",
    val cap: Double? = null,
)

/** A casting agency, registered as a project vendor of type `agency`. */
data class Agency(
    val id: String,
    val name: String = "",
    val email: String = "",
    val contactPerson: String = "",
    val phone: String = "",
    val address: String = "",
    val verified: Boolean = false,
    val createdBy: String = "",
    val departmentId: String? = null,
    val bank: BankDetails = BankDetails(),
) {
    /**
     * Who may edit or delete this agency.
     *
     * The Vendors rules, not the AD dashboard's posting right: an accountant,
     * or whoever created the row. The Agency tab deliberately never reads the
     * AD posting gate.
     */
    fun editableBy(userId: String, isAccountant: Boolean): Boolean =
        isAccountant || (createdBy.isNotBlank() && createdBy == userId)
}

/** One entry in an agency's audit trail. */
data class AgencyHistoryEntry(
    val id: String = "",
    val action: String = "",
    val byName: String = "",
    val at: Long? = null,
    val detail: String = "",
)

/** Everything `GET /ad-rate-config` answers with. */
data class RateConfig(
    val nonUnion: NonUnionRates = NonUnionRates(),
    val unionAgreements: List<UnionAgreementRef> = emptyList(),
    val vendors: List<Agency> = emptyList(),
)
