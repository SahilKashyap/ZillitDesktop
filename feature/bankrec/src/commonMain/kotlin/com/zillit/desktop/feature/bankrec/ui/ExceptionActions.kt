package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm

/**
 * The bank lines the reconciliation could not place.
 *
 * The web's ways out of one, as it offers them: ignore it, investigate a
 * credit, quick-add a debit to the ledger — and an investigation, once opened,
 * is closed with "Investigated". Each status change is a single click there
 * and here; the quick add is the one that writes a journal, so it carries the
 * form.
 */
internal class ExceptionActions(private val vm: BankRecViewModel) {

    private val page: ExceptionsPageState get() = vm.ui.exceptionsPage

    private fun edit(reducer: ExceptionsPageState.() -> ExceptionsPageState) =
        vm.update { copy(exceptionsPage = exceptionsPage.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SetExceptionsPeriod -> edit { copy(periodChoice = event.choice) }
            is BankRecEvent.SetExceptionStatus -> setStatus(event.exceptionId, event.status)
            is BankRecEvent.OpenExceptionQuickAdd -> openQuickAdd(event.exceptionId)
            is BankRecEvent.EditExceptionQuickAdd -> edit { copy(quickAdd = quickAdd?.copy(form = event.form)) }
            BankRecEvent.CloseExceptionQuickAdd -> if (page.quickAdd?.saving != true) edit { copy(quickAdd = null) }
            BankRecEvent.SubmitExceptionQuickAdd -> submitQuickAdd()
            BankRecEvent.ExportExceptionsPdf -> export()
            else -> return false
        }
        return true
    }

    private fun setStatus(id: String, status: ExceptionStatus) {
        if (page.acting != null) return
        edit { copy(acting = ExceptionAction(id, status)) }
        vm.runResult({ vm.repo.setExceptionStatus(id, status) }, {
            vm.update {
                copy(
                    exceptionsPage = exceptionsPage.copy(acting = null),
                    // Moved at once, then confirmed by the re-read.
                    exceptions = exceptions.map { if (it.id == id) it.copy(status = status) else it },
                )
            }
            vm.notify(str(S.desktop_br_exception_marked, status.label.lowercase()))
            vm.loadExceptions()
        }, { error ->
            edit { copy(acting = null) }
            vm.report(error)
        })
    }

    /**
     * The web's Quick Add form, filled from the line: its date, its reference
     * as the invoice number, the type's own guidance as the description, and
     * the amount it moved.
     */
    private fun openQuickAdd(id: String) {
        val exception = vm.ui.exceptions.firstOrNull { it.id == id } ?: return
        edit { copy(quickAdd = ExceptionQuickAddState(exceptionId = id, form = seed(exception))) }
    }

    private fun seed(exception: BankException): QuickAddForm {
        val txn = exception.transaction
        val amount = if (exception.debit > 0) exception.debit else exception.credit
        return QuickAddForm(
            date = BankRecFormat.isoDate(txn?.transactionDateMillis),
            invoiceNumber = txn?.reference.orEmpty(),
            description = exception.type.guidance.ifBlank { exception.description }
                .ifBlank { txn?.description.orEmpty() },
            amount = amountText(amount),
        )
    }

    private fun submitQuickAdd() {
        val dialog = page.quickAdd ?: return
        if (dialog.saving) return
        val form = dialog.form
        lockProblem(form.effectiveDate, vm.ui.lookups.lockedThrough)?.let { return vm.refuse(it) }
        if (form.amountValue == null) return vm.refuse(str(S.desktop_br_enter_amount))
        edit { copy(quickAdd = dialog.copy(saving = true)) }
        vm.runResult({ vm.repo.quickAddException(dialog.exceptionId, form, fromWorkspace = false) }, {
            edit { copy(quickAdd = null) }
            vm.notify(str(S.desktop_br_entry_added))
            vm.loadExceptions()
            vm.loadPeriods()
            vm.workspaceActions.refresh()
        }, { error ->
            edit { copy(quickAdd = quickAdd?.copy(saving = false)) }
            vm.report(error)
        })
    }

    /** One period's exceptions as a PDF — the route takes a single period, so "all" cannot export. */
    private fun export() {
        val periodId = resolvePeriodChoice(page.periodChoice, vm.ui.openPeriods)
        if (periodId == ALL_PERIODS) return vm.refuse(str(S.desktop_br_select_single_period))
        if (page.exporting) return
        edit { copy(exporting = true) }
        vm.launchWork {
            vm.deliver("exceptions-export-${today()}.pdf", vm.repo.exportExceptionsPdf(periodId, vm.company))
            edit { copy(exporting = false) }
        }
    }
}
