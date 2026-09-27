package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.DealRates
import com.zillit.desktop.feature.payroll.domain.EstimateInput
import com.zillit.desktop.feature.payroll.domain.EstimatedDay
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollEstimator
import com.zillit.desktop.feature.payroll.domain.ReportDay

/**
 * Filling a week from the production report — the arithmetic half of
 * Production Report Payroll, kept apart from the board's own behaviour.
 *
 * Nothing here is saved. A filled day is a statement about what the unit's
 * report WOULD pay this crew member, priced by the production's own engine and
 * held in state until the week or the selection changes.
 */
internal class ProductionReportFill(private val vm: PayrollViewModel) {

    private val state: ProductionReportState get() = vm.ui.estimate

    /**
     * The whole week, from one round trip for the report and a local pass for
     * the pay.
     *
     * Sequential on purpose: each day's turnaround rules read the day before
     * it, and only a day that was actually FILLED counts — a day with no
     * usable report breaks the chain exactly as an unfilled day does on the
     * web. It says what it did, and stays quiet when the board moved on
     * mid-fetch: a week the producer has already left is never written over.
     */
    suspend fun week(target: FillTarget, announceOnlySuccess: Boolean) {
        val week = target.week
        val userId = target.userId
        val report = vm.reports?.week(week, userId)?.getOrNull().orEmpty()
        val blanks = blankDays()
        val days = arrayOfNulls<EstimatedDay>(PayPeriod.DAYS_IN_WEEK)
        var previous: EstimateInput? = null
        var eligible = 0
        var skipped = 0
        for (index in 0 until PayPeriod.DAYS_IN_WEEK) {
            val day = if (blanks[index]) {
                eligible += 1
                price(index, report, target, previous)
            } else {
                // A day the crew member actually entered stands, and its
                // presence breaks the turnaround chain the same way a gap does.
                null
            }
            if (blanks[index] && day == null) skipped += 1
            days[index] = day
            previous = day?.input
        }
        if (state.weekStarting != week || state.selectedUserId != userId) return
        vm.update { copy(estimate = estimate.copy(filled = estimate.filled + (userId to days.toList()))) }
        announce(days.count { it != null }, eligible, skipped, announceOnlySuccess)
    }

    /**
     * One day, from its own report row. The message names which of the two
     * things went wrong — no report for the day, or a day the engine would not
     * price — because they are fixed in different places.
     */
    suspend fun day(target: FillTarget, index: Int): String? {
        val date = target.week + index * PayPeriod.DAY_MILLIS
        val row = vm.reports?.day(PayPeriod.isoDate(date), date, target.userId)?.getOrNull()
        val input = row?.toInput(date, target.rates.deal.primaryDayType)
            ?: return str(S.desktop_payroll_no_report_for_day)
        // Turnaround reads the previous day, and only a FILLED one counts.
        val previous = state.selectedDays.getOrNull(index - 1)?.input
        val calc = target.engine.calcDay(input, index, target.rates, previous).getOrNull()
        if (state.weekStarting != target.week || state.selectedUserId != target.userId) return null
        if (calc == null) return str(S.desktop_payroll_could_not_estimate_day)
        val days = state.selectedDays.toMutableList().also { it[index] = EstimatedDay(input, calc) }
        vm.update { copy(estimate = estimate.copy(filled = estimate.filled + (target.userId to days))) }
        return null
    }

    /** What a fill turned out to be, in the web's words. */
    private fun announce(filled: Int, eligible: Int, skipped: Int, onlySuccess: Boolean) {
        when {
            filled > 0 && skipped > 0 -> vm.notify(str(S.desktop_payroll_filled_some, filled, eligible, skipped))
            filled > 0 -> vm.notify(str(S.desktop_payroll_filled_all, filled))
            // Auto-fill runs on every crew member opened, so the nothing-to-do
            // outcomes stay quiet: a toast per sidebar click is noise.
            onlySuccess -> Unit
            eligible == 0 -> vm.notify(str(S.desktop_payroll_nothing_to_fill))
            else -> vm.fail(str(S.desktop_payroll_no_report_data))
        }
    }

    private suspend fun price(
        index: Int,
        report: Map<String, ReportDay>,
        target: FillTarget,
        previous: EstimateInput?,
    ): EstimatedDay? {
        val date = target.week + index * PayPeriod.DAY_MILLIS
        val input = report[PayPeriod.isoDate(date)]?.toInput(date, target.rates.deal.primaryDayType) ?: return null
        val calc = target.engine.calcDay(input, index, target.rates, previous).getOrNull() ?: return null
        return EstimatedDay(input, calc)
    }

    /**
     * Which of the seven days the estimate may overlay: a day the crew member
     * actually entered stands, whatever the report says. Indexed off the
     * TIMECARD's own week, never the board's, so the two diverging cannot drop
     * a real row.
     */
    private fun blankDays(): List<Boolean> {
        val timecard = state.timecard ?: return List(PayPeriod.DAYS_IN_WEEK) { true }
        val base = timecard.weekStarting ?: state.weekStarting ?: return List(PayPeriod.DAYS_IN_WEEK) { true }
        val byDate = timecard.days.associateBy { it.date }
        return (0 until PayPeriod.DAYS_IN_WEEK).map { index ->
            byDate[base + index * PayPeriod.DAY_MILLIS]?.dayType.isNullOrBlank()
        }
    }
}

/**
 * Everything a fill needs, resolved together. Either all four are in hand or
 * there is no fill to run — which is one question, and is asked once.
 */
internal data class FillTarget(
    val rates: DealRates,
    val engine: PayrollEstimator,
    val week: Long,
    val userId: String,
)
