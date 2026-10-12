package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Whether the deal's page should say that no approver is set up and offer
 * "Set Approval Level" — the Deal Memo twin of PO's "Set Approver"
 * (`utils/approvalChainDisplay.js` `shouldPromptApprovalLevel`).
 *
 * Such a deal sits in nobody's queue, and the approval card hides itself (it
 * needs a level), so without this the page just looks idle. The test is the
 * shared one the web's PO and card prompts use — the deal's resolved chain
 * (its department's config, else the production-wide one) has no level-1
 * approver — not [com.zillit.desktop.feature.dealmemo.domain.preview.ApprovalChain],
 * which compares departments differently.
 */
object ApprovalLevelPrompt {

    /**
     * [metadata] must have answered: until `/metadata` lands, and for good if
     * every retry fails, its configs are the empty default, which would read as
     * "no approvers" on every deal. [embedded] is anywhere but the deal's own
     * detail page (My Deal). Warns only — nothing is blocked.
     */
    fun shouldShow(
        status: String?,
        embedded: Boolean,
        canSetApprovers: Boolean,
        metadata: DealMemoMetadata,
        departmentId: String?,
    ): Boolean {
        val applies = !embedded && canSetApprovers && status == DealStatus.AwaitingApproval.wire
        return applies && metadata.loaded && !hasLevelOne(metadata.approvalTierConfigs, departmentId)
    }

    /**
     * The web's `hasTier1ApproverForDept(resolveConfigForDepartment(...))`, with
     * no amount: the department's own config when the deal names one, else the
     * `all` config. Levels with nobody are skipped when it numbers them, so a
     * level 1 exists exactly when any level of the chosen chain holds someone —
     * and a department config that is chosen but empty does not fall back.
     */
    fun hasLevelOne(configs: List<ApprovalTierConfig>, departmentId: String?): Boolean {
        // A blank id resolves like no id.
        val department = departmentId?.takeIf { it.isNotEmpty() }
        val own = department?.let { id ->
            configs.firstOrNull { it.scope == ApprovalTierConfig.SCOPE_DEPARTMENT && it.departmentId == id }
        }
        val chosen = own ?: configs.firstOrNull { it.scope == ApprovalTierConfig.SCOPE_ALL }
        return chosen != null && chosen.tiers.any { it.userIds.isNotEmpty() }
    }
}

/**
 * All Deals' "Approvers not configured" banner — the pure half
 * (`utils/approversBanner.js`). Decides which departments have nobody to
 * approve their deals.
 *
 * A department is covered by its own `department` config, else by the
 * production-wide (`all`) one. The `all` config with any approver covers
 * every department.
 */
object ApproversBanner {

    /** True when ANY level of the config names at least one approver. */
    fun hasApprovers(config: ApprovalTierConfig?): Boolean =
        config != null && config.tiers.any { it.userIds.isNotEmpty() }

    private fun cleanId(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != NULL_TEXT }

    /** The deal's master department id — top level first, then `crew_details` (drafts). */
    fun departmentIdOf(deal: DealDoc): String? =
        cleanId(deal.departmentId) ?: cleanId(DocRead.text(deal.crew, "department_id"))

    private fun labelOf(deal: DealDoc): String {
        val raw = DocRead.text(deal.json, "department_name") ?: DocRead.text(deal.crew, "department_name")
        return DealLabels.formatLabel(raw)
    }

    /**
     * Display names of the departments whose deals nobody can approve,
     * de-duplicated and sorted case-insensitively. [noDepartmentName] stands in
     * when the only uncovered deals carry no department at all.
     */
    fun missingDepartments(
        configs: List<ApprovalTierConfig>,
        deals: List<DealDoc>,
        departmentNames: Map<String, String>,
        noDepartmentName: String,
    ): List<String> {
        val global = configs.firstOrNull { it.scope == ApprovalTierConfig.SCOPE_ALL }
        if (hasApprovers(global)) return emptyList()

        val byDepartment = configs
            .filter { it.scope == ApprovalTierConfig.SCOPE_DEPARTMENT && !it.departmentId.isNullOrEmpty() }
            .associateBy { it.departmentId.orEmpty() }

        val names = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var unlabelledWithoutDepartment = false
        for (deal in deals) {
            val id = departmentIdOf(deal)
            if (id == null) {
                // Nothing can approve these without a Global config.
                val label = labelOf(deal)
                if (label.isEmpty()) unlabelledWithoutDepartment = true else names += label
            } else if (seen.add(id) && !hasApprovers(byDepartment[id] ?: global)) {
                names += departmentNames[id]?.takeIf { it.isNotEmpty() } ?: labelOf(deal).ifEmpty { id }
            }
        }
        if (unlabelledWithoutDepartment && names.isEmpty()) names += noDepartmentName
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** The banner's closing sentence — what this viewer can do about it. */
    fun tail(canEdit: Boolean): String =
        if (canEdit) str(S.desktop_dm_approvers_tail_edit) else str(S.desktop_dm_approvers_tail_ask)

    /** The banner text, or null when every department is covered. */
    fun message(missing: List<String>, canEdit: Boolean): String? {
        if (missing.isEmpty()) return null
        val head = if (missing.size == 1) {
            str(S.desktop_dm_no_approvers_set_one, missing.first())
        } else {
            str(S.desktop_dm_no_approvers_set_many, missing.size)
        }
        return "$head ${tail(canEdit)}"
    }

    /**
     * What the banner says when the tier configs could not be read: the
     * metadata's `approvers_configured === false`, with at least one deal
     * waiting. [approversConfigured] is null when the metadata does not say.
     */
    fun fallbackMessage(approversConfigured: Boolean?, awaiting: Int, canEdit: Boolean): String? {
        if (approversConfigured != false || awaiting <= 0) return null
        val head = if (awaiting == 1) {
            str(S.desktop_dm_awaiting_no_approvers_one)
        } else {
            str(S.desktop_dm_awaiting_no_approvers_many, awaiting)
        }
        return "$head ${tail(canEdit)}"
    }

    /**
     * What All Deals' banner says, or null for none. Only posting users see it
     * — the All Deals audience and exactly who the Approvers page lets edit, so
     * the wording always offers the fix. When the configs could not be read it
     * falls back to the metadata's flag; while they have not been read yet it
     * says nothing.
     */
    fun messageFor(
        canPost: Boolean,
        tierConfigs: List<ApprovalTierConfig>?,
        tiersFailed: Boolean,
        deals: List<DealDoc>,
        departmentNames: Map<String, String>,
        approversConfigured: Boolean?,
        noDepartmentName: String,
    ): String? = when {
        !canPost -> null
        tiersFailed -> fallbackMessage(
            approversConfigured,
            deals.count { it.rawStatus == DealStatus.AwaitingApproval.wire },
            canEdit = true,
        )
        tierConfigs == null -> null
        else -> message(missingDepartments(tierConfigs, deals, departmentNames, noDepartmentName), canEdit = true)
    }

    private const val NULL_TEXT = "null"
}
