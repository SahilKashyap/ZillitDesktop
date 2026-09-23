package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.ManualClaim
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard

/**
 * The two dialogs any payroll screen can open: Override Approval, and the
 * claims and deductions on one timecard.
 *
 * Claims and deductions save the moment they are added or removed — the web
 * has no Save step — and each write re-reads the timecard and the screen
 * underneath, so the totals behind the dialog move with it.
 */
internal class SharedActions(private val vm: PayrollViewModel) {

    private val dialog: AdjustmentDialog? get() = vm.ui.adjustment

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: PayrollEvent) {
        when (event) {
            is PayrollEvent.AskOverride -> askOverride(event.timecard)
            is PayrollEvent.EditOverrideReason -> vm.update { copy(override = override?.copy(reason = event.reason)) }
            PayrollEvent.ConfirmOverride -> confirmOverride()
            PayrollEvent.DismissOverride -> vm.update { copy(override = null) }
            is PayrollEvent.OpenAdjustments -> openAdjustments(event.kind, event.timecard)
            is PayrollEvent.EditAdjustment -> editForm(event)
            PayrollEvent.AddAdjustment -> addLine()
            is PayrollEvent.AttachBatch -> attachBatch(event.batchId)
            is PayrollEvent.RemoveClaim -> removeClaim(event.claim)
            is PayrollEvent.RemoveDeduction -> removeDeduction(event.deductionId)
            PayrollEvent.UnlockAdjusted -> unlockAdjusted()
            PayrollEvent.DismissAdjustments -> vm.update { copy(adjustment = null) }
            else -> Unit
        }
    }

    private fun editForm(event: PayrollEvent.EditAdjustment) = editDialog {
        copy(
            name = event.name ?: name,
            amount = event.amount ?: amount,
            nominal = event.nominal ?: nominal,
            error = null,
        )
    }

    /** Nothing comes off a week the server will not change. */
    private fun removeClaim(claim: ClaimLine) {
        val open = dialog?.takeUnless { it.readOnly } ?: return
        write(claim.id ?: claim.cashExpenseBatchId.orEmpty()) {
            vm.repository.adjustments.detachClaim(open.timecard.id, claim)
        }
    }

    /** A deal-memo deduction (`is_custom: false`) is the deal's, and stays. */
    private fun removeDeduction(deductionId: String) {
        val open = dialog?.takeUnless { it.readOnly } ?: return
        val row = open.timecard.deductions.firstOrNull { it.id == deductionId }
        if (row == null || !row.isCustom) return
        write(deductionId) { vm.repository.adjustments.removeDeduction(open.timecard.id, deductionId) }
    }

    /** Offered to a non-approving accountant, on a week still in the approval chain. */
    private fun askOverride(timecard: PayrollTimecard) {
        val ui = vm.ui
        if (!ui.canOverride || !timecard.status.isAwaitingApproval) return
        vm.update {
            copy(
                override = OverridePrompt(
                    timecardId = timecard.id,
                    name = nameOf(timecard.userId),
                    weekLabel = timecard.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty(),
                ),
            )
        }
    }

    private fun confirmOverride() {
        val prompt = vm.ui.override ?: return
        if (prompt.saving || !vm.ui.canOverride) return
        vm.update { copy(override = prompt.copy(saving = true)) }
        vm.launchWork {
            when (val result = vm.repository.override(prompt.timecardId, prompt.reason)) {
                is ZillitResult.Success -> {
                    vm.update { copy(override = null) }
                    vm.notify(result.data?.localisedMessage() ?: str(S.done_text))
                    vm.reloadOpen(silent = true)
                }
                // The dialog stays up so the reason is not lost.
                is ZillitResult.Failure -> {
                    vm.update { copy(override = override?.copy(saving = false)) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    /**
     * Opens on the full timecard — a list row can be a slim projection with no
     * claims or deductions on it — and, for claims, the batches waiting for
     * this crew member.
     */
    private fun openAdjustments(kind: AdjustmentKind, timecard: PayrollTimecard) {
        if (!vm.ui.viewer.seesAccountantViews) return
        vm.update { copy(adjustment = AdjustmentDialog(kind = kind, timecard = timecard, loading = true)) }
        refreshDialog(timecard.id, timecard.userId)
    }

    private fun refreshDialog(timecardId: String, userId: String) {
        vm.launchWork {
            val full = vm.repository.timecard(timecardId).getOrNull()
            val pending = if (dialog?.kind == AdjustmentKind.Claims) {
                vm.repository.adjustments.pendingClaims(userId).getOrNull().orEmpty()
            } else {
                emptyList()
            }
            editDialog { copy(loading = false, timecard = full ?: timecard, pending = pending) }
        }
    }

    /** A manual claim line or a flat deduction: a description and an amount above zero. */
    private fun addLine() {
        val open = dialog ?: return
        if (!open.canAdd || open.readOnly || open.busyKey != null) return
        val line = ManualClaim(
            name = open.name.trim(),
            amount = open.amount.trim().toDouble(),
            currency = open.timecard.currency,
            nominalCode = open.nominal.trim(),
        )
        write(ADD_KEY, clearForm = true) {
            when (open.kind) {
                AdjustmentKind.Claims -> vm.repository.adjustments.attachManual(open.timecard.id, line)
                AdjustmentKind.Deductions -> vm.repository.adjustments.addDeduction(open.timecard.id, line)
            }
        }
    }

    /**
     * The web leaves "+ Add" on a pending batch even over a locked week and
     * lets the server refuse it — the refusal is what offers the unlock.
     */
    private fun attachBatch(batchId: String) {
        val open = dialog ?: return
        write(batchId) { vm.repository.adjustments.attachBatch(open.timecard.id, batchId) }
    }

    private fun write(key: String, clearForm: Boolean = false, call: suspend () -> ZillitResult<PayrollTimecard?>) {
        val open = dialog ?: return
        if (open.busyKey != null) return
        editDialog { copy(busyKey = key, error = null, lockedRefusal = false) }
        vm.launchWork {
            when (val result = call()) {
                is ZillitResult.Success -> {
                    editDialog {
                        if (clearForm) {
                            copy(busyKey = null, name = "", amount = "", nominal = "")
                        } else {
                            copy(busyKey = null)
                        }
                    }
                    refreshDialog(open.timecard.id, open.timecard.userId)
                    vm.reloadOpen(silent = true)
                }
                is ZillitResult.Failure -> editDialog {
                    copy(
                        busyKey = null,
                        error = result.error.localised(),
                        lockedRefusal = result.error.isLockedRefusal(),
                    )
                }
            }
        }
    }

    /** "Unlock this week" — the payroll accountant's, after a locked refusal. */
    private fun unlockAdjusted() {
        val open = dialog ?: return
        if (!vm.ui.viewer.isPayrollAccountant || open.busyKey != null) return
        editDialog { copy(busyKey = UNLOCK_KEY, error = null) }
        vm.launchWork {
            when (val result = vm.repository.unlock(open.timecard.id)) {
                is ZillitResult.Success -> {
                    editDialog { copy(busyKey = null, lockedRefusal = false) }
                    refreshDialog(open.timecard.id, open.timecard.userId)
                    vm.reloadOpen(silent = true)
                }
                is ZillitResult.Failure -> editDialog { copy(busyKey = null, error = result.error.localised()) }
            }
        }
    }

    private fun editDialog(reducer: AdjustmentDialog.() -> AdjustmentDialog) =
        vm.update { copy(adjustment = adjustment?.reducer()) }

    private companion object {
        const val ADD_KEY = "add"
        const val UNLOCK_KEY = "unlock"
    }
}

/** The server's "that week is locked" refusal carries `{{status}}` = `locked`. */
private fun ZillitError.isLockedRefusal(): Boolean =
    (this as? ZillitError.Http)?.messageElements.orEmpty().any { element ->
        element.search == "{{status}}" && element.replacer == "locked"
    }
