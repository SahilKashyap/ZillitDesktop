package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimField

/**
 * What the batch view — Post & Ledger, Audit Queue, History and the Coding
 * Queue — does to an open batch, beyond what [CashEvent] already names.
 *
 * Its own sealed family so the view model routes it in one branch to
 * [BatchDesk]. None of these asks first: the web fires each directly
 * (`PCPostLedgerPage.jsx:1047-1088`). Post to Ledger keeps its confirmation
 * ([ConfirmAction.PostBatch]) — see [BatchDesk.post].
 */
sealed interface BatchEvent : CashEvent {

    /** One receipt fact typed on its card. */
    data class EditClaim(val claimId: String, val field: ClaimField, val value: String) : BatchEvent

    /** "Split into lines" / "Edit split": the receipt's lines in the split editor, seeded when it has none. */
    data class OpenSplit(val claimId: String) : BatchEvent

    /** The split editor's lines onto the receipt — held, like every other edit, until the batch is saved. */
    data object ApplySplit : BatchEvent

    /** Adds the receipt's reclaimable-tax line to the split editor. */
    data object AddTaxLine : BatchEvent

    /** Save / Save Progress / Save Draft — `save-claims` with every receipt. */
    data class Save(val batchId: String) : BatchEvent

    /** Audit's Send for Approval — `save-and-verify`. */
    data class SendForApproval(val batchId: String) : BatchEvent

    /** Post & Ledger's Submit for Review. */
    data class SubmitForReview(val batchId: String) : BatchEvent

    /** Coding's Forward to Accounts — `save-and-submit`. */
    data class ForwardCoded(val batchId: String) : BatchEvent
}

/** The pages that open a batch in the web's full batch view (`PostModal`). */
internal val CashDestination.isBatchWorkPage: Boolean
    get() = this == CashDestination.CodingQueue ||
        this == CashDestination.AuditQueue ||
        this == CashDestination.History ||
        isPostLedger

/** The queues whose rows belong to their assignee and a senior — Post & Ledger and the Audit Queue. */
internal val CashDestination.locksToAssignee: Boolean
    get() = isPostLedger || this == CashDestination.AuditQueue

/**
 * May this viewer work on [batch] on this page: a coordinator in the coding
 * queue; an accountant in audit, post and history — in the two assignee
 * queues only on a row that is theirs or, for a senior, anyone's.
 */
internal fun CashUiState.mayWorkOn(batch: ClaimBatch): Boolean = when {
    destination == CashDestination.CodingQueue -> viewer.isCoordinator
    destination == CashDestination.History -> viewer.isAccountant
    destination.locksToAssignee -> viewer.isAccountant && CashRules.canOpenPostRow(viewer, batch)
    else -> false
}

/** Whether the open batch's receipts can be edited now: rights, loaded, and not in the locked period. */
internal fun CashUiState.mayEdit(batch: ClaimBatch): Boolean =
    mayWorkOn(batch) && !selectedLocked && panel?.takeIf { it.batchId == batch.id }?.claims != null
