package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.coroutines.Job

/**
 * Payroll Processing's behaviour — the web's `PayrollGridModule`.
 *
 * The queue is the server's current processing week (no week in the path);
 * Outstanding is every crew member's unsettled weeks, read once and kept.
 * Paying from the table is the approver's; paying a locked week from the
 * drawer is anyone's — the web's two rules, kept apart on purpose.
 */
internal class ProcessingActions(private val vm: PayrollViewModel) {

    private val state: ProcessingState get() = vm.ui.processing
    private var loadJob: Job? = null
    private var outstandingJob: Job? = null
    private var drawerJob: Job? = null
    private var loaded = false

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: ProcessingEvent) {
        when (event) {
            is ProcessingEvent.View -> view(event.view)
            is ProcessingEvent.Nav -> edit { copy(nav = event.nav) }
            is ProcessingEvent.Department -> edit { copy(department = event.department) }
            is ProcessingEvent.Search -> edit { copy(search = event.query) }
            is ProcessingEvent.Day -> if (event.index in 0..todayIndex()) edit { copy(day = event.index) }
            ProcessingEvent.Refresh -> reload(silent = false)
            is ProcessingEvent.MarkPaid -> transition(event.timecardId, pay = true, fromDrawer = false)
            is ProcessingEvent.MarkUnpaid -> transition(event.timecardId, pay = false, fromDrawer = false)
            is ProcessingEvent.DrawerMarkPaid -> transition(event.timecardId, pay = true, fromDrawer = true)
            is ProcessingEvent.OpenDrawer -> openDrawer(event.timecardId)
            is ProcessingEvent.OpenOutstanding -> openOutstanding(event.userId)
            is ProcessingEvent.Export -> edit { copy(exportOpen = event.open) }
            is ProcessingEvent.ExportScope -> export(event.outstanding)
        }
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        reload(silent = false)
    }

    fun reload(silent: Boolean) {
        load(silent)
        if (state.view == ProcessingView.Outstanding || state.outstandingLoaded) loadOutstanding(silent)
    }

    private fun view(view: ProcessingView) {
        // Week to Date is pinned to today; Day View keeps the day it was on.
        edit { copy(view = view, day = if (view == ProcessingView.WeekToDate) todayIndex() else day) }
        if (view == ProcessingView.Outstanding && !state.outstandingLoaded) loadOutstanding(silent = false)
    }

    /**
     * The processing week the server picks. The week it names wins; the
     * current period stands in only when it names none.
     */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true, error = null) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.processingQueue()) {
                is ZillitResult.Success -> {
                    val week = result.data.weekStarting ?: vm.ui.currentWeek
                    edit {
                        copy(
                            loading = false,
                            error = null,
                            weekStarting = week,
                            timecards = result.data.timecards,
                            day = if (weekStarting == null) todayIndex(week) else day,
                        )
                    }
                }
                is ZillitResult.Failure -> edit {
                    if (silent) copy(loading = false) else copy(loading = false, error = result.error)
                }
            }
        }
    }

    private fun loadOutstanding(silent: Boolean) {
        outstandingJob?.cancel()
        if (!silent) edit { copy(outstandingLoading = true) }
        outstandingJob = vm.launchWork {
            when (val result = vm.repository.outstanding()) {
                is ZillitResult.Success ->
                    edit { copy(outstandingLoading = false, outstandingLoaded = true, outstanding = result.data) }
                is ZillitResult.Failure -> {
                    edit { copy(outstandingLoading = false) }
                    if (!silent) vm.fail(result.error.localised())
                }
            }
        }
    }

    /**
     * Mark Paid / Mark Unpaid. From the table both are the final approver's,
     * on a locked or unpaid week and on a paid one (`PayrollGridModule.jsx`
     * 1972-1974). From the drawer a locked week can be paid by anyone and an
     * unpaid one by the approver — the web's drawer footer. No confirm: the web
     * has none, and Mark Unpaid undoes it.
     */
    private fun transition(id: String, pay: Boolean, fromDrawer: Boolean) {
        val timecard = state.timecards.firstOrNull { it.id == id } ?: state.drawer?.takeIf { it.id == id } ?: return
        if (!mayTransition(timecard.status, pay, fromDrawer) || state.busyRowId != null) return
        edit { copy(busyRowId = id) }
        vm.launchWork {
            val result = if (pay) vm.repository.markPaid(id) else vm.repository.markUnpaid(id)
            when (result) {
                is ZillitResult.Success -> {
                    // The web updates the row in place, then the socket's reload confirms it.
                    moved(id, if (pay) TimecardStatus.Paid else TimecardStatus.Unpaid)
                    vm.notify(result.data?.localisedMessage() ?: str(S.done_text))
                    reload(silent = true)
                }
                is ZillitResult.Failure -> {
                    edit { copy(busyRowId = null) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    /** The table's rule and the drawer's — see [transition]. */
    private fun mayTransition(status: TimecardStatus, pay: Boolean, fromDrawer: Boolean): Boolean {
        val approver = vm.ui.viewer.isFinalApprover
        return when {
            !pay -> approver && status == TimecardStatus.Paid
            fromDrawer -> status == TimecardStatus.Locked || (status == TimecardStatus.Unpaid && approver)
            else -> approver && status.isPayable
        }
    }

    private fun moved(id: String, status: TimecardStatus) = edit {
        copy(
            busyRowId = null,
            timecards = timecards.map { if (it.id == id) it.copy(status = status) else it },
            drawer = drawer?.let { if (it.id == id) it.copy(status = status) else it },
        )
    }

    private fun openDrawer(id: String?) {
        drawerJob?.cancel()
        edit { copy(drawerId = id, drawer = null, drawerLoading = id != null) }
        if (id == null) return
        drawerJob = vm.launchWork {
            val timecard = vm.repository.timecard(id)
            if (state.drawerId == id) edit {
                copy(
                    drawerLoading = false,
                    drawer = (timecard as? ZillitResult.Success)?.data,
                )
            }
            if (timecard is ZillitResult.Failure) vm.fail(timecard.error.localised())
        }
    }

    /** Every week one crew member has a timecard for — the web's `OutstandingDetailModal`. */
    private fun openOutstanding(userId: String?) {
        if (userId == null) {
            edit { copy(detail = null) }
            return
        }
        edit { copy(detail = OutstandingDetail(userId)) }
        vm.launchWork {
            val result = vm.repository.outstandingFor(userId)
            edit {
                val open = detail?.takeIf { it.userId == userId } ?: return@edit this
                copy(
                    detail = when (result) {
                        is ZillitResult.Success -> open.copy(loading = false, weeks = result.data)
                        is ZillitResult.Failure -> open.copy(loading = false, error = result.error)
                    },
                )
            }
        }
    }

    /**
     * The workbook, generated by the server. The web's "Selected Week" scope
     * only appears off the current week, which this screen never is, so the
     * two scopes offered are the outstanding weeks and the current one.
     */
    private fun export(outstanding: Boolean) {
        val documents = vm.documents ?: return
        val files = vm.files ?: return
        if (state.exporting || !vm.ui.viewer.seesAccountantViews) return
        val week = vm.ui.currentWeek
        edit { copy(exportOpen = false, exporting = true) }
        vm.launchWork {
            val file = if (outstanding) documents.outstandingWorkbook() else documents.weekWorkbook(week)
            val name = if (outstanding) "payroll-outstanding.xlsx" else "payroll-$week.xlsx"
            val saved = when (file) {
                is ZillitResult.Success -> files.saveAndOpen(name, file.data)
                is ZillitResult.Failure -> file
            }
            edit { copy(exporting = false) }
            if (saved is ZillitResult.Failure) {
                vm.fail(saved.error.localised())
            } else {
                vm.notify(str(S.desktop_cr_file_generated))
            }
        }
    }

    /** Today's position in the week, 0-6 — the default day and the last one a day picker allows. */
    private fun todayIndex(week: Long? = state.weekStarting): Int {
        val start = week ?: return 0
        return ((vm.ui.now - start) / PayPeriod.DAY_MILLIS).toInt().coerceIn(0, LAST_DAY)
    }

    private fun edit(reducer: ProcessingState.() -> ProcessingState) =
        vm.update { copy(processing = processing.reducer()) }

    private companion object {
        const val LAST_DAY = 6
    }
}

/** The status a Processing table row is judged by — the web reads the timecard's own. */
internal val PayrollTimecard.processingBucket: ProcessingNav?
    get() = when {
        status == TimecardStatus.AwaitingApproval || status == TimecardStatus.Submitted ||
            status == TimecardStatus.Pending -> ProcessingNav.Pending
        status == TimecardStatus.Approved || status == TimecardStatus.Paid || status == TimecardStatus.Unpaid ->
            ProcessingNav.Approved
        else -> null
    }
