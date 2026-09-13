package com.zillit.desktop.feature.boxschedule.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The page's two views — the web's `activeView` Segmented. Gantt stays hidden there too. */
enum class DiaryView(val label: String, val hint: String) {
    Calendar("Calendar View", "Monthly calendar grid"),
    List("List View", "Table with expandable rows"),
}

/** The calendar's zoom — `box-schedule-calendar-mode`. */
enum class CalendarMode(val label: String, val hint: String) {
    Month("Month", "Full month grid with all weeks"),
    Week("Week", "One week at a time with bigger cells"),
    Day("Day", "Single day focused view with full details"),
}

/** The list's grouping — `box-schedule-list-mode`. */
enum class ListMode(val label: String, val hint: String) {
    ByDate("By Date", "One row per calendar day in order"),
    BySchedule("By Schedule", "Grouped by each schedule block"),
}

/**
 * The calendar's date rules, transcribed from `CalendarView.jsx`.
 *
 * Every date on the wire is an epoch-millisecond instant; the calendar
 * buckets by the machine's local day, as dayjs does in the browser. Weeks
 * start on Monday (`dayjs.updateLocale('en', { weekStart: 1 })`).
 */
object DiaryCalendar {

    /** `dayjs(ms).startOf('day').valueOf()`. */
    fun dayKey(ms: Long, zone: TimeZone): Long = startOf(dateOf(ms, zone), zone)

    fun dateOf(ms: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

    fun startOf(date: LocalDate, zone: TimeZone): Long = date.atStartOfDayIn(zone).toEpochMilliseconds()

    fun weekStart(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** Six Monday-first weeks covering [month] — the grid antd's full-screen calendar draws. */
    fun monthGrid(month: LocalDate): List<List<LocalDate>> {
        val first = LocalDate(month.year, month.month, 1)
        val start = weekStart(first)
        return (0 until WEEKS_SHOWN).map { week ->
            (0 until DAYS_IN_WEEK).map { day -> start.plus(week * DAYS_IN_WEEK + day, DateTimeUnit.DAY) }
        }
    }

    fun weekOf(anchor: LocalDate): List<LocalDate> {
        val start = weekStart(anchor)
        return (0 until DAYS_IN_WEEK).map { start.plus(it, DateTimeUnit.DAY) }
    }

    /** Previous / next month, week or day from [anchor]. */
    fun step(mode: CalendarMode, anchor: LocalDate, forward: Boolean): LocalDate {
        val sign = if (forward) 1 else -1
        return when (mode) {
            CalendarMode.Month -> anchor.plus(DatePeriod(months = sign))
            CalendarMode.Week -> anchor.plus(sign * DAYS_IN_WEEK, DateTimeUnit.DAY)
            CalendarMode.Day -> anchor.plus(sign, DateTimeUnit.DAY)
        }
    }

    fun sameMonth(a: LocalDate, b: LocalDate): Boolean = a.year == b.year && a.month == b.month

    /**
     * Every schedule on every date it covers — the web's `dayLookup`, input
     * order kept inside a day.
     */
    fun schedulesByDay(blocks: List<ScheduleBlock>, zone: TimeZone): Map<Long, List<ScheduleBlock>> {
        val lookup = linkedMapOf<Long, MutableList<ScheduleBlock>>()
        blocks.forEach { block ->
            block.calendarDays.map { dayKey(it, zone) }.distinct().forEach { key ->
                lookup.getOrPut(key) { mutableListOf() }.add(block)
            }
        }
        return lookup
    }

    /**
     * The days one event renders on — `expandEventDayKeys`.
     *
     * The occurrence's own span fans out across every day it covers. The
     * server already expands recurring series one row per occurrence, so the
     * repeat loop runs only for a row that names a cadence without being an
     * expanded occurrence: an older backend's single series row.
     */
    fun eventDayKeys(event: DiaryEvent, zone: TimeZone): List<Long> {
        val start = event.anchor.takeIf { it > 0 } ?: return emptyList()
        val end = event.endDateTime.takeIf { it > 0 } ?: start
        val duration = maxOf(0L, end - start)
        val serverExpanded = event.isRecurringInstance || event.occurrenceId.isNotBlank()
        val cadence = if (serverExpanded) null else REPEAT_CADENCE[event.repeatStatus]
        val repeatEndKey = if (cadence != null && event.repeatEndDate > 0) dayKey(event.repeatEndDate, zone) else null

        val keys = mutableListOf<Long>()
        var occurrence = dateOf(start, zone)
        var occurrenceStart = start
        repeat(MAX_OCCURRENCES) {
            val lastDay = dateOf(occurrenceStart + duration, zone)
            var cursor = occurrence
            var span = 0
            while (cursor <= lastDay && span < MAX_SPAN_DAYS) {
                keys += startOf(cursor, zone)
                cursor = cursor.plus(1, DateTimeUnit.DAY)
                span++
            }
            if (cadence == null || repeatEndKey == null) return keys.distinct()
            occurrence = occurrence.plus(cadence)
            occurrenceStart = startOf(occurrence, zone) + (start - dayKey(start, zone))
            if (startOf(occurrence, zone) > repeatEndKey) return keys.distinct()
        }
        return keys.distinct()
    }

    /** Events (never notes) bucketed onto every day they cover, start-time order inside a day. */
    fun eventsByDay(events: List<DiaryEvent>, zone: TimeZone): Map<Long, List<DiaryEvent>> {
        val map = linkedMapOf<Long, MutableList<DiaryEvent>>()
        events.filter { it.kind == DiaryKind.Event }
            .sortedBy { it.anchor }
            .forEach { event -> eventDayKeys(event, zone).forEach { map.getOrPut(it) { mutableListOf() }.add(event) } }
        return map
    }

    /** Notes on their own day — the start wins over the bare date, as the web buckets. */
    fun notesByDay(events: List<DiaryEvent>, zone: TimeZone): Map<Long, List<DiaryEvent>> =
        events.filter { it.kind == DiaryKind.Note && it.anchor > 0 }.groupBy { dayKey(it.anchor, zone) }

    /**
     * "Day N of M" for a multi-day schedule — `dayIndexInSchedule`. Null for
     * a one-day schedule, where the badge would only be noise.
     */
    fun dayIndex(block: ScheduleBlock, dayKey: Long, zone: TimeZone): Pair<Int, Int>? {
        val days = block.calendarDays
        if (days.size <= 1) return null
        val index = days.indexOfFirst { dayKey(it, zone) == dayKey }
        return if (index >= 0) (index + 1) to days.size else null
    }

    private val REPEAT_CADENCE = mapOf(
        "daily" to DatePeriod(days = 1),
        "weekly" to DatePeriod(days = DAYS_IN_WEEK),
        "monthly" to DatePeriod(months = 1),
        "yearly" to DatePeriod(years = 1),
    )

    const val DAYS_IN_WEEK = 7
    private const val WEEKS_SHOWN = 6
    private const val MAX_OCCURRENCES = 800
    private const val MAX_SPAN_DAYS = 400
}

/**
 * The day an entry is filed under on its schedule — its `date`, the web's
 * `Number(e.date) === Number(day.singleDate)` — falling back to the start.
 */
fun DiaryEvent.filedOn(zone: TimeZone): Long = DiaryCalendar.dayKey(date.takeIf { it > 0 } ?: anchor, zone)
