package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Which module's approval chain is being configured.
 *
 * Six, not four. Timecard and Deal Memo do not appear in the hub's sidebar —
 * both became their own tools — but they still have approval chains, and the
 * web adds them here for exactly that reason
 * (`ApproversModule.EXTRA_MODULES_BY_SECTION`: "Modules that DON'T appear in
 * the AH sidebar but DO need approver config"). Leaving them out made two
 * tools' chains unreachable from the desktop (found 2026-09-09).
 *
 * [tool] is the identifier whose view access decides who may be picked as an
 * approver. Invoices has no tool of its own — it lives inside the Purchase
 * Orders module — so it borrows PO's, which is what the web does.
 *
 * Labels are the hub sidebar's own, which the web's Approvers page reuses
 * ("Invoices / Accounts Payable", "Production Expense Cards", …).
 */
enum class ApprovalModule(val wire: String, private val labelKey: String, val tool: String) {
    PurchaseOrders("purchase_orders", S.ah_purchase_orders, "purchase_order_tool"),
    Invoices("invoices", S.desktop_inv_accounts_payable, "purchase_order_tool"),
    CardExpenses("card_expenses", S.ah_card_expenses, "card_expenses_tool"),
    CashExpenses("cash_expenses", S.ah_cash_expenses, "cash_expenses_tool"),
    Timecard("timecard", S.timecards, "timecard_tool"),
    DealMemo("deal_memo", S.dm_title, "deal_memo_tool"),
    ;

    val label: String get() = str(labelKey)

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
enum class ApprovalScope(val wire: String, private val labelKey: String) {
    All("all", S.desktop_everyone),
    Department("department", S.department),
    ;

    val label: String get() = str(labelKey)

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
data class ApprovalRule(
    val type: String = "",
    val userIds: List<String> = emptyList(),
    /** Only an `amount` rule carries one — "approve when over this much". */
    val amountThreshold: Double? = null,
) {
    val isAssigned: Boolean get() = userIds.isNotEmpty()

    /**
     * Whether a kind has been picked.
     *
     * A new rule starts on "Select a rule..." as on the web, and an untyped
     * rule is an unfinished row: it offers no Add Users and is dropped on save.
     */
    val isTyped: Boolean get() = type.isNotBlank()

    companion object {
        /** The validator's whole vocabulary is these two. */
        const val DEFAULT = "default"
        const val AMOUNT = "amount"
    }
}

/** One level of the chain. [order] is 1-based, as shown. */
data class ApprovalTier(val order: Int, val rules: List<ApprovalRule> = emptyList()) {
    val isAssigned: Boolean get() = rules.any { it.isAssigned }

    val approverCount: Int get() = rules.sumOf { it.userIds.size }

    /**
     * Everyone on the level, across every rule.
     *
     * A person sits on a level once: the Default approver cannot also be an
     * "Amount greater than" approver on the same level — the web's
     * `collectTierUserIds`, which both its picker and its add step read.
     */
    val userIds: List<String> get() = rules.flatMap { it.userIds }

    val hasDefault: Boolean get() = rules.any { it.type == ApprovalRule.DEFAULT }

    /**
     * Whether [rule]'s kind is fixed: an amount rule on a level that already
     * has a Default. The web disables that select, so the only way to change
     * such a level is through its Default rule.
     */
    fun locks(rule: ApprovalRule): Boolean = hasDefault && rule.type == ApprovalRule.AMOUNT
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

    /** The level numbered [order], if there is one. */
    fun level(order: Int?): ApprovalTier? = order?.let { wanted -> tiers.firstOrNull { it.order == wanted } }
}

/**
 * Who may be picked as an approver on a module — the web's `pickerUsers`.
 *
 * Accepted crew holding view access on the module's tool, plus the accounts
 * team, who may approve anything. Until the rights lookup answers, or when it
 * fails, that is the accounts team alone: the web fails closed, because
 * offering the whole roster would let a chain route documents to someone who
 * cannot open them.
 */
object ApprovalCandidates {
    fun pick(users: List<HubUser>, viewRights: Set<String>?): List<HubUser> {
        val accountants = HubUsers.accountsTeam(users)
        if (viewRights == null) return accountants
        val accountantIds = accountants.map { it.id }.toSet()
        return HubUsers.accepted(users).filter { it.id in viewRights || it.id in accountantIds }
    }
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
     * The levels as a save sends them, empties still in — the web's `rawTiers`.
     *
     * Rules nobody gave a kind are dropped, along with anyone added to them
     * before the kind was cleared. Levels are numbered by position.
     */
    fun forSave(tiers: List<ApprovalTier>): List<ApprovalTier> = tiers.mapIndexed { index, tier ->
        tier.copy(order = index + 1, rules = tier.rules.filter { it.isTyped })
    }

    /**
     * Whether any "Amount greater than" rule lacks a positive threshold.
     *
     * Checked on the levels that survive compaction, as the web does, so a
     * half-filled level about to be dropped cannot block the save.
     */
    fun hasInvalidAmount(tiers: List<ApprovalTier>): Boolean = tiers.any { tier ->
        tier.rules.any { rule -> rule.type == ApprovalRule.AMOUNT && !((rule.amountThreshold ?: 0.0) > 0.0) }
    }

    /** "Level 2", "Level 2 and Level 4", "Level 1, Level 2 and Level 3" — the web's `fmtLevels`. */
    fun levelsText(levels: List<Int>): String {
        val labels = levels.map { str(S.desktop_level_n, it) }
        return if (labels.size <= 1) {
            labels.firstOrNull().orEmpty()
        } else {
            str(S.desktop_docdist_x_and_y, labels.dropLast(1).joinToString(", "), labels.last())
        }
    }

    /** What the save asks before a filled level moves up, word for word the web's. */
    fun compactionMessage(levels: List<Int>): String {
        val plural = levels.size > 1
        return if (plural) {
            str(S.desktop_hub_levels_have_no_approvers_compaction, levelsText(levels))
        } else {
            str(S.desktop_hub_level_has_no_approvers_compaction, levelsText(levels))
        }
    }

    /**
     * Why this chain cannot be saved, or null when it can.
     *
     * An out-of-sequence chain is refused outright. A chain with only trailing
     * blanks is allowed through — [compacted] removes them on the way out.
     */
    fun validationError(tiers: List<ApprovalTier>): String? = when {
        tiers.none { it.isAssigned } -> str(S.desktop_hub_add_at_least_one_approver)
        !inSequence(tiers) -> {
            val empty = emptyLevels(tiers).filter { level ->
                tiers.drop(level).any { it.isAssigned }
            }
            str(S.desktop_hub_level_x_has_no_approvers_fill_in_order, empty.joinToString(", "))
        }
        else -> null
    }
}
