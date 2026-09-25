package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardCurrencies
import com.zillit.desktop.feature.cardexpenses.domain.CardInboxHost
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ImportedRow
import com.zillit.desktop.feature.cardexpenses.domain.InboxWrite
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.domain.StatementCurrencyOptions
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.TransactionSelection
import com.zillit.desktop.feature.cardexpenses.domain.statementFileError

/**
 * Import Statement, the Receipt Inbox and All Transactions — every action on
 * those three pages and on the detail and manual match they open.
 *
 * The web's rules, not the desktop's earlier ones: no confirmation in front of
 * the inbox's row actions, the server's own message on every success, and a
 * refetch rather than an optimistic edit, because the server decides which
 * section a receipt lands in.
 */
@Suppress("TooManyFunctions") // One handler per user action on three pages.
internal class CardInboxActions(
    private val vm: CardExpensesViewModel,
    private val host: CardInboxHost?,
    private val loadBanks: suspend () -> List<CardBank>,
) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table; splitting it hides the mapping.
    fun handle(event: InboxEvent) {
        when (event) {
            is InboxEvent.ToggleSection -> edit {
                val section = event.section
                copy(collapsed = if (section in collapsed) collapsed - section else collapsed + section)
            }

            is InboxEvent.OpenDetail -> openDetail(event.receiptId)
            InboxEvent.CloseDetail -> edit { copy(detail = null) }
            InboxEvent.ShowHistory -> showHistory()
            InboxEvent.HideHistory -> edit { copy(detail = detail?.copy(history = null)) }

            is InboxEvent.Attach -> attach(event.receiptId)
            is InboxEvent.FlagPersonal -> flagPersonal(event.receipt)
            is InboxEvent.DismissDuplicate -> writeAndReload(InboxWrite.DismissDuplicate(event.receiptId))
            is InboxEvent.DismissPersonal -> writeAndReload(InboxWrite.DismissPersonal(event.receiptId))
            InboxEvent.RerunMatch -> rerun()

            is InboxEvent.OpenManualMatch -> openManualMatch(event.receipt)
            is InboxEvent.SelectCandidate -> edit {
                // A second click on the selected row clears it, as the web's does.
                copy(
                    manualMatch = manualMatch?.let { open ->
                        open.copy(selectedId = event.transactionId.takeIf { it != open.selectedId })
                    },
                )
            }

            InboxEvent.ConfirmManualMatch -> confirmManualMatch()
            InboxEvent.CloseManualMatch -> edit {
                if (manualMatch?.confirming == true) this else copy(manualMatch = null)
            }

            InboxEvent.PickStatement -> pickStatement()
            is InboxEvent.DropStatement -> accept(event.file)
            is InboxEvent.ChooseCurrency -> editImport { copy(currency = event.code) }
            InboxEvent.CancelImport -> editImport { if (importing) this else clearedPending() }
            InboxEvent.StartImport -> startImport()
            is InboxEvent.ToggleImportRow -> toggleImportRow(event.rowId)
            InboxEvent.ToggleAllImportRows -> editImport {
                val fresh = result?.rows.orEmpty().filter { it.isNew }.map { it.id }
                copy(selected = if (fresh.isNotEmpty() && selected.containsAll(fresh)) emptySet() else fresh.toSet())
            }

            InboxEvent.ClearImportSelection -> editImport { copy(selected = emptySet()) }
            InboxEvent.SubmitToCrew -> submitToCrew()
            InboxEvent.NewImport -> editImport {
                clearedPending().copy(result = null, submission = null, selected = emptySet())
            }

            is InboxEvent.ToggleTransaction -> vm.update {
                copy(
                    selection = if (event.transactionId in selection) {
                        selection - event.transactionId
                    } else {
                        selection + event.transactionId
                    },
                )
            }

            is InboxEvent.ToggleAllTransactions -> vm.update {
                copy(selection = TransactionSelection.toggleAll(selection, event.visibleIds))
            }

            is InboxEvent.AskDelete -> editLedger { if (deleting) this else copy(deleteTarget = event.transaction) }
            InboxEvent.ConfirmDelete -> confirmDelete()
            is InboxEvent.AskBulkDelete -> editLedger { if (bulkDeleting) this else copy(bulkConfirm = event.open) }
            is InboxEvent.ConfirmBulkDelete -> confirmBulkDelete(event.transactionIds)
        }
    }

    // -- the page reads --------------------------------------------------------

    /**
     * Import Statement's read: the currencies a statement may be in.
     *
     * Leaves the page's own import alone — a socket refresh lands here too,
     * and wiping the statement the accountant is reviewing would be the one
     * thing worse than not refreshing.
     */
    suspend fun loadImport(): ZillitResult<Reducer> {
        val project = host?.currencies() ?: CardCurrencies()
        val bankAccounts = loadBanks()
        return ZillitResult.Success {
            val options = StatementCurrencyOptions.of(providers, bankAccounts, project.codes)
            copy(
                banks = bankAccounts.ifEmpty { banks },
                inbox = inbox.copy(
                    import = inbox.import.copy(
                        currencies = options,
                        currenciesLoading = false,
                        currency = options.resolve(inbox.import.currency),
                        projectCurrencies = project,
                    ),
                ),
            )
        }
    }

    /** All Transactions' catalogues: the departments filter and the default currency the Value tile is in. */
    suspend fun ledgerCatalogues(): Reducer {
        val departments = host?.departments().orEmpty()
        val currencies = host?.currencies()
        return {
            copy(
                inbox = inbox.copy(
                    ledger = inbox.ledger.copy(
                        departments = departments.ifEmpty { inbox.ledger.departments },
                        defaultCurrency = currencies?.defaultCode ?: inbox.ledger.defaultCurrency,
                    ),
                ),
            )
        }
    }

    // -- inbox -----------------------------------------------------------------

    /**
     * Opens the detail on the slim row and reads the full one
     * (`ReceiptInboxPage.jsx:324-336`). Only the newest open may write: a
     * reply for a receipt already closed or replaced is dropped.
     */
    private fun openDetail(receiptId: String) {
        val row = vm.current.receipts.firstOrNull { it.id == receiptId } ?: return
        edit { copy(detail = ReceiptDetailState(receiptId, ReceiptDetail.of(row))) }
        vm.readRow(CardRowReads.detail(vm.current.destination), receiptId)
        vm.run {
            val read = vm.repo.inboxReceiptDetail(receiptId)
            if (vm.current.inbox.detail?.receiptId != receiptId) return@run
            edit {
                val open = detail ?: return@edit this
                copy(
                    detail = when (read) {
                        is ZillitResult.Success -> open.copy(detail = read.data, loading = false)
                        // Keep the slim row — the view falls back to list data.
                        is ZillitResult.Failure -> open.copy(loading = false)
                    },
                )
            }
            loadMedia(receiptId)
        }
    }

    private suspend fun loadMedia(receiptId: String) {
        val media = vm.current.inbox.detail?.detail?.media ?: return
        val store = host ?: return
        editDetail(receiptId) { copy(media = MediaState(loading = true)) }
        val fetched = store.media(media)
        editDetail(receiptId) {
            copy(
                media = when (fetched) {
                    is ZillitResult.Success -> MediaState(bytes = fetched.data)
                    is ZillitResult.Failure -> MediaState(failed = true)
                },
            )
        }
    }

    /** The trail, read when History is pressed — not with the detail. */
    private fun showHistory() {
        val receiptId = vm.current.inbox.detail?.receiptId ?: return
        editDetail(receiptId) { copy(history = HistoryState()) }
        vm.run {
            val trail = vm.repo.receiptHistory(receiptId).getOrNull().orEmpty()
            editDetail(receiptId) { copy(history = history?.copy(loading = false, entries = trail)) }
        }
    }

    private fun attach(receiptId: String) {
        if (vm.current.inbox.attachingId == receiptId) return
        edit { copy(attachingId = receiptId) }
        vm.run {
            if (write(InboxWrite.ConfirmMatch(receiptId))) vm.reload()
            edit { copy(attachingId = null) }
        }
    }

    /**
     * Section-aware, as the web's row menu is: a receipt linked to a statement
     * line flags the line (which syncs the receipt), an unlinked one flags itself.
     */
    private fun flagPersonal(receipt: CardReceipt) {
        val transactionId = receipt.transactionId?.takeIf { it.isNotBlank() }
        writeAndReload(
            if (transactionId != null) {
                InboxWrite.FlagTransactionPersonal(transactionId)
            } else {
                InboxWrite.FlagReceiptPersonal(receipt.id)
            },
        )
    }

    /** No confirmation and no statement: the web re-matches everything on one press. */
    private fun rerun() {
        if (vm.current.inbox.rerunning) return
        edit { copy(rerunning = true) }
        vm.run {
            if (write(InboxWrite.RerunMatch)) vm.reload()
            edit { copy(rerunning = false) }
        }
    }

    private fun openManualMatch(receipt: CardReceipt) {
        edit { copy(manualMatch = ManualMatchState(receipt)) }
        vm.run {
            val candidates = vm.repo.inboxMatchCandidates(receipt.id).getOrNull().orEmpty()
            edit {
                val open = manualMatch?.takeIf { it.receipt.id == receipt.id } ?: return@edit this
                copy(manualMatch = open.copy(loading = false, candidates = candidates))
            }
        }
    }

    private fun confirmManualMatch() {
        val open = vm.current.inbox.manualMatch ?: return
        val transactionId = open.selectedId ?: return
        if (open.confirming) return
        edit { copy(manualMatch = manualMatch?.copy(confirming = true)) }
        vm.run {
            if (write(InboxWrite.ManualMatch(open.receipt.id, transactionId))) {
                edit { copy(manualMatch = null) }
                vm.reload()
            } else {
                edit { copy(manualMatch = manualMatch?.copy(confirming = false)) }
            }
        }
    }

    // -- import ----------------------------------------------------------------

    private fun pickStatement() {
        val picker = host ?: return
        if (vm.current.inbox.import.importing) return
        vm.run {
            when (val picked = picker.pickStatement()) {
                is ZillitResult.Failure -> vm.fail(picked.error.localised())
                is ZillitResult.Success -> picked.data?.let(::accept)
            }
        }
    }

    /**
     * Takes the file and stops (`processFile`). The format is checked here —
     * the picker's filter does nothing for a drop — and before anything is
     * stored, so a wrong file never leaves the machine.
     */
    private fun accept(file: StatementFile) {
        if (vm.current.inbox.import.importing) return
        statementFileError(file.name)?.let { refusal ->
            vm.fail(refusal)
            return
        }
        editImport { copy(pendingFile = file) }
    }

    private fun startImport() {
        val store = host ?: return
        val import = vm.current.inbox.import
        val file = import.pendingFile ?: return
        if (import.importBlocked || import.importing) return
        val currency = import.currency.trim().takeIf { it.isNotEmpty() }
        editImport { copy(importing = true) }
        vm.run {
            val stored = store.storeStatement(file)
            val imported = when (stored) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> vm.repo.importStatementFile(stored.data, currency)
            }
            when (imported) {
                is ZillitResult.Failure -> {
                    editImport { copy(importing = false) }
                    vm.fail(imported.error.localised())
                }

                is ZillitResult.Success -> {
                    val result = imported.data.data
                    editImport {
                        copy(
                            importing = false,
                            pendingFile = null,
                            result = result,
                            submission = null,
                            importedCurrency = currency.orEmpty(),
                            // Every new row, ticked — the web's auto-select.
                            selected = result.rows.filter { it.isNew }.map { it.id }.toSet(),
                        )
                    }
                    imported.data.message?.let { message -> vm.update { copy(notice = message) } }
                }
            }
        }
    }

    private fun toggleImportRow(rowId: String) = editImport {
        val row = result?.rows?.firstOrNull { it.id == rowId }?.takeIf { it.isNew } ?: return@editImport this
        copy(selected = if (row.id in selected) selected - row.id else selected + row.id)
    }

    /**
     * Sends every ticked row, as the web does; the server skips the ones with
     * no holder and says so. Rows with a holder move to Pending Receipt here,
     * without a re-read — the import is not a list the server can re-serve.
     */
    private fun submitToCrew() {
        val import = vm.current.inbox.import
        val ids = import.result?.rows.orEmpty().filter { it.id in import.selected }.map { it.id }
        if (ids.isEmpty() || import.submitting) return
        editImport { copy(submitting = true) }
        vm.run {
            when (val sent = vm.repo.submitToCrew(ids)) {
                is ZillitResult.Failure -> {
                    editImport { copy(submitting = false) }
                    vm.fail(sent.error.localised())
                }

                is ZillitResult.Success -> {
                    editImport {
                        copy(
                            submitting = false,
                            submission = sent.data.data,
                            selected = emptySet(),
                            result = result?.copy(
                                rows = result.rows.map { row ->
                                    if (row.id in ids && !row.holderId.isNullOrBlank()) {
                                        row.copy(status = ImportedRow.PENDING_RECEIPT)
                                    } else {
                                        row
                                    }
                                },
                            ),
                        )
                    }
                    sent.data.message?.let { message -> vm.update { copy(notice = message) } }
                }
            }
        }
    }

    /** Back to "no statement in hand"; the currency goes with it (`clearPending`). */
    private fun ImportState.clearedPending(): ImportState =
        copy(pendingFile = null, currency = currencies.resolve(""))

    // -- all transactions ------------------------------------------------------

    private fun confirmDelete() {
        val ledger = vm.current.inbox.ledger
        val target = ledger.deleteTarget ?: return
        if (ledger.deleting) return
        editLedger { copy(deleting = true) }
        vm.run {
            val removed = write(InboxWrite.DeleteTransaction(target.id))
            editLedger { copy(deleting = false, deleteTarget = if (removed) null else deleteTarget) }
            // Re-read rather than wait on the socket: the row must leave either way.
            if (removed) vm.reload()
        }
    }

    /**
     * The bulk delete (`AllTransactionsPage.jsx:273-309`): the count, not the
     * status, is what happened. Some removed → the server's message, and only
     * what was sent leaves the selection; none → say nothing was deleted and
     * keep the selection, which is still the accountant's live intent.
     */
    private fun confirmBulkDelete(transactionIds: List<String>) {
        if (transactionIds.isEmpty() || vm.current.inbox.ledger.bulkDeleting) return
        editLedger { copy(bulkDeleting = true) }
        vm.run {
            when (val outcome = vm.repo.bulkDeleteTransactionsCounted(transactionIds)) {
                is ZillitResult.Failure -> {
                    editLedger { copy(bulkDeleting = false) }
                    vm.fail(outcome.error.localised())
                }

                is ZillitResult.Success -> {
                    editLedger { copy(bulkDeleting = false, bulkConfirm = false) }
                    if (outcome.data.data > 0) {
                        vm.update {
                            copy(
                                selection = selection - transactionIds.toSet(),
                                notice = outcome.data.message ?: notice,
                            )
                        }
                    } else {
                        vm.fail(str(S.desktop_ce_inbox_nothing_deleted))
                    }
                    vm.reload()
                }
            }
        }
    }

    // -- plumbing ----------------------------------------------------------------

    /** A write and, on success, a re-read of the page. */
    private fun writeAndReload(request: InboxWrite) = vm.run {
        if (write(request)) vm.reload()
    }

    /** Sends [write]; the server's message on success, its refusal on failure. */
    private suspend fun write(request: InboxWrite): Boolean = when (val outcome = vm.repo.inboxWrite(request)) {
        is ZillitResult.Success -> {
            outcome.data?.let { message -> vm.update { copy(notice = message) } }
            true
        }

        is ZillitResult.Failure -> {
            vm.fail(outcome.error.localised())
            false
        }
    }

    private fun edit(block: InboxState.() -> InboxState) = vm.update { copy(inbox = inbox.block()) }

    private fun editImport(block: ImportState.() -> ImportState) = edit { copy(import = import.block()) }

    private fun editLedger(block: LedgerState.() -> LedgerState) = edit { copy(ledger = ledger.block()) }

    /** Edits the open detail only while it is still [receiptId]'s. */
    private fun editDetail(receiptId: String, block: ReceiptDetailState.() -> ReceiptDetailState) = edit {
        val open = detail?.takeIf { it.receiptId == receiptId } ?: return@edit this
        copy(detail = open.block())
    }
}
