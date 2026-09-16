package com.zillit.desktop.feature.accounthub.domain

/**
 * Importing a union agreement's rule tables into the non-union pay
 * breakdown — the web's `agreementRuleImport.js`.
 *
 * ## Routing
 *
 * - `agreement.overtimes.rows` → overtimes, except the penalty-shaped ones,
 *   which agreements embed inside the OT block → penalties;
 * - `agreement.premiums.rows` → premiums;
 * - `agreement.turnaround.rows` → penalties: the breakdown has no turnaround
 *   list, and turnarounds are penalty-like triggered rules;
 * - `agreement.allowances.rows` → skipped: flat fees, not OT/premium/penalty
 *   rules, so they have no home here.
 *
 * Every imported row is a *clean* non-union rule: union-only fields with no
 * destination are dropped, `is_enhancement` is always false (non-union rules
 * are multiplicative, base × a), and each row gets a **fresh** id — a CBA
 * publishes several rules under one id, and reusing them would collapse
 * distinct rules in the pay engine's match key.
 */
object AgreementRuleImport {

    /**
     * Projects an agreement's tables into the three lists.
     *
     * [salt] makes the minted ids unique across imports — two imports of the
     * same agreement must never collide.
     */
    fun project(
        overtimes: List<AgreementRuleRow>,
        premiums: List<AgreementRuleRow>,
        turnarounds: List<AgreementRuleRow>,
        salt: String,
    ): ImportedRules {
        var sequence = 0
        fun row(source: AgreementRuleRow, kind: PayRuleKind): PayRule {
            sequence += 1
            return toRule(source, kind, id = "nu-imp-$salt-${kind.wire}-$sequence")
        }
        return ImportedRules(
            overtimes = overtimes.filterNot(::isPenalty).map { row(it, PayRuleKind.Overtimes) },
            premiums = premiums.map { row(it, PayRuleKind.Premiums) },
            penalties = overtimes.filter(::isPenalty).map { row(it, PayRuleKind.Penalties) } +
                turnarounds.map { row(it, PayRuleKind.Penalties) },
        )
    }

    /** A penalty published inside the OT block — by id or by label, as agreements are inconsistent. */
    fun isPenalty(row: AgreementRuleRow): Boolean {
        val id = row.id.lowercase()
        val label = row.label.lowercase()
        return PENALTY_ID.containsMatchIn(id) || PENALTY_LABEL.containsMatchIn(label)
    }

    /**
     * The canonical single trigger for a row.
     *
     * An agreement row carries a multi-entry, per-day-type triggers array
     * padded with engine keys; the editor's template matching cannot reverse
     * that, so every imported row used to collapse to its list's default
     * type. Classification is **signal-first** — the trigger's identity keys
     * — never label-first: "Non-Camera OT" contains "camera", and only the
     * `camera` flag can tell the two apart. (The web also splits pre-dawn
     * from night-work-early by label; both emit the same trigger, and the
     * row's own label carries the distinction here.)
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per template, in precedence order.
    fun classify(row: AgreementRuleRow, kind: PayRuleKind): PayTrigger {
        val t = row.trigger
        val hasAfter = t.afterMinutes != null
        val hasBefore = t.beforeMinutes != null
        val hasLess = t.lessMinutes != null

        // Penalty-shaped signals first, whichever list the row came from.
        if (t.mealCurtailed) return PayTrigger(mealCurtailed = true)
        if (t.meal) return PayTrigger(meal = true, afterMinutes = t.afterMinutes ?: 0)
        val bareShortfall = hasLess && !hasAfter && !t.clock
        if (bareShortfall && t.dayNumber == null) return PayTrigger(lessMinutes = t.lessMinutes)

        // Camera OT — the flag is the only reliable camera / non-camera signal.
        if (t.camera && hasAfter) {
            return PayTrigger(afterMinutes = t.afterMinutes, camera = true, incrementMinutes = CAMERA_INCREMENT)
        }
        // Consecutive-day OT (an hours threshold) against a day-based premium (none).
        if (t.dayNumber == SEVENTH || t.dayNumber == SIXTH) {
            return PayTrigger(dayNumber = t.dayNumber, consecutive = true, afterMinutes = t.afterMinutes)
        }
        if (t.dayKinds.isNotEmpty()) return PayTrigger(dayKinds = t.dayKinds)
        if (t.clock && hasAfter) return PayTrigger(clock = true, afterMinutes = t.afterMinutes)
        if (t.clock && hasBefore) return PayTrigger(clock = true, beforeMinutes = t.beforeMinutes)
        if (hasAfter) return PayTrigger(afterMinutes = t.afterMinutes)

        // Unrecognised: the list's default template with a minimal trigger.
        return when (kind) {
            PayRuleKind.Premiums -> PayTrigger(dayNumber = SIXTH, consecutive = true)
            PayRuleKind.Penalties -> PayTrigger(meal = true, afterMinutes = 0)
            PayRuleKind.Overtimes -> PayTrigger(afterMinutes = 0)
        }
    }

    private fun toRule(source: AgreementRuleRow, kind: PayRuleKind, id: String): PayRule = PayRule(
        id = id,
        label = source.label.trim().ifBlank { "Untitled rule" },
        rateType = source.rateType,
        rateAmount = source.rateAmount?.let(::amountText) ?: "0",
        // Premiums are usually per day, OT and penalties per hour — only when
        // the agreement row states no basis of its own.
        basis = source.basis ?: if (kind == PayRuleKind.Premiums) PayRateBasis.Day else PayRateBasis.Hour,
        triggers = listOf(classify(source, kind)),
        isEnhancement = false,
        capped = source.capped && source.capAmount != null,
        capAmount = source.capAmount?.takeIf { source.capped }?.let(::amountText).orEmpty(),
        nominalCode = source.nominalCode,
        note = source.note,
    )

    private fun amountText(amount: Double): String {
        val whole = amount.toLong()
        return if (amount == whole.toDouble()) whole.toString() else amount.toString()
    }

    private val PENALTY_ID = Regex("penalty|broken[_\\s-]turnaround|rest[_\\s-]day|curtail")
    private val PENALTY_LABEL = Regex("penalty|broken turnaround|rest day|curtail")
    private const val CAMERA_INCREMENT = 15
    private const val SIXTH = 6
    private const val SEVENTH = 7
}

/**
 * One rule row as an agreement publishes it, reduced to what the import
 * reads: the rate through the engine's own normalisation (a legacy row may
 * spell it `multiplier` / `flat` / `percentage` / `amount` rather than the
 * canonical pair), and the first trigger with its padding stripped.
 */
data class AgreementRuleRow(
    val id: String = "",
    val label: String = "",
    val rateType: PayRateType = PayRateType.Multiplier,
    val rateAmount: Double? = null,
    /** Null when the row states none; the list decides then. */
    val basis: PayRateBasis? = null,
    val trigger: PayTrigger = PayTrigger(),
    val capped: Boolean = false,
    val capAmount: Double? = null,
    val nominalCode: String = "",
    val note: String = "",
)

/** The three lists an import produces, ready to append to the breakdown. */
data class ImportedRules(
    val overtimes: List<PayRule> = emptyList(),
    val premiums: List<PayRule> = emptyList(),
    val penalties: List<PayRule> = emptyList(),
) {
    val total: Int get() = overtimes.size + premiums.size + penalties.size

    fun rulesFor(kind: PayRuleKind): List<PayRule> = when (kind) {
        PayRuleKind.Overtimes -> overtimes
        PayRuleKind.Premiums -> premiums
        PayRuleKind.Penalties -> penalties
    }

    /** The breakdown with these appended — the web's `commitRules` merge. */
    fun appendedTo(pay: NonUnionPay): NonUnionPay = pay.copy(
        overtimes = pay.overtimes + overtimes,
        premiums = pay.premiums + premiums,
        penalties = pay.penalties + penalties,
    )
}

/** One published agreement, as the import's picker lists it. */
data class UnionAgreementSummary(val identifier: String, val name: String, val territory: String? = null)

/** A shooting territory the union catalogue is organised by — `uk`, `us`. */
data class UnionTerritory(val id: String, val label: String)

/**
 * The web's bundled territory taxonomy (`accountHub/data/regions.js`), flat.
 *
 * The import's picker shows only the covered ones when the registry answers,
 * and the whole list when it does not — fail open, so a blip never empties
 * the dropdown.
 */
object UnionTerritories {
    val all: List<UnionTerritory> = listOf(
        "uk" to "United Kingdom", "ie" to "Ireland", "us" to "United States", "ca" to "Canada",
        "at" to "Austria", "pt" to "Portugal", "gr" to "Greece", "de" to "Germany", "fr" to "France",
        "es" to "Spain", "it" to "Italy", "be" to "Belgium", "nl" to "Netherlands",
        "au" to "Australia", "nz" to "New Zealand", "jp" to "Japan", "in" to "India",
        "sg" to "Singapore", "kr" to "South Korea",
        "ae" to "UAE / Dubai", "za" to "South Africa", "ma" to "Morocco",
        "mx" to "Mexico", "br" to "Brazil", "co" to "Colombia", "ar" to "Argentina",
        "dk" to "Denmark", "fi" to "Finland", "no" to "Norway", "se" to "Sweden",
        "cz" to "Czech Republic", "hu" to "Hungary", "pl" to "Poland", "ro" to "Romania",
        "bg" to "Bulgaria", "hr" to "Croatia", "rs" to "Serbia", "mt" to "Malta", "lu" to "Luxembourg",
        "is" to "Iceland", "il" to "Israel",
    ).map { (id, label) -> UnionTerritory(id, label) }

    /** The covered territories in catalogue order, or the whole catalogue when coverage is unknown or empty. */
    fun offered(covered: Set<String>?, keep: String? = null): List<UnionTerritory> =
        if (covered.isNullOrEmpty()) all else all.filter { it.id in covered || it.id == keep }
}
