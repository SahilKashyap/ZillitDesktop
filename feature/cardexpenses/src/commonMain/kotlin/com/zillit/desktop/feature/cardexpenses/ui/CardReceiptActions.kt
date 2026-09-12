package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachment
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.PickKind
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding

/**
 * Everything that happens to one receipt: choosing it, attaching to it,
 * uploading it, coding it.
 *
 * Its own collaborator alongside [CardRegisterActions], for the same reason —
 * these are the actions with real rules of their own (a headroom gate, a
 * three-way coding commit, a picker that has to be allowed to be cancelled)
 * rather than a call and a reload.
 */
internal class CardReceiptActions(
    private val vm: CardExpensesViewModel,
    private val uploader: CardAttachmentUploader?,
) {

    /**
     * Selecting a receipt fetches what it might match, its trail, and seeds
     * the coding editor.
     *
     * All three at once because the detail pane shows all three, and a pane
     * that fills in stages reads as broken.
     */
    fun select(receiptId: String?) {
        val receipt = vm.current.receipts.firstOrNull { it.id == receiptId }
        vm.update {
            copy(
                selectedReceiptId = receiptId,
                matchCandidates = emptyList(),
                receiptHistory = emptyList(),
                coding = receipt?.let(CodingDraft::of),
            )
        }
        if (receipt == null) return
        // Only where there is no match: a matched receipt with a list of
        // alternatives underneath invites someone to re-point it for no reason.
        if (receipt.transactionId == null) loadCandidates(receipt.id)
        loadHistory(receipt.id)
    }

    /**
     * Fills a pane the reader may already have left.
     *
     * Both of these check what is selected *after* the call returns, because
     * rows belonging to a receipt nobody is looking at would replace the ones
     * they are reading.
     */
    private fun loadCandidates(receiptId: String) = vm.run {
        val candidates = vm.repo.matchCandidates(receiptId).getOrNull() ?: return@run
        if (vm.current.selectedReceiptId == receiptId) {
            vm.update { copy(matchCandidates = candidates) }
        }
    }

    private fun loadHistory(receiptId: String) = vm.run {
        val trail = vm.repo.receiptHistory(receiptId).getOrNull() ?: return@run
        if (vm.current.selectedReceiptId == receiptId) {
            vm.update { copy(receiptHistory = trail) }
        }
    }

    /**
     * Picks a receipt image or PDF and stores it against one draft row.
     *
     * A cancelled picker is not a failure and says nothing: the person changed
     * their mind, which is not something to apologise for.
     */
    fun attach(index: Int) {
        val pick = uploader ?: return
        vm.run {
            vm.update { copy(uploading = true) }
            val stored = pick.pick(PickKind.Receipt)
            vm.update { copy(uploading = false) }
            when (stored) {
                is ZillitResult.Failure -> vm.fail(stored.error.localised())
                is ZillitResult.Success -> stored.data?.let { file -> put(index, file) }
            }
        }
    }

    fun clearAttachment(index: Int) = vm.update {
        copy(
            draft = draft.mapIndexed { position, row ->
                if (position == index) row.copy(attachmentKey = null, attachmentName = null) else row
            },
        )
    }

    /**
     * Submits the drafted receipts, refusing above the card's headroom.
     *
     * This gate blocks, unlike the cash float's: a card limit is an
     * authorisation the production granted, and exceeding it is an overspend
     * rather than something to route to a reimbursement.
     */
    fun submitDraft() {
        val state = vm.current
        val invalid = state.draft.firstNotNullOfOrNull { receipt ->
            when {
                receipt.description.isBlank() -> "Each receipt needs a description."
                receipt.amountValue <= 0 -> "Each receipt needs an amount."
                receipt.date == null -> "Each receipt needs the date it was spent."
                receipt.attachmentKey.isNullOrBlank() -> "Each receipt needs its image or PDF attached."
                else -> null
            }
        }
        if (invalid != null) {
            vm.fail(invalid)
            return
        }
        if (state.headroom.batchExceeds(state.draftTotal)) {
            vm.fail("This batch is over the card's remaining limit. Ask for a top-up before uploading it.")
            return
        }
        vm.submitDraftReceipts(state)
    }

    /**
     * Commits the coding editor.
     *
     * Saving a draft accepts a blank code — putting half a code down and
     * coming back is the point of the draft — while both of the routes that
     * move the receipt on require one, because the next person cannot approve
     * an uncoded receipt.
     */
    fun commitCoding(commit: CodingCommit) {
        val state = vm.current
        val draft = state.coding ?: return
        val receipt = state.receipts.firstOrNull { it.id == draft.receiptId } ?: return
        if (commit != CodingCommit.Draft && !draft.coded) {
            vm.fail("A nominal code is needed before this can go on.")
            return
        }
        if (commit == CodingCommit.ApproveAndSubmit && !state.viewer.isApprover) {
            vm.fail("You are not an approver on this project.")
            return
        }
        val coding = draft.wire(receipt)
        when (commit) {
            CodingCommit.Draft ->
                vm.act("Coding saved") { vm.repo.updateReceiptCoding(draft.receiptId, coding) }

            CodingCommit.Submit ->
                vm.act("Sent for approval") { vm.repo.submitReceiptForApproval(draft.receiptId, coding) }

            CodingCommit.ApproveAndSubmit ->
                vm.act("Coded and approved") { vm.repo.approveAndSubmitReceipt(draft.receiptId, coding) }

            // The holder coding their own receipt. A different route from the
            // coordinator's draft save: this one advances the receipt out of
            // coding, because a holder has nothing further to add to it.
            CodingCommit.Own ->
                vm.act("Coding saved") { vm.repo.codeReceipt(draft.receiptId, coding) }
        }
    }

    private fun put(index: Int, file: CardAttachment) = vm.update {
        copy(
            draft = draft.mapIndexed { position, row ->
                if (position == index) {
                    row.copy(attachmentKey = file.key, attachmentName = file.fileName)
                } else {
                    row
                }
            },
        )
    }

    /**
     * The coding as the wire wants it, with the receipt's own card context.
     *
     * The service refuses a receipt write that does not name the card the
     * receipt belongs to, so both ride along on every one of the three.
     */
    private fun CodingDraft.wire(receipt: CardReceipt) = ReceiptCoding(
        nominalCode = nominalCode.trim(),
        episode = episode.trim().takeIf { it.isNotEmpty() },
        codeDescription = codeDescription.trim().takeIf { it.isNotEmpty() },
        cardId = receipt.cardId,
        currency = receipt.currency,
    )
}

/** What the coding queue's three buttons do with the same draft. */
internal enum class CodingCommit { Draft, Submit, ApproveAndSubmit, Own }
