package com.zillit.desktop.feature.accounthub.domain

/** Which module's approval chain is being configured. */
enum class ApprovalModule(val wire: String, val label: String) {
    PurchaseOrders("purchase_orders", "Purchase Orders"),
    Invoices("invoices", "Invoices"),
    CardExpenses("card_expenses", "Card Expenses"),
    CashExpenses("cash_expenses", "Petty Cash"),
    ;

    companion object {
        fun from(wire: String?): ApprovalModule =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: PurchaseOrders
    }
}

/**
 * Whether a chain applies to the whole production or to one department.
 *
 * The server resolves a department's chain first and falls back to the
 * production-wide one, so a department with no configuration of its own is not
 * a department with no approvals.
 */
enum class ApprovalScope(val wire: String, val label: String) {
    All("all", "Everyone"),
    Department("department", "Department"),
    ;

    companion object {
        fun from(wire: String?): ApprovalScope =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: All
    }
}

/**
 * One rule inside a level: a kind of approver, and who fills it.
 *
 * A level can carry several — "any head of department" alongside two named
 * people — which is why a level is not simply a list of user ids.
 */
data class ApprovalRule(val type: String = "", val userIds: List<String> = emptyList()) {
    val isAssigned: Boolean get() = userIds.isNotEmpty()
}

/** One level of the chain. [order] is 1-based, as shown. */
data class ApprovalTier(val order: Int, val rules: List<ApprovalRule> = emptyList()) {
    val isAssigned: Boolean get() = rules.any { it.isAssigned }

    val approverCount: Int get() = rules.sumOf { it.userIds.size }
}

/** A saved chain for one module and scope. */
data class ApprovalConfig(
    val id: String = "",
    val module: ApprovalModule = ApprovalModule.PurchaseOrders,
    val scope: ApprovalScope = ApprovalScope.All,
    val departmentId: String? = null,
    val departmentName: String = "",
    val tiers: List<ApprovalTier> = emptyList(),
) {
    val isConfigured: Boolean get() = tiers.any { it.isAssigned }

    val levelCount: Int get() = tiers.count { it.isAssigned }
}

/**
 * The sequencing rule: levels fill from the bottom up.
 *
 * A level may hold approvers only once every level before it already does.
 * Without it a chain can be saved with level 1 empty and level 2 filled, and
 * nothing then routes — the document waits at a level with nobody in it.
 */
object ApprovalSequence {

    /**
     * Whether the assigned levels form an unbroken run from the first.
     *
     * Trailing empty levels are fine — those are rows added but not yet filled,
     * and refusing them would stop the user mid-edit.
     */
    fun inSequence(tiers: List<ApprovalTier>): Boolean {
        val firstEmpty = tiers.indexOfFirst { !it.isAssigned }
        if (firstEmpty == -1) return true
        return tiers.drop(firstEmpty + 1).none { it.isAssigned }
    }

    /**
     * Drops every empty level and renumbers the survivors 1..N.
     *
     * Empty levels anywhere collapse — leading and middle as well as trailing —
     * so a filled level below an empty one moves up rather than being saved
     * behind a gap.
     */
    fun compacted(tiers: List<ApprovalTier>): List<ApprovalTier> =
        tiers.filter { it.isAssigned }.mapIndexed { index, tier -> tier.copy(order = index + 1) }

    /** 1-based positions of the empty levels, for the confirmation wording. */
    fun emptyLevels(tiers: List<ApprovalTier>): List<Int> =
        tiers.mapIndexedNotNull { index, tier -> (index + 1).takeIf { !tier.isAssigned } }

    /**
     * Why this chain cannot be saved, or null when it can.
     *
     * An out-of-sequence chain is refused outright. A chain with only trailing
     * blanks is allowed through — [compacted] removes them on the way out.
     */
    fun validationError(tiers: List<ApprovalTier>): String? = when {
        tiers.none { it.isAssigned } -> "Add at least one approver."
        !inSequence(tiers) -> {
            val empty = emptyLevels(tiers).filter { level ->
                tiers.drop(level).any { it.isAssigned }
            }
            "Level ${empty.joinToString(", ")} has no approvers. " +
                "Levels must be filled in order."
        }
        else -> null
    }
}
