package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.ActiveDeal
import com.zillit.desktop.feature.payroll.domain.DealRates
import com.zillit.desktop.feature.payroll.domain.EstimateInput
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ReportDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.coroutines.Job

/**
 * Production Report Payroll — the web's `ProductionReportPayrollModule`.
 *
 * ## The roster is the crew WITH A DEAL, not the crew with a timecard
 *
 * That is the whole point of the board: a crew member who has not filled a
 * card in yet still appears, and the producer can ask what they would be paid
 * if their week matched the unit's production report.
 *
 * ## Every fill is local and nothing is saved
 *
 * A filled day is priced by the production's own pay engine and kept in state.
 * It overlays only the BLANK days of a real timecard — a day the crew member
 * actually entered is never overwritten — and reselecting or paging the week
 * restores the untouched document.
 *
 * ## One engine at a time
 *
 * The engine is loaded per agreement and made active, so a fill in flight
 * blocks every action that would point it somewhere else. Without that, a
 * whole-week fill finishes its remaining days against another crew member's
 * agreement, and the money is quietly wrong.
 */
internal class ProductionReportActions(private val vm: PayrollViewModel) {

    private val state: ProductionReportState get() = vm.ui.estimate
    private var loadJob: Job? = null
    private var selectionJob: Job? = null
    private var fillJob: Job? = null
    private val fill = ProductionReportFill(vm)

    fun onEvent(event: ProductionReportEvent) {
        when (event) {
            is ProductionReportEvent.ShiftWeek -> shiftWeek(event.steps)
            ProductionReportEvent.CurrentWeek -> openWeek(vm.ui.currentWeek)
            is ProductionReportEvent.Select -> select(event.userId)
            ProductionReportEvent.ToggleAutoFill -> toggleAutoFill()
            is ProductionReportEvent.FillDay -> fillDay(event.index)
            is ProductionReportEvent.ClearDay -> clearDay(event.index)
            ProductionReportEvent.FillWeek -> fillWeek(announceOnlySuccess = false)
            ProductionReportEvent.ClearWeek -> clearWeek()
        }
    }

    fun ensureLoaded() {
        if (state.weekStarting != null) return
        openWeek(vm.ui.currentWeek)
    }

    fun reload(silent: Boolean) {
        state.weekStarting?.let { load(it, silent) }
    }

    private fun shiftWeek(steps: Int) {
        val week = state.weekStarting ?: return
        if (state.fillBusy) return
        if (steps > 0 && week >= vm.ui.currentWeek) return
        openWeek(PayPeriod.shift(week, steps, vm.ui.metadata.payPeriodStartDay))
    }

    /**
     * Estimates are week-scoped, so a new week starts from its own report —
     * and the auto-fill bookkeeping forgets who it attempted. The MODE itself
     * survives: it is a way of browsing, not week data.
     */
    private fun openWeek(week: Long) {
        edit { copy(weekStarting = week, filled = emptyMap(), attempted = emptySet(), existing = emptyMap()) }
        load(week, silent = false)
    }

    /** The roster, and the week's timecards so a crew member with one shows it. */
    private fun load(week: Long, silent: Boolean) {
        loadJob?.cancel()
        val ui = vm.ui
        val roster = vm.ui.people.values.filter { it.hasDeal }
            .sortedWith(compareBy({ ui.departmentOf(it.userId) }, { ui.nameOf(it.userId) }))
        if (!silent) edit { copy(loading = true, error = null) }
        edit { copy(crew = roster, selectedUserId = selectedUserId ?: roster.firstOrNull()?.userId) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.crew(week)) {
                is ZillitResult.Success -> {
                    edit { copy(loading = false, error = null, existing = result.data.associateBy { it.userId }) }
                }

                is ZillitResult.Failure ->
                    edit {
                        if (silent) copy(loading = false) else copy(loading = false, error = result.error)
                    }
            }
            openSelection()
        }
    }

    private fun select(userId: String) {
        if (state.fillBusy) return
        if (state.crew.none { it.userId == userId }) return
        edit { copy(selectedUserId = userId, timecard = null, deal = null) }
        openSelection()
    }

    /**
     * The selected crew member's source: their real timecard when the week has
     * one, and their deal whenever the week is still fillable. A settled
     * timecard — locked, paid, unpaid or posted — is not estimated over, so
     * its deal is never fetched.
     */
    private fun openSelection() {
        selectionJob?.cancel()
        val userId = state.selectedUserId ?: return
        vm.resolveHolidayPay(userId)
        selectionJob = vm.launchWork {
            val slim = state.existing[userId]
            var status = slim?.status ?: TimecardStatus.Unknown
            if (slim != null) {
                edit { copy(timecardLoading = true) }
                val timecard = vm.repository.timecard(slim.id).getOrNull()
                if (state.selectedUserId != userId) return@launchWork
                edit { copy(timecard = timecard, timecardLoading = false) }
                status = timecard?.status ?: status
            }
            if (status.isSettled) return@launchWork
            loadDeal(userId)
            autoFillIfArmed(userId)
        }
    }

    private suspend fun loadDeal(userId: String) {
        val deals = vm.deals ?: return
        edit { copy(dealLoading = true) }
        val deal = deals.activeDeal(userId).getOrNull()
        val rates = deal?.let { priced(it) }
        if (state.selectedUserId != userId) return
        edit { copy(deal = rates, dealLoading = false) }
    }

    /** The deal, run through the engine. Null when there is no engine or no rates. */
    private suspend fun priced(deal: ActiveDeal): DealRates? = vm.estimator?.load(deal)?.getOrNull()

    private fun toggleAutoFill() {
        if (state.fillBusy) return
        edit { copy(autoFill = !autoFill) }
        state.selectedUserId?.let(::autoFillIfArmed)
    }

    /**
     * Auto-fill runs once per crew member per week, whatever the outcome —
     * marked attempted either way, so a crew member with no deal or no report
     * is not retried on every revisit.
     */
    private fun autoFillIfArmed(userId: String) {
        val current = state
        if (!current.autoFill || current.deal == null) return
        if (userId in current.attempted) return
        edit { copy(attempted = attempted + userId) }
        fillWeek(announceOnlySuccess = true)
    }

    /**
     * A whole week. Nothing happens at all when there is no deal or no engine
     * to price with — quietly under auto-fill, which runs on every crew member
     * the producer opens and would otherwise toast on each of them.
     */
    private fun fillWeek(announceOnlySuccess: Boolean) {
        if (state.fillBusy) return
        val target = target() ?: return noDeal(announceOnlySuccess)
        fillJob?.cancel()
        fillJob = vm.launchWork {
            edit { copy(fillingAll = true) }
            fill.week(target, announceOnlySuccess)
            edit { copy(fillingAll = false) }
        }
    }

    /** One day of the open week. */
    private fun fillDay(index: Int) {
        if (state.fillBusy) return
        val target = target() ?: return noDeal(announceOnlySuccess = false)
        fillJob?.cancel()
        fillJob = vm.launchWork {
            edit { copy(fillingDate = target.week + index * PayPeriod.DAY_MILLIS) }
            val refusal = fill.day(target, index)
            edit { copy(fillingDate = null) }
            refusal?.let(vm::fail)
        }
    }

    /**
     * Everything a fill needs, or nothing. Resolved in one place so the two
     * entry points cannot drift on what counts as ready.
     */
    private fun target(): FillTarget? = vm.estimator?.let { engine ->
        FillTarget(
            rates = state.deal ?: return@let null,
            engine = engine,
            week = state.weekStarting ?: return@let null,
            userId = state.selectedUserId ?: return@let null,
        )
    }

    private fun clearDay(index: Int) {
        val userId = state.selectedUserId ?: return
        if (state.filled[userId] == null) return
        val days = state.selectedDays.toMutableList().also { it[index] = null }
        edit { copy(filled = filled + (userId to days)) }
    }

    private fun clearWeek() {
        val userId = state.selectedUserId ?: return
        edit { copy(filled = filled - userId) }
    }

    private fun noDeal(announceOnlySuccess: Boolean) {
        edit { copy(fillingAll = false, fillingDate = null) }
        if (!announceOnlySuccess) vm.fail(str(S.desktop_payroll_no_active_deal))
    }

    private fun edit(reducer: ProductionReportState.() -> ProductionReportState) =
        vm.update { copy(estimate = estimate.reducer()) }

}

/**
 * A report day as the estimate states it. The unit's crew call and unit wrap
 * are taken as BOTH the worked window and the scheduled reference, which is
 * what "what would they be paid if their week matched the unit's" means.
 *
 * Null when the day carries no usable times and is not a flat day.
 */
internal fun ReportDay.toInput(dateMillis: Long, primaryDayType: String): EstimateInput? {
    if (isFlatDay) {
        return EstimateInput(
            dateMillis = dateMillis,
            dayType = EstimateInput.FLAT,
            call = null,
            timeOut = null,
            unitCall = null,
            unitWrap = null,
        )
    }
    val call = crewCall?.takeIf { it.isNotBlank() } ?: return null
    val wrap = unitWrap?.takeIf { it.isNotBlank() } ?: return null
    return EstimateInput(
        dateMillis = dateMillis,
        dayType = dayType?.takeIf { it.isNotBlank() } ?: primaryDayType,
        call = call,
        timeOut = wrap,
        unitCall = call,
        unitWrap = wrap,
    )
}
