package com.zillit.desktop.feature.home.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One entry on the production calendar.
 *
 * `EventDetailItem` on Android. Times arrive as epoch millis in the event's own
 * [timezone]; a shoot spanning territories genuinely has events in several.
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean = false,
    val location: String? = null,
    /**
     * Where the location actually is — the web sends these beside the
     * description (`AddCalendarEvent.jsx:433-438`); null when the location
     * was typed rather than picked on the map.
     */
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val description: String? = null,
    val colorHex: String? = null,
    val timezone: String? = null,
    val status: String? = null,
    val invitedCount: Int = 0,
    /** Who made it, for the detail popover. */
    val creatorName: String? = null,
    val creatorId: String? = null,
    /** Minutes before the start. 0 means no reminder. */
    val reminderMinutes: Int = 0,
    /** A video call attached to the event, when the production uses them. */
    val hasCall: Boolean = false,
    /** Part of a repeating series. */
    val isRecurring: Boolean = false,
    /** The repeat rule, for editing it. */
    val recurrence: Recurrence = Recurrence(),
    /** Members or personal — the server's `type`. */
    val audience: EventAudience = EventAudience.Members,
    /** How the people on it are meeting; null on a personal event. */
    val callType: CallType? = null,
    /** Crew on the event, so reopening it does not uninvite them. */
    val inviteeIds: Set<String> = emptySet(),
    /** Guests invited by address rather than by crew record. */
    val externalEmails: List<String> = emptyList(),
) {
    /** Where this user stands on it, read from whatever the server called it. */
    val inviteStatus: InviteStatus get() = inviteStatusOf(status)

    /** Never prints the description — events carry location and cast detail. */
    override fun toString(): String = "CalendarEvent(id=$id, allDay=$isAllDay, start=$startMillis)"
}

/** Which calendar layout is on screen. */
enum class CalendarViewMode(val label: String) {
    Month("Month"),
    Week("Week"),
    Day("Day"),
}

/**
 * The days a month grid must draw.
 *
 * Always whole weeks, so the grid is rectangular — a month view that ended
 * mid-row would leave a ragged edge and shift every subsequent column.
 *
 * Leading and trailing days belong to the neighbouring months and are drawn
 * dimmed; [inMonth] says which.
 */
data class MonthGrid(
    val month: LocalDate,
    val days: List<GridDay>,
) {
    data class GridDay(val date: LocalDate, val inMonth: Boolean)

    /** Rows of seven, for a `LazyVerticalGrid` or a column of rows. */
    val weeks: List<List<GridDay>> get() = days.chunked(DAYS_PER_WEEK)

    companion object {
        const val DAYS_PER_WEEK = 7
    }
}

/**
 * Builds the grid for the month containing [anchor].
 *
 * [weekStart] is configurable because productions run internationally and a
 * week starting Sunday versus Monday changes which row a shoot day lands on.
 */
fun monthGrid(anchor: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): MonthGrid {
    val first = LocalDate(anchor.year, anchor.month, 1)
    val lead = ((first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber) + WEEK) % WEEK

    val gridStart = first.minusDays(lead)
    val daysInMonth = first.plus(1, kotlinx.datetime.DateTimeUnit.MONTH)
        .minusDays(1).day

    // Whole weeks covering the month, then padded to a full final row.
    val span = lead + daysInMonth
    // Rounded up to whole weeks.
    val total = ((span + WEEK - 1) / WEEK) * WEEK

    return MonthGrid(
        month = first,
        days = (0 until total).map { offset ->
            val date = gridStart.plusDays(offset)
            MonthGrid.GridDay(date = date, inMonth = date.month == first.month && date.year == first.year)
        },
    )
}

/**
 * Events overlapping [date], in start order.
 *
 * Overlap, not containment: a night shoot running 22:00–04:00 belongs to both
 * days, and a filter on start time alone would drop it from the second.
 */
fun List<CalendarEvent>.on(date: LocalDate, zone: TimeZone): List<CalendarEvent> {
    val dayStart = date.atStartOfDayMillis(zone)
    val dayEnd = date.plusDays(1).atStartOfDayMillis(zone)

    return filter { event ->
        val end = if (event.endMillis > 0) event.endMillis else event.startMillis
        event.startMillis < dayEnd && end >= dayStart
    }.sortedWith(compareByDescending<CalendarEvent> { it.isAllDay }.thenBy { it.startMillis })
}

/** The seven dates of the week containing [anchor]. */
fun weekOf(anchor: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate> {
    val back = ((anchor.dayOfWeek.isoDayNumber - weekStart.isoDayNumber) + WEEK) % WEEK
    val start = anchor.minusDays(back)
    return (0 until MonthGrid.DAYS_PER_WEEK).map { start.plusDays(it) }
}

/** "Mon 4 Aug 2026" — the date an event falls on, for the detail popover. */
fun CalendarEvent.dateLabel(zone: TimeZone): String {
    val date = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
    val weekday = date.dayOfWeek.name.take(ABBREVIATION).lowercase().replaceFirstChar { it.uppercase() }
    val month = date.month.name.take(ABBREVIATION).lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday ${date.day} $month ${date.year}"
}

/** `HH:mm` in [zone], or "All day". */
fun CalendarEvent.timeLabel(zone: TimeZone): String {
    if (isAllDay) return "All day"
    val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone)
    val end = Instant.fromEpochMilliseconds(endMillis.takeIf { it > 0 } ?: startMillis)
        .toLocalDateTime(zone)
    return "${start.hhmm()} – ${end.hhmm()}"
}

/** The time label with its day in front — what a reschedule question needs. */
fun CalendarEvent.dayAndTimeLabel(zone: TimeZone): String {
    val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone)
    val weekday = start.date.dayOfWeek.name.abbreviated()
    val month = start.date.month.name.abbreviated()
    return "$weekday ${start.date.day} $month, ${timeLabel(zone)}"
}

/** "MONDAY" → "Mon" — the three-letter shape every calendar prints. */
private fun String.abbreviated(): String =
    take(DAY_ABBREVIATION).lowercase().replaceFirstChar { it.uppercase() }

private const val DAY_ABBREVIATION = 3

private fun LocalDateTime.hhmm(): String = "${hour.pad()}:${minute.pad()}"

private fun Int.pad(): String = if (this < TEN) "0$this" else "$this"

private const val TEN = 10

/** Days in a week — the modulus all the grid arithmetic turns on. */
private const val WEEK = MonthGrid.DAYS_PER_WEEK

private val DayOfWeek.isoDayNumber: Int get() = ordinal + 1

internal fun LocalDate.plusDays(days: Int): LocalDate =
    plus(days, kotlinx.datetime.DateTimeUnit.DAY)

private fun LocalDate.minusDays(days: Int): LocalDate =
    plus(-days, kotlinx.datetime.DateTimeUnit.DAY)

/** Midnight at the start of this date, in [zone]. */
internal fun LocalDate.atStartOfDayMillisIn(zone: TimeZone): Long = startOfDayMillis(zone)

/** Midnight at the start of this date, in [zone]. */
internal fun LocalDate.startOfDayMillis(zone: TimeZone): Long = atStartOfDayMillis(zone)

private fun LocalDate.atStartOfDayMillis(zone: TimeZone): Long =
    atStartOfDayIn(zone).toEpochMilliseconds()

/** "Mon", "Aug" — three letters, as every call sheet prints them. */
private const val ABBREVIATION = 3
