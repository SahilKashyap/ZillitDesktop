package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.MonthGrid
import com.zillit.desktop.feature.home.calendar.monthGrid
import com.zillit.desktop.feature.home.calendar.pickerMonthFor
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The date picker's grid.
 *
 * The maths is `monthGrid`, already used by the month view — what is new is
 * which month the picker opens on, which is the thing that annoys people when
 * it is wrong.
 */
class DatePickerTest {

    private val today = LocalDate(2026, 8, 4)

    @Test
    fun `it opens on the date already in the field`() {
        // So reopening a picker lands where the user left it.
        assertEquals(LocalDate(2027, 3, 9), pickerMonthFor("2027-03-09", today))
    }

    @Test
    fun `a blank field opens on today`() {
        assertEquals(today, pickerMonthFor("", today))
        assertEquals(today, pickerMonthFor("   ", today))
    }

    @Test
    fun `a half-typed date opens on today rather than nothing`() {
        // The field stays editable, so it will often hold something unfinished.
        listOf("2026-", "2026-08-", "tomorrow", "04/08/2026").forEach { text ->
            assertEquals(today, pickerMonthFor(text, today), text)
        }
    }

    @Test
    fun `surrounding whitespace does not stop it finding the month`() {
        assertEquals(LocalDate(2026, 8, 4), pickerMonthFor("  2026-08-04  ", today))
    }

    @Test
    fun `the grid is whole weeks, so the columns never shift`() {
        val grid = monthGrid(today, DayOfWeek.MONDAY)

        assertEquals(0, grid.days.size % MonthGrid.DAYS_PER_WEEK)
        assertTrue(grid.weeks.all { it.size == MonthGrid.DAYS_PER_WEEK })
    }

    @Test
    fun `the grid runs into the neighbouring months`() {
        // Those days are pickable: the 1st of next month is often exactly what
        // someone at the end of a month wants.
        val grid = monthGrid(LocalDate(2026, 8, 1), DayOfWeek.MONDAY)

        assertTrue(grid.days.any { !it.inMonth }, "August 2026 starts on a Saturday")
        assertTrue(grid.days.first().date < LocalDate(2026, 8, 1))
    }

    @Test
    fun `the week can start on Sunday`() {
        // Productions run internationally, and the start day changes which row
        // a shoot day lands on.
        val monday = monthGrid(today, DayOfWeek.MONDAY)
        val sunday = monthGrid(today, DayOfWeek.SUNDAY)

        assertEquals(DayOfWeek.MONDAY, monday.days.first().date.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.days.first().date.dayOfWeek)
    }

    @Test
    fun `every day of the month is present exactly once`() {
        val grid = monthGrid(LocalDate(2026, 2, 15), DayOfWeek.MONDAY)

        val inMonth = grid.days.filter { it.inMonth }.map { it.date.day }
        assertEquals((1..28).toList(), inMonth, "February 2026 has 28 days")
    }
}
