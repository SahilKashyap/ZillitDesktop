package com.zillit.desktop.feature.payroll.domain

/**
 * What the accountant's crew drawer reads off a full timecard below its week
 * strip — the web's `CrewDrawer` (`PayrollRunModule.jsx` 3855-4757): a card for
 * every day on the document, the week-level allowance lines, and the claims
 * and deductions with the words that say where each came from.
 *
 * Kept free of Compose so the rules (which day opens, what a rest day hides,
 * what the merged section leaves out) are tested without rendering.
 */
object DrawerBreakdown {

    /**
     * The day the drawer opens on: today when today falls inside the week —
     * the web's `defaultDayIdx` — else the first day the document holds
     * anything for, else the first day. A past week has no "today", and
     * landing on a blank Monday there hides the week's only worked days.
     */
    fun defaultDay(weekStarting: Long?, days: List<TimecardDay>, now: Long): Int {
        val week = weekStarting?.takeIf { it > 0 } ?: return 0
        val today = now.floorDiv(PayPeriod.DAY_MILLIS) * PayPeriod.DAY_MILLIS
        val todayIndex = ((today - week) / PayPeriod.DAY_MILLIS).toInt()
        if (today >= week && todayIndex < PayPeriod.DAYS_IN_WEEK) return todayIndex
        val held = days.mapNotNull { it.date }.toSet()
        val first = (0 until PayPeriod.DAYS_IN_WEEK).firstOrNull { week + it * PayPeriod.DAY_MILLIS in held }
        return first ?: 0
    }

    /**
     * One card per day the document holds, in date order. A day with no date
     * cannot be placed in the week and is left out, as the web's sort would
     * float it to the top unlabelled.
     */
    fun dayCards(days: List<TimecardDay>): List<DayCard> = days.filter { it.date != null }
        .sortedBy { it.date }
        .map { day ->
            val date = day.date ?: 0L
            val basic = day.rates.firstOrNull { it.isBasic }
            DayCard(
                weekday = PayPeriod.weekdayName(date),
                dayMonth = PayPeriod.dayMonth(date),
                dayType = day.dayType?.takeIf { it.isNotBlank() },
                total = day.dayTotal,
                // A canonical non-paid set, so a new day type recedes without a change here.
                isRest = day.dayType.isNullOrBlank() || day.dayType in DayTypes.NON_PAID,
                callIn = PayPeriod.hhmm(day.loginTime).takeIf { day.loginTime != null && day.loginTime > 0 },
                callOut = PayPeriod.hhmm(day.logoutTime).takeIf { day.logoutTime != null && day.logoutTime > 0 },
                minutesWorked = day.minutesWorked,
                // Basic reads on its own row at the top of the card, and only when it paid.
                basic = basic?.takeIf { it.rateAmount > 0 },
                ots = day.rates.filterNot { it.isBasic },
                allowances = day.allowances,
            )
        }

    /**
     * The merged "Allowances, Rentals, Upgrades & Extras" section. A day's
     * allowance and rental lines already have a home on their day card, so
     * only the days' extras — which have none, and are in the hero's Upgrades
     * & Extras total — and the week's own lines are listed, each with its
     * Daily / Weekly cadence.
     */
    fun mergedEntries(timecard: PayrollTimecard): MergedEntries = WeekView.entries(
        days = timecard.days.map { it.copy(allowances = emptyList()) },
        weeklyAllowances = timecard.weeklyAllowances,
        weeklyExtras = timecard.weeklyExtras,
    )

    /** `12%`, or `7.5%` — a percentage deduction's pill, as the web prints the rate. */
    fun percentLabel(rate: Double): String =
        (if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()) + "%"

    /** A claim's source pill: a cash-expense batch is CASH, anything typed in is MANUAL. */
    fun isCash(claim: ClaimLine): Boolean = !claim.cashExpenseBatchId.isNullOrBlank()
}

/** One day's card in the drawer's Daily Breakdown. */
data class DayCard(
    val weekday: String,
    val dayMonth: String,
    val dayType: String?,
    val total: Double,
    /** Off, on holiday or sick unpaid — dimmed, and its times are not shown. */
    val isRest: Boolean,
    /** `07:30`, or null when the crew member never logged a time. */
    val callIn: String?,
    val callOut: String?,
    val minutesWorked: Int,
    val basic: PayLine?,
    val ots: List<PayLine>,
    val allowances: List<PayLine>,
) {
    /** The In / Out strip: shown on a worked day that logged at least one time. */
    val showsTimes: Boolean get() = !isRest && (callIn != null || callOut != null)
}
