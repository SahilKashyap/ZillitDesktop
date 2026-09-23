package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.BatchOutcome
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.JournalCoding
import com.zillit.desktop.feature.payroll.domain.OverallStatus
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.RunRow
import com.zillit.desktop.feature.payroll.domain.RunSelection
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.rowActionAllowed
import kotlinx.coroutines.Job

/**
 * Payroll Run's behaviour — the web's `PayrollRunModule`.
 *
 * ## The approval tail of the lifecycle
 *
 * The accountant approver final-approves an approved week; the payroll
 * accountant locks it; somebody marks the locked week paid. Who may do which
 * is [RunSelection.approvalFor] and [rowActionAllowed] — the web's matrices —
 * and each is re-checked here before anything is sent. Mark Paid is not
 * role-gated on the web's toolbar; it is gated here on the screen's own
 * audience, the accountants, because that is who the Run is offered to.
 */
internal class RunActions(private val vm: PayrollViewModel) {

    private val state: RunState get() = vm.ui.run
    private var loadJob: Job? = null
    private var drawerJob: Job? = null

    /** Set by the journal: a successful lock-then-post confirm re-opens the post. */
    var afterConfirm: (() -> Unit)? = null

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: RunEvent) {
        when (event) {
            is RunEvent.ShiftWeek -> shiftWeek(event.steps)
            RunEvent.CurrentWeek -> openWeek(vm.ui.currentWeek)
            RunEvent.Refresh -> reload(silent = false)
            is RunEvent.Search -> edit { copy(search = event.query) }
            is RunEvent.Status -> edit { copy(status = event.filter) }
            // Picking the active one again clears it — the web's toggle.
            is RunEvent.Department -> edit { copy(department = event.department.takeIf { it != department }) }
            is RunEvent.EmploymentFilter -> edit { copy(employment = event.employment.takeIf { it != employment }) }
            is RunEvent.ToggleRow -> edit {
                val id = event.timecardId
                copy(selected = if (id in selected) selected - id else selected + id)
            }
            RunEvent.ToggleAllShown -> toggleAllShown()
            RunEvent.ClearSelection -> edit { copy(selected = emptySet()) }
            is RunEvent.Ask -> ask(event.action)
            RunEvent.Confirm -> confirm()
            RunEvent.DismissConfirm -> edit { copy(confirm = null) }
            is RunEvent.Row -> row(event.timecardId, event.action)
            is RunEvent.OpenDrawer -> openDrawer(event.timecardId)
            is RunEvent.Unlock -> unlock(event.timecardId)
            is RunEvent.Export -> export(event.format)
            RunEvent.GoToProcessing -> vm.emit(PayrollEffect.Navigate(PayrollDestination.Processing.path))
            is RunEvent.Journal -> edit { copy(journalOpen = event.open) }
        }
    }

    /** Opens on last week, as the web does, once the period boundary is known. */
    fun ensureLoaded() {
        if (state.weekStarting != null) return
        val ui = vm.ui
        openWeek(PayPeriod.shift(ui.currentWeek, -1, ui.metadata.payPeriodStartDay))
    }

    fun reload(silent: Boolean) {
        state.weekStarting?.let { load(it, silent) }
    }

    private fun shiftWeek(steps: Int) {
        val week = state.weekStarting ?: return
        if (steps > 0 && week >= vm.ui.currentWeek) return
        openWeek(PayPeriod.shift(week, steps, vm.ui.metadata.payPeriodStartDay))
    }

    private fun openWeek(week: Long) {
        edit {
            copy(
                weekStarting = week,
                timecards = emptyList(),
                selected = emptySet(),
                confirm = null,
                drawerId = null,
                drawer = null,
                journal = JournalState(),
            )
        }
        load(week, silent = false)
    }

    /**
     * The week's full timecards, then their saved journal coding — keyed by
     * the timecards, because a week's queue can hold a late approval from
     * another week. The coding is advisory: a failed read leaves the ledger
     * uncoded rather than the grid empty. [next] runs once the week has
     * landed — the journal's "…& Post" retrying its post.
     */
    private fun load(week: Long, silent: Boolean, next: (() -> Unit)? = null) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true, error = null) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.runQueue(week)) {
                is ZillitResult.Success -> {
                    val timecards = result.data
                    val coding = vm.repository.journal.coding(timecards.map { it.id }, week).getOrNull()
                    val alive = timecards.map { it.id }.toSet()
                    edit {
                        copy(
                            loading = false,
                            error = null,
                            timecards = timecards,
                            selected = selected.filter { it in alive }.toSet(),
                            journal = journal.copy(coding = coding ?: JournalCoding()),
                        )
                    }
                }

                is ZillitResult.Failure -> edit {
                    if (silent) {
                        copy(loading = false)
                    } else {
                        copy(loading = false, error = result.error, timecards = emptyList())
                    }
                }
            }
            next?.invoke()
        }
    }

    private fun toggleAllShown() {
        val shown = vm.ui.runShownRows().map { it.id }
        edit { copy(selected = if (shown.isNotEmpty() && selected.containsAll(shown)) emptySet() else shown.toSet()) }
    }

    /** Opens the confirm for a toolbar action — only one this viewer's toolbar would offer. */
    private fun ask(action: RunAction) {
        val ui = vm.ui
        val selection = RunSelection.of(ui.runRows(), state.selected)
        val allowed = when (action) {
            RunAction.MarkPaid -> selection.offersMarkPaid && ui.viewer.seesAccountantViews
            else -> selection.approvalFor(ui.viewer)?.first == action
        }
        if (!allowed) return
        edit { copy(confirm = confirmFor(action, selection)) }
    }

    /**
     * The transition itself — the web's `performApprovalAction`: approve and
     * lock is two calls, the lock taking what the approval actually moved plus
     * the rows that were already ACCT Approved.
     */
    private fun confirm() {
        val confirm = state.confirm ?: return
        if (confirm.working) return
        if (!confirmStillAllowed(confirm)) {
            edit { copy(confirm = null) }
            vm.fail(str(S.desktop_payroll_no_rights))
            return
        }
        edit { copy(confirm = confirm.copy(working = true)) }
        vm.launchWork {
            val result = perform(confirm)
            when (result) {
                is ZillitResult.Success -> {
                    edit { copy(confirm = null, selected = if (confirm.runScope) selected else emptySet()) }
                    vm.notify(result.data?.localisedMessage() ?: str(S.done_text))
                    state.weekStarting?.let { load(it, silent = true, next = afterConfirm.takeIf { confirm.thenPost }) }
                }

                is ZillitResult.Failure -> {
                    edit { copy(confirm = confirm.copy(working = false)) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    private suspend fun perform(confirm: RunConfirm): ZillitResult<String?> {
        val repository = vm.repository
        return when (confirm.action) {
            RunAction.FinalApprove -> repository.finalApprove(confirm.ids).message()
            RunAction.Lock -> repository.lock(confirm.ids).message()
            RunAction.MarkPaid -> repository.markPaidBatch(confirm.ids).message()
            RunAction.FinalApproveAndLock -> {
                val approved = repository.finalApprove(confirm.ids)
                if (approved !is ZillitResult.Success) return approved.message()
                val toLock = (approved.data.marked.ifEmpty { confirm.ids } + confirm.alsoLockIds).distinct()
                repository.lock(toLock).message()
            }
        }
    }

    /** The selection, or the run's blockers, still holds the rows the confirm named, and the role still allows it. */
    private fun confirmStillAllowed(confirm: RunConfirm): Boolean {
        val viewer = vm.ui.viewer
        return when (confirm.action) {
            RunAction.MarkPaid -> viewer.seesAccountantViews
            RunAction.FinalApprove -> viewer.isFinalApprover
            RunAction.FinalApproveAndLock -> viewer.isFinalApprover && viewer.isPayrollAccountant
            RunAction.Lock -> viewer.isPayrollAccountant
        }
    }

    /**
     * A row's own button, in the web's first-match order. The same order
     * decides whether the action is allowed, so a row event for anything the
     * row would not show is dropped.
     */
    private fun row(id: String, action: RowAction) {
        val ui = vm.ui
        val timecard = state.timecards.firstOrNull { it.id == id } ?: return
        if (action == RowAction.View) {
            openDrawer(id)
            return
        }
        if (!rowActionAllowed(action, timecard.status, ui.viewer, ui.canOverride) || state.busyRowId != null) return
        if (action == RowAction.Override) {
            vm.onEvent(PayrollEvent.AskOverride(timecard))
            return
        }
        edit { copy(busyRowId = id) }
        vm.launchWork {
            val result = performRow(id, action)
            // The drawer closes after the transitions that move the row on — the web's.
            val closeDrawer = result is ZillitResult.Success && action != RowAction.MarkUnpaid
            edit {
                copy(
                    busyRowId = null,
                    drawerId = if (closeDrawer) null else drawerId,
                    drawer = if (closeDrawer) null else drawer,
                )
            }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notify(result.data?.localisedMessage() ?: str(S.done_text))
                    reload(silent = true)
                }
                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    /** One row's transition; approve-and-lock locks what the approval actually moved. */
    private suspend fun performRow(id: String, action: RowAction): ZillitResult<String?> {
        val repository = vm.repository
        return when (action) {
            RowAction.FinalApprove -> repository.finalApprove(listOf(id)).message()
            RowAction.FinalApproveAndLock -> when (val approved = repository.finalApprove(listOf(id))) {
                is ZillitResult.Success -> repository.lock(approved.data.marked.ifEmpty { listOf(id) }).message()
                is ZillitResult.Failure -> approved
            }
            RowAction.Lock -> repository.lock(listOf(id)).message()
            RowAction.MarkPaid -> repository.markPaid(id)
            RowAction.MarkUnpaid -> repository.markUnpaid(id)
            RowAction.Override, RowAction.View -> ZillitResult.Success(null)
        }
    }

    private fun openDrawer(id: String?) {
        drawerJob?.cancel()
        edit { copy(drawerId = id, drawer = null, drawerLoading = id != null) }
        if (id == null) return
        drawerJob = vm.launchWork {
            val timecard = vm.repository.timecard(id)
            if (state.drawerId == id) {
                edit { copy(drawerLoading = false, drawer = (timecard as? ZillitResult.Success)?.data) }
            }
            if (timecard is ZillitResult.Failure) vm.fail(timecard.error.localised())
        }
    }

    /** Unlock — the payroll accountant's, on a locked row, from the drawer. The drawer stays open. */
    private fun unlock(id: String) {
        val timecard = state.timecards.firstOrNull { it.id == id } ?: state.drawer?.takeIf { it.id == id } ?: return
        if (timecard.status != TimecardStatus.Locked || !vm.ui.viewer.isPayrollAccountant) return
        edit { copy(busyRowId = id) }
        vm.launchWork {
            val result = vm.repository.unlock(id)
            edit { copy(busyRowId = null) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notify(result.data?.localisedMessage() ?: str(S.done_text))
                    reload(silent = true)
                    if (state.drawerId == id) openDrawer(id)
                }
                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    /** Export Summary — the server renders it; a declined export names why. */
    private fun export(format: ExportFormat) {
        val week = state.weekStarting ?: return
        val documents = vm.documents ?: return
        val files = vm.files ?: return
        if (state.exporting || !vm.ui.viewer.seesAccountantViews) return
        edit { copy(exporting = true) }
        vm.launchWork {
            val saved = when (val file = documents.runSummary(week, format)) {
                is ZillitResult.Success ->
                    files.saveAndOpen("payroll-run-summary_${fileStamp(vm.ui.now)}.${format.wire}", file.data)
                is ZillitResult.Failure -> file
            }
            edit { copy(exporting = false) }
            if (saved is ZillitResult.Failure) vm.fail(saved.error.localised())
        }
    }

    /** Builds a confirm over the given selection — used by the journal's lock gate too. */
    fun confirmFor(action: RunAction, selection: RunSelection, runScope: Boolean = false, thenPost: Boolean = false) =
        RunConfirm(
            action = action,
            ids = when (action) {
                RunAction.FinalApprove, RunAction.FinalApproveAndLock -> selection.approvedIds
                RunAction.Lock -> selection.finalApprovedIds
                RunAction.MarkPaid -> selection.paidEligibleIds
            },
            alsoLockIds = if (action == RunAction.FinalApproveAndLock) selection.finalApprovedIds else emptyList(),
            selectedTotal = selection.total,
            runScope = runScope,
            thenPost = thenPost,
        )

    /** Opens a confirm the journal built. */
    fun present(confirm: RunConfirm) = edit { copy(confirm = confirm) }

    private fun edit(reducer: RunState.() -> RunState) = vm.update { copy(run = run.reducer()) }
}

private fun ZillitResult<BatchOutcome>.message(): ZillitResult<String?> = when (this) {
    is ZillitResult.Success -> ZillitResult.Success(data.message)
    is ZillitResult.Failure -> this
}

/**
 * The rows the grid shows: search on name or role, then the status chip,
 * department and employment filters, which narrow each other.
 */
internal fun PayrollUiState.runShownRows(): List<RunRow> {
    val filter = run
    return runRows().filter { row ->
        row.matches(filter.search, roleOf(row.timecard.userId)) &&
            when (filter.status) {
                RunStatusFilter.All -> true
                RunStatusFilter.Pending -> row.overall == OverallStatus.Pending
                RunStatusFilter.Approved -> row.overall == OverallStatus.Approved
            } &&
            (filter.department == null || row.department == filter.department) &&
            (filter.employment == null || row.employment == filter.employment)
    }
}
