package com.zillit.desktop.feature.payroll.domain

/**
 * One day of one crew member's week on the processing grid — the web's
 * `dayData[i]` (`payrollData.js` 506-622). Times are only known from the full
 * document; the processing projection carries money per day but no times.
 */
data class ProcessingDay(
    val dayType: String?,
    val myCall: Long? = null,
    val unitCall: Long? = null,
    val unitWrap: Long? = null,
    val release: Long? = null,
    val basic: Double = 0.0,
    val ots: Double = 0.0,
    val allowances: Double = 0.0,
) {
    val total: Double get() = basic + ots + allowances

    /** No day type, `REST` or `OFF`: nothing worked. */
    val isOff: Boolean
        get() = dayType.isNullOrBlank() || dayType.equals(TimecardDay.REST, ignoreCase = true) ||
            dayType.equals(OFF, ignoreCase = true)

    companion object {
        const val OFF = "OFF"
        val Off = ProcessingDay(dayType = OFF)
    }
}

/**
 * A crew member's week on the processing grid, whichever projection it came
 * in — placed by date from the week start, as the web places them.
 */
data class ProcessingRow(val timecard: PayrollTimecard, val weekStarting: Long) {

    val days: List<ProcessingDay> by lazy { buildDays() }

    val daysWorked: Int get() = days.count { !it.isOff }

    /** Day sums, falling back to the server's scalars when the days carry nothing. */
    val basicTotal: Double get() = days.sumOf { it.basic }.takeIf { it > 0 } ?: timecard.basicPay

    val otTotal: Double
        get() = days.sumOf { it.ots }.takeIf { it > 0 } ?: timecard.outstanding?.ots ?: timecard.overtimePay

    /** Per-day allowances and rentals only — weekly lines are in the total, not here (the web's column). */
    val allowanceTotal: Double
        get() = days.sumOf { it.allowances }.takeIf { it > 0 }
            ?: timecard.outstanding?.allowancesAndRentals
            ?: timecard.totalAllowances

    /** Total pay: net of deductions, weekly allowances, extras and claims included. */
    val totalPay: Double
        get() = timecard.outstanding?.let { figures ->
            PayrollTimecard.round2(
                timecard.basicPay + figures.ots + figures.allowancesAndRentals + timecard.claimsTotal -
                    timecard.deductionsTotal,
            )
        } ?: timecard.net

    private fun buildDays(): List<ProcessingDay> {
        val cells = MutableList(DAYS) { ProcessingDay.Off }
        timecard.daySummary.forEach { summary ->
            indexOf(summary.date)?.let { index ->
                cells[index] = ProcessingDay(
                    dayType = summary.dayType,
                    basic = summary.basic,
                    ots = summary.ots,
                    allowances = summary.allowancesRentals,
                )
            }
        }
        timecard.days.forEach { day ->
            val index = indexOf(day.date) ?: day.dayNumber?.minus(1)?.takeIf { it in 0 until DAYS } ?: return@forEach
            cells[index] = ProcessingDay(
                dayType = day.dayType,
                myCall = day.loginTime,
                unitCall = day.callTime,
                unitWrap = day.wrapTime,
                release = day.logoutTime,
                basic = day.basicPay,
                ots = day.otTotal,
                allowances = day.allowTotal,
            )
        }
        return cells
    }

    private fun indexOf(date: Long?): Int? {
        val start = timecard.weekStarting ?: weekStarting
        return date?.let { kotlin.math.round((it - start) / PayPeriod.DAY_MILLIS.toDouble()).toInt() }
            ?.takeIf { it in 0 until DAYS }
    }

    companion object {
        const val DAYS = 7
    }
}

/**
 * One crew member's unsettled weeks summed — the web's
 * `aggregateOutstandingByUser`: one row per user, the heaviest first.
 */
data class OutstandingRow(val userId: String, val weeks: List<ProcessingRow>) {
    val id: String get() = "outstanding-$userId"
    val status: TimecardStatus get() = weeks.firstOrNull()?.timecard?.status ?: TimecardStatus.Unknown
    val days: Int get() = weeks.sumOf { it.timecard.totalDays.takeIf { days -> days > 0 } ?: it.daysWorked }
    val basic: Double get() = weeks.sumOf { it.basicTotal }
    val ots: Double get() = weeks.sumOf { it.otTotal }
    val allowances: Double get() = weeks.sumOf { it.allowanceTotal }
    val total: Double get() = weeks.sumOf { it.totalPay }

    companion object {
        fun of(timecards: List<PayrollTimecard>, weekStarting: Long): List<OutstandingRow> =
            timecards.groupBy { it.userId }
                .map { (userId, cards) -> OutstandingRow(userId, cards.map { ProcessingRow(it, weekStarting) }) }
                .sortedByDescending { it.total }
    }
}
