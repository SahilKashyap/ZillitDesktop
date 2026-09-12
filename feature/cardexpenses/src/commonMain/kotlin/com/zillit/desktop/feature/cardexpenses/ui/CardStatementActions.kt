package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.PickKind

/**
 * Importing a bank statement and reviewing what came off it.
 *
 * Its own collaborator because the import is two operations the card service
 * deliberately keeps apart — putting the file in storage, then naming it — and
 * the review after it has a rule of its own: a row nobody can be attributed to
 * has nowhere to be sent.
 */
internal class CardStatementActions(
    private val vm: CardExpensesViewModel,
    private val uploader: CardAttachmentUploader?,
) {

    /**
     * Picks a statement file, stores it, and hands the server the pointer.
     *
     * The service ingests from storage rather than from a multipart upload, so
     * the two steps are this application's: put the file somewhere the server
     * can read it, then name it.
     */
    fun import() {
        val pick = uploader ?: return
        vm.run {
            vm.update { copy(uploading = true) }
            val stored = pick.pick(PickKind.Statement)
            vm.update { copy(uploading = false) }
            when (stored) {
                is ZillitResult.Failure -> vm.fail(stored.error.localised())
                is ZillitResult.Success -> {
                    val file = stored.data ?: return@run
                    val currency = vm.current.statementCurrency.trim().takeIf { it.isNotEmpty() }
                    vm.act("${file.fileName} imported") { vm.repo.importStatement(file.key, currency) }
                }
            }
        }
    }

    /**
     * The imports list, and the open statement's rows alongside it.
     *
     * Both together: the point of the screen is reviewing one statement's
     * rows, and making that a second navigation would put a click between an
     * accountant and the work.
     */
    suspend fun load(): ZillitResult<CardUiState.() -> CardUiState> {
        val imports = vm.repo.imports()
        if (imports is ZillitResult.Failure) return imports
        val loaded = (imports as ZillitResult.Success).data
        val open = vm.current.openImportId ?: loaded.firstOrNull()?.id
        val rows = open?.let { vm.repo.importRows(it).getOrNull() }.orEmpty()
        return ZillitResult.Success { copy(imports = loaded, openImportId = open, importRows = rows) }
    }

    fun open(importId: String?) {
        vm.update { copy(openImportId = importId, importRows = emptyList(), selection = emptySet()) }
        if (importId == null) return
        vm.run {
            vm.repo.importRows(importId).getOrNull()?.let { rows ->
                if (vm.current.openImportId == importId) vm.update { copy(importRows = rows) }
            }
        }
    }

    fun processRows() {
        val importId = vm.current.openImportId ?: return
        val ids = vm.current.selection.toList()
        if (ids.isEmpty()) {
            vm.fail("Tick the rows to accept first.")
            return
        }
        vm.act("${ids.size} row(s) accepted") { vm.repo.processImport(importId, ids) }
        vm.update { copy(selection = emptySet()) }
    }

    /**
     * Sends the ticked rows to their cardholders.
     *
     * A row with nobody on it has nowhere to go, so those are dropped from the
     * send and named rather than silently included.
     */
    fun submitRows() {
        val ticked = vm.current.importRows.filter { it.id in vm.current.selection }
        val sendable = ticked.filter { it.canSubmit }
        if (sendable.isEmpty()) {
            vm.fail("None of the ticked rows has a cardholder on it, so there is nobody to ask.")
            return
        }
        val skipped = ticked.size - sendable.size
        val message = "${sendable.size} row(s) sent" +
            if (skipped > 0) " · $skipped skipped with no holder" else ""
        vm.act(message) { vm.repo.submitRowsToHolders(sendable.map { it.id }) }
        vm.update { copy(selection = emptySet()) }
    }
}
