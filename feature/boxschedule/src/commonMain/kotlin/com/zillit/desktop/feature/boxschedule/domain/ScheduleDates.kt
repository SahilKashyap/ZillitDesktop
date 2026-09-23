package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** The schedule form's "How to set dates" tabs — `CreateScheduleModal`'s `dateTab`. */
enum class DateTab(private val labelKey: String, private val descriptionKey: String) {
    DateRange(S.cs_date_range, S.cs_date_range_desc),
    Calendar(S.calendar, S.desktop_bs_calendar_tab_desc),
    DayWise(S.cs_day_wise, S.desktop_bs_day_wise_desc),
    ;

    val label: String get() = str(labelKey)
    val description: String get() = str(descriptionKey)
}

/** Date Range's two ways in — "Set by Days" or "Set by End Date". */
enum class RangeMode(private val labelKey: String) {
    ByDays(S.cs_set_by_days),
    ByEndDate(S.cs_set_by_end_date),
    ;

    val label: String get() = str(labelKey)
}

/** The dates a schedule form describes, per tab — the web's `calendarDays` memo. */
object ScheduleDates {

    /** Sunday-first, as the web's weekday buttons run. */
    val WEEKDAYS: List<DayOfWeek> = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )

    const val MAX_DAYS = 365

    /** [count] consecutive days from [start]. */
    fun byDays(start: LocalDate?, count: Int): List<LocalDate> =
        if (start == null || count <= 0) emptyList() else (0 until count).map { start.plus(it, DateTimeUnit.DAY) }

    /** Every day from [start] to [end] inclusive; nothing when the range runs backwards. */
    fun range(start: LocalDate?, end: LocalDate?): List<LocalDate> {
        if (start == null || end == null || end < start) return emptyList()
        return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= end }.toList()
    }

    /** The days in the range that fall on one of [weekdays]. */
    fun dayWise(start: LocalDate?, end: LocalDate?, weekdays: Set<DayOfWeek>): List<LocalDate> =
        if (weekdays.isEmpty()) emptyList() else range(start, end).filter { it.dayOfWeek in weekdays }

    /** Which weekday buttons the range leaves pickable; every one when there is no range yet. */
    fun availableWeekdays(start: LocalDate?, end: LocalDate?): Set<DayOfWeek> {
        val days = range(start, end)
        return if (start == null || end == null) WEEKDAYS.toSet() else days.map { it.dayOfWeek }.toSet()
    }

    /**
     * The tab an existing block reopens on: an ad-hoc pick, or any gap in its
     * dates, is the Calendar tab — a range picker cannot say "Apr 1, 5, 8".
     */
    fun initialTab(block: ScheduleBlock?): DateTab = when {
        block == null -> DateTab.DateRange
        block.dateRangeType == "by_dates" -> DateTab.Calendar
        block.calendarDays.size > 1 && DiaryMath.hasGaps(block.calendarDays) -> DateTab.Calendar
        else -> DateTab.DateRange
    }

    /** The dates as local-midnight instants, ascending and distinct — what the wire takes. */
    fun toWire(days: List<LocalDate>, zone: TimeZone): List<Long> =
        days.distinct().sorted().map { DiaryCalendar.startOf(it, zone) }
}
