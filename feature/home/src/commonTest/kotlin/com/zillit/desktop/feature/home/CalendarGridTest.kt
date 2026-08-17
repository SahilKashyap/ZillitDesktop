package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.MonthGrid
import com.zillit.desktop.feature.home.calendar.monthGrid
import com.zillit.desktop.feature.home.calendar.on
import com.zillit.desktop.feature.home.calendar.timeLabel
import com.zillit.desktop.feature.home.calendar.weekOf
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Month-grid arithmetic and event placement.
 *
 * Calendars are all edge cases — leap years, months starting on a Sunday,
 * events crossing midnight — and every one of them is visible to a user the
 * moment it is wrong.
 */
class CalendarGridTest {

    private val utc = TimeZone.UTC

    @Test
    fun `a grid is always whole weeks`() {
        // A ragged final row shifts every column beneath it.
        listOf(
            LocalDate(2026, 2, 1),
            LocalDate(2026, 8, 15),
            LocalDate(2024, 2, 10),
        ).forEach { anchor ->
            val grid = monthGrid(anchor)
            assertEquals(0, grid.days.size % MonthGrid.DAYS_PER_WEEK, "ragged grid for $anchor")
            grid.weeks.forEach { assertEquals(7, it.size) }
        }
    }

    @Test
    fun `the grid covers every day of the month`() {
        val grid = monthGrid(LocalDate(2026, 8, 1))
        val august = grid.days.filter { it.inMonth }.map { it.date.day }

        assertEquals((1..31).toList(), august)
    }

    @Test
    fun `February in a leap year has 29 in-month days`() {
        assertEquals(29, monthGrid(LocalDate(2024, 2, 1)).days.count { it.inMonth })
        assertEquals(28, monthGrid(LocalDate(2026, 2, 1)).days.count { it.inMonth })
    }

    @Test
    fun `leading days come from the previous month`() {
        // 1 Aug 2026 is a Saturday, so a Monday-start grid leads with five days
        // of July.
        val grid = monthGrid(LocalDate(2026, 8, 1), weekStart = DayOfWeek.MONDAY)
        val lead = grid.days.takeWhile { !it.inMonth }

        assertEquals(5, lead.size)
        assertTrue(lead.all { it.date.month == kotlinx.datetime.Month.JULY })
    }

    @Test
    fun `the week start is configurable`() {
        // Productions run internationally; Sunday-start moves which row a shoot
        // day lands on.
        val monday = monthGrid(LocalDate(2026, 8, 1), DayOfWeek.MONDAY)
        val sunday = monthGrid(LocalDate(2026, 8, 1), DayOfWeek.SUNDAY)

        assertEquals(DayOfWeek.MONDAY, monday.days.first().date.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.days.first().date.dayOfWeek)
    }

    @Test
    fun `a month starting on the week-start day has no leading days`() {
        // 1 June 2026 is a Monday.
        val grid = monthGrid(LocalDate(2026, 6, 1), DayOfWeek.MONDAY)

        assertTrue(grid.days.first().inMonth, "an empty leading week would waste a row")
    }

    @Test
    fun `a week is seven consecutive days from the week start`() {
        val week = weekOf(LocalDate(2026, 8, 5), DayOfWeek.MONDAY) // a Wednesday

        assertEquals(7, week.size)
        assertEquals(LocalDate(2026, 8, 3), week.first())
        assertEquals(LocalDate(2026, 8, 9), week.last())
    }

    // -- placement --------------------------------------------------------

    private fun event(id: String, start: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id = id, title = id, startMillis = start, endMillis = end, isAllDay = allDay)

    /** 2026-08-03T00:00:00Z */
    private val aug3 = 1_785_715_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `an event lands on its own day`() {
        val events = listOf(event("a", aug3 + 9 * hour, aug3 + 10 * hour))

        assertEquals(listOf("a"), events.on(LocalDate(2026, 8, 3), utc).map { it.id })
        assertTrue(events.on(LocalDate(2026, 8, 4), utc).isEmpty())
    }

    @Test
    fun `a night shoot crossing midnight appears on both days`() {
        // 22:00 to 04:00. Filtering on start time alone would drop it from the
        // second day, which is the day most of it happens on.
        val events = listOf(event("night", aug3 + 22 * hour, aug3 + 28 * hour))

        assertEquals(listOf("night"), events.on(LocalDate(2026, 8, 3), utc).map { it.id })
        assertEquals(listOf("night"), events.on(LocalDate(2026, 8, 4), utc).map { it.id })
    }

    @Test
    fun `all-day events sort above timed ones`() {
        val events = listOf(
            event("timed", aug3 + 9 * hour, aug3 + 10 * hour),
            event("allday", aug3, aug3 + day, allDay = true),
        )

        assertEquals(listOf("allday", "timed"), events.on(LocalDate(2026, 8, 3), utc).map { it.id })
    }

    @Test
    fun `timed events sort by start`() {
        val events = listOf(
            event("late", aug3 + 15 * hour, aug3 + 16 * hour),
            event("early", aug3 + 8 * hour, aug3 + 9 * hour),
        )

        assertEquals(listOf("early", "late"), events.on(LocalDate(2026, 8, 3), utc).map { it.id })
    }

    @Test
    fun `an event with no end still places`() {
        // The server omits `end_datetime` on some events; treating a missing end
        // as zero would put it in 1970 and drop it from every day.
        val events = listOf(event("open", aug3 + 9 * hour, 0))

        assertEquals(listOf("open"), events.on(LocalDate(2026, 8, 3), utc).map { it.id })
    }

    @Test
    fun `time labels are zero padded and all-day is named`() {
        assertEquals("09:00 – 10:30", event("x", aug3 + 9 * hour, aug3 + 10 * hour + 1_800_000).timeLabel(utc))
        assertEquals("All day", event("x", aug3, aug3 + day, allDay = true).timeLabel(utc))
    }

    @Test
    fun `an event never prints its description`() {
        // Events carry location and cast detail.
        val text = event("x", aug3, aug3).copy(description = "cast pickup address").toString()

        assertTrue(!text.contains("cast pickup address"))
    }
}
