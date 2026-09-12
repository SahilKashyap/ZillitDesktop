package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.StatementFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bringing a statement in — the web's two screens.
 *
 * First the account and the file, chosen and shown; only on "Import &
 * Auto-Match" is the file stored and read. Then the five steps, walked while
 * the service works, and the counts it reports. The steps between upload and
 * done are paced rather than reported — the service answers once, at the end —
 * which is exactly what the web does, and why they stop at the checks and wait.
 */
internal class ImportActions(
    private val vm: BankRecViewModel,
    private val files: StatementFiles?,
) {

    private val state: ImportState get() = vm.ui.import

    private var stepper: Job? = null

    private fun edit(reducer: ImportState.() -> ImportState) = vm.update { copy(import = import.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            BankRecEvent.OpenImport -> {
                if (files == null) return true.also { vm.refuse("This installation cannot upload files.") }
                vm.update { copy(import = ImportState(open = true)) }
            }

            is BankRecEvent.SelectImportAccount -> edit { copy(bankAccountId = event.bankAccountId, error = null) }
            BankRecEvent.BrowseStatement -> browse()
            is BankRecEvent.DropStatement -> choose(event.file)
            is BankRecEvent.ImportDragOver -> edit { copy(dragOver = event.over) }
            BankRecEvent.StartImport -> start()
            BankRecEvent.CloseImport -> close()
            else -> return false
        }
        return true
    }

    private fun browse() {
        val source = files ?: return
        if (state.picking || state.processing) return
        edit { copy(picking = true) }
        vm.launchWork {
            when (val picked = source.pick()) {
                is ZillitResult.Failure -> edit { copy(picking = false, error = picked.error.localised()) }
                is ZillitResult.Success -> {
                    edit { copy(picking = false) }
                    picked.data?.let(::choose)
                }
            }
        }
    }

    /**
     * Takes a file, from the picker or a drop, if it is one the service reads.
     *
     * Checked by extension, as the web's `accept` list is: an operating system
     * reports `.ofx` and `.qif` as plain text or as an opaque stream, and a
     * type allowlist refuses perfectly good statements.
     */
    private fun choose(file: PickedStatement) {
        if (state.processing) return
        val refusal = when {
            file.extension !in StatementFiles.EXTENSIONS ->
                "${file.name} is not a statement file. Supported formats: CSV, OFX, QIF, MT940, PDF."

            file.bytes.size > StatementFiles.MAX_BYTES -> "${file.name} is over the 20 MB limit."
            else -> null
        }
        edit {
            if (refusal != null) {
                copy(error = refusal, dragOver = false)
            } else {
                copy(file = file, error = null, dragOver = false, result = null)
            }
        }
    }

    private fun start() {
        val source = files ?: return
        val file = state.file ?: return
        val accountId = state.bankAccountId.ifBlank { return }
        if (state.processing) return
        edit { copy(processing = true, step = ImportStep.Upload.ordinal, error = null, result = null) }
        stepper = vm.launchWork {
            // Paced, not reported: parse, match, checks — then it waits on the
            // service at the checks, as the web's own interval does.
            while (isActive) {
                delay(STEP_MILLIS)
                edit { if (step < ImportStep.Validate.ordinal) copy(step = step + 1) else this }
            }
        }
        vm.launchWork {
            val outcome = when (val stored = source.upload(file)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> vm.repo.importStatement(stored.data, accountId)
            }
            stepper?.cancel()
            when (outcome) {
                is ZillitResult.Failure -> edit {
                    copy(processing = false, step = 0, error = outcome.error.localised().ifBlank { "Import failed" })
                }

                is ZillitResult.Success -> {
                    edit { copy(step = ImportStep.Done.ordinal, result = outcome.data) }
                    // A statement produces periods, lines, exceptions, alerts and
                    // variances in one go, so every list is re-read.
                    vm.loadPeriods()
                    vm.loadExceptions()
                    vm.loadFraudAlerts()
                    vm.loadFxVariances()
                    vm.workspaceActions.refresh()
                }
            }
        }
    }

    /** Refused while a file is still being read — closing then would strand the import. */
    private fun close() {
        if (state.processing && state.result == null) return
        stepper?.cancel()
        vm.update { copy(import = ImportState()) }
    }

    private companion object {
        const val STEP_MILLIS = 1_200L
    }
}
