package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.ApprovalTierConfig
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** One `approvals[]` row: `{tier_number, action, user_id, acted_at}`. */
data class DealApproval(val tierNumber: Int?, val action: String?, val userId: String?, val actedAt: Long?) {
    val isApprove: Boolean get() = action == APPROVE

    companion object {
        private const val APPROVE = "approve"

        fun listOf(deal: DealDoc): List<DealApproval> = DocRead.objects(deal.json["approvals"]).map { row ->
            DealApproval(
                // Compared strictly as a number, as the web's `===` does.
                tierNumber = (row["tier_number"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                    ?.takeIf { it == floor(it) }?.toInt(),
                action = DocRead.text(row, "action"),
                userId = DocRead.text(row, "user_id"),
                actedAt = DocRead.epoch(row, "acted_at"),
            )
        }
    }
}

enum class ChainNodeState { Done, Current, Pending }

/** One level of the chain as the approval card draws it. */
data class ChainNode(
    val tier: Int,
    val state: ChainNodeState,
    /** The approver who signed this level, when they are in the directory. */
    val approverId: String?,
    val actedAt: Long?,
)

/**
 * The approval chain for one deal (`DMDealPreviewPage.jsx:1405-1492`).
 *
 * The chain is the tier config scoped to the deal's top-level department,
 * else the project-wide one. Levels are expected to be numbered 1..N — the
 * next level is the lowest number with no approval, whatever order the config
 * lists them in.
 */
data class ApprovalChain(
    val nodes: List<ChainNode>,
    /** The first level (in the config's order) whose rules name the viewer. */
    val userTier: Int?,
    /** The viewer's level is next: every lower level approved and theirs not yet. */
    val canAct: Boolean,
) {
    val approvedCount: Int get() = nodes.count { it.state == ChainNodeState.Done }

    companion object {
        val None = ApprovalChain(emptyList(), null, false)

        fun of(deal: DealDoc, metadata: DealMemoMetadata, viewerId: String?): ApprovalChain {
            val config = configFor(deal, metadata) ?: return None
            val approvals = DealApproval.listOf(deal).filter { it.isApprove }
            fun approvalAt(tier: Int) = approvals.firstOrNull { it.tierNumber == tier }
            val nextTier = (1..config.tiers.size).firstOrNull { approvalAt(it) == null }
            val nodes = config.tiers.sortedBy { it.order }.map { tier ->
                val approval = approvalAt(tier.order)
                ChainNode(
                    tier = tier.order,
                    state = when {
                        approval != null -> ChainNodeState.Done
                        tier.order == nextTier -> ChainNodeState.Current
                        else -> ChainNodeState.Pending
                    },
                    approverId = approval?.userId,
                    actedAt = approval?.actedAt,
                )
            }
            val userTier = viewerId?.takeIf { it.isNotEmpty() }?.let { id ->
                config.tiers.firstOrNull { tier -> tier.rules.any { id in it } }?.order
            }
            val canAct = userTier != null &&
                (1 until userTier).all { approvalAt(it) != null } &&
                approvalAt(userTier) == null
            return ApprovalChain(nodes, userTier, canAct)
        }

        /** The deal's department config — only when the deal names a department — else the `all` one. */
        private fun configFor(deal: DealDoc, metadata: DealMemoMetadata): ApprovalTierConfig? {
            val departmentId = deal.departmentId
            val scoped = departmentId?.let { id ->
                metadata.approvalTierConfigs.firstOrNull {
                    it.scope == ApprovalTierConfig.SCOPE_DEPARTMENT && it.departmentId == id
                }
            }
            return scoped ?: metadata.approvalTierConfigs.firstOrNull { it.scope == ApprovalTierConfig.SCOPE_ALL }
        }
    }
}
