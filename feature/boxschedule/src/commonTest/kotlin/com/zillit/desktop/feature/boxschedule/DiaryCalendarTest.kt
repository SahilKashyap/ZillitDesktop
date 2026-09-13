package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.ContentFilter
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryExports
import com.zillit.desktop.feature.boxschedule.domain.DiaryFilter
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDates
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The calendar's rules from `CalendarView.jsx`, the filters, and the schedule form's dates. */
class DiaryCalendarTest {

    private val zone = TimeZone.of("Europe/London")
    private fun day(y: Int, m: Int, d: Int) = DiaryCalendar.startOf(LocalDate(y, m, d), zone)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(zone).toEpochMilliseconds()

    private fun block(id: String, type: String, vararg days: Long, title: String = "") = ScheduleBlock(
        id = id, title = title, typeId = "t-$type", typeName = type, color = "#E74C3C",
        calendarDays = days.toList(), startDate = days.min(), endDate = days.max(),
        numberOfDays = days.size, dateRangeType = "by_days",
    )

    @Suppress("LongParameterList") // A test row: every field some case varies.
    private fun entry(
        id: String,
        kind: DiaryKind = DiaryKind.Event,
        date: Long = 0,
        start: Long = 0,
        end: Long = 0,
        title: String = id,
        body: String = "",
        repeat: String = "",
        repeatEnd: Long = 0,
        occurrenceId: String = "",
    ) = DiaryEvent(
        id = id, kind = kind, title = title, body = body, date = date, startDateTime = start, endDateTime = end,
        fullDay = false, location = "", color = "", scheduleDayId = "", noteType = "", repeatStatus = repeat,
        occurrenceId = occurrenceId, masterEventId = "", isRecurringInstance = occurrenceId.isNotBlank(),
        calendarEventId = "", repeatEndDate = repeatEnd,
    )

    @Test
    fun `the month grid is six Monday-first weeks covering the month`() {
        val grid = DiaryCalendar.monthGrid(LocalDate(2026, 9, 13))
        assertEquals(6, grid.size)
        assertTrue(grid.all { it.size == 7 })
        assertEquals(LocalDate(2026, 8, 31), grid.first().first(), "September 2026 starts on a Tuesday")
        assertTrue(grid.all { it.first().dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(
            LocalDate(2026, 9, 7),
            DiaryCalendar.weekStart(LocalDate(2026, 9, 13)),
            "a Sunday belongs to the week before",
        )
    }

    @Test
    fun `stepping moves by the mode's unit`() {
        val anchor = LocalDate(2026, 1, 31)
        assertEquals(LocalDate(2026, 2, 28), DiaryCalendar.step(CalendarMode.Month, anchor, forward = true))
        assertEquals(LocalDate(2026, 1, 24), DiaryCalendar.step(CalendarMode.Week, anchor, forward = false))
        assertEquals(LocalDate(2026, 2, 1), DiaryCalendar.step(CalendarMode.Day, anchor, forward = true))
    }

    @Test
    fun `an event covers every day of its span, and a server occurrence is never re-expanded`() {
        val overnight = entry("e", start = at(2026, 9, 13, 22), end = at(2026, 9, 15, 1))
        assertEquals(
            listOf(day(2026, 9, 13), day(2026, 9, 14), day(2026, 9, 15)),
            DiaryCalendar.eventDayKeys(overnight, zone),
        )

        val occurrence = entry(
            "m", start = at(2026, 9, 13, 9), end = at(2026, 9, 13, 10), repeat = "daily",
            repeatEnd = at(2026, 9, 20, 8), occurrenceId = "m_1",
        )
        assertEquals(listOf(day(2026, 9, 13)), DiaryCalendar.eventDayKeys(occurrence, zone))
    }

    @Test
    fun `a legacy series row fans out to its repeat end, inclusive of the last day`() {
        // The end's time-of-day sits before the event's start on that day; the day still counts.
        val series = entry(
            "s",
            start = at(2026, 9, 13, 9),
            end = at(2026, 9, 13, 10),
            repeat = "daily",
            repeatEnd = at(2026, 9, 16, 8),
        )
        assertEquals(
            listOf(day(2026, 9, 13), day(2026, 9, 14), day(2026, 9, 15), day(2026, 9, 16)),
            DiaryCalendar.eventDayKeys(series, zone),
        )
    }

    @Test
    fun `day N of M counts only multi-day schedules`() {
        val shoot = block("b", "Shoot Day", day(2026, 9, 14), day(2026, 9, 15), day(2026, 9, 16))
        assertEquals(2 to 3, DiaryCalendar.dayIndex(shoot, day(2026, 9, 15), zone))
        assertNull(DiaryCalendar.dayIndex(block("one", "Prep", day(2026, 9, 14)), day(2026, 9, 14), zone))
    }

    @Test
    fun `rows keep the per-type count and mark the first row of each run`() {
        val rows = DiaryMath.explode(
            listOf(
                block("p", "Prep", day(2026, 9, 10), day(2026, 9, 11)),
                block("s", "Shoot Day", day(2026, 9, 12)),
            ),
            zone,
        )
        assertEquals(listOf(1, 2, 1), rows.map { it.dayNumber })
        assertEquals(
            listOf("p-${day(2026, 9, 10)}", "p-${day(2026, 9, 11)}", "s-${day(2026, 9, 12)}"),
            rows.map { it.key },
        )
    }

    @Test
    fun `filters narrow each view the way the web does`() {
        val prep = block("p", "Prep", day(2026, 9, 10), title = "Recce")
        val shoot = block("s", "Shoot Day", day(2026, 9, 12))
        val note = entry("n", kind = DiaryKind.Note, date = day(2026, 9, 12), title = "Rain cover")
        val event = entry(
            "e",
            date = day(2026, 9, 12),
            start = at(2026, 9, 12, 9),
            title = "Blocking",
            body = "rain plan",
        )

        val byType = DiaryFilter(typeName = "Prep")
        assertEquals(listOf(prep), byType.calendarBlocks(listOf(prep, shoot)))
        assertEquals(2, byType.calendarEvents(listOf(note, event)).size, "a type filter leaves entries alone")

        val eventsOnly = DiaryFilter(content = ContentFilter.Events)
        assertTrue(eventsOnly.calendarBlocks(listOf(prep, shoot)).isEmpty())
        assertEquals(listOf(event), eventsOnly.calendarEvents(listOf(note, event)))

        val search = DiaryFilter(search = "rain")
        assertEquals(listOf(note, event), search.calendarEvents(listOf(note, event)), "title or body")
        val rows = DiaryMath.explode(listOf(prep, shoot), zone)
        assertEquals(1, DiaryFilter(search = "sat sep 12").listRows(rows, zone).size, "the list matches dates")
        assertEquals(0, DiaryFilter(search = "rain").activeCount, "search does not count")
        assertEquals(2, DiaryFilter(typeName = "Prep", content = ContentFilter.Notes).activeCount)
    }

    @Test
    fun `the schedule form's three ways to set dates`() {
        val monday = LocalDate(2026, 9, 14)
        assertEquals(3, ScheduleDates.byDays(monday, 3).size)
        assertTrue(ScheduleDates.range(LocalDate(2026, 9, 16), monday).isEmpty(), "a backwards range is nothing")
        val weekdays = ScheduleDates.dayWise(monday, LocalDate(2026, 9, 27), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(listOf(14, 18, 21, 25), weekdays.map { it.day })
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            ScheduleDates.availableWeekdays(monday, LocalDate(2026, 9, 15)),
        )
        assertEquals(7, ScheduleDates.availableWeekdays(null, null).size)
    }

    @Test
    fun `dates read the way dayjs prints them`() {
        assertEquals("Sun, Sep 13", DiaryFormat.shortDay(day(2026, 9, 13), zone))
        assertEquals("Mon, Sep 07", DiaryFormat.listDate(day(2026, 9, 7), zone))
        assertEquals("Sunday, September 13, 2026", DiaryFormat.longDate(day(2026, 9, 13), zone))
        assertEquals("9:05 PM", DiaryFormat.time(at(2026, 9, 13, 21, 5), zone))
        assertEquals("12:00 AM", DiaryFormat.time(day(2026, 9, 13), zone))
        assertEquals("Sep 7 – Sep 13, 2026", DiaryFormat.weekTitle(LocalDate(2026, 9, 7), LocalDate(2026, 9, 13)))
        val span = block("b", "Shoot Day", day(2026, 12, 30), day(2027, 1, 2))
        assertEquals("2 day(s) · Dec 30, 2026 – Jan 2, 2027", DiaryFormat.spanLabel(span, zone))
    }

    @Test
    fun `the schedule as text numbers per type and dashes a day off`() {
        val rows = DiaryMath.explode(
            listOf(
                block("s", "Shoot Day", day(2026, 9, 14), title = "Stage 4"),
                block("o", "Day Off", day(2026, 9, 15)),
            ),
            zone,
        )
        val text = DiaryExports.scheduleText(rows, LocalDate(2026, 9, 13), zone)
        assertTrue(text.startsWith("PRODUCTION SCHEDULE\nPrepared: September 13, 2026"))
        assertTrue(text.contains("   1  |  Mon, Sep 14     |  SHOOT DAY    |  Stage 4"))
        assertTrue(text.contains("   —  |  Tue, Sep 15     |  DAY OFF      |  "))
        assertFalse(text.contains("PERSONAL"))
    }
}
