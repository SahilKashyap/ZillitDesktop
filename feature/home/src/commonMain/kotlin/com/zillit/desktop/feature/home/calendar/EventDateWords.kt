package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * How the detail popover words a date.
 *
 * Transcribed from the web's `calendarV3/utils/date.js` — `formatRange`,
 * `formatDate`, `formatDateTime` and dayjs's `week()` — because the same event
 * is read on three clients and a date that disagreed between them would send
 * somebody to set on the wrong day. Separate from [CalendarEvent] only because
 * that file is already at its function budget.
 */

/**
 * "Wed, Oct 14 11:30 AM - 8:00 PM" — the web's `formatRange`.
 *
 * One day collapses to a single date and two clock times. An overnight event
 * prints the date at both ends instead, because "10:00 PM - 4:00 AM" on one
 * line is the oldest way to miss a night shoot by a day.
 */
fun CalendarEvent.rangeLabel(zone: TimeZone): String {
    val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone)
    val end = Instant.fromEpochMilliseconds(endMillis.takeIf { it > startMillis } ?: startMillis)
        .toLocalDateTime(zone)
    val startDay = "${start.date.dayOfWeek.name.abbreviated()}, ${start.date.dayAndMonth()}"

    return when {
        isAllDay -> "$startDay ${str(S.all_day)}"
        start.date == end.date -> "$startDay ${start.clock()} - ${end.clock()}"
        else -> "${start.date.dayAndMonth()}, ${start.clock()} - " +
            "${end.date.dayAndMonth()}, ${end.clock()}"
    }
}

/** "Wed, Oct 14, 2026" — the web's `formatDate`. */
fun CalendarEvent.fullDateLabel(zone: TimeZone): String =
    Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date.longDateText()

/** "Wed, Oct 14, 2026 11:30 AM" — the web's `formatDateTime`. */
fun dateTimeLabel(millis: Long, zone: TimeZone): String {
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "${at.date.longDateText()} ${at.clock()}"
}

/**
 * The week of the year, counted the way dayjs's `week()` counts it: weeks
 * open on Sunday and week 1 is whichever one holds 1 January.
 *
 * Matching that exactly matters. The number sits beside the date in the detail
 * popover on every client, and one that disagreed by one with the web's would
 * be worse than no number at all — call sheets are filed by week.
 */
fun weekOfYear(date: LocalDate): Int {
    // Days from the Sunday that opens week 1. Sunday is 0 here, so ISO's
    // Monday 1 stays 1 and ISO's Sunday 7 wraps to 0.
    val week = MonthGrid.DAYS_PER_WEEK
    val lead = LocalDate(date.year, 1, 1).dayOfWeek.isoDayNumber % week
    return (date.dayOfYear - 1 + lead) / week + 1
}

/** "Oct 14" — a date with no year, for a range that already states one. */
private fun LocalDate.dayAndMonth(): String = "${month.name.abbreviated()} $day"

private fun LocalDate.longDateText(): String =
    "${dayOfWeek.name.abbreviated()}, ${dayAndMonth()}, $year"

/** "11:30 AM" — the twelve-hour clock the web and the phones both print. */
private fun LocalDateTime.clock(): String {
    val onTheClock = (hour % NOON).takeIf { it != 0 } ?: NOON
    val meridiem = str(if (hour < NOON) S.desktop_inv_time_am else S.desktop_inv_time_pm)
    return "$onTheClock:${minute.pad()} $meridiem"
}

/** Noon — the hour both the wrap and the meridiem turn on. */
private const val NOON = 12
