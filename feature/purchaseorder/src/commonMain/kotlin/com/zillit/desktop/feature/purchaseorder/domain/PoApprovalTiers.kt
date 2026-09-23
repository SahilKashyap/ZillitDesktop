package com.zillit.desktop.feature.purchaseorder.domain

/**
 * The production's purchase-order approval chain, as the Approvers screen
 * configured it — the web's `approvalTiersApi.list("purchase_orders")`.
 *
 * Two shapes reach the client and both are modelled, exactly as the web's
 * `resolveConfigForDepartment` accepts both: the current one, a list of
 * [PoTierConfig] rows (one for the whole production, one per department that
 * has its own chain), and the legacy one, already resolved — tier number to the
 * approvers who sit on it ([legacy]).
 */
data class PoApprovalTiers(
    val configs: List<PoTierConfig> = emptyList(),
    /** The legacy `{ "1": [{user_id, department_id}], ... }` shape, when the server sent it. */
    val legacy: Map<Int, List<PoTierApprover>> = emptyMap(),
) {
    val isEmpty: Boolean get() = configs.isEmpty() && legacy.isEmpty()

    /**
     * The chain that applies to one order — the web's
     * `resolveConfigForDepartment(tierConfig, po.department_id, po.totalAmount,
     * { fallbackToGlobal: true })`, which every PO caller passes.
     *
     * The department's own chain wins over the production's; within a tier an
     * amount rule the order clears replaces the default approvers; a tier with
     * nobody left for this amount borrows the production-wide tier at the same
     * position; a tier still empty is skipped and the rest renumbered. Each
     * entry of the answer is one tier, tier 1 first.
     */
    fun resolve(departmentId: String?, amount: Double?): List<List<PoTierApprover>> {
        if (configs.isEmpty()) return legacy.entries.sortedBy { it.key }.map { it.value }
        val department = departmentId?.takeIf { it.isNotBlank() }
            ?.let { id -> configs.firstOrNull { it.scope == SCOPE_DEPARTMENT && it.departmentId == id } }
        val production = configs.firstOrNull { it.scope == SCOPE_ALL }
        val chosen = department ?: production ?: return emptyList()
        val borrowed = if (department != null && production != null && production !== department) production else null
        return chosen.tiers.mapIndexedNotNull { index, tier ->
            val users = tier.usersFor(amount).ifEmpty { borrowed?.tiers?.getOrNull(index)?.usersFor(amount).orEmpty() }
            users.distinct().takeIf { it.isNotEmpty() }?.map { PoTierApprover(it, departmentId) }
        }
    }

    /**
     * Where an order stands in its chain, and whether [userId] may decide it
     * now — the web's `getApprovalVisibility`.
     *
     * Only a pending order has a decision to take, and only the approvers of
     * its *next* undecided tier may take it. With no chain configured nobody
     * can approve, which is the web's answer too.
     */
    fun visibility(order: PurchaseOrder, userId: String): PoApprovalStep {
        val chain = resolve(order.departmentId, order.gross)
        val total = chain.size
        val approved = order.approvals.filter { it.decided }
        if (order.status != PoStatus.AwaitingApproval || total == 0) {
            return PoApprovalStep(nextTier = null, totalTiers = total, approvedCount = approved.size)
        }
        val approvedTiers = approved.map { it.level }.toSet()
        val next = (1..total).firstOrNull { it !in approvedTiers }
        // Tier 1 is department-scoped in the legacy shape; a resolved chain
        // carries the order's own department, so it always matches.
        val canApprove = next != null && chain[next - 1].any { approver ->
            approver.userId == userId && approver.coversDepartment(order.departmentId)
        }
        return PoApprovalStep(
            nextTier = next,
            totalTiers = total,
            approvedCount = approved.size,
            canApprove = canApprove,
        )
    }

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_DEPARTMENT = "department"
        const val RULE_DEFAULT = "default"
        const val RULE_AMOUNT = "amount"
    }
}

/** One chain: the production's (`scope = all`) or one department's. */
data class PoTierConfig(
    val scope: String,
    val departmentId: String?,
    val tiers: List<PoTier>,
)

/** One level of a chain, and who may approve at it. */
data class PoTier(val order: Int, val rules: List<PoTierRule>) {

    /**
     * Who approves this tier for an order of [amount].
     *
     * The web's `usersForTier`: amount rules the order reaches win, otherwise
     * the default rule's people; with no amount at all (display only), every
     * rule's people.
     */
    fun usersFor(amount: Double?): List<String> {
        if (amount == null) return rules.flatMap { it.userIds }
        val matched = rules
            .filter { it.type == PoApprovalTiers.RULE_AMOUNT }
            .filter { rule -> rule.amountThreshold?.let { amount >= it } == true }
            .flatMap { it.userIds }
        if (matched.isNotEmpty()) return matched
        return rules.filter { it.type == PoApprovalTiers.RULE_DEFAULT }.flatMap { it.userIds }
    }
}

data class PoTierRule(val type: String, val amountThreshold: Double?, val userIds: List<String>)

data class PoTierApprover(val userId: String, val departmentId: String?) {
    /** The web's `canUserApproveAtTier`: a department on both sides must be the same one. */
    fun coversDepartment(orderDepartment: String?): Boolean =
        departmentId == null || orderDepartment == null || departmentId == orderDepartment
}

/**
 * The next decision on one order: which tier it is, how many there are, and
 * whether the viewer is the one to make it.
 */
data class PoApprovalStep(
    val nextTier: Int?,
    val totalTiers: Int,
    val approvedCount: Int,
    val canApprove: Boolean = false,
) {
    /**
     * The body the approve route wants — the web's
     * `{ tier_number: vis.nextTier || totalTiers || 1, total_tiers: totalTiers || 1 }`.
     */
    val tierNumber: Int get() = nextTier ?: totalTiers.takeIf { it > 0 } ?: 1
    val tierCount: Int get() = totalTiers.takeIf { it > 0 } ?: 1
}
