package com.zillit.desktop.feature.cashexpenses.domain

import kotlin.math.abs
import kotlin.math.round

/**
 * The Post & Ledger, Audit and Sign-off rules, as the web states them
 * (`lib/postLedgerActions.js`, `PCPostLedgerPage.jsx`, `SeniorBatchItem.jsx`).
 *
 * Pure functions over the viewer and the batch so the screen and the view
 * model ask the same question and cannot disagree about the answer — the
 * port's recurring defect was a screen that gated an action and a handler
 * that did not.
 */
object CashRules {

    /** Statuses the Post & Ledger list shows; the sign-off queue also carries escalated ones. */
    val POSTABLE: Set<BatchStatus> = setOf(BatchStatus.ReadyToPost, BatchStatus.AcctOverride, BatchStatus.UnderReview)

    /** Statuses a batch can carry a query thread in — the web's `QUERY_BADGE_STATUSES`. */
    private val QUERYABLE: Set<BatchStatus> = setOf(
        BatchStatus.InAudit,
        BatchStatus.ReadyToPost,
        BatchStatus.UnderReview,
        BatchStatus.Escalated,
    )

    fun isReviewing(batch: ClaimBatch): Boolean =
        batch.status == BatchStatus.UnderReview || batch.status == BatchStatus.Escalated

    /**
     * Who may open a Post & Ledger row.
     *
     * An unassigned batch is a senior's to pick up; an assigned one is its
     * assignee's, and a senior's. Everyone else sees the row locked.
     */
    fun canOpenPostRow(viewer: CashViewer, batch: ClaimBatch): Boolean {
        val assignee = batch.assignedTo?.takeIf { it.isNotBlank() }
        return viewer.isSenior || (assignee != null && assignee == viewer.userId)
    }

    /**
     * Whether a review or query flag stops this viewer posting.
     *
     * Only when there is a sign-off flow to route the batch into — with it off,
     * a flagged batch would strand its assignee with no action at all.
     */
    fun flagsBlockPosting(viewer: CashViewer, claims: List<Claim>): Boolean {
        val flagged = claims.any { claim -> claim.processingFlags.any { it == REVIEW || it == QUERY } }
        return viewer.metadata.requireSeniorSignOff && !viewer.isSenior && flagged
    }

    /**
     * May this viewer post this batch — the web's `canPostBatch`.
     *
     * A senior posts by role. Anyone else needs to be on the team with an
     * unlimited or non-zero posting limit. A flag that blocks posting wins
     * over both. The amount is not compared with the limit: the web does not,
     * and the server holds the ceiling.
     */
    fun canPost(viewer: CashViewer, claims: List<Claim>): Boolean {
        if (flagsBlockPosting(viewer, claims)) return false
        if (viewer.isSenior) return true
        val metadata = viewer.metadata
        val limit = metadata.postingLimit
        return metadata.isTeamMember && (metadata.postingLimitUnlimited || (limit != null && limit > 0))
    }

    /** Escalate: a non-senior kicks a batch not already under review up to a senior. */
    fun canEscalate(viewer: CashViewer, batch: ClaimBatch): Boolean =
        viewer.metadata.requireSeniorSignOff && !viewer.isSenior && !isReviewing(batch)

    /** Submit for Review: a non-senior who cannot post hands the batch to a senior. */
    fun canSubmitForReview(viewer: CashViewer, batch: ClaimBatch, claims: List<Claim>): Boolean =
        viewer.metadata.requireSeniorSignOff && !viewer.isSenior && !canPost(viewer, claims) && !isReviewing(batch)

    fun canQuery(batch: ClaimBatch): Boolean = batch.status in QUERYABLE

    /** Return to Accounts is offered on an escalated batch only. */
    fun canReturnToAccounts(batch: ClaimBatch): Boolean = batch.status == BatchStatus.Escalated

    // -- approvals ------------------------------------------------------------

    /**
     * The level this viewer signs next, or null when it is not theirs.
     *
     * A production whose metadata carries no chains at all falls back to the
     * approver grant, sending no level — the call as it was before chains
     * existed — rather than leaving every approver with nothing to press.
     */
    fun approvalStep(
        viewer: CashViewer,
        departmentId: String?,
        amount: Double,
        approvals: List<TierApproval>,
    ): TierStep? = ApprovalTiers.stepFor(
        viewer.metadata.approvalTierConfigs,
        departmentId,
        amount,
        approvals,
        viewer.userId,
    )

    fun mayApprove(viewer: CashViewer, departmentId: String?, amount: Double, approvals: List<TierApproval>): Boolean =
        if (viewer.metadata.approvalTierConfigs.isEmpty()) {
            viewer.isApprover
        } else {
            approvalStep(viewer, departmentId, amount, approvals) != null
        }

    fun mayApprove(viewer: CashViewer, batch: ClaimBatch): Boolean =
        mayApprove(viewer, batch.departmentId, batch.totalGross, batch.approvals)

    fun mayApprove(viewer: CashViewer, float: CashFloat): Boolean =
        mayApprove(viewer, float.departmentId, float.requestedAmount, float.approvals)

    // -- the coding the post and the audit require ---------------------------

    /**
     * What one receipt's coding comes to.
     *
     * Lines when it has any; a single cost code counts the receipt at its
     * gross; nothing counts nothing.
     */
    fun codedAmount(claim: Claim): Double = when {
        claim.lineItems.isNotEmpty() -> LineItemEditor.total(LineItemEditor.fromWire(claim.lineItems))
        !claim.costCode.isNullOrBlank() -> claim.grossAmount
        else -> 0.0
    }

    fun codedTotal(claims: List<Claim>): Double = round2(claims.sumOf(::codedAmount))

    /**
     * The coded total differs from the batch gross by more than a penny.
     *
     * A zero or missing gross never blocks — the web's `isAmountMismatch`.
     */
    fun amountMismatch(claims: List<Claim>, batchGross: Double): Boolean {
        if (batchGross <= 0) return false
        return abs(round((codedTotal(claims) - batchGross) * HUNDRED) / HUNDRED) > PENNY_TOLERANCE
    }

    /** Lines that would reach the ledger with no nominal code. */
    fun missingNominals(claims: List<Claim>): Int = claims.sumOf { claim ->
        claim.lineItems.count { line ->
            !line.autoDeduction && line.account.isNullOrBlank() && !(line.description.isBlank() && line.total == 0.0)
        }
    }

    /** Coded in full: every line has a code and the lines reach the receipt, or a cost code on its own. */
    fun isCoded(claim: Claim): Boolean {
        if (claim.lineItems.isEmpty()) return !claim.costCode.isNullOrBlank()
        val coded = claim.lineItems.filterNot { it.autoDeduction }.all { !it.account.isNullOrBlank() }
        return coded && abs(codedAmount(claim) - claim.grossAmount) < LINE_TOLERANCE
    }

    fun allCoded(claims: List<Claim>): Boolean = claims.isNotEmpty() && claims.all(::isCoded)

    fun allVerified(claims: List<Claim>): Boolean = claims.isNotEmpty() && claims.all { it.isVerified }

    private const val REVIEW = "review"
    private const val QUERY = "query"
    private const val HUNDRED = 100.0
    private const val PENNY_TOLERANCE = 0.01
    private const val LINE_TOLERANCE = 0.005
}
