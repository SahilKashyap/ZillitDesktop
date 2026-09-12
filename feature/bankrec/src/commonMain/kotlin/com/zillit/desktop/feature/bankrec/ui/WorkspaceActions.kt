package com.zillit.desktop.feature.bankrec.ui

/**
 * The reconciliation workspace: which period is open, how it is filtered, and
 * the two period-wide acts — running the matching rules again, and signing off.
 *
 * Matching itself is [MatchActions]; the drawer is [QuickEntryActions].
 */
internal class WorkspaceActions(private val vm: BankRecViewModel) {

    private val workspace: WorkspaceState get() = vm.ui.workspace

    private fun edit(reducer: WorkspaceState.() -> WorkspaceState) =
        vm.update { copy(workspace = workspace.reducer()) }

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.FilterWorkspace -> edit { copy(filter = event.filter) }
            is BankRecEvent.SelectRow -> edit {
                copy(selectedId = if (selectedId == event.rowId) null else event.rowId)
            }
            is BankRecEvent.SetWorkspaceExpanded -> edit { copy(expanded = event.expanded) }
            BankRecEvent.ToggleQuickEntry -> edit { copy(showQuickEntry = !showQuickEntry) }
            BankRecEvent.RerunAutoMatch -> rerun()
            BankRecEvent.OpenSignOff -> if (workspace.periodId.isNotBlank()) edit { copy(signOff = SignOffState()) }
            is BankRecEvent.EditSignOffNote -> edit { copy(signOff = signOff?.copy(note = event.note)) }
            BankRecEvent.CloseSignOff -> if (workspace.signOff?.submitting != true) edit { copy(signOff = null) }
            BankRecEvent.ConfirmSignOff -> signOff()
            else -> return false
        }
        return true
    }

    /**
     * A bare Workspace tab: the newest period in progress, inline.
     *
     * The period already open stays open if it is still in progress — coming
     * back to the tab is not a request for a different month.
     */
    fun openCurrent() {
        val state = vm.ui
        val keep = state.period(workspace.periodId)?.takeIf { it.isOpen }
        val periodId = keep?.id ?: state.currentPeriod?.id
        edit { copy(expanded = false) }
        if (periodId == null) {
            if (!state.periodsLoading) edit { WorkspaceState(showQuickEntry = showQuickEntry) }
            return
        }
        load(periodId)
    }

    /**
     * Overview's or History's Open: that period, in the full view.
     *
     * An explicit choice wins over "the first one in progress" — more than one
     * period can be open, and picking the first would silently ignore which Open
     * was clicked.
     */
    fun openPeriod(periodId: String) {
        vm.update { copy(tab = BankTab.Workspace) }
        edit { copy(expanded = true) }
        load(periodId)
    }

    /** Re-reads the open period without blanking what is on screen. */
    fun refresh() {
        val periodId = workspace.periodId.ifBlank { return }
        load(periodId)
    }

    /**
     * Keeps the workspace on a period that still exists and is still open.
     *
     * A period signed off or deleted — here or by somebody else — leaves the
     * workspace showing lines that no longer belong to anything open, so it
     * moves to the next period in progress, or to its empty state. And a tab
     * opened before the list arrived has nothing to open on until it does.
     */
    fun onPeriodsChanged() {
        val state = vm.ui
        if (state.tab != BankTab.Workspace && workspace.periodId.isBlank()) return
        val current = state.period(workspace.periodId)
        when {
            current != null && current.isOpen -> Unit
            state.tab == BankTab.Workspace -> {
                val next = state.currentPeriod?.id
                if (next == null) {
                    edit { WorkspaceState(showQuickEntry = showQuickEntry, expanded = false) }
                } else if (next != workspace.periodId) {
                    load(next)
                }
            }

            else -> edit { WorkspaceState(showQuickEntry = showQuickEntry) }
        }
    }

    private fun load(periodId: String) {
        val switching = periodId != workspace.periodId
        edit {
            if (switching) {
                WorkspaceState(
                    periodId = periodId,
                    loading = true,
                    expanded = expanded,
                    showQuickEntry = showQuickEntry,
                )
            } else {
                // The same period again: the rows stay while the read runs, so
                // a refresh never flashes the page back to a skeleton.
                copy(loading = transactions.isEmpty() && ledger.isEmpty())
            }
        }
        vm.runResult({ vm.repo.workspace(periodId) }, { data ->
            if (workspace.periodId != periodId) return@runResult
            edit {
                copy(
                    transactions = data.transactions,
                    ledger = data.ledger,
                    closingZillit = data.closingZillit,
                    loading = false,
                    // A selection that no longer names a row would light nothing.
                    selectedId = selectedId?.takeIf { id ->
                        data.transactions.any { it.id == id } || data.ledger.any { it.id == id }
                    },
                )
            }
        }, { error ->
            if (workspace.periodId == periodId) edit { copy(loading = false) }
            vm.report(error)
        })
    }

    private fun rerun() {
        val periodId = workspace.periodId.ifBlank { return }
        if (workspace.rerunning) return
        edit { copy(rerunning = true, selectedId = null) }
        vm.runResult({ vm.repo.rerunAutoMatch(periodId) }, {
            edit { copy(rerunning = false) }
            vm.notify("Auto-match re-run.")
            load(periodId)
            // A re-run moves counts, can add or remove fraud flags, and clears
            // exceptions a new match now answers.
            vm.loadPeriods()
            vm.loadExceptions()
            vm.loadFraudAlerts()
        }, { error ->
            edit { copy(rerunning = false) }
            vm.report(error)
        })
    }

    /**
     * Closes the period.
     *
     * Marks its invoices paid, computes the closing balance and locks it. The
     * note is required when anything is outstanding — an unresolved fraud flag,
     * an unmatched or suggested line, an open exception, or a difference —
     * because signing off over those is a judgement somebody will be asked
     * about later.
     */
    private fun signOff() {
        val dialog = workspace.signOff ?: return
        val periodId = workspace.periodId.ifBlank { return }
        if (dialog.submitting) return
        val view = vm.ui.workspaceView()
        if (view.hasIssues && dialog.note.isBlank()) {
            return vm.refuse("Explain why you are signing off with exceptions.")
        }
        edit { copy(signOff = dialog.copy(submitting = true)) }
        vm.runResult({ vm.repo.signOffPeriod(periodId, dialog.note.trim()) }, {
            edit { copy(signOff = null) }
            vm.notify("Reconciliation signed off.")
            // The period has left "in progress"; the list decides where the
            // workspace goes next, with no page reload.
            vm.loadPeriods()
            vm.loadExceptions()
            vm.loadFraudAlerts()
            vm.loadFxVariances()
        }, { error ->
            edit { copy(signOff = signOff?.copy(submitting = false)) }
            vm.report(error)
        })
    }
}
