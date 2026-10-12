package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.bankrec.domain.TxnStatus

/**
 * Taking a match back.
 *
 * The workspace's Unreconcile mode: matched lines get a tick and an undo bar,
 * one line goes through the single endpoint and several through the bulk one,
 * and both are confirmed first — a fraud line returns to review with its alert
 * re-opened, which nobody should discover afterwards.
 *
 * Nothing is patched locally. The reverted line's status, banner and exception
 * come from fields the server has just reset, so the workspace is re-read.
 */
internal class UnmatchActions(private val vm: BankRecViewModel) {

    private val workspace: WorkspaceState get() = vm.ui.workspace

    private fun edit(reducer: WorkspaceState.() -> WorkspaceState) =
        vm.update { copy(workspace = workspace.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SetUnreconcile -> setMode(event.on)
            is BankRecEvent.ToggleUnmatchTick -> toggleTick(event.rowId)
            is BankRecEvent.AskUnmatch -> ask(event.rowIds)
            BankRecEvent.DismissUnmatch -> if (workspace.unmatch?.submitting != true) edit { copy(unmatch = null) }
            BankRecEvent.ConfirmUnmatch -> confirm()
            else -> return false
        }
        return true
    }

    /** Off clears the ticks; on is refused for a signed-off period, which cannot be unreconciled. */
    private fun setMode(on: Boolean) {
        if (on && vm.ui.workspaceView().periodComplete) return
        edit { copy(unreconcile = on, unmatchTicked = emptySet()) }
    }

    private fun toggleTick(rowId: String) {
        if (!vm.ui.workspaceView().unreconcileMode) return
        edit {
            copy(unmatchTicked = if (rowId in unmatchTicked) unmatchTicked - rowId else unmatchTicked + rowId)
        }
    }

    private fun ask(rowIds: List<String>) {
        val view = vm.ui.workspaceView()
        if (!view.unreconcileMode) return
        val matched = rowIds.filter { id -> view.bankRow(id)?.status == TxnStatus.Matched }
        if (matched.isEmpty()) return
        edit { copy(unmatch = UnmatchRequest(matched)) }
    }

    private fun confirm() {
        val request = workspace.unmatch ?: return
        if (request.submitting) return
        val ids = request.ids
        edit { copy(unmatch = request.copy(submitting = true)) }
        vm.runResult({
            // One line goes to the single endpoint, which names a message; bulk names none yet.
            if (ids.size == 1) vm.repo.unmatchTransaction(ids.first()) else vm.repo.bulkUnmatchTransactions(ids)
        }, { message ->
            message?.takeIf { it.isNotBlank() }?.let { vm.notify(it.localisedMessage()) }
            edit { copy(unmatchTicked = unmatchTicked - ids.toSet(), unmatch = null) }
            // The alert re-opens, an exception can too, and the period's counts move.
            vm.loadPeriods()
            vm.loadExceptions()
            vm.loadFraudAlerts()
            vm.workspaceActions.refresh()
        }, { error ->
            edit { copy(unmatch = unmatch?.copy(submitting = false)) }
            vm.report(error)
        })
    }
}
