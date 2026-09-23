package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.CashRules

/**
 * Runs the answered [CashPrompt] through the desk that owns the action.
 *
 * Every branch reaches a handler that checks its own right — the dispatch
 * used to go straight to the repository, so a prompt arriving here approved,
 * posted or closed money with no check of its own.
 */
internal class PromptDesk(
    private val host: CashHost,
    private val batches: BatchDesk,
    private val floats: FloatDesk,
    private val recon: ReconDesk,
) {

    /** Resolves [prompt]; a problem the person can fix puts it back open. */
    fun resolve(prompt: CashPrompt) {
        host.update { copy(prompt = null) }
        val done = when (prompt) {
            is CashPrompt.Confirm -> confirm(prompt).let { true }
            is CashPrompt.Assign -> assign(prompt)
            is CashPrompt.WithReason -> reasoned(prompt)
            is CashPrompt.WithAmount -> amount(prompt)
            is CashPrompt.ReadyToCollect -> floats.readyToCollect(prompt)
            is CashPrompt.RecordReturn -> floats.recordReturn(prompt)
            is CashPrompt.NewReconciliation -> recon.create(prompt)
        }
        if (!done) host.update { copy(prompt = prompt) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per confirmable action.
    private fun confirm(prompt: CashPrompt.Confirm) {
        val id = prompt.targetId
        val viewer = host.state.viewer
        when (prompt.action) {
            ConfirmAction.ApproveFloat -> floats.approve(id)
            ConfirmAction.OverrideFloat -> if (viewer.canOverrideFloat()) {
                host.act(str(S.desktop_ce_float_overridden)) { host.repository.overrideFloat(id) }
            } else {
                host.noRights()
            }

            ConfirmAction.CollectFloat -> floats.collect(id)
            ConfirmAction.CloseFloat -> floats.close(id)
            ConfirmAction.ApproveBatch -> batches.approve(id)
            ConfirmAction.OverrideBatch -> if (viewer.canOverrideBatch()) {
                host.act(str(S.desktop_ce_batch_overridden)) { host.repository.overrideBatch(id) }
            } else {
                host.noRights()
            }

            ConfirmAction.PostBatch -> batches.post(id)
            ConfirmAction.SubmitForReview -> batches.submitForReview(id)
            ConfirmAction.SaveAndVerify -> batches.sendForApproval(id)
            ConfirmAction.SaveAndSubmitCoded -> batches.forwardCoded(id)
            ConfirmAction.ReturnToAccounts -> batches.returnToAccounts(id)
            ConfirmAction.CompleteTopUp -> floats.completeTopUp(id)
            ConfirmAction.SkipTopUp -> floats.skipTopUp(id)
            ConfirmAction.SignOffReconciliation -> recon.signOff()
            ConfirmAction.SubmitReconciliation -> recon.submit()
            ConfirmAction.ReceiveFunds -> floats.receiveFunds(id)
            ConfirmAction.CancelFunds -> floats.cancelFunds(id)
        }
    }

    private fun reasoned(prompt: CashPrompt.WithReason): Boolean {
        val reason = prompt.reason.trim()
        if (reason.isEmpty()) {
            host.refuse(str(S.desktop_a_reason_is_required))
            return false
        }
        when (prompt.action) {
            ReasonedAction.RejectFloat -> {
                val float = floats.float(prompt.targetId) ?: return true
                if (CashRules.mayApprove(host.state.viewer, float)) {
                    host.act(str(S.desktop_ce_float_rejected)) { host.repository.rejectFloat(prompt.targetId, reason) }
                } else {
                    host.noRights()
                }
            }

            ReasonedAction.RejectBatch -> batches.reject(prompt.targetId, reason)
            ReasonedAction.EscalateBatch -> batches.escalate(prompt.targetId, reason)
        }
        return true
    }

    private fun amount(prompt: CashPrompt.WithAmount): Boolean {
        val amount = prompt.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            host.refuse(str(S.desktop_card_amount_greater_than_zero))
            return false
        }
        return when (prompt.action) {
            AmountAction.PartialTopUp -> floats.partialTopUp(prompt.targetId, amount, prompt.note.trim())
            AmountAction.RequestFloatTopUp -> {
                host.act(str(S.desktop_card_topup_requested)) {
                    host.repository.requestFloatTopUp(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
                }
                true
            }
        }
    }

    /**
     * Assign or Reassign — Post & Ledger's, Audit's and History's, as the
     * web's batch page offers it to anyone who may open the batch, while it is
     * outside the locked period.
     *
     * The batch stays where it is afterwards — only its owner moved — so the
     * row is patched in place rather than the queue reloaded under the reader.
     */
    private fun assign(prompt: CashPrompt.Assign): Boolean {
        val state = host.state
        val batch = state.queueBatches.firstOrNull { it.id == prompt.batchId }
        val allowed = state.viewer.isAccountant && batch != null && !state.selectedLocked &&
            (!state.destination.isPostLedger || CashRules.canOpenPostRow(state.viewer, batch))
        if (!allowed) {
            host.noRights()
            return true
        }
        if (!BatchAssignment.canSubmit(batch, prompt.selectedUserId, prompt.reason)) {
            host.refuse(str(S.desktop_ce_choose_someone_and_reason))
            return false
        }
        val reason = BatchAssignment.reasonFor(batch, prompt.reason)
        host.work {
            when (val result = host.repository.assignBatch(prompt.batchId, prompt.selectedUserId, reason)) {
                is ZillitResult.Success -> host.update {
                    copy(
                        queueBatches = BatchAssignment.applied(queueBatches, prompt.batchId, prompt.selectedUserId),
                        notice = if (BatchAssignment.isUnassigned(batch)) {
                            str(S.assigned)
                        } else {
                            str(S.desktop_ce_reassigned)
                        },
                        // Handing a Post & Ledger batch on closes it for anyone it is no longer theirs.
                        selectedBatchId = if (state.destination.isPostLedger) null else selectedBatchId,
                        panel = if (state.destination.isPostLedger) null else panel,
                    )
                }

                is ZillitResult.Failure -> {
                    host.refuse(result.error.localised())
                    host.update { copy(prompt = prompt) }
                }
            }
        }
        return true
    }
}
