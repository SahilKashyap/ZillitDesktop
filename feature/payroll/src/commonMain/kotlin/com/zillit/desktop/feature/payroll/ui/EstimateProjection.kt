package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.feature.payroll.domain.EstimatedDay
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus

/**
 * What Production Report Payroll draws — the web's `displayTimecard`.
 *
 * With no real timecard the week IS the estimate. With one, each real day
 * stands and the estimate overlays only the BLANK days, its pay added to the
 * week's totals. Ephemeral either way: nothing here is saved, and reselecting
 * or paging the week restores the untouched document.
 */
internal fun PayrollUiState.estimateTimecard(): PayrollTimecard? {
    val userId = estimate.selectedUserId ?: return null
    val week = estimate.weekStarting ?: return null
    val filled = estimate.selectedDays
    val real = estimate.timecard ?: return synthetic(userId, week, filled)
    // A real timecard's days are keyed off ITS week, never the module's, so a
    // divergence between the two cannot drop real rows.
    val base = real.weekStarting ?: week
    val byDate = real.days.associateBy { it.date }
    var addedBasic = 0.0
    var addedOt = 0.0
    var addedGross = 0.0
    var addedMinutes = 0
    val days = (0 until PayPeriod.DAYS_IN_WEEK).map { index ->
        val date = base + index * PayPeriod.DAY_MILLIS
        val realDay = byDate[date]
        if (!realDay?.dayType.isNullOrBlank()) return@map requireNotNull(realDay)
        val day = filled.getOrNull(index) ?: return@map realDay ?: TimecardDay(date = date, dayType = null)
        addedBasic += day.calc.basicPay
        addedOt += day.calc.otPay
        addedGross += day.calc.dayGross
        addedMinutes += day.calc.workedMinutes
        day.toTimecardDay(date)
    }
    return real.copy(
        days = days,
        basicPay = real.basicPay + addedBasic,
        overtimePay = real.overtimePay + addedOt,
        // The backend's own totals describe the SAVED week, so an overlay must
        // drop them — left in place they would print the real gross beside
        // estimated days and read as pay that is already owed.
        totalOt = real.totalOt?.plus(addedOt),
        totalGross = real.totalGross?.plus(addedGross),
        totalHours = (real.totalHours ?: 0.0) + addedMinutes.toDouble() / MINUTES_PER_HOUR,
    )
}

/** A week that exists only as an estimate — no timecard was ever created. */
private fun synthetic(userId: String, week: Long, filled: List<EstimatedDay?>): PayrollTimecard {
    val minutes = filled.sumOf { it?.calc?.workedMinutes ?: 0 }
    return PayrollTimecard(
        id = "$ESTIMATE_ID_PREFIX$userId:$week",
        userId = userId,
        // No wire status: this week exists only as an estimate, and the rail
        // and hero say so in their own words rather than borrowing one.
        status = TimecardStatus.Unknown,
        weekStarting = week,
        basicPay = filled.sumOf { it?.calc?.basicPay ?: 0.0 },
        overtimePay = filled.sumOf { it?.calc?.otPay ?: 0.0 },
        totalGross = filled.sumOf { it?.calc?.dayGross ?: 0.0 },
        totalHours = minutes.toDouble() / MINUTES_PER_HOUR,
        days = filled.mapIndexed { index, day ->
            val date = week + index * PayPeriod.DAY_MILLIS
            day?.toTimecardDay(date) ?: TimecardDay(date = date, dayType = null)
        },
    )
}

/** An estimated day in the shape the read-only week draws. */
private fun EstimatedDay.toTimecardDay(date: Long) = TimecardDay(
    date = date,
    dayType = input.dayType,
    callTime = input.unitCall.atUtc(date),
    wrapTime = input.unitWrap.atUtc(date),
    loginTime = input.call.atUtc(date),
    logoutTime = input.timeOut.atUtc(date),
    minutesWorked = calc.workedMinutes,
    rates = calc.lines,
)

/**
 * `HH:mm` on a day, as UTC wall clock — the zone every stored timecard time is
 * written in, so an estimate renders the same clock a saved week would.
 */
private fun String?.atUtc(date: Long): Long? {
    val parts = this?.split(':') ?: return null
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return date + hours * HOUR_MILLIS + minutes * MINUTE_MILLIS
}

/** Whether the selected crew member's week may still be filled from the report. */
internal val PayrollUiState.canFillSelected: Boolean
    get() {
        if (estimate.selectedUserId == null) return false
        val status = estimate.timecard?.status ?: estimate.selectedExisting?.status ?: return true
        return !status.isSettled
    }

/** Whether that day is blank on the real timecard — the only days an estimate overlays. */
internal fun PayrollUiState.blankOnSelected(index: Int): Boolean {
    val real = estimate.timecard ?: return true
    val base = real.weekStarting ?: estimate.weekStarting ?: return true
    val day = real.days.firstOrNull { it.date == base + index * PayPeriod.DAY_MILLIS }
    return day?.dayType.isNullOrBlank()
}

private const val ESTIMATE_ID_PREFIX = "estimate:"
private const val MINUTES_PER_HOUR = 60.0
private const val HOUR_MILLIS = 3_600_000L
private const val MINUTE_MILLIS = 60_000L
