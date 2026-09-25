package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The parts of the settings document the Settings page saves one at a time —
 * the web's `saveSection` (`PCSettingsPage.jsx:334-388`).
 *
 * Each section PATCHes only its own keys, so saving one cannot overwrite what
 * someone else changed in another, and a refusal of one leaves the rest
 * untouched. The team, the request cap and the assignment rules save through
 * their own routes and are not listed here.
 */
enum class CashSettingsSection {
    /** `{float_custodian_account, bs_code_from, bs_code_to}`, each null when blank. */
    Custodian,

    /** `{department_coordinators}`. */
    Coordinators,

    /** `{approval_override: {…the four flags}}`. */
    Approval,

    /** `{reimburse_to_payroll}` — a boolean; the validator refuses anything else. */
    Reimbursement,

    /** `{deduction_rules}`. */
    Deduction,

    /** `{quick_codes}`. */
    QuickCodes,
    ;

    /** Whether [draft] differs from [stored] in this section. */
    fun isDirty(draft: CashSettings, stored: CashSettings?): Boolean =
        stored == null || draft.slice(this) != stored.slice(this)

    /** [target] with this section's fields taken from [source] — the rest of [target] untouched. */
    fun merge(target: CashSettings, source: CashSettings): CashSettings = when (this) {
        Custodian -> target.copy(
            custodianAccount = source.custodianAccount,
            bsCodeFrom = source.bsCodeFrom,
            bsCodeTo = source.bsCodeTo,
        )
        Coordinators -> target.copy(departmentCoordinators = source.departmentCoordinators)
        Approval -> target.copy(
            overrideFloatRequest = source.overrideFloatRequest,
            overrideReceiptBatch = source.overrideReceiptBatch,
            requireCoordinatorCoding = source.requireCoordinatorCoding,
            requireSeniorSignOff = source.requireSeniorSignOff,
        )
        Reimbursement -> target.copy(reimburseToPayroll = source.reimburseToPayroll)
        Deduction -> target.copy(deductionRules = source.deductionRules)
        QuickCodes -> target.copy(quickCodes = source.quickCodes)
    }

    private fun CashSettings.slice(section: CashSettingsSection): Any = when (section) {
        Custodian -> listOf(custodianAccount, bsCodeFrom, bsCodeTo)
        Coordinators -> departmentCoordinators
        Approval -> listOf(overrideFloatRequest, overrideReceiptBatch, requireCoordinatorCoding, requireSeniorSignOff)
        Reimbursement -> reimburseToPayroll
        Deduction -> deductionRules
        QuickCodes -> quickCodes
    }
}

/**
 * The coordinator rows' own checks, before anything is sent — the web's
 * `saveCoordinators` (`PCSettingsPage.jsx:364-377`). Keys are `"<row>_dept"`
 * and `"<row>_users"`, as the web keys its errors; empty means the rows save.
 */
object CoordinatorRules {
    const val DEPARTMENT = "dept"
    const val USERS = "users"

    fun key(row: Int, field: String): String = "${row}_$field"

    fun errors(rows: List<DepartmentCoordinator>): Set<String> = buildSet {
        rows.forEachIndexed { index, row ->
            if (row.departmentId.isBlank()) add(key(index, DEPARTMENT))
            if (row.userIds.isEmpty()) add(key(index, USERS))
        }
    }

    /**
     * The departments a row may pick: every department not already taken by
     * another row. The row's own choice stays offered.
     */
    fun departmentsFor(
        index: Int,
        rows: List<DepartmentCoordinator>,
        departments: List<CashDepartment>,
    ): List<CashDepartment> {
        val taken = rows.filterIndexed { i, _ -> i != index }.map { it.departmentId }.filter { it.isNotBlank() }.toSet()
        return departments.filterNot { it.id in taken }
    }
}

/**
 * A deduction rule being added or edited — the web's rule modal
 * (`PCSettingsPage.jsx:1053-1229`). [index] is null for a new rule.
 */
data class DeductionRuleEdit(val rule: DeductionRule, val index: Int? = null) {
    val isNew: Boolean get() = index == null
    val canCommit: Boolean get() = rule.title.isNotBlank()

    /**
     * A process change: anything but a deduction reads a minimum amount, so
     * its threshold type is forced to `min_amount` (`PCSettingsPage.jsx:1095-1103`).
     */
    fun withProcess(process: RuleProcess): DeductionRuleEdit = copy(
        rule = rule.copy(
            processType = process,
            thresholdType = if (process == RuleProcess.DeductAmount) rule.thresholdType else RuleThreshold.MinAmount,
        ),
    )

    /** Adds a quick code's keywords to the triggers, once each. */
    fun withQuickCode(code: QuickCode): DeductionRuleEdit =
        copy(rule = rule.copy(triggerCodes = (rule.triggerCodes + code.keywords).distinct()))

    companion object {
        /** A blank custom rule — `addDeductionRule` (`PCSettingsPage.jsx:506-521`). */
        fun fresh(nowMillis: Long): DeductionRuleEdit = DeductionRuleEdit(
            DeductionRule(
                id = "custom_$nowMillis",
                type = "custom",
                title = "",
                processType = RuleProcess.DeductAmount,
                thresholdType = RuleThreshold.Percentage,
                thresholdValue = 0.0,
                enabled = true,
            ),
        )

        /**
         * Whether a quick code's chip is spent: any of its keywords, or its
         * name, is already a trigger, ignoring case (`PCSettingsPage.jsx:1183-1185`).
         */
        fun alreadyAdded(rule: DeductionRule, code: QuickCode): Boolean = rule.triggerCodes.any { trigger ->
            code.keywords.any { it.equals(trigger, ignoreCase = true) } || code.name.equals(trigger, ignoreCase = true)
        }
    }
}

/** Comma-separated text to a list: split, trimmed, blanks dropped — as the web parses on save. */
fun String.commaList(): List<String> = split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The nominal codes an assignment rule can name.
 *
 * Copied from the web's static `NOMINAL_CODES`
 * (`zillit_web/src/accountHub/data/purchase-orders.js:7-23`), which the Petty
 * Cash settings page offers rather than the production's chart.
 */
object CashNominalCatalogue {
    val codes: Map<String, String> = linkedMapOf(
        "2100" to "Production — General",
        "2200" to "Art Department — Materials",
        "2300" to "Art Department — Props",
        "2400" to "Camera — Equipment Hire",
        "2500" to "Camera — Purchases",
        "2600" to "Costume — Hire",
        "2700" to "Electrical — Equipment",
        "2716" to "Office Stationery",
        "2800" to "Locations — Fees",
        "2900" to "Transport — Vehicle Hire",
        "3000" to "Catering",
        "3100" to "Post Production — Edit",
        "3200" to "Music",
        "4000" to "Travel & Accommodation",
        "5000" to "Miscellaneous",
    )

    /** "2400 — Camera — Equipment Hire", or the bare code for one not listed. */
    fun label(code: String): String = codes[code]?.let { "$code — $it" } ?: code
}
