package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * "What still needs configuring" — the setup tours.
 *
 * Ported from the web's `useSetupTips`. Three rules govern every check, and
 * all three exist because a tip that fires when nothing is wrong teaches
 * people to skip the tour the one time it matters:
 *
 *  1. **Unknown ≠ missing.** Nothing is flagged until the setup has loaded.
 *  2. **Untouched only.** A section is missing when it holds *nothing*, never
 *     when it is merely partial.
 *  3. **An absent key is not an empty one.** Banks are only flagged when the
 *     list has actually been read.
 *
 * Payroll defaults are deliberately not covered: the server seeds them, so
 * the only state a check could fire on is a deliberate choice.
 */
enum class SetupGap(
    private val titleKey: String,
    private val bodyKey: String,
    private val noteKeys: List<Pair<String, String>>,
    /** Where the step points: the Production Setup nav row, the Deal Memo tab, or the chart. */
    val target: SetupTourTarget,
) {
    Company(
        S.desktop_hub_companies_entities,
        S.desktop_hub_tour_company_body,
        listOf(
            S.desktop_hub_tour_used_on to S.desktop_hub_tour_company_used_on,
            S.note to S.desktop_hub_tour_company_note,
        ),
        SetupTourTarget.Nav,
    ),
    Bank(
        S.desktop_bank_accounts,
        S.desktop_hub_tour_bank_body,
        listOf(
            S.desktop_hub_tour_used_for to S.desktop_hub_tour_bank_used_for,
        ),
        SetupTourTarget.Nav,
    ),
    Currency(
        S.desktop_project_currencies,
        S.desktop_hub_tour_currency_body,
        emptyList(),
        SetupTourTarget.Nav,
    ),
    Tags(
        S.desktop_account_tags,
        S.desktop_hub_tour_tags_body,
        listOf(
            S.desktop_hub_tour_used_on to S.desktop_hub_tour_tags_used_on,
            S.desktop_hub_tour_also to S.desktop_hub_tour_tags_also,
        ),
        SetupTourTarget.Nav,
    ),
    Tax(
        S.desktop_tax_types,
        S.desktop_hub_tour_tax_body,
        listOf(
            S.desktop_hub_tour_used_on to S.desktop_hub_tour_tax_used_on,
        ),
        SetupTourTarget.Nav,
    ),
    Coa(
        S.desktop_chart_of_accounts,
        S.desktop_hub_tour_coa_body,
        listOf(
            S.desktop_balance_sheet_codes to S.desktop_hub_tour_coa_balance_sheet_codes,
            S.desktop_layers to S.desktop_hub_tour_coa_layers,
        ),
        SetupTourTarget.Chart,
    ),
    Schedule(
        S.desktop_production_schedule,
        S.desktop_hub_tour_schedule_body,
        listOf(
            S.desktop_deal_dates to S.desktop_hub_tour_schedule_deal_dates,
            S.desktop_hub_prep_shoot_wrap to S.desktop_hub_tour_schedule_prep_shoot_wrap,
            S.custom to S.desktop_hub_tour_schedule_custom,
        ),
        SetupTourTarget.DealTab,
    ),
    NonUnionPay(
        S.desktop_hub_non_union_pay_breakdown,
        S.desktop_hub_tour_non_union_body,
        listOf(
            S.desktop_day_types to S.desktop_hub_tour_non_union_day_types,
            S.desktop_dm_pay_rules to S.desktop_hub_tour_non_union_pay_rules,
        ),
        SetupTourTarget.DealTab,
    ),
    Allowances(
        S.dm_allow_title,
        S.desktop_hub_tour_allowances_body,
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Agreements(
        S.desktop_dm_agreement_documents,
        S.desktop_hub_tour_agreements_body,
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Conditions(
        S.desktop_standard_deal_conditions,
        S.desktop_hub_tour_conditions_body,
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Bureau(
        S.desktop_payroll_bureau,
        S.desktop_hub_tour_bureau_body,
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    ;

    val title: String get() = str(titleKey)
    val body: String get() = str(bodyKey)
    val notes: List<Pair<String, String>> get() = noteKeys.map { (label, text) -> str(label) to str(text) }
}

enum class SetupTourTarget { Nav, DealTab, Chart }

/** The modal shown before the production tour — guide page 1, near verbatim. */
object SetupTourIntro {
    val TITLE: String get() = str(S.desktop_hub_account_hub_production_setup)
    val BODY: String get() = str(S.desktop_hub_tour_intro_body)
}

/** What the tour has to know about the setup, read off the loaded state. */
data class SetupSnapshot(
    val ready: Boolean,
    val companies: Int,
    /** Null when the bank list has not been read — rule 3. */
    val banks: Int?,
    val currencies: Int,
    val tags: Int,
    val taxes: Int,
    val coaReady: Boolean,
    val coaEmpty: Boolean,
    val scheduleSet: Boolean,
    val payRules: Int,
    val entitlements: Int,
    val agreements: Int,
    val conditions: Int,
    val bureaus: Int,
)

object SetupTour {
    /** Production Setup gaps, in guide order (1.1 → 1.5, then the chart). */
    fun productionGaps(snapshot: SetupSnapshot): List<SetupGap> {
        if (!snapshot.ready) return emptyList()
        return buildList {
            if (snapshot.companies == 0) add(SetupGap.Company)
            if (snapshot.banks == 0) add(SetupGap.Bank)
            if (snapshot.currencies == 0) add(SetupGap.Currency)
            if (snapshot.tags == 0) add(SetupGap.Tags)
            if (snapshot.taxes == 0) add(SetupGap.Tax)
            if (snapshot.coaReady && snapshot.coaEmpty) add(SetupGap.Coa)
        }
    }

    /** Deal Memo Setup gaps, in guide order (2.1 → 2.6). */
    fun dealMemoGaps(snapshot: SetupSnapshot): List<SetupGap> {
        if (!snapshot.ready) return emptyList()
        return buildList {
            if (!snapshot.scheduleSet) add(SetupGap.Schedule)
            if (snapshot.payRules == 0) add(SetupGap.NonUnionPay)
            if (snapshot.entitlements == 0) add(SetupGap.Allowances)
            if (snapshot.agreements == 0) add(SetupGap.Agreements)
            if (snapshot.conditions == 0) add(SetupGap.Conditions)
            if (snapshot.bureaus == 0) add(SetupGap.Bureau)
        }
    }

    /** The hub-wide tour: production gaps, then deal gaps, as the web's "production" scope. */
    fun gaps(snapshot: SetupSnapshot): List<SetupGap> = productionGaps(snapshot) + dealMemoGaps(snapshot)

    /** The web shows the tour 800 ms after the shell settles. */
    const val OPEN_DELAY_MS = 800L

    /** The `ah_setup_tour_seen` flag, one per production per scope. */
    fun seenKey(projectId: String, scope: String = "production"): String = "ah_setup_tour_seen.$projectId.$scope"
}
