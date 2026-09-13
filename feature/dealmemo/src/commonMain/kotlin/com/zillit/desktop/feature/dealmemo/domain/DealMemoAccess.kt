package com.zillit.desktop.feature.dealmemo.domain

/**
 * What this person may do in the deal memo tool — the web's
 * `resolveDealMemoRights` (`hooks/useDealMemoRights.js`).
 *
 * Accounts-department members are posting users whatever the tool grid says:
 * every accountant must see the management tabs and pass every posting check.
 * A member whose invitation is still pending has not joined the production —
 * they reach the tool for their own deal and nothing else, so they never post,
 * but they must still *view*, or the tool would turn them away before My Deal.
 *
 * Only an explicit `pending` restricts. The member list lands after the tool
 * opens, and treating "not known yet" as pending would flash My Deal at every
 * accountant on every load.
 */
data class DealMemoRights(
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val isPending: Boolean = false,
    /** The tool grid has answered at least once — before that, nothing is refused. */
    val rightsLoaded: Boolean = false,
) {
    companion object {
        private const val ACCOUNTS = "accounts"
        private const val PENDING = "pending"

        fun resolve(
            rightsLoaded: Boolean,
            hasPostingAccess: Boolean,
            hasViewAccess: Boolean,
            departmentIdentifier: String?,
            memberStatus: String?,
        ): DealMemoRights {
            val isAccountant = departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true
            val isPending = memberStatus?.trim()?.equals(PENDING, ignoreCase = true) == true
            return DealMemoRights(
                canView = hasPostingAccess || hasViewAccess || isAccountant,
                canPost = !isPending && (hasPostingAccess || isAccountant),
                isPending = isPending,
                rightsLoaded = rightsLoaded,
            )
        }
    }
}

/**
 * The deal-memo metadata — the web's `dealMemoApi.getMetadata()`, fetched once
 * per module entry and read by three gates: the preview's approval chain, its
 * Approve & Sign action, and the Approval Queue tab.
 *
 * Every one of those gates is closed by [Unknown]. That is why the fetch
 * retries (`DealMemoModule.jsx` `METADATA_RETRY_DELAYS_MS`): one lost request
 * used to hide the whole approval card from an approver for the rest of the
 * visit, with nothing on screen to say so.
 */
data class DealMemoMetadata(
    val isApprover: Boolean = false,
    val approverDepartmentIds: List<String> = emptyList(),
    val approvalTierConfigs: List<ApprovalTierConfig> = emptyList(),
    /** Whether a successful answer has arrived — the retry stops here. */
    val loaded: Boolean = false,
) {
    /**
     * The chain a deal goes through: the config scoped to the deal's top-level
     * master department, else the project-wide one (`DMDealPreviewPage.jsx:1405-1420`).
     */
    fun configFor(departmentId: String?): ApprovalTierConfig? =
        approvalTierConfigs.firstOrNull {
            it.scope == ApprovalTierConfig.SCOPE_DEPARTMENT && it.departmentId == departmentId
        }
            ?: approvalTierConfigs.firstOrNull { it.scope == ApprovalTierConfig.SCOPE_ALL }

    companion object {
        val Unknown = DealMemoMetadata()

        /** The web's retry cadence after a failed or empty answer. */
        val RETRY_DELAYS_MILLIS: List<Long> = listOf(2_000L, 8_000L, 30_000L)
    }
}

/** One approval chain: `{scope: "department" | "all", department_id, tiers[]}`. */
data class ApprovalTierConfig(
    val scope: String,
    val departmentId: String? = null,
    val tiers: List<ApprovalTier> = emptyList(),
) {
    companion object {
        const val SCOPE_DEPARTMENT = "department"
        const val SCOPE_ALL = "all"
    }
}

/** A tier: its order in the chain and the people any of whose rules may sign it. */
data class ApprovalTier(val order: Int, val rules: List<List<String>> = emptyList()) {
    val userIds: List<String> get() = rules.flatten().distinct()
}
