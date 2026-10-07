package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import kotlinx.serialization.json.JsonObject

/**
 * Which spend module a setup modal configures. Both edit the module's own
 * `/settings` document, and both share one modal (the web's `SharedSpendDetail`);
 * they differ in labels, the approval toggles and a few fields.
 */
enum class SpendKind(val moduleKey: String) {
    Cards("card_expenses"),
    Cash("cash_expenses"),
    ;

    /** The approval switches, in the order the web lists them. */
    val approvalToggles: List<SpendApprovalToggle>
        get() = when (this) {
            Cards -> listOf(
                SpendApprovalToggle(
                    "override_card_req",
                    S.desktop_ce_insights_override_card_label,
                    S.desktop_ce_insights_override_card_desc,
                ),
                SpendApprovalToggle(
                    "override_receipt",
                    S.desktop_ce_insights_override_receipt_label,
                    S.desktop_ce_insights_override_receipt_desc,
                ),
                SpendApprovalToggle(
                    "require_coord_code",
                    S.desktop_pc_coord_code_label,
                    S.desktop_ce_insights_coord_code_desc,
                ),
            )
            Cash -> listOf(
                SpendApprovalToggle(
                    "override_float_req",
                    S.desktop_pc_override_float_label,
                    S.desktop_pc_override_float_desc,
                ),
                SpendApprovalToggle(
                    "override_receipt_batch",
                    S.desktop_pc_override_batch_label,
                    S.desktop_pc_override_batch_desc,
                ),
                SpendApprovalToggle(
                    "require_coord_code",
                    S.desktop_pc_coord_code_label,
                    S.desktop_pc_coord_code_desc,
                ),
                SpendApprovalToggle(
                    "require_senior_sign_off",
                    S.desktop_pc_senior_signoff_label,
                    S.desktop_pc_senior_signoff_desc,
                ),
            )
        }
}

/** One switch under Approval Overrides: its key in `approval_override` and its copy. */
data class SpendApprovalToggle(val key: String, val labelKey: String, val hintKey: String)

private val NO_EXTRA = JsonObject(emptyMap())

/**
 * One accountant who posts, with the ceiling they may post to.
 *
 * [postingLimit] is three-valued and the difference matters: **null is
 * unlimited**, **zero is submit-only** (may post nothing) and anything else a
 * ceiling. An absent key reads as zero, as the web's `normalize` does. [extra]
 * carries whatever else the stored row held, so a save does not strip it.
 */
data class SpendTeamMember(
    val userId: String = "",
    val postingLimit: Double? = 0.0,
    val canOverride: Boolean = false,
    val isSenior: Boolean = false,
    val extra: JsonObject = NO_EXTRA,
) {
    val isUnlimited: Boolean get() = postingLimit == null

    val isSubmitOnly: Boolean get() = postingLimit == 0.0

    /** A senior is unlimited and may override, whatever the row says; the web's dialog cascades the same way. */
    fun normalised(): SpendTeamMember = if (isSenior) copy(postingLimit = null, canOverride = true) else this
}

/** A department's coordinator(s): who codes its receipts, and whether it has to. */
data class SpendCoordinator(
    val departmentId: String = "",
    val userIds: List<String> = emptyList(),
    val codingRequired: Boolean = false,
    /** Cash only: may see the department's floats. */
    val viewDepartmentFloats: Boolean = false,
    val extra: JsonObject = NO_EXTRA,
)

/**
 * A deduction or processing rule. [processType] and [thresholdType] stay the
 * wire's own strings, so a value this client does not know round-trips.
 */
data class SpendDeductionRule(
    val id: String = "",
    val type: String = CUSTOM,
    val title: String = "",
    val description: String = "",
    val processType: String = DEDUCT_AMOUNT,
    val thresholdType: String = PERCENTAGE,
    val thresholdValue: Double = 0.0,
    val enable: Boolean = true,
    val triggerCodes: List<String> = emptyList(),
    val systemDefault: Boolean = false,
    val extra: JsonObject = NO_EXTRA,
) {
    val isPercentage: Boolean get() = thresholdType == PERCENTAGE

    /** Anything but a deduction measures a minimum amount — the web's `changeProcess`. */
    fun withProcess(next: String): SpendDeductionRule =
        copy(processType = next, thresholdType = if (next == DEDUCT_AMOUNT) thresholdType else MIN_AMOUNT)

    companion object {
        const val CUSTOM = "custom"
        const val DEDUCT_AMOUNT = "deduct_amount"
        const val SENIOR_REVIEW = "senior_review"
        const val NEED_QUERY = "need_query"
        const val PERCENTAGE = "percentage"
        const val MIN_AMOUNT = "min_amount"
    }
}

/** A keyword that maps a receipt to a nominal code. The keywords are held as typed; see [keywords]. */
data class SpendQuickCode(
    val name: String = "",
    val nominalCode: String = "",
    /**
     * The keywords as the comma-separated text the person edits. Held as text
     * because a list re-joined on every keystroke eats the comma just typed;
     * the web holds a draft beside the list for the same reason.
     */
    val keywordsText: String = "",
    val vat: Double = 0.0,
    val extra: JsonObject = NO_EXTRA,
) {
    val keywords: List<String> get() = splitKeywords(keywordsText)

    companion object {
        fun splitKeywords(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** The VAT choices the dropdown offers. */
        val VAT_CHOICES = listOf(20.0, 5.0, 0.0)
    }
}

/** A card provider: one bank bound to one company with its own custodian and float accounts. */
data class CardProvider(
    val id: String,
    val name: String = "",
    val bankId: String = "",
    val companyId: String = "",
    val custodianAccount: String = "",
    val floatMin: String = "",
    val floatMax: String = "",
    val extra: JsonObject = NO_EXTRA,
) {
    /** Whether the row holds anything at all — the web's sanitiser drops rows the person never filled in. */
    val isBlank: Boolean
        get() = name.isBlank() && bankId.isBlank() && custodianAccount.isBlank() &&
            floatMin.isBlank() && floatMax.isBlank()
}

/**
 * The document the Card and Petty Cash setup modals edit — the module's
 * `/settings` row.
 *
 * Only the keys this modal owns are modelled; the data layer sends back only
 * the ones that changed, so anything else in the stored document is left
 * alone. [approval] holds the kind's switches, [approvalExtra] any other key
 * the stored `approval_override` carried.
 */
data class SpendSettings(
    val kind: SpendKind = SpendKind.Cards,
    /** Cash only: the float custodian account and the BS code range. */
    val custodianAccount: String = "",
    val bsCodeFrom: String = "",
    val bsCodeTo: String = "",
    val team: List<SpendTeamMember> = emptyList(),
    val coordinators: List<SpendCoordinator> = emptyList(),
    val approval: Map<String, Boolean> = emptyMap(),
    val approvalExtra: JsonObject = NO_EXTRA,
    val deductionRules: List<SpendDeductionRule> = SpendDefaults.deductionRules(kind),
    val quickCodes: List<SpendQuickCode> = SpendDefaults.quickCodes(kind),
    /** Cards only. */
    val providers: List<CardProvider> = emptyList(),
) {
    val approvalsOn: Int get() = approval.values.count { it }

    /** The Accounts & Custodian chip: providers for cards, the filled account fields for cash. */
    val accountCount: Int
        get() = when (kind) {
            SpendKind.Cards -> providers.size
            SpendKind.Cash -> listOf(custodianAccount, bsCodeFrom, bsCodeTo).count { it.isNotBlank() }
        }

    fun approvalOn(key: String): Boolean = approval[key] == true
}

/** What the web shows when a production has stored no rules or quick codes of its own. */
object SpendDefaults {

    fun deductionRules(kind: SpendKind): List<SpendDeductionRule> {
        val cards = kind == SpendKind.Cards
        return listOf(
            rule(
                "fuel_deduction",
                if (cards) "Fuel — Personal Use Deduction" else "Fuel Deduction",
                if (cards) {
                    "Deduct 20% from all fuel receipts for estimated personal use. " +
                        "Applied automatically during audit; accountant can override per line."
                } else {
                    "Deduct a percentage of fuel expenses for personal use."
                },
                SpendDeductionRule.DEDUCT_AMOUNT,
                SpendDeductionRule.PERCENTAGE,
                FUEL_PERCENT,
                listOf("fuel", "petrol", "diesel", "filling station"),
            ),
            rule(
                "accommodation_cap",
                if (cards) "Accommodation — Cap" else "Accommodation Cap",
                if (cards) {
                    "Flag accommodation receipts exceeding £150/night for senior review."
                } else {
                    "Flag accommodation expenses above the nightly cap for senior review."
                },
                SpendDeductionRule.SENIOR_REVIEW,
                SpendDeductionRule.MIN_AMOUNT,
                ACCOMMODATION_CAP,
                listOf("hotel", "accommodation", "B&B"),
            ),
            rule(
                "meal_cap",
                if (cards) "Subsistence — Meal Cap" else "Meal Cap",
                if (cards) {
                    "Cap individual meal claims at £30 per person per meal. Amounts above require explanation."
                } else {
                    "Query receipts above the per-meal allowance."
                },
                SpendDeductionRule.NEED_QUERY,
                SpendDeductionRule.MIN_AMOUNT,
                MEAL_CAP,
                listOf("catering", "lunch", "dinner", "meal", "food", "cafe", "coffee"),
            ),
            rule(
                "high_value",
                if (cards) "High-Value — Senior Approval" else "High Value Receipt",
                if (cards) {
                    "Any single receipt over £200 requires Production Accountant sign-off " +
                        "regardless of who has approved."
                } else {
                    "Send any high-value receipt for senior review regardless of category."
                },
                SpendDeductionRule.SENIOR_REVIEW,
                SpendDeductionRule.MIN_AMOUNT,
                HIGH_VALUE,
                emptyList(),
            ),
        )
    }

    fun quickCodes(kind: SpendKind): List<SpendQuickCode> {
        val cards = kind == SpendKind.Cards
        return listOf(
            code("Fuel", "fuel, petrol, diesel, filling station", if (cards) STANDARD_VAT else 0.0),
            code(
                "Parking",
                if (cards) "parking, car park, NCP, multipark" else "parking, NCP",
                0.0,
            ),
            code(
                "Taxi",
                if (cards) "taxi, uber, lyft, cab, black car" else "taxi, uber, bolt",
                STANDARD_VAT,
            ),
            code(
                "Catering",
                if (cards) "lunch, dinner, meal, food, cafe, coffee" else "catering, lunch, food, cafe, coffee",
                STANDARD_VAT,
            ),
        )
    }

    private fun rule(
        id: String,
        title: String,
        description: String,
        process: String,
        threshold: String,
        value: Double,
        triggers: List<String>,
    ) = SpendDeductionRule(
        id = id,
        type = id,
        title = title,
        description = description,
        processType = process,
        thresholdType = threshold,
        thresholdValue = value,
        enable = false,
        triggerCodes = triggers,
        systemDefault = true,
    )

    private fun code(name: String, keywords: String, vat: Double) =
        SpendQuickCode(name = name, keywordsText = keywords, vat = vat)

    private const val FUEL_PERCENT = 20.0
    private const val ACCOMMODATION_CAP = 150.0
    private const val MEAL_CAP = 30.0
    private const val HIGH_VALUE = 200.0
    private const val STANDARD_VAT = 20.0
}
