package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.InboxWrite
import com.zillit.desktop.feature.cardexpenses.domain.canDelete

/**
 * Everything that happens once a confirmation is answered, and the bulk
 * operations behind the queues' selection bars.
 *
 * Its own collaborator because it is where the rights live: each prompt that
 * reaches here is checked against the viewer **here**, not only by the screen
 * that raised it — the module's defining defect was screens that gated and a
 * handler that trusted them.
 */
@Suppress("TooManyFunctions") // One resolver per prompt shape plus the bulk bars.
internal class CardPromptActions(
    private val vm: CardExpensesViewModel,
    /** Deleting a card also closes its drilldown; the register owns that. */
    private val deleteCard: (String) -> Unit,
) {

    fun resolve() {
        val prompt = vm.current.prompt ?: return
        vm.update { copy(prompt = null) }
        when (prompt) {
            is CardPrompt.Confirm -> confirm(prompt)
            is CardPrompt.WithReason -> reason(prompt)
            is CardPrompt.WithAmount -> amount(prompt)
            is CardPrompt.WithCardNumber -> cardNumber(prompt)
        }
    }

    private fun cardNumber(prompt: CardPrompt.WithCardNumber) {
        if (!vm.current.viewer.isAccountant) return refuse()
        val digits = prompt.number.filter(Char::isDigit)
        if (digits.length < MIN_CARD_DIGITS) {
            vm.fail(str(S.desktop_card_enter_full_number))
            vm.update { copy(prompt = prompt) }
            return
        }
        vm.act(str(S.ah_physical_card_assigned_toast)) { vm.repo.assignPhysicalCard(prompt.targetId, digits) }
    }

    /**
     * The right each confirmable action needs, independent of the screen.
     *
     * The card lifecycle, posting and deleting are an accountant's; approving
     * a card is whoever its chain's next tier names; overriding needs both the
     * person's grant and the production's switch. The claimant's own steps —
     * submit, match, flag, dismiss — are left alone: a person acting on their
     * own receipt is not something any screen refuses.
     */
    @Suppress("CyclomaticComplexMethod") // A rights table; one line per action.
    private fun CardConfirmAction.permitted(state: CardUiState, targetId: String): Boolean {
        val viewer = state.viewer
        return when (this) {
            CardConfirmAction.ApproveCard -> state.cardApproval(targetId).canApprove
            CardConfirmAction.OverrideCard ->
                viewer.canOverrideCard && state.cards.any { it.id == targetId && it.status == CardStatus.Pending }

            CardConfirmAction.ApproveReceipt, CardConfirmAction.BulkApprove, CardConfirmAction.BulkReject ->
                viewer.isApprover

            CardConfirmAction.OverrideReceipt, CardConfirmAction.BulkOverride -> viewer.canOverrideReceipt
            CardConfirmAction.DeleteTransaction ->
                viewer.isAccountant && state.transactions.firstOrNull { it.id == targetId }?.status?.canDelete != false

            CardConfirmAction.InvestigateAlert ->
                viewer.isAccountant && state.alerts.any { it.id == targetId && it.status == CardAlert.ACTIVE }

            CardConfirmAction.DismissAlert -> viewer.isAccountant && state.alerts.any { it.id == targetId && it.isOpen }
            CardConfirmAction.SuspendCard,
            CardConfirmAction.ReactivateCard,
            CardConfirmAction.PostTransaction,
            CardConfirmAction.FlagTransactionPersonal,
            CardConfirmAction.BulkDeleteTransactions,
            CardConfirmAction.RerunMatching,
            CardConfirmAction.CompleteTopUp,
            CardConfirmAction.SkipTopUp,
            -> viewer.isAccountant

            else -> true
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per confirmable action.
    private fun confirm(prompt: CardPrompt.Confirm) {
        val id = prompt.targetId
        val state = vm.current
        if (!prompt.action.permitted(state, id)) return refuse()
        val repo = vm.repo
        val me = state.viewer.userId
        when (prompt.action) {
            CardConfirmAction.ApproveCard -> {
                val step = state.cardApproval(id)
                vm.act(str(S.ah_card_approved_toast)) { repo.approveCard(id, step, me) }
            }

            CardConfirmAction.OverrideCard ->
                vm.act(str(S.desktop_card_overridden_toast)) { repo.overrideCard(id, me, CARD_OVERRIDE_REASON) }

            CardConfirmAction.SuspendCard -> vm.act(str(S.ah_card_suspended_toast)) { repo.suspendCard(id) }
            CardConfirmAction.ReactivateCard -> vm.act(str(S.ah_card_reactivated_toast)) { repo.reactivateCard(id) }
            CardConfirmAction.DeleteCard -> deleteCard(id)
            CardConfirmAction.ApproveReceipt ->
                vm.act(str(S.desktop_card_receipt_approved)) {
                    // The step being signed: one past the sign-offs already collected.
                    val tier = (vm.current.receipts.firstOrNull { it.id == id }?.approvals?.size ?: 0) + 1
                    repo.approveReceipt(id, tier, me)
                }

            CardConfirmAction.OverrideReceipt ->
                vm.act(str(S.desktop_card_receipt_overridden)) { repo.overrideReceipt(id, me, RECEIPT_OVERRIDE_REASON) }

            CardConfirmAction.SubmitReceiptForApproval ->
                vm.act(str(S.ah_sent_for_approval_toast)) { repo.submitReceiptForApproval(id) }

            CardConfirmAction.ConfirmMatch ->
                vm.act(str(S.desktop_card_match_confirmed)) { repo.confirmReceiptMatch(id) }

            CardConfirmAction.UnmatchReceipt -> vm.act(str(S.desktop_card_match_removed)) { repo.unmatchReceipt(id) }
            CardConfirmAction.FlagPersonal -> flagPersonal(id)
            CardConfirmAction.DismissDuplicate ->
                vm.act(str(S.ah_duplicate_dismissed_toast)) { repo.dismissDuplicate(id) }

            CardConfirmAction.DismissPersonal ->
                vm.act(str(S.desktop_card_personal_flag_cleared)) { repo.dismissPersonal(id) }

            CardConfirmAction.DeleteReceipt -> vm.act(str(S.ah_receipt_deleted_toast)) { repo.deleteReceipt(id) }
            CardConfirmAction.CompleteTopUp -> completeTopUp(id)
            CardConfirmAction.SkipTopUp -> vm.act(str(S.ah_topup_skipped_toast)) { repo.skipTopUp(id) }
            CardConfirmAction.DismissAlert -> vm.act(str(S.ah_alert_dismissed_toast)) { repo.dismissAlert(id) }
            CardConfirmAction.InvestigateAlert ->
                vm.act(str(S.desktop_card_marked_for_investigation)) { repo.investigateAlert(id) }

            CardConfirmAction.BulkApprove -> bulk(BulkAction.Approve)
            CardConfirmAction.BulkReject -> bulk(BulkAction.Reject)
            CardConfirmAction.BulkOverride -> bulk(BulkAction.Override)
            CardConfirmAction.PostTransaction ->
                vm.act(str(S.desktop_card_transaction_posted)) { repo.postTransaction(id) }

            CardConfirmAction.FlagTransactionPersonal ->
                vm.act(str(S.desktop_card_flagged_personal)) { repo.flagTransactionPersonal(id) }

            CardConfirmAction.DeleteTransaction -> {
                vm.act(str(S.desktop_card_transaction_deleted)) { repo.deleteTransaction(id) }
                vm.update { copy(selectedTransactionId = null) }
            }

            CardConfirmAction.BulkDeleteTransactions -> bulkDeleteTransactions()
            // The web re-runs every statement (`rerunMatch()` sends `{}`); the
            // inbox page itself now raises `InboxEvent.RerunMatch` instead.
            CardConfirmAction.RerunMatching -> vm.act(str(S.desktop_card_matching_rerun)) {
                repo.inboxWrite(InboxWrite.RerunMatch).map { }
            }
        }
    }

    /**
     * Flag Personal from a receipt: the **statement line** when the receipt is
     * linked to one — which also releases the holder's committed headroom — and
     * the receipt itself otherwise (`ReceiptInboxPage.jsx:607-625`).
     */
    private fun flagPersonal(receiptId: String) {
        val linked = vm.current.receipts.firstOrNull { it.id == receiptId }?.transactionId
        vm.act(str(S.desktop_card_flagged_personal)) {
            if (linked != null && vm.current.viewer.isAccountant) {
                vm.repo.flagTransactionPersonal(linked)
            } else {
                vm.repo.flagReceiptPersonal(receiptId)
            }
        }
    }

    /** Funding a request in full, refused where it would carry the card past its limit. */
    private fun completeTopUp(topUpId: String) {
        val topUp = vm.current.topUps.firstOrNull { it.id == topUpId }
        if (topUp != null && topUp.overfills(topUp.amount)) {
            vm.fail(str(S.desktop_card_topup_over_limit))
            return
        }
        vm.act(str(S.desktop_card_topup_completed)) { vm.repo.completeTopUp(topUpId) }
    }

    private fun reason(prompt: CardPrompt.WithReason) {
        val reason = prompt.reason.trim()
        if (reason.isEmpty()) {
            vm.fail(str(S.desktop_a_reason_is_required))
            vm.update { copy(prompt = prompt) }
            return
        }
        val state = vm.current
        val viewer = state.viewer
        val repo = vm.repo
        when (prompt.action) {
            CardReasonAction.RejectCard -> {
                if (!state.cardApproval(prompt.targetId).canApprove) return refuse()
                vm.act(str(S.ah_card_rejected_toast)) { repo.rejectCard(prompt.targetId, reason, viewer.userId) }
            }

            CardReasonAction.RejectReceipt -> {
                if (!viewer.isApprover) return refuse()
                vm.act(str(S.desktop_card_receipt_rejected)) {
                    repo.rejectReceipt(prompt.targetId, reason, viewer.userId)
                }
            }

            CardReasonAction.QueryTransaction ->
                vm.act(str(S.ah_query_sent_toast)) { repo.queryTransaction(prompt.targetId, reason) }

            CardReasonAction.RejectTransaction ->
                vm.act(str(S.desktop_card_transaction_rejected)) { repo.rejectTransaction(prompt.targetId, reason) }

            CardReasonAction.ResolveAlert -> {
                val open = state.alerts.any { it.id == prompt.targetId && it.isOpen }
                if (!viewer.isAccountant || !open) return refuse()
                vm.act(str(S.ah_alert_resolved_toast)) { repo.resolveAlert(prompt.targetId, reason) }
            }
        }
    }

    private fun amount(prompt: CardPrompt.WithAmount) {
        when (prompt.action) {
            CardAmountAction.RequestTopUp -> {
                val amount = prompt.amount.trim().toDoubleOrNull()
                if (amount == null || amount <= 0) return retry(prompt, str(S.desktop_card_amount_greater_than_zero))
                vm.act(str(S.desktop_card_topup_requested)) {
                    vm.repo.requestTopUp(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
                }
            }

            CardAmountAction.PartialTopUp -> partialTopUp(prompt)
        }
    }

    /**
     * A part-payment: the note is required and the amount optional, as on the
     * web — the note is the only record of why a row is half funded, and the
     * desktop used to drop it on the floor.
     */
    private fun partialTopUp(prompt: CardPrompt.WithAmount) {
        if (!vm.current.viewer.isAccountant) return refuse()
        val note = prompt.note.trim()
        if (note.isEmpty()) return retry(prompt, str(S.desktop_card_partial_note_required))
        val typed = prompt.amount.trim()
        val amount = typed.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (typed.isNotEmpty() && (amount == null || amount <= 0)) {
            return retry(prompt, str(S.desktop_card_amount_greater_than_zero))
        }
        val topUp = vm.current.topUps.firstOrNull { it.id == prompt.targetId }
        if (amount != null && topUp != null && topUp.overfills(amount)) {
            return retry(prompt, str(S.desktop_card_topup_over_limit))
        }
        vm.act(str(S.desktop_card_topup_recorded)) { vm.repo.partialTopUp(prompt.targetId, amount, note) }
    }

    private fun retry(prompt: CardPrompt, message: String) {
        vm.fail(message)
        vm.update { copy(prompt = prompt) }
    }

    /**
     * Codes and posts the ticked bulk rows — an accountant's, and only the
     * rows this viewer may tick: a row assigned to someone else is theirs.
     */
    fun bulkPost() {
        val state = vm.current
        if (!state.viewer.isAccountant) return refuse()
        val allowed = state.selectableBulkItems.map { it.id }.toSet()
        val ids = state.selection.filter { it in allowed }
        if (ids.isEmpty()) {
            vm.fail(str(S.desktop_nothing_is_selected))
            return
        }
        val coding = state.bulkCoding
        counted(block = { vm.repo.bulkProcess(ids, coding) }) { outcome ->
            copy(
                bulkCoding = BulkCoding(),
                // Partial failure is reported as such: "posted 38" when two did
                // not go is how a discrepancy is found a week later.
                notice = if (outcome.failed > 0) {
                    str(S.desktop_card_posted_count_failed, outcome.succeeded, outcome.failed)
                } else {
                    str(S.desktop_card_posted_count, outcome.succeeded)
                },
            )
        }
    }

    /**
     * Deletes the ticked statement lines that may still be deleted.
     *
     * Approved and posted lines are bookkeeping, not clutter
     * (`transactionQuery.js:55-58`); they are dropped from the send even if a
     * stale selection still names them.
     */
    private fun bulkDeleteTransactions() {
        val state = vm.current
        val deletable = state.transactions.filter { it.status.canDelete }.map { it.id }.toSet()
        val ids = state.selection.filter { it in deletable }
        if (ids.isEmpty()) {
            vm.fail(str(S.desktop_nothing_is_selected))
            return
        }
        counted(block = { vm.repo.bulkDeleteTransactions(ids) }) { outcome ->
            copy(
                selectedTransactionId = null,
                notice = if (outcome.failed > 0) {
                    str(S.desktop_card_deleted_count_skipped, outcome.succeeded, outcome.failed)
                } else {
                    str(S.desktop_card_deleted_count, outcome.succeeded)
                },
            )
        }
    }

    private fun counted(
        block: suspend () -> ZillitResult<BulkOutcome>,
        settle: CardUiState.(BulkOutcome) -> CardUiState,
    ) = vm.run {
        vm.update { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                vm.update { settle(result.data).copy(busy = false, selection = emptySet()) }
                vm.reload()
            }

            is ZillitResult.Failure -> {
                vm.update { copy(busy = false) }
                vm.fail(result.error.localised())
            }
        }
    }

    private fun bulk(action: BulkAction) {
        val ids = vm.current.selection.toList()
        if (ids.isEmpty()) {
            vm.fail(str(S.desktop_nothing_is_selected))
            return
        }
        val notice = when (action) {
            BulkAction.Approve -> str(S.desktop_card_receipts_approved_count, ids.size)
            BulkAction.Reject -> str(S.desktop_card_receipts_rejected_count, ids.size)
            BulkAction.Override -> str(S.desktop_card_receipts_overridden_count, ids.size)
        }
        vm.act(notice) { vm.repo.bulkApproval(action, ids) }
        vm.update { copy(selection = emptySet()) }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))

    private companion object {
        const val MIN_CARD_DIGITS = 12

        /** What the web records when an accountant overrides without saying why. */
        const val CARD_OVERRIDE_REASON = "Overridden by accountant"
        const val RECEIPT_OVERRIDE_REASON = "Accountant override"
    }
}
