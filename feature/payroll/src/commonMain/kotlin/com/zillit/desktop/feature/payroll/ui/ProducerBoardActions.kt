package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollCrewRow
import kotlinx.coroutines.Job

/**
 * Producer Board Payroll Status — the web's `ProducerBoardModule`.
 *
 * ## The week, then one document
 *
 * The board lists a whole unit's week from the slim projection
 * (`/weekly/{ws}/crew`) and fetches the full timecard only for the row the
 * producer opens. That is what makes paging weeks cheap on a large
 * production, and it is why the right-hand panel loads on its own.
 *
 * ## It opens on the CURRENT period, always
 *
 * Deliberately: the web reads no week out of the route here, so entering the
 * board mid-month lands on the period in progress and the navigator walks
 * back from there.
 *
 * ## Nothing here writes
 *
 * The board's override / approve / submit actions were removed when the
 * producer flow was respecified — "the producer reviews and adds claims;
 * approval lives elsewhere in the chain" (`api/payroll/producer-board.js`).
 */
internal class ProducerBoardActions(private val vm: PayrollViewModel) {

    private val state: ProducerBoardState get() = vm.ui.producer
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    fun onEvent(event: ProducerBoardEvent) {
        when (event) {
            is ProducerBoardEvent.ShiftWeek -> shiftWeek(event.steps)
            ProducerBoardEvent.CurrentWeek -> openWeek(vm.ui.currentWeek)
            is ProducerBoardEvent.Select -> select(event.timecardId)
        }
    }

    /** The current period — never a Monday default, which is why this waits on the metadata. */
    fun ensureLoaded() {
        if (state.weekStarting != null) return
        openWeek(vm.ui.currentWeek)
    }

    fun reload(silent: Boolean) {
        state.weekStarting?.let { load(it, silent) }
    }

    /** The board never peers into the future: there are no timecards there to read. */
    private fun shiftWeek(steps: Int) {
        val week = state.weekStarting ?: return
        if (steps > 0 && week >= vm.ui.currentWeek) return
        openWeek(PayPeriod.shift(week, steps, vm.ui.metadata.payPeriodStartDay))
    }

    private fun openWeek(week: Long) {
        edit { copy(weekStarting = week, crew = emptyList()) }
        load(week, silent = false)
    }

    /**
     * Reads the week's crew. A selection that survives into the new week stays
     * selected; otherwise the first row opens, as the web's does. A refresh
     * driven by the socket is silent — skeleton rows flashing over a list the
     * producer is reading is the thing the web guards against explicitly.
     */
    private fun load(week: Long, silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true, error = null) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.crew(week)) {
                is ZillitResult.Success -> {
                    val rows = sorted(result.data)
                    val keep = state.selectedId?.takeIf { id -> rows.any { it.id == id } }
                    val open = keep ?: rows.firstOrNull()?.id
                    edit { copy(loading = false, error = null, crew = rows, selectedId = open) }
                    when {
                        open == null -> clearDetail()
                        open != keep || !silent -> loadDetail(open)
                        else -> Unit
                    }
                }

                is ZillitResult.Failure -> {
                    edit {
                        if (silent) {
                            copy(loading = false)
                        } else {
                            copy(loading = false, error = result.error, crew = emptyList(), selectedId = null)
                        }
                    }
                    if (!silent) clearDetail()
                }
            }
        }
    }

    /** Department, then name — the order the sidebar's groups are drawn in. */
    private fun sorted(rows: List<PayrollCrewRow>): List<PayrollCrewRow> {
        val ui = vm.ui
        return rows.sortedWith(compareBy({ ui.departmentOf(it.userId) }, { ui.nameOf(it.userId) }))
    }

    private fun select(timecardId: String) {
        if (state.crew.none { it.id == timecardId }) return
        edit { copy(selectedId = timecardId) }
        loadDetail(timecardId)
    }

    private fun loadDetail(timecardId: String) {
        detailJob?.cancel()
        state.crew.firstOrNull { it.id == timecardId }?.let { vm.resolveHolidayPay(it.userId) }
        edit { copy(timecardLoading = true) }
        detailJob = vm.launchWork {
            val timecard = vm.repository.timecard(timecardId).getOrNull()
            // A late answer for a row the producer has since moved off is
            // dropped rather than drawn over the row they are now reading.
            if (state.selectedId != timecardId) return@launchWork
            edit { copy(timecard = timecard, timecardLoading = false) }
        }
    }

    private fun clearDetail() {
        detailJob?.cancel()
        edit { copy(timecard = null, timecardLoading = false) }
    }

    private fun edit(reducer: ProducerBoardState.() -> ProducerBoardState) =
        vm.update { copy(producer = producer.reducer()) }
}
