package com.zillit.desktop.feature.accounthub.domain

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
    val title: String,
    val body: String,
    val notes: List<Pair<String, String>>,
    /** Where the step points: the Production Setup nav row, the Deal Memo tab, or the chart. */
    val target: SetupTourTarget,
) {
    Company(
        "Companies & Entities",
        "Add the companies for the project.",
        listOf(
            "Used on" to "Each purchase order, invoice, card and cash expense receipt, and deal memo.",
            "Note" to "Each company can have multiple bank accounts connected to it, so add companies before bank " +
                "accounts.",
        ),
        SetupTourTarget.Nav,
    ),
    Bank(
        "Bank Accounts",
        "Add the bank accounts for the project, along with the nominal and AP clearance code.",
        listOf(
            "Used for" to "The ledger entries of purchase orders, invoices, cards, floats, card and cash expense " +
                "receipts, and payrolls.",
        ),
        SetupTourTarget.Nav,
    ),
    Currency(
        "Project Currencies",
        "Add the currencies the project is going to deal with. Set one currency as the project's default and add " +
            "exchange rates for all the other selected currencies.",
        emptyList(),
        SetupTourTarget.Nav,
    ),
    Tags(
        "Account Tags",
        "Add free-form text tags.",
        listOf(
            "Used on" to "The line items of purchase orders, invoices, and card and cash expense receipts.",
            "Also" to "As a filter while generating reports such as the Trial Balance and Bible Reports.",
        ),
        SetupTourTarget.Nav,
    ),
    Tax(
        "Tax Types",
        "Add the countries and their tax types for the project.",
        listOf(
            "Used on" to "Directly in the line items of purchase orders, invoices, and card and cash expense receipts.",
        ),
        SetupTourTarget.Nav,
    ),
    Coa(
        "Chart of Accounts",
        "Three sub-tabs: Chart of Accounts, Balance Sheet Codes and Layers. Budget account codes appear here — " +
            "uploading the budget creates a Chart of Accounts from its lines.",
        listOf(
            "Balance sheet codes" to "Accountants can add balance sheet codes here and edit the existing ones. " +
                "Used in Production Cards, Petty Cash Floats and Production Bank Accounts.",
            "Layers" to "A tagging feature that creates analytical dimensions for the line items of different " +
                "Account Hub modules. A layer can hold multiple codes, and multiple layer codes can be added to " +
                    "each line item.",
        ),
        SetupTourTarget.Chart,
    ),
    Schedule(
        "Production Schedule",
        "Add the schedule dates for the deals.",
        listOf(
            "Deal dates" to "Deal start and end dates.",
            "Prep / shoot / wrap" to "Prep, shoot and wrap start and end dates.",
            "Custom" to "Any custom day type and its start and end dates.",
        ),
        SetupTourTarget.DealTab,
    ),
    NonUnionPay(
        "Non-Union Pay Breakdown",
        "Here we define the day types, pay rules, and the departments those rules apply to.",
        listOf(
            "Day types" to "The defaults are SWD, CWD and SCWD. You can add any custom day type and customise the " +
                "minimum working hours and minimum meal break duration.",
            "Pay rules" to "Use any type of rule from the list and customise the pay, durations, triggers, " +
                "increments and other parameters for them.",
        ),
        SetupTourTarget.DealTab,
    ),
    Allowances(
        "Allowances & Rentals",
        "Add the default allowances and rentals for all the deals, which will be added to the deals automatically " +
            "and can be edited or removed from there.",
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Agreements(
        "Agreement Documents",
        "Add agreements, NDAs or any other production document here, which will be attached to the deals " +
            "automatically. These can be removed while creating the deals.",
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Conditions(
        "Standard Deal Conditions",
        "Add the default clauses inserted into the 'Terms & Conditions' step of every new deal memo.",
        emptyList(),
        SetupTourTarget.DealTab,
    ),
    Bureau(
        "Payroll Bureau",
        "Add the payroll bureaus to the system. These will appear while creating a deal, and one can be selected " +
            "to attach to the deal.",
        emptyList(),
        SetupTourTarget.DealTab,
    ),
}

enum class SetupTourTarget { Nav, DealTab, Chart }

/** The modal shown before the production tour — guide page 1, near verbatim. */
object SetupTourIntro {
    const val TITLE = "Account Hub — Production Setup"
    const val BODY = "First we need to set up a few things in the Account Hub before starting work in any other module."
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
