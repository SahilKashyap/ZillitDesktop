package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import kotlinx.coroutines.Job

/**
 * Payroll History's behaviour — the web's `AccountantPayrollModule`.
 *
 * ## The queue is the week's paid timecards
 *
 * `/weekly/{ws}/paid` returns the paid rows, and the posted ones so the
 * accountant can see what already went. The week opens on the last completed
 * period, and the navigator walks back as far as it likes but never past the
 * current week.
 *
 * ## No posting here
 *
 * The web took History's Post, Post Selected and Post All Ready out on
 * 2026-07-09 (1f836cbe7): posting to the ledger happens in Payroll Run's
 * Journal Ledger, where each line carries its own code and date. This page
 * reads; it no longer writes to the ledger.
 */
internal class HistoryActions(private val vm: PayrollViewModel) {

    private val state: HistoryState get() = vm.ui.history
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: HistoryEvent) {
        when (event) {
            is HistoryEvent.ShiftWeek -> shiftWeek(event.steps)
            HistoryEvent.CurrentWeek -> openWeek(vm.ui.currentWeek)
            HistoryEvent.Refresh -> reload(silent = false)
            is HistoryEvent.Search -> edit { copy(search = event.query) }
            is HistoryEvent.Select -> select(event.timecardId)
            is HistoryEvent.Tab -> edit { copy(tab = event.tab) }
            HistoryEvent.DownloadPayslip -> downloadPayslip()
        }
    }

    /** Opens on the last completed period — the web's default — once the period boundary is known. */
    fun ensureLoaded() {
        if (state.weekStarting != null) return
        val ui = vm.ui
        openWeek(PayPeriod.shift(ui.currentWeek, -1, ui.metadata.payPeriodStartDay))
    }

    fun reload(silent: Boolean) {
        state.weekStarting?.let { load(it, silent) }
    }

    /** No future weeks: payroll cannot process timecards that do not exist yet. */
    private fun shiftWeek(steps: Int) {
        val week = state.weekStarting ?: return
        if (steps > 0 && week >= vm.ui.currentWeek) return
        openWeek(PayPeriod.shift(week, steps, vm.ui.metadata.payPeriodStartDay))
    }

    private fun openWeek(week: Long) {
        edit { copy(weekStarting = week, rows = emptyList()) }
        load(week, silent = false)
    }

    /**
     * Reads the week. The queue is sorted by department then name, so the row
     * selected by default is the first one the list draws; the open row stays
     * open across a refresh while it is still in the queue.
     */
    private fun load(week: Long, silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true, error = null) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.paidCrew(week)) {
                is ZillitResult.Success -> {
                    val ui = vm.ui
                    val rows = result.data.sortedWith(
                        compareBy<PayrollTimecard>({ ui.departmentOf(it.userId) }, { ui.nameOf(it.userId) }),
                    )
                    val keep = state.selectedId?.takeIf { id -> rows.any { it.id == id } }
                    edit {
                        copy(
                            loading = false,
                            error = null,
                            rows = rows,
                            selectedId = keep ?: rows.firstOrNull()?.id,
                        )
                    }
                    val open = keep ?: rows.firstOrNull()?.id
                    if (open != null && (open != keep || !silent)) loadDetail(open) else if (open == null) clearDetail()
                }

                is ZillitResult.Failure -> edit {
                    if (silent) copy(loading = false) else copy(
                        loading = false,
                        error = result.error,
                        rows = emptyList(),
                    )
                }
            }
        }
    }

    private fun select(id: String) {
        if (state.rows.none { it.id == id }) return
        edit { copy(selectedId = id) }
        loadDetail(id)
    }

    /**
     * The full timecard, then the crew member's deal: the slim queue carries
     * no days, history or claims, and the deal names the lines the week has
     * not coded itself. They fail apart — a crew member with no active deal
     * still has a breakdown.
     */
    private fun loadDetail(id: String) {
        detailJob?.cancel()
        edit { copy(detailLoading = true, detail = null, deal = null) }
        detailJob = vm.launchWork {
            val timecard = vm.repository.timecard(id).getOrNull()
            val deal = timecard?.userId?.let { vm.repository.settings.activeDealCoding(it).getOrNull() }
            if (state.selectedId == id) edit { copy(detailLoading = false, detail = timecard, deal = deal) }
        }
    }

    private fun clearDetail() = edit { copy(detail = null, deal = null, detailLoading = false) }

    private fun downloadPayslip() {
        val detail = state.detail ?: return
        val week = detail.weekStarting
        val documents = vm.documents
        val files = vm.files
        if (week == null || documents == null || files == null) return
        if (!vm.ui.viewer.seesAccountantViews || state.payslipBusy) return
        edit { copy(payslipBusy = true) }
        vm.launchWork {
            val saved = when (val pdf = documents.payslip(week, detail.userId)) {
                is ZillitResult.Success -> files.saveAndOpen("payslip_${fileStamp(vm.ui.now)}.pdf", pdf.data)
                is ZillitResult.Failure -> pdf
            }
            edit { copy(payslipBusy = false) }
            if (saved is ZillitResult.Failure) vm.fail(saved.error.localised())
        }
    }

    private fun edit(reducer: HistoryState.() -> HistoryState) = vm.update { copy(history = history.reducer()) }
}

/**
 * The queue grouped by department, alphabetically, after the search — the
 * web's `deptGroups`: the search matches name, designation or department.
 */
internal fun PayrollUiState.historyGroups(): List<Pair<String, List<PayrollTimecard>>> {
    val query = history.search.trim().lowercase()
    return history.rows
        .filter { row ->
            query.isEmpty() ||
                "${nameOf(row.userId)} ${roleOf(row.userId)} ${departmentOf(row.userId)}".lowercase().contains(query)
        }
        .groupBy { departmentOf(it.userId) }
        .toList()
        .sortedBy { it.first }
}

/** `2026-05-04_1430` — the web's `exportTs`, read in UTC. */
internal fun fileStamp(now: Long): String {
    val minutes = (now.mod(PayPeriod.DAY_MILLIS)) / MILLIS_PER_MINUTE
    val hh = (minutes / MINUTES_PER_HOUR).toString().padStart(2, '0')
    val mm = (minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')
    return "${PayPeriod.isoDate(now)}_$hh$mm"
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
