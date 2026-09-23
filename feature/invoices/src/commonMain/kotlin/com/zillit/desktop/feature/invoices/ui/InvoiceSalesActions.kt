package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.domain.LineItems
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite

/**
 * Sales Invoices — money owed to the production (the web's `SalesPage`):
 * raising one with its lines, sending it, marking it paid, and deleting a
 * draft once "delete it?" is answered. Every write needs the posting right,
 * here as on the page's buttons.
 */
internal class InvoiceSalesActions(private val vm: InvoicesViewModel) {

    private var nextLine = 0

    /** True when [event] was this page's own. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            InvoicesEvent.StartSalesInvoice -> startSalesInvoice()
            is InvoicesEvent.EditSalesLines -> editSalesLines(event.edit)
            is InvoicesEvent.EditSalesInvoice -> vm.update { copy(salesDraft = event.draft) }
            InvoicesEvent.ConfirmSalesInvoice -> confirmSalesInvoice()
            InvoicesEvent.CancelSalesInvoice -> vm.update { copy(salesDraft = null) }
            is InvoicesEvent.SendSalesInvoice -> act(str(S.desktop_sent_to_the_client)) {
                vm.repo.sendSalesInvoice(event.invoice.id)
            }
            is InvoicesEvent.MarkSalesInvoicePaid -> act(str(S.desktop_marked_paid)) {
                vm.repo.markSalesInvoicePaid(event.invoice.id)
            }
            // A draft only, and only after "delete it?" — the web's ConfirmModal.
            is InvoicesEvent.DeleteSalesInvoice -> if (event.invoice.status == SalesInvoiceStatus.Draft) {
                vm.update { copy(confirmSalesDelete = event.invoice) }
            }
            InvoicesEvent.CancelDeleteSales -> vm.update { copy(confirmSalesDelete = null) }
            InvoicesEvent.ConfirmDeleteSales -> vm.state.value.confirmSalesDelete?.let { invoice ->
                vm.update { copy(confirmSalesDelete = null) }
                act(str(S.drive_deleted_default)) { vm.repo.deleteSalesInvoice(invoice.id) }
            }
            else -> return false
        }
        return true
    }

    fun load() {
        vm.update { copy(loading = false) }
        vm.run {
            when (val result = vm.repo.salesInvoices()) {
                is ZillitResult.Success -> vm.update { copy(salesInvoices = result.data) }
                is ZillitResult.Failure -> vm.update { copy(error = result.error.localised()) }
            }
        }
    }

    /** Raise an invoice: dated today, the project's currency, one empty line — the web's `resetForm`. */
    private fun startSalesInvoice() {
        val state = vm.state.value
        if (!state.viewer.mayPost) return
        val today = InvoiceFormat.today(vm.now())
        vm.update {
            copy(
                salesDraft = SalesInvoiceDraft(
                    currency = projectCurrency,
                    invoiceDate = today,
                    lines = LineDraft(listOf(CodedLine(newLineId()))),
                ),
            )
        }
    }

    private fun editSalesLines(edit: LineEdit) {
        val draft = vm.state.value.salesDraft ?: return
        if (draft.busy) return
        vm.update {
            val lines = LineItems.apply(draft.lines, edit, ::newLineId)
            copy(salesDraft = salesDraft?.copy(lines = lines, lineError = null))
        }
    }

    private fun newLineId(): String = "li-${vm.now()}-${++nextLine}"

    /**
     * Raise it — `handleCreate`: a client, an invoice date and lines that pass
     * the web's check; the reference is `SI-` and the time's last six digits
     * when none is typed, and a blank due date is today plus the 30-day terms.
     */
    private fun confirmSalesInvoice() {
        val state = vm.state.value
        val draft = state.salesDraft ?: return
        if (!draft.isReady || draft.busy || !state.viewer.mayPost) return
        val check = LineItems.check(draft.lines.lines)
        if (!check.ok) {
            val flagged = check.problems.map { it.lineId }.toSet()
            vm.update {
                val lines = draft.lines.copy(flagged = flagged)
                copy(salesDraft = salesDraft?.copy(lineError = lineMessage(check), lines = lines))
            }
            return
        }
        vm.update { copy(salesDraft = salesDraft?.copy(busy = true)) }
        val today = InvoiceFormat.today(vm.now())
        vm.run {
            val result = vm.repo.createSalesInvoice(
                SalesInvoiceWrite(
                    reference = draft.reference.trim().ifBlank { "SI-${vm.now().toString().takeLast(REF_DIGITS)}" },
                    clientName = draft.clientName,
                    description = draft.description,
                    currency = draft.currency.ifBlank { state.projectCurrency },
                    invoiceDate = draft.invoiceDate.trim(),
                    dueDate = draft.dueDate.trim().ifBlank { InvoiceFormat.plusDays(today, PAY_TERMS_DAYS) },
                    lines = draft.lines.lines,
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
                load()
            }
        }
    }

    private fun act(done: String, action: suspend () -> ZillitResult<Unit>) {
        // The row buttons need the posting right; so does the handler.
        if (vm.state.value.busy || !vm.state.value.viewer.mayPost) return
        vm.update { copy(busy = true) }
        vm.run {
            val result = action()
            vm.update { copy(busy = false, error = (result as? ZillitResult.Failure)?.error?.localised()) }
            if (result is ZillitResult.Success) {
                vm.notice(done)
                load()
            }
        }
    }

    private companion object {
        /** The web's default payment terms, "30 days". */
        const val PAY_TERMS_DAYS = 30

        /** `SI-` and the last six digits of the time — the web's generated reference. */
        const val REF_DIGITS = 6
    }
}
