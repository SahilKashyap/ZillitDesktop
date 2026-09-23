package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Paying, billing and handing on — the Payment Runs, Sales Invoices and
 * Invoice Entry mutations, split out of the ViewModel so it stays a router.
 *
 * They share one shape: the server owns the transition, so every one of them
 * reloads what it changed rather than editing the list in place.
 */
internal class InvoicePayments(private val vm: InvoicesViewModel) {

    /** The Payments page needs its batches as well as its open items. */
    fun loadPaymentRuns() {
        vm.run {
            vm.repo.paymentRuns().getOrNull()?.let { runs -> vm.update { copy(paymentRuns = runs) } }
        }
    }

    fun loadSalesInvoices() {
        vm.update { copy(loading = false) }
        vm.run {
            when (val result = vm.repo.salesInvoices()) {
                is ZillitResult.Success -> vm.update { copy(salesInvoices = result.data) }
                is ZillitResult.Failure -> vm.update { copy(error = result.error.localised()) }
            }
        }
    }

    /** The header tick: everything on the tab, or nothing. */
    fun toggleSelectAll() {
        val ids = vm.state.value.paymentRows.map { it.id }.toSet()
        vm.update { copy(selected = if (selected.containsAll(ids) && ids.isNotEmpty()) emptySet() else ids) }
    }

    /**
     * Processes what is ticked.
     *
     * A selection that shares one method acts at once; a mixed one opens the
     * sheet, because each method leaves the queue a different way and a single
     * button would have to guess which.
     */
    fun processSelected(method: PayMethod?) {
        val rows = vm.state.value.selectedPaymentRows
        if (rows.isEmpty()) {
            vm.update { copy(error = str(S.desktop_inv_tick_to_pay_first)) }
            return
        }
        val chosen = method ?: vm.state.value.selectedPayMethod
        if (chosen == null) {
            vm.update { copy(runDraft = ProcessRequest(rows)) }
            return
        }
        when {
            chosen == PayMethod.Bacs -> createBacsRuns()
            chosen in PaymentRuns.WIRE_METHODS -> markPaid(rows.filter { it.payMethod == chosen }.map { it.id })
            // A cheque is not paid from here: it is printed, which is the
            // detail dialog. The web opens the first of the selection too.
            chosen == PayMethod.Cheque -> rows.firstOrNull { it.payMethod == chosen }?.let { vm.openInvoice(it) }
            else -> vm.update { copy(error = str(S.desktop_inv_not_processed_here, chosen.label)) }
        }
    }

    /**
     * One run per vendor and currency, numbered on from the runs already there.
     *
     * The runs that land stay landed: a failure half way through stops the
     * rest and is reported, rather than being retried into duplicates.
     */
    fun createBacsRuns() {
        val groups = vm.state.value.bacsGroups
        if (groups.isEmpty()) {
            vm.update { copy(error = str(S.desktop_inv_nothing_ticked_bacs)) }
            return
        }
        vm.update { copy(runDraft = runDraft?.copy(busy = PayMethod.Bacs), busy = true) }
        vm.run {
            val existing = vm.state.value.paymentRuns
            var failure: ZillitError? = null
            val done = mutableSetOf<String>()
            for ((index, group) in groups.withIndex()) {
                val result = vm.repo.createPaymentRun(
                    name = group.runName,
                    number = PaymentRuns.nextNumber(existing, index),
                    payMethod = PayMethod.Bacs,
                    invoiceIds = group.ids,
                )
                if (result is ZillitResult.Failure) {
                    failure = result.error
                    break
                }
                done += group.ids
            }
            vm.update {
                copy(
                    busy = false,
                    runDraft = if (failure == null) null else runDraft?.copy(busy = null),
                    selected = selected - done,
                    error = failure?.localised(),
                )
            }
            if (failure == null) vm.notice(str(S.desktop_inv_payment_run_created_n, groups.size))
            loadPaymentRuns()
            vm.refresh()
        }
    }

    fun markPaid(ids: List<String>) {
        if (ids.isEmpty()) return
        vm.update { copy(busy = true, runDraft = runDraft?.copy(busy = PayMethod.Wire)) }
        vm.run {
            val result = vm.repo.markPaid(ids)
            vm.update {
                copy(
                    busy = false,
                    runDraft = if (result is ZillitResult.Success) null else runDraft?.copy(busy = null),
                    selected = if (result is ZillitResult.Success) selected - ids.toSet() else selected,
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                vm.notice(str(S.desktop_inv_marked_paid_n, ids.size))
                vm.refresh()
            }
        }
    }

    fun confirmRejectRun() {
        val request = vm.state.value.rejectRun ?: return
        if (!request.isReady || request.busy) return
        vm.update { copy(rejectRun = rejectRun?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.rejectPaymentRun(request.run.id, request.reason)
            vm.update {
                copy(
                    rejectRun = if (result is ZillitResult.Success) null else rejectRun?.copy(busy = false),
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                vm.notice(str(S.ah_run_rejected_toast))
                loadPaymentRuns()
                vm.refresh()
            }
        }
    }

    fun actOnRun(run: PaymentRun, done: String, action: suspend (String) -> ZillitResult<Unit>) {
        if (vm.state.value.busy) return
        vm.update { copy(busy = true) }
        vm.run {
            val result = action(run.id)
            vm.update { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                vm.notice(done)
                loadPaymentRuns()
            }
        }
    }

    fun confirmSalesInvoice() {
        val draft = vm.state.value.salesDraft ?: return
        if (!draft.isReady || draft.busy) return
        vm.update { copy(salesDraft = salesDraft?.copy(busy = true)) }
        vm.run {
            val result = vm.repo.createSalesInvoice(
                SalesInvoice(
                    id = "",
                    reference = draft.reference,
                    clientName = draft.clientName,
                    description = draft.description,
                    grossAmount = draft.amountValue ?: 0.0,
                    currency = draft.currency.ifBlank { vm.state.value.projectCurrency },
                    dueDateMs = draft.dueDateMs,
                ),
            )
            vm.update {
                copy(
                    salesDraft = if (result is ZillitResult.Success) null else salesDraft?.copy(busy = false),
                    error = (result as? ZillitResult.Failure)?.error?.localised(),
                )
            }
            if (result is ZillitResult.Success) {
                vm.notice(str(S.desktop_inv_sales_invoice_raised))
                loadSalesInvoices()
            }
        }
    }

    fun actOnSales(done: String, action: suspend () -> ZillitResult<Unit>) {
        if (vm.state.value.busy) return
        vm.update { copy(busy = true) }
        vm.run {
            val result = action()
            vm.update { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                vm.notice(done)
                loadSalesInvoices()
            }
        }
    }

    /** The rows behind the tick boxes, in the order they are on screen. */
    fun selectedRows(): List<Invoice> {
        val picked = vm.state.value.selected
        return vm.state.value.entryRows.filter { it.id in picked }
    }

    /** The assign sheet opens over what is ticked; nothing ticked, nothing to hand on. */
    fun startAssign() {
        val ids = vm.state.value.selected.toList()
        if (ids.isEmpty()) {
            vm.update { copy(error = str(S.desktop_inv_tick_to_assign_first)) }
            return
        }
        vm.update { copy(assignFor = AssignRequest(invoiceIds = ids), assignees = vm.team()) }
    }

    /**
     * Assigns one at a time, as the web does.
     *
     * A part-done assignment is still an assignment: the rows that landed keep
     * their new owner, and only the failure is reported.
     */
    fun confirmAssign() {
        val request = vm.state.value.assignFor ?: return
        if (!request.isReady || request.busy) return
        vm.update { copy(assignFor = assignFor?.copy(busy = true)) }
        vm.run {
            val failure = request.invoiceIds.firstNotNullOfOrNull { id ->
                (vm.repo.assign(id, request.userId, request.reasonText) as? ZillitResult.Failure)?.error
            }
            vm.update {
                copy(
                    assignFor = if (failure == null) null else assignFor?.copy(busy = false),
                    selected = if (failure == null) emptySet() else selected,
                    error = failure?.localised(),
                )
            }
            if (failure == null) {
                vm.notice(str(S.desktop_inv_assigned_n, request.invoiceIds.size))
                vm.refresh()
            }
        }
    }
}
