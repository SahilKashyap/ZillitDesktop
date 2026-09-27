package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.SummaryTone
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardMeal
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.WeekView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Monday 2026-05-04 00:00 UTC. */
private const val WEEK = 1_777_852_800_000L

private fun day(
    offset: Int,
    type: String? = "SWD",
    rates: List<PayLine> = emptyList(),
    allowances: List<PayLine> = emptyList(),
    extras: List<PayLine> = emptyList(),
    meals: List<TimecardMeal> = emptyList(),
) = TimecardDay(
    date = WEEK + offset * PayPeriod.DAY_MILLIS,
    dayType = type,
    rates = rates,
    allowances = allowances,
    extras = extras,
    meals = meals,
)

private fun line(label: String, amount: Double, rental: Boolean = false, qty: Double? = null) =
    PayLine(identifier = label, label = label, rateAmount = amount, qty = qty, isRental = rental)

private fun timecard(
    days: List<TimecardDay> = emptyList(),
    weeklyAllowances: List<PayLine> = emptyList(),
    weeklyExtras: List<PayLine> = emptyList(),
    basic: Double = 0.0,
    overtime: Double = 0.0,
) = PayrollTimecard(
    id = "tc",
    userId = "u",
    status = TimecardStatus.Approved,
    weekStarting = WEEK,
    currency = "GBP",
    basicPay = basic,
    overtimePay = overtime,
    days = days,
    weeklyAllowances = weeklyAllowances,
    weeklyExtras = weeklyExtras,
)

class WeekRowsTest {

    /**
     * A week is always seven rows. A timecard with four days filled is a week
     * with three blanks, not a four-row table.
     */
    @Test
    fun `a week is seven rows, whatever the document holds`() {
        val rows = WeekView.rows(WEEK, listOf(day(0), day(3)))
        assertEquals(PayPeriod.DAYS_IN_WEEK, rows.size)
        assertEquals("Monday", rows.first().weekday)
        assertEquals("Sunday", rows.last().weekday)
        assertEquals("SWD", rows[0].dayType)
        assertEquals(null, rows[1].dayType)
    }

    /** A Wednesday-start production's first row is Wednesday, not Monday. */
    @Test
    fun `the first row is whichever weekday the period starts on`() {
        val wednesday = WEEK + 2 * PayPeriod.DAY_MILLIS
        assertEquals("Wednesday", WeekView.rows(wednesday, emptyList()).first().weekday)
    }

    /**
     * Meal columns follow the busiest day and never fall below one: seeding
     * from zero drew no meal column at all for a week where nobody broke,
     * which is not the same as there being no such column.
     */
    @Test
    fun `meal columns follow the busiest day, with a floor of one`() {
        assertEquals(1, WeekView.mealColumns(WeekView.rows(WEEK, listOf(day(0)))))
        val busy = listOf(day(0, meals = listOf(TimecardMeal(1, 2), TimecardMeal(3, 4))), day(1))
        assertEquals(2, WeekView.mealColumns(WeekView.rows(WEEK, busy)))
    }

    @Test
    fun `a non-paid day collapses and a flat day loses its times`() {
        val rows = WeekView.rows(WEEK, listOf(day(0, "REST"), day(1, "Flat"), day(2, "SWD")))
        assertTrue(rows[0].isMerged)
        assertFalse(rows[0].isFlatPay)
        assertTrue(rows[1].isFlatPay)
        assertFalse(rows[1].isMerged)
        assertFalse(rows[2].isMerged)
        assertFalse(rows[2].isFlatPay)
    }
}

class WeekSummaryTest {

    /**
     * The backend's `total_gross` wins where it exists: a front-end sum and a
     * stored aggregate that disagree is the web's "two grosses" bug.
     */
    @Test
    fun `the backend's own gross wins, and a present zero is used`() {
        val card = timecard(basic = 100.0, overtime = 50.0).copy(totalGross = 900.0)
        fun gross(card: PayrollTimecard) = WeekView.summary(card).single { it.label.startsWith("Est") }.value
        assertEquals("£900.00", gross(card))
        assertEquals("£0.00", gross(card.copy(totalGross = 0.0)))
    }

    /** Without one, the gross is exactly what the other cards show. */
    @Test
    fun `without a backend gross the strip adds up to itself`() {
        val card = timecard(
            days = listOf(
                day(0, allowances = listOf(line("perdiem", 20.0), line("kit", 30.0, rental = true))),
            ),
            weeklyAllowances = listOf(line("box", 40.0, rental = true, qty = 2.0)),
            weeklyExtras = listOf(line("upgrade", 15.0)),
            basic = 100.0,
            overtime = 50.0,
        )
        val cards = WeekView.summary(card).associateBy { it.label }
        assertEquals("£20.00", cards.getValue("Allowances").value)
        assertEquals("£110.00", cards.getValue("Rentals").value)
        assertEquals("£15.00", cards.getValue("Upgrades & Extras").value)
        // 100 basic + 50 OT + 20 allowances + 110 rentals + 15 extras.
        assertEquals("£295.00", cards.getValue("Est. Gross").value)
    }

    /** `total_ot` is preferred over `overtime_pay`, which only older docs carry. */
    @Test
    fun `overtime prefers the backend's total`() {
        val cards = WeekView.summary(timecard(overtime = 50.0).copy(totalOt = 75.0)).associateBy { it.label }
        assertEquals("£75.00", cards.getValue("Overtime").value)
    }

    @Test
    fun `a missing figure is an em dash, not a zero`() {
        val cards = WeekView.summary(timecard()).associateBy { it.label }
        assertEquals(WeekView.DASH, cards.getValue("Basic").value)
        assertEquals(SummaryTone.Mute, cards.getValue("Basic").tone)
        assertEquals(WeekView.DASH, cards.getValue("Worked").value)
    }

    @Test
    fun `worked hours read as hours and minutes`() {
        val cards = WeekView.summary(timecard().copy(totalHours = 7.5)).associateBy { it.label }
        assertEquals("7h 30m", cards.getValue("Worked").value)
        assertEquals("8h", WeekView.summary(timecard().copy(totalHours = 8.0)).first { it.label == "Worked" }.value)
    }

    /** Holiday pay accrues; it is never part of the gross, and it is optional. */
    @Test
    fun `holiday pay is a card of its own, only when there is one`() {
        assertTrue(WeekView.summary(timecard(basic = 100.0)).none { it.label == "Holiday Pay" })
        val withHp = WeekView.summary(timecard(basic = 100.0), holidayPay = 12.07)
        val card = withHp.single { it.label == "Holiday Pay" }
        assertEquals("£12.07", card.value)
        // The gross is unchanged by it.
        assertEquals("£100.00", withHp.single { it.label == "Est. Gross" }.value)
    }
}

class WeekEntriesTest {

    /** Daily lines aggregate by pay code across the days they appear on. */
    @Test
    fun `daily lines are grouped and counted, weekly ones stand alone`() {
        val entries = WeekView.entries(
            days = listOf(
                day(0, allowances = listOf(line("perdiem", 20.0))),
                day(1, allowances = listOf(line("perdiem", 20.0))),
            ),
            weeklyAllowances = listOf(line("box", 50.0)),
        )
        val perdiem = entries.allowances.single { it.label == "perdiem" }
        assertEquals(40.0, perdiem.amount)
        assertTrue(perdiem.daily)
        assertEquals("2 days", perdiem.sub)
        val box = entries.allowances.single { it.label == "box" }
        assertFalse(box.daily)
        assertEquals(null, box.sub)
    }

    @Test
    fun `rentals are their own bucket and extras another`() {
        val entries = WeekView.entries(
            days = listOf(
                day(
                    0,
                    allowances = listOf(line("kit", 30.0, rental = true)),
                    extras = listOf(line("grade", 10.0)),
                ),
            ),
            weeklyAllowances = listOf(line("van", 60.0, rental = true)),
        )
        assertTrue(entries.allowances.isEmpty())
        assertEquals(2, entries.rentals.size)
        assertEquals(1, entries.extras.size)
        assertEquals(100.0, entries.total)
    }

    /**
     * A quantity that is missing is one; a quantity that is genuinely zero
     * stays zero — a capped week nobody worked pays nothing, not a full week.
     */
    @Test
    fun `a weekly line multiplies by its quantity, and a zero means zero`() {
        val entries = WeekView.entries(
            days = emptyList(),
            weeklyAllowances = listOf(line("kit", 50.0, qty = 0.0), line("box", 50.0)),
        )
        assertEquals(0.0, entries.allowances.single { it.label == "kit" }.amount)
        assertEquals(50.0, entries.allowances.single { it.label == "box" }.amount)
    }
}

/**
 * The drawer's day tiles. A week that holds today stops at today; a past
 * week's days are all readable, because the week navigator is what moves
 * between weeks.
 */
class DrawerWeekTest {

    private fun indexOfToday(now: Long, week: Long): Int? =
        ((now.floorDiv(PayPeriod.DAY_MILLIS) * PayPeriod.DAY_MILLIS - week) / PayPeriod.DAY_MILLIS)
            .toInt()
            .takeIf { it in 0 until PayPeriod.DAYS_IN_WEEK }

    @Test
    fun `today is found within its own week, whatever the hour`() {
        val wednesdayNoon = WEEK + 2 * PayPeriod.DAY_MILLIS + 12 * HOUR
        assertEquals(2, indexOfToday(wednesdayNoon, WEEK))
        // A minute before midnight is still that day, not the next.
        assertEquals(2, indexOfToday(WEEK + 3 * PayPeriod.DAY_MILLIS - MINUTE, WEEK))
        assertEquals(0, indexOfToday(WEEK, WEEK))
        assertEquals(6, indexOfToday(WEEK + 6 * PayPeriod.DAY_MILLIS, WEEK))
    }

    @Test
    fun `a past or future week has no today, so every day is readable`() {
        val laterWeek = WEEK + 3 * PayPeriod.WEEK_MILLIS
        assertNull(indexOfToday(laterWeek, WEEK))
        assertNull(indexOfToday(WEEK - PayPeriod.DAY_MILLIS, WEEK))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
    }
}
