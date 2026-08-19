package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.Recurrence
import com.zillit.desktop.feature.home.calendar.RecurrenceError
import com.zillit.desktop.feature.home.calendar.RecurrenceFrequency
import com.zillit.desktop.feature.home.calendar.defaultRecurrenceEnd
import com.zillit.desktop.feature.home.calendar.endMillis
import com.zillit.desktop.feature.home.calendar.sundayFirstIndexToDayOfWeek
import com.zillit.desktop.feature.home.calendar.toSundayFirstIndex
import com.zillit.desktop.feature.home.calendar.validate
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeat rules.
 *
 * Two things bite here: the weekday indexing, where an off-by-one books a
 * shoot day on the wrong day of the week; and the end boundary, where stopping
 * at midnight silently drops the last occurrence.
 */
class RecurrenceTest {

    private val zone = TimeZone.UTC

    private fun weekly(days: Set<Int> = emptySet(), end: String = "") =
        Recurrence(RecurrenceFrequency.Custom, days, end)

    // -- wire values -------------------------------------------------------

    @Test
    fun `frequencies keep the numbers the server stores`() {
        // Reordering the enum would silently change every existing event.
        assertEquals(0, RecurrenceFrequency.Never.wireValue)
        assertEquals(1, RecurrenceFrequency.Daily.wireValue)
        assertEquals(2, RecurrenceFrequency.Weekly.wireValue)
        assertEquals(3, RecurrenceFrequency.Monthly.wireValue)
        assertEquals(4, RecurrenceFrequency.Yearly.wireValue)
        assertEquals(5, RecurrenceFrequency.Custom.wireValue)
    }

    @Test
    fun `an unknown frequency reads as not repeating`() {
        // Better a one-off than a guess at how often something recurs.
        assertEquals(RecurrenceFrequency.Never, RecurrenceFrequency.of(99))
        assertEquals(RecurrenceFrequency.Never, RecurrenceFrequency.of(null))
    }

    // -- weekday indexing --------------------------------------------------

    @Test
    fun `Sunday is zero, as the server counts it`() {
        // DayOfWeek counts Monday as 1 and Sunday as 7.
        assertEquals(0, DayOfWeek.SUNDAY.toSundayFirstIndex())
        assertEquals(1, DayOfWeek.MONDAY.toSundayFirstIndex())
        assertEquals(6, DayOfWeek.SATURDAY.toSundayFirstIndex())
    }

    @Test
    fun `the weekday conversion round-trips`() {
        DayOfWeek.entries.forEach { day ->
            assertEquals(day, sundayFirstIndexToDayOfWeek(day.toSundayFirstIndex()), day.name)
        }
    }

    // -- validation --------------------------------------------------------

    @Test
    fun `a one-off needs nothing`() {
        assertTrue(Recurrence().validate("2026-08-04").isEmpty())
    }

    @Test
    fun `a custom repeat needs at least one weekday`() {
        // The server rejects this as `recurrence_rule.selectedDays must contain
        // at least 1 items`, which is written for whoever wrote the schema.
        val errors = weekly(end = "2026-12-31").validate("2026-08-04")

        assertTrue(RecurrenceError.NoWeekdays in errors)
    }

    @Test
    fun `other frequencies do not need weekdays`() {
        val monthly = Recurrence(RecurrenceFrequency.Monthly, endDateText = "2026-12-31")

        assertFalse(RecurrenceError.NoWeekdays in monthly.validate("2026-08-04"))
    }

    @Test
    fun `any repeat needs an end date`() {
        val daily = Recurrence(RecurrenceFrequency.Daily)

        assertTrue(RecurrenceError.NoEndDate in daily.validate("2026-08-04"))
    }

    @Test
    fun `the repeat cannot end before the event starts`() {
        val daily = Recurrence(RecurrenceFrequency.Daily, endDateText = "2026-01-01")

        assertTrue(RecurrenceError.EndBeforeStart in daily.validate("2026-08-04"))
    }

    @Test
    fun `a repeat that stops the day it starts is refused`() {
        // A one-off wearing a rule. The web's picker disables everything
        // before the day after the event, so the earliest it can stop is the
        // next day.
        val daily = Recurrence(RecurrenceFrequency.Daily, endDateText = "2026-08-04")

        assertTrue(RecurrenceError.EndBeforeStart in daily.validate("2026-08-04"))
    }

    @Test
    fun `the day after the event is the earliest it can stop`() {
        val daily = Recurrence(RecurrenceFrequency.Daily, endDateText = "2026-08-05")

        assertTrue(daily.validate("2026-08-04").isEmpty())
    }

    @Test
    fun `choosing a frequency suggests where it stops`() {
        val start = LocalDate(2026, 8, 4)

        assertEquals(LocalDate(2026, 8, 5), defaultRecurrenceEnd(RecurrenceFrequency.Daily, start))
        assertEquals(LocalDate(2026, 8, 11), defaultRecurrenceEnd(RecurrenceFrequency.Weekly, start))
        assertEquals(LocalDate(2026, 9, 4), defaultRecurrenceEnd(RecurrenceFrequency.Monthly, start))
        assertEquals(LocalDate(2027, 8, 4), defaultRecurrenceEnd(RecurrenceFrequency.Yearly, start))
        // A custom rule repeats on weekdays, so it is measured in weeks.
        assertEquals(LocalDate(2026, 8, 11), defaultRecurrenceEnd(RecurrenceFrequency.Custom, start))
        assertNull(defaultRecurrenceEnd(RecurrenceFrequency.Never, start))
    }

    @Test
    fun `a missing end date does not also complain about ordering`() {
        val errors = Recurrence(RecurrenceFrequency.Daily).validate("2026-08-04")

        assertTrue(RecurrenceError.NoEndDate in errors)
        assertFalse(RecurrenceError.EndBeforeStart in errors)
    }

    @Test
    fun `a complete custom rule passes`() {
        val rule = weekly(days = setOf(1, 3, 5), end = "2026-12-31")

        assertTrue(rule.validate("2026-08-04").isEmpty())
    }

    // -- the end boundary --------------------------------------------------

    @Test
    fun `the repeat ends at the very end of its last day`() {
        // Stopping at midnight drops the occurrence *on* the end date — nobody
        // reports it as a bug and everybody notices.
        val rule = Recurrence(RecurrenceFrequency.Daily, endDateText = "2026-12-31")

        val end = rule.endMillis(zone)!!
        val nextMidnight = 1_798_761_600_000L // 2027-01-01T00:00:00Z
        assertEquals(nextMidnight - 1, end)
    }

    @Test
    fun `no end date means no end millis`() {
        assertNull(Recurrence(RecurrenceFrequency.Daily).endMillis(zone))
        assertNull(Recurrence(RecurrenceFrequency.Daily, endDateText = "nonsense").endMillis(zone))
    }
}
