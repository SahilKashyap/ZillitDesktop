package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm

/**
 * The bank lines the reconciliation could not place.
 *
 * Two ways out: say what is happening to it, or post it to the ledger and be
 * done. Posting is the one that writes, so it carries the form and the guard.
 */
internal class ExceptionActions(private val vm: BankRecViewModel) {

    private val state: ExceptionsState get() = vm.ui.exceptions

    private fun edit(reducer: ExceptionsState.() -> ExceptionsState) =
        vm.update { copy(exceptions = exceptions.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.FilterExceptions -> {
                edit { copy(periodId = event.periodId) }
                load(force = true)
            }

            is BankRecEvent.SetExceptionStatus -> setStatus(event.id, event.status, "")
            is BankRecEvent.ComposeExceptionNote ->
                edit { copy(noting = event.exception, noteText = event.exception.notes, noteStatus = event.status) }

            is BankRecEvent.EditExceptionNote -> edit { copy(noteText = event.text) }
            BankRecEvent.DismissExceptionNote -> edit { copy(noting = null, noteText = "") }
            BankRecEvent.SaveExceptionNote -> saveNote()
            is BankRecEvent.ComposeQuickAdd -> compose(event.exception)
            is BankRecEvent.EditQuickAdd -> edit { copy(form = event.form) }
            BankRecEvent.DismissQuickAdd -> edit { copy(posting = null) }
            BankRecEvent.SaveQuickAdd -> post()
            else -> return false
        }
        return true
    }

    fun load(force: Boolean) {
        if (!force && state.rows.isNotEmpty()) return
        val periodId = state.periodId
        edit { copy(loading = true) }
        vm.runResult({ vm.repo.exceptions(periodId.takeIf { it.isNotBlank() }) }, { rows ->
            edit { copy(rows = rows, loading = false) }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
    }

    private fun compose(exception: BankException) = edit {
        copy(
            posting = exception,
            // Seeded from the line so the description is not retyped from the
            // row the accountant is looking at.
            form = form.copy(description = exception.title, nominalCode = "", costCentre = ""),
        )
    }

    private fun setStatus(id: String, status: ExceptionStatus, notes: String) {
        edit { copy(acting = id) }
        vm.runResult({ vm.repo.setExceptionStatus(id, status, notes) }, {
            edit {
                copy(
                    acting = "",
                    rows = rows.map { if (it.id == id) it.copy(status = status, notes = notes) else it },
                )
            }
            vm.notify("Exception marked ${status.label.lowercase()}.")
        }, { error ->
            edit { copy(acting = "") }
            vm.report(error)
        })
    }

    private fun saveNote() {
        val exception = state.noting ?: return
        val note = state.noteText
        val status = state.noteStatus
        edit { copy(noting = null, noteText = "") }
        setStatus(exception.id, status, note)
    }

    /**
     * Posts the exception to the ledger.
     *
     * Refused without an account code: the server would take it and the entry
     * would land somewhere nobody chose, which is a journal to unpick rather
     * than an error to read.
     */
    private fun post() {
        val exception = state.posting ?: return
        val form = state.form
        form.problem?.let { return vm.refuse(it) }
        edit { copy(saving = true) }
        vm.runResult({ vm.repo.quickAddException(exception.id, form) }, {
            edit { copy(saving = false, posting = null, form = QuickAddForm()) }
            vm.notify("Posted to ${form.nominalCode}.")
            load(force = true)
            vm.loadPeriods()
        }, { error ->
            edit { copy(saving = false) }
            vm.report(error)
        })
    }
}
