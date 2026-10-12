package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.TalentDates
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.domain.TalentSchedule
import com.zillit.desktop.feature.talentdeals.domain.TrancheDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The payment schedule — the fee split into dated tranches. The rule most
 * likely to be broken by a later edit is the percentage base: a percentage is
 * of the TOTAL FEE, never of the remainder. And the three date fields describe
 * ONE span, so editing any of them must leave the other two agreeing with it.
 * Ports the web's `schedule.test.js`.
 */
class TalentScheduleTest {

    private var n = 0
    private fun t(value: String, weeks: String = "", start: String = "", end: String = "") =
        TrancheDraft(id = "t${n++}", label = "Shoot", value = value, weeks = weeks, start = start, end = end)

    private fun pct(vararg values: String) = values.map { t(it) }

    // -- amountOf ----------------------------------------------------------------------

    @Test
    fun `a percentage resolves against the total fee`() {
        assertEquals(20000.0, TalentSchedule.amountOf(t("20"), 100000.0, PaidAs.Percent))
        assertEquals(60000.0, TalentSchedule.amountOf(t("60"), 100000.0, PaidAs.Percent))
    }

    @Test
    fun `a flat amount is taken as it is, whatever the fee`() {
        assertEquals(50000.0, TalentSchedule.amountOf(t("50000"), 100000.0, PaidAs.Flat))
        assertEquals(50000.0, TalentSchedule.amountOf(t("50000"), 0.0, PaidAs.Flat))
    }

    @Test
    fun `money rounds to the penny`() {
        assertEquals(3333.3, TalentSchedule.amountOf(t("33.333"), 10000.0, PaidAs.Percent))
    }

    // -- totals ------------------------------------------------------------------------

    @Test
    fun `the 20-60-10-10 split allocates the whole fee and reports balanced`() {
        val totals = TalentSchedule.totals(pct("20", "60", "10", "10"), "500000", PaidAs.Percent)
        assertEquals(500000.0, totals.allocated)
        assertEquals(0.0, totals.remaining)
        assertEquals(100.0, totals.pctUsed)
        assertTrue(totals.balanced)
    }

    @Test
    fun `a short split reports the shortfall and an over-allocated one a negative remainder`() {
        val short = TalentSchedule.totals(pct("20", "60"), "100000", PaidAs.Percent)
        assertEquals(80000.0, short.allocated)
        assertEquals(20000.0, short.remaining)
        assertFalse(short.balanced)

        val over = TalentSchedule.totals(pct("60", "60"), "100000", PaidAs.Percent)
        assertEquals(-20000.0, over.remaining)
        assertTrue(over.over)
        assertFalse(over.balanced)
    }

    @Test
    fun `an empty schedule is not balanced, whatever the fee, and a zero fee balances nothing`() {
        assertFalse(TalentSchedule.totals(emptyList(), "100000", PaidAs.Percent).balanced)
        assertFalse(TalentSchedule.totals(pct("100"), "0", PaidAs.Percent).balanced)
    }

    @Test
    fun `one penny of slack and no more`() {
        // Three thirds of 10,000 resolve to 9,999.99 — correct, not an error.
        assertTrue(TalentSchedule.totals(pct("33.33", "33.33", "33.34"), "10000", PaidAs.Percent).balanced)
        assertFalse(TalentSchedule.totals(pct("33", "33", "33"), "10000", PaidAs.Percent).balanced)
    }

    @Test
    fun `a flat schedule counts no percentage`() {
        val totals = TalentSchedule.totals(listOf(t("40000"), t("60000")), "100000", PaidAs.Flat)
        assertEquals(0.0, totals.pctUsed)
        assertTrue(totals.balanced)
    }

    // -- dates and weeks ---------------------------------------------------------------

    @Test
    fun `weeks count INCLUSIVELY from the start`() {
        // A one-week tranche starting Monday the 5th ends Sunday the 11th, not the 12th.
        assertEquals("2026-01-11", TalentDates.endOfWeeks("2026-01-05", "1"))
        assertEquals("2026-01-18", TalentDates.endOfWeeks("2026-01-05", "2"))
    }

    @Test
    fun `a span reads back as the same number of weeks`() {
        assertEquals("1", TalentDates.weeksBetween("2026-01-05", "2026-01-11"))
        assertEquals("2", TalentDates.weeksBetween("2026-01-05", "2026-01-18"))
        assertEquals("1.4", TalentDates.weeksBetween("2026-01-05", "2026-01-14"))
    }

    @Test
    fun `a half-filled row yields nothing rather than a guess`() {
        assertEquals("", TalentDates.endOfWeeks("", "2"))
        assertEquals("", TalentDates.endOfWeeks("2026-01-05", ""))
        assertEquals("", TalentDates.endOfWeeks("2026-01-05", "0"))
        assertEquals("", TalentDates.weeksBetween("2026-01-05", ""))
        assertEquals("", TalentDates.weeksBetween("2026-06-01", "2026-05-01"))
    }

    @Test
    fun `calendar arithmetic crosses months, years and leap days`() {
        assertEquals("1", TalentDates.weeksBetween("2026-03-29", "2026-04-04"))
        assertEquals("2028-03-05", TalentDates.endOfWeeks("2028-02-28", "1"))
        assertEquals("2027-01-03", TalentDates.endOfWeeks("2026-12-28", "1"))
    }

    @Test
    fun `editing one of start end weeks fills the others in`() {
        val row = t("50", weeks = "4")
        val started = TalentSchedule.withStart(row, "2026-02-02")
        assertEquals("2026-03-01", started.end)

        val ended = TalentSchedule.withEnd(started.copy(weeks = ""), "2026-02-15")
        assertEquals("2", ended.weeks)

        val reweeked = TalentSchedule.withWeeks(ended, "3")
        assertEquals("2026-02-22", reweeked.end)

        // An end with no start leaves weeks blank rather than invent a start.
        assertEquals("", TalentSchedule.withEnd(t("1"), "2026-02-15").weeks)
        // A start with an end but no weeks derives the weeks.
        assertEquals("2", TalentSchedule.withStart(t("1", end = "2026-02-15"), "2026-02-02").weeks)
    }

    @Test
    fun `schedule weeks add the typed figure, falling back to a tranche's dates`() {
        val weeks = TalentSchedule.scheduleWeeks(
            listOf(t("50", weeks = "4"), t("50", weeks = "", start = "2026-02-02", end = "2026-02-15")),
        )
        assertEquals(6.0, weeks)
        assertEquals(0.0, TalentSchedule.scheduleWeeks(emptyList()))
    }

    // -- the epoch form ----------------------------------------------------------------

    @Test
    fun `an input date is UTC midnight on the wire and reads back unchanged`() {
        assertEquals(1767571200000L, TalentDates.toEpoch("2026-01-05"))
        assertEquals("2026-01-05", TalentDates.fromEpoch(TalentDates.toEpoch("2026-01-05")))
        assertNull(TalentDates.toEpoch(""))
        assertNull(TalentDates.toEpoch("not a date"))
        assertNull(TalentDates.toEpoch("2026-02-30"))
        assertEquals("", TalentDates.fromEpoch(null))
        assertEquals("", TalentDates.fromEpoch(0))
    }

    @Test
    fun `dates before the epoch survive the round trip`() {
        assertEquals("1969-12-31", TalentDates.fromEpoch(TalentDates.toEpoch("1969-12-31")))
    }

    @Test
    fun `the register's date style and the shoot day's`() {
        assertEquals("05 Jan 2026", TalentDates.display("2026-01-05"))
        assertEquals("not-a-date", TalentDates.display("not-a-date"))
        // 5 Jan 2026 was a Monday; read in UTC whatever the viewer's zone.
        assertEquals("Mon 05 Jan", TalentDates.shootDay(TalentDates.toEpoch("2026-01-05")!!))
        assertEquals("Thu 01 Jan", TalentDates.shootDay(0))
    }

    // -- figures -----------------------------------------------------------------------

    @Test
    fun `numbers parse with separators and give 0 for nonsense`() {
        assertEquals(9000.0, TalentNumbers.num("9,000"))
        assertEquals(0.0, TalentNumbers.num(""))
        assertEquals(0.0, TalentNumbers.num(null))
        assertEquals(0.0, TalentNumbers.num("abc"))
        assertEquals(0.0, TalentNumbers.num("Infinity"))
        assertEquals(1.5, TalentNumbers.num(" 1.5 "))
    }

    @Test
    fun `rounding is JavaScript's - a half goes up`() {
        assertEquals(1.0, TalentNumbers.r2(1.005))
        assertEquals(-2.0, TalentNumbers.r2(-2.0))
        assertEquals(0.01, TalentNumbers.r2(0.005))
    }

    @Test
    fun `plain drops the point-zero and a zero reads blank`() {
        assertEquals("500000", TalentNumbers.plain(500000.0))
        assertEquals("1234.5", TalentNumbers.plain(1234.5))
        assertEquals("", TalentNumbers.plain(0.0))
    }

    @Test
    fun `money groups to two places behind an isolated symbol`() {
        assertEquals("1,234,567.89", TalentNumbers.groupAmount(1234567.89))
        assertEquals("0.00", TalentNumbers.groupAmount(0.0))
        assertEquals("-1,234.50", TalentNumbers.groupAmount(-1234.5))
        assertEquals("999.00", TalentNumbers.groupAmount(999.0))
        assertEquals("\u2068£\u2069" + "1,000.00", TalentNumbers.formatMoney(1000.0, "£"))
    }

    @Test
    fun `whole figures go on the wire without a decimal`() {
        assertEquals("500000", TalentNumbers.jsonNumber(500000.0).toString())
        assertEquals("12.5", TalentNumbers.jsonNumber(12.5).toString())
    }
}
