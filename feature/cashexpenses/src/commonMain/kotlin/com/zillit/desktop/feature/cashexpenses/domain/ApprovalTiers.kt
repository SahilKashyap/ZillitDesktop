package com.zillit.desktop.feature.cashexpenses.domain

/** One signature on a float or batch: who approved, at which level. */
data class TierApproval(val userId: String, val tierNumber: Int)

/** One of the production's approval chains, as `/metadata` sends it. */
data class ApprovalTierConfig(
    /** `all`, or `department` for a chain that belongs to [departmentId]. */
    val scope: String,
    val departmentId: String?,
    val tiers: List<ApprovalTier>,
)

/** One level of a chain; its rules decide who may sign at it. */
data class ApprovalTier(val rules: List<TierRule>)

/** `default`, or `amount` with a threshold the document has to reach. */
data class TierRule(val type: String, val amountThreshold: Double?, val userIds: List<String>)

/** The level an approval is signed at, sent as `tier_number` / `total_tiers`. */
data class TierStep(val tierNumber: Int, val totalTiers: Int)

/**
 * The web's approval-chain arithmetic (`utils/approval-helpers.js`).
 *
 * The approval routes take the level being signed — `tier_number` and
 * `total_tiers` — and the web works both out here before every approve. The
 * desktop sent neither, so the server had nothing to record the signature
 * against.
 *
 * Resolution: the department's own chain wins, else the production-wide one.
 * At each level the users of any amount rule the document reaches are the
 * approvers; failing that, the default rule's. A level nobody qualifies for is
 * skipped and the rest renumber from one.
 */
object ApprovalTiers {

    /** Level number → who may sign at it; null when no chain applies. */
    fun resolve(
        configs: List<ApprovalTierConfig>,
        departmentId: String?,
        amount: Double?,
    ): Map<Int, List<String>>? {
        if (configs.isEmpty()) return null
        val department = departmentId?.takeIf { it.isNotBlank() }?.let { id ->
            configs.firstOrNull { it.scope == DEPARTMENT && it.departmentId == id }
        }
        val config = department ?: configs.firstOrNull { it.scope == ALL } ?: return null
        val levels = config.tiers
            .map { tier -> usersFor(tier, amount).distinct() }
            .filter { it.isNotEmpty() }
        if (levels.isEmpty()) return null
        return levels.mapIndexed { index, users -> index + 1 to users }.toMap()
    }

    fun total(resolved: Map<Int, List<String>>?): Int = resolved?.size ?: 0

    /** The first level nobody has signed yet, or null once all have been. */
    fun next(approvals: List<TierApproval>, resolved: Map<Int, List<String>>?): Int? {
        val total = total(resolved)
        if (total == 0) return null
        val signed = approvals.map { it.tierNumber }.toSet()
        return (1..total).firstOrNull { it !in signed }
    }

    fun canApprove(resolved: Map<Int, List<String>>?, tier: Int, userId: String): Boolean =
        resolved?.get(tier).orEmpty().contains(userId)

    /**
     * The level this person may sign next, or null when it is not theirs.
     *
     * Both halves: the chain has an unsigned level, and they are one of that
     * level's approvers.
     */
    fun stepFor(
        configs: List<ApprovalTierConfig>,
        departmentId: String?,
        amount: Double?,
        approvals: List<TierApproval>,
        userId: String,
    ): TierStep? {
        val resolved = resolve(configs, departmentId, amount)
        val next = next(approvals, resolved) ?: return null
        if (!canApprove(resolved, next, userId)) return null
        return TierStep(next, total(resolved))
    }

    /**
     * An accountant looking at a record no chain covers — the web's
     * `isAccountant && !hasTier1ApproverForDept(resolved, department_id)`
     * (`PCApprovalPage.jsx:280, 1291`). Nobody can approve it until a level
     * is set, so the queue offers "Set Approval Level" in place of the actions.
     */
    fun needsApprovalLevel(viewer: CashViewer, departmentId: String?, amount: Double): Boolean =
        viewer.isAccountant &&
            resolve(viewer.metadata.approvalTierConfigs, departmentId, amount) == null

    /** How many levels the chain covering this record has; zero when none does. */
    fun totalFor(viewer: CashViewer, departmentId: String?, amount: Double): Int =
        total(resolve(viewer.metadata.approvalTierConfigs, departmentId, amount))

    private fun usersFor(tier: ApprovalTier, amount: Double?): List<String> {
        if (amount == null) return tier.rules.flatMap { it.userIds }
        val matched = tier.rules
            .filter { it.type == AMOUNT && it.amountThreshold != null && amount >= it.amountThreshold }
            .flatMap { it.userIds }
        if (matched.isNotEmpty()) return matched
        return tier.rules.filter { it.type == DEFAULT }.flatMap { it.userIds }
    }

    private const val ALL = "all"
    private const val DEPARTMENT = "department"
    private const val AMOUNT = "amount"
    private const val DEFAULT = "default"
}
