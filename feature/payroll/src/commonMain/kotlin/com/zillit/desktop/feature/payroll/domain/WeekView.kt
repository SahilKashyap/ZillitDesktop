package com.zillit.desktop.feature.payroll.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The read-only week — the model behind the web's `ReadOnlyWeeklyView`.
 *
 * The producer surfaces show a crew member's week without the editor: the
 * figures come from what the server stored on the document (`rates_ots`,
 * `allowances`, `weekly_allowances`, `basic_pay`, …) rather than from a
 * recompute, so nothing here needs the crew member's deal.
 *
 * Kept free of Compose so the shapes, the sums and the day-type rules are
 * tested without rendering anything.
 */
object WeekView {

    /**
     * Seven rows from [weekStarting], whatever the document holds. A week is
     * always seven rows: a timecard with four days filled reads as a week with
     * three blanks, not as a four-row table.
     */
    fun rows(weekStarting: Long, days: List<TimecardDay>): List<WeekRow> {
        val byDate = days.associateBy { it.date }
        return (0 until PayPeriod.DAYS_IN_WEEK).map { index ->
            val date = weekStarting + index * PayPeriod.DAY_MILLIS
            val day = byDate[date]
            WeekRow(
                index = index,
                dateMillis = date,
                weekday = PayPeriod.weekdayName(date),
                dayMonth = PayPeriod.dayMonth(date),
                dayType = day?.dayType?.takeIf { it.isNotBlank() },
                unitCall = PayPeriod.hhmm(day?.callTime),
                unitWrap = PayPeriod.hhmm(day?.wrapTime),
                myCall = PayPeriod.hhmm(day?.loginTime),
                myWrap = PayPeriod.hhmm(day?.logoutTime),
                meals = day?.meals.orEmpty(),
                rates = day?.rates.orEmpty(),
                day = day,
            )
        }
    }

    /**
     * How many meal columns the table draws: as many as the busiest day, and
     * never fewer than one. Seeding from zero would draw no meal column at all
     * for a week where nobody broke, which is not the same as there being no
     * such column.
     */
    fun mealColumns(rows: List<WeekRow>): Int = rows.maxOfOrNull { it.meals.size }?.coerceAtLeast(1) ?: 1

    /**
     * The KPI strip — the web's `buildSummaryCards`.
     *
     * Basic, Worked and Overtime read the document's own aggregates;
     * Allowances, Rentals and Extras are re-derived from the lines, because
     * the stored `total_allowances` merges the two buckets and covers only the
     * daily half. Est. Gross prefers the backend's `total_gross` and otherwise
     * sums exactly what the other cards show, so the strip always adds up.
     *
     * [holidayPay] is an accrual, NOT a part of the gross, and its card is
     * omitted when the deal has no holiday-pay line.
     */
    fun summary(timecard: PayrollTimecard, holidayPay: Double = 0.0): List<SummaryCard> {
        val code = timecard.currency
        val ot = timecard.otTotal
        val allowances = timecard.allowancesTotal
        val rentals = timecard.rentalsTotal
        val extras = timecard.extrasTotal
        val hours = timecard.totalHours ?: 0.0
        return buildList {
            add(card(S.desktop_payroll_basic, timecard.basicPay, code, SummaryTone.Ink))
            add(
                SummaryCard(
                    label = str(S.desktop_day_type_worked),
                    value = if (hours > 0) hoursLabel((hours * MINUTES_PER_HOUR).toInt()) else DASH,
                    tone = if (hours > 0) SummaryTone.Ink else SummaryTone.Mute,
                ),
            )
            add(card(S.overtime, ot, code, SummaryTone.Red))
            add(card(S.allowances_label, allowances, code, SummaryTone.Green))
            add(card(S.dm_allow_card_rentals, rentals, code, SummaryTone.Green))
            add(card(S.desktop_payroll_upgrades_extras, extras, code, SummaryTone.Pink))
            add(
                SummaryCard(
                    label = str(S.desktop_payroll_est_gross),
                    value = Money.format(timecard.estimatedGross, code),
                    tone = SummaryTone.Amber,
                    highlight = true,
                ),
            )
            if (holidayPay > 0) {
                add(
                    SummaryCard(
                        label = str(S.dm_rates_card_hp),
                        value = Money.format(holidayPay, code),
                        tone = SummaryTone.Green,
                        sub = str(S.desktop_payroll_hp_accrual_note),
                    ),
                )
            }
        }
    }

    /**
     * The merged "Allowances, Rentals, Upgrades & Extras" list — the web's
     * `groupServerEntries`. Daily lines are aggregated by pay code across the
     * days they appear on and carry their day count; weekly lines stand alone.
     */
    fun entries(
        days: List<TimecardDay>,
        weeklyAllowances: List<PayLine> = emptyList(),
        weeklyExtras: List<PayLine> = emptyList(),
    ): MergedEntries {
        val allowances = mutableListOf<EntryRow>()
        val rentals = mutableListOf<EntryRow>()
        val extras = mutableListOf<EntryRow>()

        aggregateDaily(days.flatMap { it.allowances }).forEach { (_, group) ->
            val row = group.row(prefix = "d")
            (if (group.row.isRental) rentals else allowances) += row
        }
        aggregateDaily(days.flatMap { it.extras }).forEach { (_, group) -> extras += group.row(prefix = "dx") }

        weeklyAllowances.forEachIndexed { index, line ->
            val row = EntryRow(
                key = "w:$index",
                label = line.displayLabel,
                amount = round2(line.lineAmount),
                daily = false,
                currency = line.currency,
            )
            (if (line.isRental) rentals else allowances) += row
        }
        weeklyExtras.forEachIndexed { index, line ->
            extras += EntryRow(
                key = "x:$index",
                label = line.displayLabel,
                amount = round2(line.lineAmount),
                daily = false,
                currency = line.currency,
            )
        }
        return MergedEntries(allowances = allowances, rentals = rentals, extras = extras)
    }

    /** `7h 30m`, or an em dash for nothing — the web's `fmtH`. */
    fun hoursLabel(minutes: Int): String {
        if (minutes <= 0) return DASH
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
    }

    private fun card(labelKey: String, amount: Double, code: String?, tone: SummaryTone): SummaryCard {
        val missing = amount <= 0
        return SummaryCard(
            label = str(labelKey),
            value = if (missing) DASH else Money.format(amount, code),
            tone = if (missing) SummaryTone.Mute else tone,
        )
    }

    private fun aggregateDaily(lines: List<PayLine>): Map<String, DailyGroup> {
        val groups = linkedMapOf<String, DailyGroup>()
        lines.forEach { line ->
            val key = line.identifier?.takeIf { it.isNotBlank() } ?: line.displayLabel
            val group = groups[key] ?: DailyGroup(key, line)
            groups[key] = group.plus(line)
        }
        return groups
    }

    private data class DailyGroup(val key: String, val row: PayLine, val total: Double = 0.0, val dayCount: Int = 0) {
        fun plus(line: PayLine) = copy(total = total + line.lineAmount, dayCount = dayCount + 1)

        fun row(prefix: String) = EntryRow(
            key = "$prefix:$key",
            label = row.displayLabel,
            amount = round2(total),
            daily = true,
            sub = if (dayCount == 1) str(S.desktop_one_day) else str(S.desktop_payroll_days_count, dayCount),
            currency = row.currency,
        )
    }

    private fun round2(value: Double): Double = PayrollTimecard.round2(value)

    const val DASH = "—"
    private const val MINUTES_PER_HOUR = 60
}

/** One of the seven rows a read-only week always draws. */
data class WeekRow(
    val index: Int,
    val dateMillis: Long,
    /** `Monday` — whatever weekday the production's period starts on. */
    val weekday: String,
    /** `06 Jul`. */
    val dayMonth: String,
    val dayType: String?,
    val unitCall: String,
    val unitWrap: String,
    val myCall: String,
    val myWrap: String,
    val meals: List<TimecardMeal>,
    val rates: List<PayLine>,
    /** The stored day, for anything the row shape does not surface. */
    val day: TimecardDay?,
) {
    /** A day off, on holiday or off sick — the row collapses into one cell. */
    val isMerged: Boolean get() = dayType in DayTypes.NON_PAID

    /** A flat-rate day: no times, no meals, no OT bands — just the daily basic. */
    val isFlatPay: Boolean get() = dayType in DayTypes.FLAT_PAY

    val otMinutes: Int get() = rates.sumOf { it.workDuration }

    val otAmount: Double get() = rates.sumOf { it.rateAmount }

    /** What a merged row prints across its single cell. */
    val mergedLabel: String
        get() = when (dayType) {
            "REST" -> str(S.desktop_day_type_day_off)
            "Sick" -> str(S.desktop_payroll_sick_day)
            else -> dayType.orEmpty()
        }
}

/**
 * The day-type sets, mirrored from the pay engine's own
 * (`data/timecardDayCalc.js`). They decide what a row is allowed to show, so
 * they live here rather than being re-tested string by string at each cell.
 */
object DayTypes {
    /** Paid a flat daily rate: no call, no wrap, no meals, no OT. */
    val FLAT_PAY = setOf(
        "Turnaround", "Flat", "Custom", "Prep", "Wrap", "Post", "Travel",
        "Idle Day", "Non-work Holiday Pay", "Sick (Paid)", "Sick (SSP)",
    )

    /** Not paid at all. `Sick` bare is legacy; the selector now offers the split types. */
    val NON_PAID = setOf("REST", "Holiday", "Sick", "Sick (Unpaid)")

    val SHOOT = setOf("SWD", "CWD", "SCWD")
}

/** One card of the week's KPI strip. */
data class SummaryCard(
    val label: String,
    val value: String,
    val tone: SummaryTone,
    /** The Est. Gross card, which anchors the strip. */
    val highlight: Boolean = false,
    /** Fine print under the value — the holiday-pay card's accrual note. */
    val sub: String? = null,
)

enum class SummaryTone { Ink, Mute, Red, Green, Pink, Amber }

/** One line of the merged allowances column. */
data class EntryRow(
    val key: String,
    val label: String,
    val amount: Double,
    /** Daily rather than weekly — the cadence badge beside the label. */
    val daily: Boolean,
    val sub: String? = null,
    val currency: String? = null,
)

/** The week's allowances, rentals and extras, grouped as the column draws them. */
data class MergedEntries(
    val allowances: List<EntryRow> = emptyList(),
    val rentals: List<EntryRow> = emptyList(),
    val extras: List<EntryRow> = emptyList(),
) {
    val all: List<EntryRow> get() = allowances + rentals + extras

    val total: Double get() = all.sumOf { it.amount }
}
