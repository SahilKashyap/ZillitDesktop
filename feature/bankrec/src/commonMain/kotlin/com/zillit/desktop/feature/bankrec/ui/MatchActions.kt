package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import kotlinx.coroutines.Job

/**
 * Which bank line answers which ledger entry.
 *
 * Matching is the one act here that is invisible once done — a wrong match
 * leaves both sides reading as reconciled, and nothing on the screen says they
 * do not belong together. So both ways in are confirmed, and both confirmations
 * name both sides: accepting the engine's suggestion, and choosing an entry by
 * hand.
 */
internal class MatchActions(private val vm: BankRecViewModel) {

    private val workspace: WorkspaceState get() = vm.ui.workspace

    private var flashJob: Job? = null

    private fun edit(reducer: WorkspaceState.() -> WorkspaceState) =
        vm.update { copy(workspace = workspace.reducer()) }

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.ViewInvoice -> viewInvoice(event.invoiceId)
            is BankRecEvent.ProposeMatch -> edit {
                copy(proposal = MatchProposal(event.transactionId, event.invoiceId))
            }
            BankRecEvent.DismissMatch -> if (!workspace.accepting) edit { copy(proposal = null) }
            BankRecEvent.ConfirmMatch -> confirmProposal()
            is BankRecEvent.OpenManualMatch ->
                edit { copy(manualMatchId = event.transactionId, manualMatchEntryId = null) }

            is BankRecEvent.PickManualMatch -> edit { copy(manualMatchEntryId = event.ledgerRowId) }
            BankRecEvent.CloseManualMatch ->
                if (!workspace.manualMatching) edit { copy(manualMatchId = null, manualMatchEntryId = null) }

            BankRecEvent.ConfirmManualMatch -> confirmManual()
            else -> return false
        }
        return true
    }

    /**
     * Scrolls to the invoice a suggestion names and lights it for three
     * seconds — long enough to find, short enough not to be mistaken for a
     * selection.
     */
    private fun viewInvoice(invoiceId: String) {
        val row = vm.ui.workspaceView().invoice(invoiceId) ?: return
        edit { copy(flashId = row.id) }
        flashJob?.cancel()
        flashJob = vm.after(FLASH_MILLIS) {
            if (workspace.flashId == row.id) edit { copy(flashId = null) }
        }
    }

    private fun confirmProposal() {
        val proposal = workspace.proposal ?: return
        if (workspace.accepting) return
        val view = vm.ui.workspaceView()
        val txn = view.bankRow(proposal.transactionId)?.txn ?: return edit { copy(proposal = null) }
        val entry = view.invoice(proposal.invoiceId)
        // The engine suggests invoices; an entry this client resolved says which
        // table it is really in.
        val kind = entry?.entry?.kind?.takeIf { it != LedgerEntryKind.Other } ?: LedgerEntryKind.Invoice
        edit { copy(accepting = true) }
        vm.runResult({ vm.repo.matchTransaction(txn.id, proposal.invoiceId, kind) }, {
            val wasFraud = txn.fraudType != null
            edit {
                copy(
                    accepting = false,
                    proposal = null,
                    transactions = transactions.map { row ->
                        if (row.id != txn.id) {
                            row
                        } else {
                            row.copy(
                                status = TxnStatus.Matched,
                                matchedInvoiceIds = listOf(proposal.invoiceId),
                                // Accepting a flagged line is the review: the
                                // service records it as accepted in the audit
                                // trail, and the bar under it now says so.
                                fraudStatus = if (wasFraud) FraudStatus.Accepted else row.fraudStatus,
                            )
                        }
                    },
                    ledger = ledger.map { row ->
                        if (row.entityId == proposal.invoiceId || row.id == proposal.invoiceId) {
                            row.copy(transactionIds = (row.transactionIds + txn.id).distinct())
                        } else {
                            row
                        }
                    },
                )
            }
            vm.notify(if (wasFraud) "Fraud reviewed and match accepted." else "Match confirmed.")
            afterMatch()
        }, { error ->
            edit { copy(accepting = false) }
            vm.report(error)
        })
    }

    private fun confirmManual() {
        val txnId = workspace.manualMatchId ?: return
        val entryId = workspace.manualMatchEntryId ?: return
        if (workspace.manualMatching) return
        val view = vm.ui.workspaceView()
        val entry = view.ledgerRow(entryId)?.entry ?: return
        edit { copy(manualMatching = true) }
        vm.runResult({ vm.repo.matchTransaction(txnId, entry.entityId, entry.kind) }, {
            edit {
                copy(
                    manualMatching = false,
                    manualMatchId = null,
                    manualMatchEntryId = null,
                    transactions = transactions.map { row ->
                        if (row.id == txnId) {
                            row.copy(status = TxnStatus.Matched, matchedInvoiceIds = listOf(entry.entityId))
                        } else {
                            row
                        }
                    },
                    ledger = ledger.map { row ->
                        if (row.entityId == entry.entityId || row.id == entry.id) {
                            row.copy(transactionIds = (row.transactionIds + txnId).distinct())
                        } else {
                            row
                        }
                    },
                )
            }
            vm.notify("Matched to ${view.ledgerRow(entryId)?.title.orEmpty().ifBlank { "the ledger entry" }}.")
            afterMatch()
        }, { error ->
            edit { copy(manualMatching = false) }
            vm.report(error)
        })
    }

    /**
     * A match moves more than the two rows: the service auto-accepts the
     * line's fraud alert, usually clears an exception, and the period's counts
     * change. Marked locally first so the row does not sit unmatched while the
     * reads run.
     */
    private fun afterMatch() {
        vm.loadPeriods()
        vm.loadExceptions()
        vm.loadFraudAlerts()
        vm.workspaceActions.refresh()
    }

    private companion object {
        const val FLASH_MILLIS = 3_000L
    }
}
