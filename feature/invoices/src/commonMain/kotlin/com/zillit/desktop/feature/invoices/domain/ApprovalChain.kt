package com.zillit.desktop.feature.invoices.domain

/** Whether a chain applies to the whole production or one department. */
enum class TierScope(val wire: String) {
    All("all"),
    Department("department"),
    ;

    companion object {
        fun from(wire: String?): TierScope = if (wire?.trim()?.lowercase() == Department.wire) Department else All
    }
}

/**
 * One rule inside a tier. `type` is `default` or `amount`; an amount rule
 * only counts once the invoice's gross reaches [amountThreshold]. Any other
 * type — blank included — counts as neither, as on the web.
 */
data class TierRule(val type: String, val amountThreshold: Double? = null, val userIds: List<String> = emptyList()) {
    val isAmount: Boolean get() = type == AMOUNT

    val isDefault: Boolean get() = type == DEFAULT

    fun applies(gross: Double): Boolean = !isAmount || (amountThreshold != null && gross >= amountThreshold)

    companion object {
        const val AMOUNT = "amount"
        const val DEFAULT = "default"
    }
}

/** One level of a configured chain, in the server's `order`. */
data class TierLevel(val order: Int, val rules: List<TierRule> = emptyList())

/** One `approval-tiers?module=invoices` row. */
data class ApprovalTierConfig(
    val id: String = "",
    val scope: TierScope = TierScope.All,
    val departmentId: String? = null,
    val tiers: List<TierLevel> = emptyList(),
)

/** A tier after resolution: renumbered 1..N, only the users who count for this invoice. */
data class ResolvedTier(val number: Int, val userIds: List<String>)

/**
 * The client-side approval helper the web computes before every `approve`
 * (`utils/approval-helpers.js`). The server enforces the same rules, but the
 * `tier_number` / `total_tiers` it is sent come from here — get them wrong and
 * the approval lands on the wrong tier.
 */
object ApprovalChain {

    /** The department's own row beats the production-wide (`all`) one. */
    fun resolveConfigForDepartment(configs: List<ApprovalTierConfig>, departmentId: String?): ApprovalTierConfig? {
        val dept = departmentId?.takeIf { it.isNotBlank() }
        return dept?.let { id ->
            configs.firstOrNull { it.scope == TierScope.Department && it.departmentId == id }
        } ?: configs.firstOrNull { it.scope == TierScope.All }
    }

    /**
     * Per tier, in the order the server lists them (the web reads the array,
     * not `order`): amount rules whose threshold the gross reaches win;
     * otherwise the default rules. Tiers that resolve to nobody are dropped
     * and the survivors renumbered 1..N (`approval-helpers.js:176-222`).
     */
    fun resolveTiers(config: ApprovalTierConfig?, gross: Double): List<ResolvedTier> {
        if (config == null) return emptyList()
        return config.tiers
            .map { tier -> usersFor(tier, gross) }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, users -> ResolvedTier(index + 1, users) }
    }

    fun tiersFor(configs: List<ApprovalTierConfig>, invoice: Invoice): List<ResolvedTier> =
        resolveTiers(resolveConfigForDepartment(configs, invoice.departmentId), invoice.grossAmount)

    fun totalTiers(tiers: List<ResolvedTier>): Int = tiers.size

    /** The first tier number nobody has signed; null once every tier has. */
    fun nextTier(tiers: List<ResolvedTier>, approvals: List<Approval>): Int? {
        val done = approvals.map { it.tierNumber }.toSet()
        return tiers.map { it.number }.firstOrNull { it !in done }
    }

    fun isApprover(tiers: List<ResolvedTier>, userId: String): Boolean =
        userId.isNotBlank() && tiers.any { userId in it.userIds }

    /** In approval, and [userId] sits on the tier that is up next. */
    fun canApprove(invoice: Invoice, tiers: List<ResolvedTier>, userId: String): Boolean {
        if (invoice.status != InvoiceStatus.Approval || userId.isBlank()) return false
        val next = nextTier(tiers, invoice.approvals) ?: return false
        return tiers.firstOrNull { it.number == next }?.userIds?.contains(userId) == true
    }

    /** Deduplicated as the web's `seen` set does; an id is kept as the server stored it. */
    private fun usersFor(tier: TierLevel, gross: Double): List<String> {
        val amountUsers = tier.rules.filter { it.isAmount && it.applies(gross) }.flatMap { it.userIds }
        val users = amountUsers.ifEmpty { tier.rules.filter { it.isDefault }.flatMap { it.userIds } }
        return users.distinct()
    }
}
