package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay

/**
 * The approval-side mutations — approve, reject, override, chase, delete —
 * split out of the ViewModel so it stays a router. Every one reloads the list
 * (and the open detail) afterwards: the server owns the state transitions.
 */
internal class InvoiceActions(private val vm: InvoicesViewModel) {

    fun approve(invoice: Invoice) {
        // A row dated in a closed cost-report period loses its mutating actions.
        if (vm.state.value.isLocked(invoice) || readOnlyDetail(invoice)) return
        val tiers = vm.tiersFor(invoice)
        // No tier left to sign: the web returns without a call (`ApprovalPage.jsx:275`).
        val next = ApprovalChain.nextTier(tiers, invoice.approvals) ?: return
        val total = ApprovalChain.totalTiers(tiers).coerceAtLeast(1)
        val fromQueue = vm.onApprovalQueue
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            when (val r = vm.repo.approveWithMessage(invoice.id, next, total)) {
                is ZillitResult.Failure -> vm.update {
                    copy(busy = false, detail = detail?.copy(acting = false), error = r.error.localised())
                }
                is ZillitResult.Success -> {
                    // The web's modal closes once the decision lands and reads
                    // the row it was opened from (`InvoiceDetailModal.jsx:432-438`);
                    // the accountant queue reads it after the await (`ApprovalPage.jsx:277-286`).
                    vm.readDepartmentRow(invoice.id)
                    if (fromQueue) vm.readApprovalQueueRow(invoice.id)
                    vm.update { copy(busy = false, detail = detail?.takeIf { it.invoice.id != invoice.id }) }
                    vm.notice(
                        serverSaid(r.data) ?: if (next >= total) {
                            str(S.desktop_inv_approved)
                        } else {
                            str(S.desktop_inv_approved_at_tier, next, total)
                        },
                    )
                    vm.refresh()
                }
            }
        }
    }

    /** The server's own `message`, translated, when it sent one — the web's `showApiSuccess`. */
    private fun serverSaid(key: String?): String? = key?.takeIf { it.isNotBlank() }?.localisedMessage()

    fun reject() {
        val d = vm.state.value.detail ?: return
        if (vm.state.value.isLocked(d.invoice) || !d.decisions) return
        // Checked trimmed, sent as typed — the web posts the raw reason (`rejectOne`).
        val reason = d.rejectReason
        if (reason.isBlank()) {
            vm.update { copy(error = str(S.desktop_rejection_reason_required)) }
            return
        }
        val fromQueue = vm.onApprovalQueue
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            when (val r = vm.repo.rejectWithMessage(d.invoice.id, reason)) {
                is ZillitResult.Failure -> vm.update {
                    copy(busy = false, detail = detail?.copy(acting = false), error = r.error.localised())
                }
                is ZillitResult.Success -> {
                    // Closed on success, as the web's reject sub-modal closes its parent,
                    // reading the row under its page (`InvoiceDetailModal.jsx:948`).
                    vm.readDepartmentRow(d.invoice.id)
                    if (fromQueue) vm.readApprovalQueueRow(d.invoice.id)
                    vm.update { copy(busy = false, detail = detail?.takeIf { it.invoice.id != d.invoice.id }) }
                    vm.notice(serverSaid(r.data) ?: str(S.desktop_inv_rejected))
                    vm.refresh()
                }
            }
        }
    }

    /**
     * Skip the rest of the chain — `POST /:id/override`, one call.
     *
     * This used to be two PATCHes (`override`, then `approved`), which walked
     * past the server's own gate (`override_permission_required`,
     * `cannot_override_held_invoice`, `cannot_override_paid_invoice`), its
     * bell fan-out and its auto-assignment: all three hang off this route, not
     * off a status write (`invoices.js` `override`, `ApprovalPage.jsx`).
     */
    fun override(invoice: Invoice) = overrideWith(invoice, str(S.desktop_inv_approval_chain_overridden))

    /**
     * Override & Pay on an override or urgent row — the same route. The web
     * sends all three override paths through it, so an invoice reaches a
     * payable state one way, the checked way.
     */
    fun overrideAndPay(invoice: Invoice) = overrideWith(invoice, str(S.desktop_inv_approved_for_payment))

    private fun overrideWith(invoice: Invoice, success: String) {
        // The button is shown only with override rights; the handler holds
        // the same line, so no other path reaches the write without them.
        val s = vm.state.value
        // The department board hands its detail no override at all
        // (`DepartmentInvoiceModule.jsx:1246-1277`), whatever the reader's rights.
        if (!s.isAccountant || !s.viewer.canOverride || s.isLocked(invoice) || readOnlyDetail(invoice)) return
        val fromQueue = vm.onApprovalQueue
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run { finish(vm.repo.overrideWithMessage(invoice.id), invoice.id, success, fromQueue) }
    }

    /** The Register's detail is read-only: no decision leaves it, whatever sent the event. */
    private fun readOnlyDetail(invoice: Invoice): Boolean =
        vm.state.value.detail?.takeIf { it.invoice.id == invoice.id }?.decisions == false

    /**
     * Chase the next approver — the web's `handleChase` (`ApprovalPage.jsx:243-262`):
     * one chase in flight at a time, "Chased" for two seconds on success and
     * then Chase again; no toast, and a failure is only logged there, so
     * nothing is raised here either.
     */
    fun chase(invoice: Invoice) {
        if (vm.state.value.chasing != null) return
        vm.update { copy(chasing = invoice.id) }
        vm.run {
            val result = vm.repo.chase(invoice.id)
            vm.update { copy(chasing = null, chased = if (result.isSuccess) chased + invoice.id else chased) }
            if (result.isSuccess) {
                delay(CHASED_MILLIS)
                vm.update { copy(chased = chased - invoice.id) }
            }
        }
    }

    /** The batch bar: one `approve` per ticked row I can approve, in order. */
    fun approveSelected() {
        val s = vm.state.value
        val rows = s.shownInvoices.filter {
            it.id in s.selected && !s.isLocked(it) && ApprovalChain.canApprove(it, vm.tiersFor(it), s.viewer.userId)
        }
        if (rows.isEmpty()) {
            vm.update { copy(error = str(S.desktop_inv_none_selected_waiting_on_you)) }
            return
        }
        val fromQueue = vm.onApprovalQueue
        vm.update { copy(busy = true) }
        vm.run {
            var done = 0
            var failure: String? = null
            for (invoice in rows) {
                val tiers = vm.tiersFor(invoice)
                val next = ApprovalChain.nextTier(tiers, invoice.approvals) ?: 1
                when (val r = vm.repo.approve(invoice.id, next, tiers.size.coerceAtLeast(1))) {
                    is ZillitResult.Failure -> failure = r.error.localised()
                    is ZillitResult.Success -> {
                        done++
                        // Each row read once its approval lands (`ApprovalPage.jsx:395-399`).
                        if (fromQueue) vm.readApprovalQueueRow(invoice.id)
                    }
                }
            }
            vm.update { copy(busy = false, selected = emptySet(), error = failure) }
            if (done > 0) vm.notice("$done invoice(s) approved")
            vm.refresh()
        }
    }

    /**
     * The confirmation stays up, spinning, until the delete answers, and stays
     * up on a refusal so it can be tried again — the web's `ConfirmModal`
     * with `loading` (`DepartmentInvoiceModule.jsx:1282-1293`). Once it lands
     * the row's unread is read, so a deleted invoice leaves no count behind.
     */
    fun deleteConfirmed() {
        val invoice = vm.state.value.confirmDelete ?: return
        if (vm.state.value.busy) return
        val fromQueue = vm.onApprovalQueue
        vm.update { copy(busy = true) }
        vm.run {
            when (val r = vm.repo.deleteWithMessage(invoice.id)) {
                is ZillitResult.Failure -> vm.update { copy(busy = false, error = r.error.localised()) }
                is ZillitResult.Success -> {
                    // After the await: a refusal keeps the badge of an invoice
                    // that still exists (`ApprovalPage.jsx:351-364`).
                    vm.readDepartmentRow(invoice.id)
                    if (fromQueue) vm.readApprovalQueueRow(invoice.id)
                    vm.update {
                        copy(
                            busy = false,
                            confirmDelete = null,
                            invoices = invoices.filterNot { it.id == invoice.id },
                            detail = detail?.takeIf { it.invoice.id != invoice.id },
                        )
                    }
                    vm.notice(serverSaid(r.data) ?: str(S.desktop_inv_deleted))
                    vm.refresh()
                }
            }
        }
    }

    private fun finish(outcome: ZillitResult<*>, id: String, success: String, fromQueue: Boolean) {
        when (outcome) {
            is ZillitResult.Failure -> vm.update {
                copy(busy = false, detail = detail?.copy(acting = false), error = outcome.error.localised())
            }
            is ZillitResult.Success -> {
                // The detail closes on success, as the web's Override and
                // Override & Pay both call `onClose()` (`InvoiceDetailModal.jsx:891-897, 912-918`).
                // The accountant queue reads the row only once the gated call lands
                // (`ApprovalPage.jsx:320-331, 628-639`) — Pre-approval's Override reads nothing.
                vm.readDepartmentRow(id)
                if (fromQueue) vm.readApprovalQueueRow(id)
                vm.update { copy(busy = false, detail = detail?.takeIf { it.invoice.id != id }) }
                vm.notice((outcome.data as? String)?.let(::serverSaid) ?: success)
                vm.refresh()
            }
        }
    }

    private companion object {
        /** How long "Chased" shows before Chase is offered again (`setTimeout(…, 2000)`). */
        const val CHASED_MILLIS = 2_000L
    }
}
