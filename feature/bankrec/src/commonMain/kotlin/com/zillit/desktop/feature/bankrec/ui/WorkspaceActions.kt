package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.TxnStatus

/**
 * The reconciliation itself: which bank line answers which ledger entry.
 *
 * Matching is the one act here that is invisible once done — a wrong match
 * leaves both sides reading as reconciled, and nothing on the screen says they
 * do not belong together. So every match is proposed and then confirmed, and
 * the confirmation names both sides.
 */
internal class WorkspaceActions(private val vm: BankRecViewModel) {

    private val workspace: WorkspaceState get() = vm.ui.workspace

    private fun edit(reducer: WorkspaceState.() -> WorkspaceState) =
        vm.update { copy(workspace = workspace.reducer()) }

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.FilterWorkspace -> edit { copy(filter = event.filter) }
            BankRecEvent.RerunAutoMatch -> rerun()
            is BankRecEvent.SelectTransaction -> edit { copy(selected = event.transaction) }
            is BankRecEvent.ProposeMatch ->
                edit { copy(pending = PendingMatch(event.transaction, event.entry, event.wasSuggested)) }

            BankRecEvent.DismissMatch -> edit { copy(pending = null) }
            BankRecEvent.ConfirmMatch -> confirmMatch()
            is BankRecEvent.AcceptSuggestion -> acceptSuggestion(event.transaction)
            BankRecEvent.AskSignOff -> edit { copy(confirmingSignOff = true) }
            is BankRecEvent.EditSignOffNote -> edit { copy(signOffNote = event.note) }
            BankRecEvent.DismissSignOff -> edit { copy(confirmingSignOff = false) }
            BankRecEvent.ConfirmSignOff -> signOff()
            else -> return false
        }
        return true
    }

    /** Opens the workspace on whichever period is being worked on. */
    fun open(force: Boolean) {
        val periodId = workspace.periodId.takeIf { id -> vm.ui.periods.any { it.id == id && it.isOpen } }
            ?: vm.ui.currentPeriod?.id
            ?: vm.ui.openPeriods.firstOrNull()?.id
            ?: return edit { WorkspaceState() }
        if (!force && periodId == workspace.periodId && workspace.transactions.isNotEmpty()) return
        load(periodId)
    }

    fun openPeriod(periodId: String) {
        vm.update { copy(tab = BankTab.Workspace) }
        load(periodId)
    }

    /**
     * Keeps the open reconciliation on a period that still exists.
     *
     * Two jobs, and both matter. A period signed off or deleted by somebody
     * else leaves the workspace showing lines that no longer belong to
     * anything. And the tab can be opened *before* the period list has
     * arrived — there is nothing to open on at that moment, so the workspace
     * waits here for the list rather than staying empty until the user
     * switches away and back.
     */
    fun onPeriodsChanged() {
        val current = workspace.periodId
        if (current.isNotBlank() && vm.ui.periods.any { it.id == current }) return
        if (current.isNotBlank()) edit { WorkspaceState() }
        if (vm.ui.tab == BankTab.Workspace) open(force = true)
    }

    private fun load(periodId: String) {
        edit { copy(periodId = periodId, loading = true, selected = null, pending = null) }
        vm.runResult({ vm.repo.workspace(periodId) }, { data ->
            edit {
                copy(
                    transactions = data.transactions,
                    ledger = data.ledger,
                    loading = false,
                )
            }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
    }

    private fun rerun() {
        val periodId = workspace.periodId.ifBlank { return }
        edit { copy(rerunning = true) }
        vm.runResult({ vm.repo.rerunAutoMatch(periodId) }, {
            edit { copy(rerunning = false) }
            vm.notify("Matching rules run again.")
            load(periodId)
        }, { error ->
            edit { copy(rerunning = false) }
            vm.report(error)
        })
    }

    /**
     * Takes the engine's own suggestion for a line.
     *
     * Still confirmed: a suggestion is a guess with a confidence on it, and
     * accepting one reconciles two records that nothing afterwards will
     * question.
     */
    private fun acceptSuggestion(transaction: BankTransaction) {
        val entry = suggestedEntry(transaction)
        if (entry == null) {
            vm.refuse("There is no suggested entry to accept for this line.")
            return
        }
        edit { copy(pending = PendingMatch(transaction, entry, wasSuggested = true)) }
    }

    /** The ledger entry the engine suggested, when it is still unmatched. */
    private fun suggestedEntry(transaction: BankTransaction): LedgerEntry? =
        transaction.matchedInvoiceIds.firstNotNullOfOrNull { id ->
            workspace.ledger.firstOrNull { it.entityId == id && !it.isMatched }
        }

    private fun confirmMatch() {
        val pending = workspace.pending ?: return
        edit { copy(matching = true) }
        vm.runResult(
            { vm.repo.matchTransaction(pending.transaction.id, pending.entry.entityId, pending.entry.kind) },
            {
                edit {
                    copy(
                        matching = false,
                        pending = null,
                        selected = null,
                        // Marked here as well as reloaded: the reload is a
                        // round trip, and the row that was just reconciled
                        // should not sit unmatched while it runs.
                        transactions = transactions.map { row ->
                            if (row.id == pending.transaction.id) {
                                row.copy(
                                    status = TxnStatus.Matched,
                                    matchedInvoiceIds = listOf(pending.entry.entityId),
                                )
                            } else {
                                row
                            }
                        },
                        ledger = ledger.map { row ->
                            if (row.entityId == pending.entry.entityId) {
                                row.copy(transactionIds = row.transactionIds + pending.transaction.id)
                            } else {
                                row
                            }
                        },
                    )
                }
                vm.notify("Matched to ${pending.entry.title}.")
                // The service also clears exceptions and fraud alerts on a
                // match, so the period counts move too.
                vm.loadPeriods()
            },
            { error ->
                edit { copy(matching = false) }
                vm.report(error)
            },
        )
    }

    /**
     * Closes the period.
     *
     * Marks its invoices paid, computes the closing balance and locks it.
     * Confirmed, and the note is required when anything is still outstanding —
     * signing off over unmatched lines is a judgement somebody will be asked
     * about later.
     */
    private fun signOff() {
        val periodId = workspace.periodId.ifBlank { return }
        val note = workspace.signOffNote
        if (hasOutstanding() && note.isBlank()) {
            vm.refuse("Say why this period is being signed off with items outstanding.")
            return
        }
        edit { copy(signingOff = true) }
        vm.runResult({ vm.repo.signOffPeriod(periodId, note) }, {
            edit { WorkspaceState() }
            vm.notify("Period signed off.")
            vm.loadPeriods()
        }, { error ->
            edit { copy(signingOff = false) }
            vm.report(error)
        })
    }

    /** Whether anything on the period still needs a person. */
    fun hasOutstanding(): Boolean =
        workspace.transactions.any { it.effectiveStatus != TxnStatus.Matched }
}
