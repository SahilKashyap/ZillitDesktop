package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus

/**
 * The approval-side mutations — approve, reject, override, chase, delete —
 * split out of the ViewModel so it stays a router. Every one reloads the list
 * (and the open detail) afterwards: the server owns the state transitions.
 */
internal class InvoiceActions(private val vm: InvoicesViewModel) {

    fun approve(invoice: Invoice) {
        val tiers = vm.tiersFor(invoice)
        val next = ApprovalChain.nextTier(tiers, invoice.approvals) ?: 1
        val total = ApprovalChain.totalTiers(tiers).coerceAtLeast(1)
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            when (val r = vm.repo.approve(invoice.id, next, total)) {
                is ZillitResult.Failure -> vm.update {
                    copy(busy = false, detail = detail?.copy(acting = false), error = r.error.userMessage)
                }
                is ZillitResult.Success -> {
                    vm.update { copy(busy = false, detail = detail?.copy(acting = false)) }
                    vm.notice(if (next >= total) "Invoice approved" else "Approved at tier $next of $total")
                    vm.refresh()
                    vm.refreshDetail(invoice.id)
                }
            }
        }
    }

    fun reject() {
        val d = vm.state.value.detail ?: return
        val reason = d.rejectReason.trim()
        if (reason.isEmpty()) {
            vm.update { copy(error = "A rejection reason is required") }
            return
        }
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            when (val r = vm.repo.reject(d.invoice.id, reason)) {
                is ZillitResult.Failure -> vm.update {
                    copy(busy = false, detail = detail?.copy(acting = false), error = r.error.userMessage)
                }
                is ZillitResult.Success -> {
                    vm.update {
                        copy(busy = false, detail = detail?.copy(acting = false, rejecting = false, rejectReason = ""))
                    }
                    vm.notice("Invoice rejected")
                    vm.refresh()
                    vm.refreshDetail(d.invoice.id)
                }
            }
        }
    }

    /** Skip the chain: `override` first (the audit trail), then `approved`. */
    fun override(invoice: Invoice) {
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            val first = vm.repo.patchStatus(invoice.id, InvoiceStatus.Override, ApprovalStatus.Approved)
            val outcome = when (first) {
                is ZillitResult.Failure -> first
                is ZillitResult.Success -> vm.repo.patchStatus(
                    invoice.id,
                    InvoiceStatus.Approved,
                    ApprovalStatus.Approved,
                )
            }
            finish(outcome, invoice.id, "Approval chain overridden")
        }
    }

    /** Release an override / urgent row: one PATCH straight to approved. */
    fun overrideAndPay(invoice: Invoice) {
        vm.update { copy(busy = true, detail = detail?.copy(acting = true)) }
        vm.run {
            val outcome = vm.repo.patchStatus(invoice.id, InvoiceStatus.Approved, ApprovalStatus.Approved)
            finish(outcome, invoice.id, "Approved for payment")
        }
    }

    fun chase(invoice: Invoice) {
        vm.run {
            when (val r = vm.repo.chase(invoice.id)) {
                is ZillitResult.Failure -> vm.update { copy(error = r.error.userMessage) }
                is ZillitResult.Success -> {
                    vm.update { copy(chased = chased + invoice.id) }
                    vm.notice("Reminder sent to the next approver")
                }
            }
        }
    }

    /** The batch bar: one `approve` per ticked row I can approve, in order. */
    fun approveSelected() {
        val s = vm.state.value
        val rows = s.shownInvoices.filter {
            it.id in s.selected && ApprovalChain.canApprove(it, vm.tiersFor(it), s.viewer.userId)
        }
        if (rows.isEmpty()) {
            vm.update { copy(error = "None of the selected invoices are waiting on you") }
            return
        }
        vm.update { copy(busy = true) }
        vm.run {
            var done = 0
            var failure: String? = null
            for (invoice in rows) {
                val tiers = vm.tiersFor(invoice)
                val next = ApprovalChain.nextTier(tiers, invoice.approvals) ?: 1
                when (val r = vm.repo.approve(invoice.id, next, tiers.size.coerceAtLeast(1))) {
                    is ZillitResult.Failure -> failure = r.error.userMessage
                    is ZillitResult.Success -> done++
                }
            }
            vm.update { copy(busy = false, selected = emptySet(), error = failure) }
            if (done > 0) vm.notice("$done invoice(s) approved")
            vm.refresh()
        }
    }

    fun deleteConfirmed() {
        val invoice = vm.state.value.confirmDelete ?: return
        vm.update { copy(confirmDelete = null, busy = true) }
        vm.run {
            when (val r = vm.repo.delete(invoice.id)) {
                is ZillitResult.Failure -> vm.update { copy(busy = false, error = r.error.userMessage) }
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            busy = false,
                            invoices = invoices.filterNot { it.id == invoice.id },
                            detail = detail?.takeIf { it.invoice.id != invoice.id },
                        )
                    }
                    vm.notice("Invoice deleted")
                    vm.refresh()
                }
            }
        }
    }

    private fun finish(outcome: ZillitResult<*>, id: String, success: String) {
        when (outcome) {
            is ZillitResult.Failure -> vm.update {
                copy(busy = false, detail = detail?.copy(acting = false), error = outcome.error.userMessage)
            }
            is ZillitResult.Success -> {
                vm.update { copy(busy = false, detail = detail?.copy(acting = false)) }
                vm.notice(success)
                vm.refresh()
                vm.refreshDetail(id)
            }
        }
    }
}
