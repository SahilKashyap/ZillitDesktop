package com.zillit.desktop.feature.cardexpenses.domain

/**
 * One approval chain, as `/metadata`'s `approval_tier_configs` carries it.
 *
 * `scope` is `all` or `department`; a department's own chain wins over the
 * production-wide one.
 */
data class TierConfig(
    val scope: String,
    val departmentId: String?,
    val tiers: List<ApprovalTier>,
)

/** One step of a chain: whoever its rules name may sign it off. */
data class ApprovalTier(val order: Int, val rules: List<TierRule>)

/**
 * Who may approve a tier. A `default` rule always applies; an `amount` rule
 * only at or above its threshold, and when one matches it replaces the
 * defaults.
 */
data class TierRule(val type: String, val amountThreshold: Double?, val userIds: List<String>)

/** One sign-off a card or receipt has collected. */
data class CardApproval(val userId: String, val tierNumber: Int)

/** Where a record stands in its chain, for one viewer. */
data class TierVisibility(val canApprove: Boolean, val nextTier: Int?, val totalTiers: Int)

/**
 * The approval chain, resolved the web's way (`utils/approval-helpers.js`).
 *
 * The desktop offered Approve and Reject on a card request to **any**
 * accountant and sent an empty body; the web offers them only to the person
 * the next tier names, and the approve carries `tier_number` and
 * `total_tiers` so the server knows which step was signed.
 */
object ApprovalTiers {

    /**
     * The chain that applies to a record in [departmentId], as one list of
     * user ids per tier, or null when none is configured.
     *
     * [amount] narrows `amount` rules; without it every rule's users count,
     * which is the web's display-only reading. A tier nobody qualifies for is
     * skipped and the rest renumbered, as the web does.
     */
    fun resolve(configs: List<TierConfig>, departmentId: String?, amount: Double?): List<List<String>>? {
        val departmental = departmentId?.let { id ->
            configs.firstOrNull { it.scope == DEPARTMENT && it.departmentId == id }
        }
        val config = departmental ?: configs.firstOrNull { it.scope == ALL } ?: return null
        return config.tiers
            .sortedBy { it.order }
            .map { usersFor(it, amount).distinct() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [userId] may sign the next step.
     *
     * The next step is the lowest tier nobody has signed yet. A record with no
     * chain configured can be seen by everyone and approved by nobody — the
     * web's reading, and the reason an unconfigured production shows Override
     * rather than Approve.
     */
    fun visibility(chain: List<List<String>>?, approvals: List<CardApproval>, userId: String): TierVisibility {
        val total = chain?.size ?: 0
        if (chain == null || total == 0) return TierVisibility(canApprove = false, nextTier = null, totalTiers = 0)
        val signed = approvals.map { it.tierNumber }.toSet()
        val next = (1..total).firstOrNull { it !in signed }
        val canApprove = next != null && userId.isNotBlank() && userId in chain[next - 1]
        return TierVisibility(canApprove = canApprove, nextTier = next, totalTiers = total)
    }

    /**
     * Whether [userId] is the next approver on a receipt in the approval queue.
     *
     * The queue's own test (`ApprovalQueuePage.jsx:97-118`): some chain whose
     * earlier tiers were each signed by one of that tier's people, and whose
     * next tier names this viewer. Unlike [visibility] it reads the raw tiers
     * by their `order`, as the web does there.
     */
    fun isNextApprover(configs: List<TierConfig>, receipt: CardReceipt, userId: String): Boolean {
        if (userId.isBlank()) return false
        val next = receipt.approvals.size + 1
        return configs.any { config ->
            val scoped = config.scope != DEPARTMENT || config.departmentId.isNullOrBlank() ||
                receipt.departmentId.isNullOrBlank() || config.departmentId == receipt.departmentId
            scoped && earlierTiersHold(config, receipt.approvals) &&
                config.tiers.firstOrNull { it.order == next }?.names(userId) == true
        }
    }

    private fun earlierTiersHold(config: TierConfig, approvals: List<CardApproval>): Boolean =
        approvals.withIndex().all { (index, approval) ->
            config.tiers.firstOrNull { it.order == index + 1 }?.names(approval.userId) == true
        }

    private fun ApprovalTier.names(userId: String): Boolean = rules.any { userId in it.userIds }

    private fun usersFor(tier: ApprovalTier, amount: Double?): List<String> {
        if (amount == null) return tier.rules.flatMap { it.userIds }
        val matched = tier.rules
            .filter { it.type == AMOUNT && it.amountThreshold != null && amount >= it.amountThreshold }
            .flatMap { it.userIds }
        return matched.ifEmpty { tier.rules.filter { it.type == DEFAULT }.flatMap { it.userIds } }
    }

    private const val ALL = "all"
    private const val DEPARTMENT = "department"
    private const val DEFAULT = "default"
    private const val AMOUNT = "amount"
}
