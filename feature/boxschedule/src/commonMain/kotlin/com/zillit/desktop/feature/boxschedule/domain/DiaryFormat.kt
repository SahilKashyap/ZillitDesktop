package com.zillit.desktop.feature.boxschedule.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The diary's dates and times as the web prints them with dayjs.
 *
 * One formatter so a date reads the same in the calendar header, the list,
 * the drawers and the history: `ddd, MMM D` is always "Sun, Sep 13".
 */
@Suppress("TooManyFunctions") // One function per dayjs pattern the web prints.
object DiaryFormat {

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun monthName(date: LocalDate): String = MONTHS[date.month.ordinal]
    fun monthShort(date: LocalDate): String = monthName(date).take(ABBREVIATION)
    fun weekday(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.ordinal]
    fun weekdayShort(date: LocalDate): String = weekday(date).take(ABBREVIATION)

    /** `MMMM YYYY` — "September 2026". */
    fun monthTitle(date: LocalDate): String = "${monthName(date)} ${date.year}"

    /** `MMM D` – `MMM D, YYYY` — "Sep 7 – Sep 13, 2026". */
    fun weekTitle(start: LocalDate, end: LocalDate): String =
        "${monthShort(start)} ${start.day} – ${monthShort(end)} ${end.day}, ${end.year}"

    /** `dddd, MMM D, YYYY` — "Sunday, Sep 13, 2026". */
    fun dayTitle(date: LocalDate): String = "${weekday(date)}, ${monthShort(date)} ${date.day}, ${date.year}"

    /** `dddd, MMMM D, YYYY` — "Sunday, September 13, 2026". */
    fun longDate(date: LocalDate): String = "${weekday(date)}, ${monthName(date)} ${date.day}, ${date.year}"
    fun longDate(ms: Long, zone: TimeZone): String = longDate(date(ms, zone))

    /** `MMMM D, YYYY` — "September 13, 2026". */
    fun fullDate(date: LocalDate): String = "${monthName(date)} ${date.day}, ${date.year}"
    fun fullDate(ms: Long, zone: TimeZone): String = fullDate(date(ms, zone))

    /** `MMM D, YYYY` — "Sep 13, 2026". */
    fun mediumDate(date: LocalDate): String = "${monthShort(date)} ${date.day}, ${date.year}"
    fun mediumDate(ms: Long, zone: TimeZone): String = mediumDate(date(ms, zone))

    /** `ddd, MMM D, YYYY` — "Sun, Sep 13, 2026". */
    fun shortWeekdayDate(ms: Long, zone: TimeZone): String {
        val d = date(ms, zone)
        return "${weekdayShort(d)}, ${monthShort(d)} ${d.day}, ${d.year}"
    }

    /** `ddd, MMM D` — "Sun, Sep 13". */
    fun shortDay(date: LocalDate): String = "${weekdayShort(date)}, ${monthShort(date)} ${date.day}"
    fun shortDay(ms: Long, zone: TimeZone): String = shortDay(date(ms, zone))

    /** `ddd, MMM DD` — "Sun, Sep 07", the list's DATE column. */
    fun listDate(ms: Long, zone: TimeZone): String {
        val d = date(ms, zone)
        return "${weekdayShort(d)}, ${monthShort(d)} ${d.day.pad()}"
    }

    /** `MMM D` — "Sep 13". */
    fun monthDay(date: LocalDate): String = "${monthShort(date)} ${date.day}"
    fun monthDay(ms: Long, zone: TimeZone): String = monthDay(date(ms, zone))

    /** `ddd MMM DD YYYY`, lower-cased — what the web's search box matches dates against. */
    fun searchable(ms: Long, zone: TimeZone): String {
        val d = date(ms, zone)
        return "${weekdayShort(d)} ${monthShort(d)} ${d.day.pad()} ${d.year}".lowercase()
    }

    /** `h:mm A` — "9:05 AM". */
    fun time(ms: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        val hour = t.hour % HALF_DAY
        val meridiem = if (t.hour >= HALF_DAY) "PM" else "AM"
        return "${if (hour == 0) HALF_DAY else hour}:${t.minute.pad()} $meridiem"
    }

    /** "Full Day", "9:00 AM – 10:00 AM", or just the start. */
    fun timeRange(event: DiaryEvent, zone: TimeZone): String = when {
        event.fullDay -> "Full Day"
        event.startDateTime > 0 && event.endDateTime > 0 ->
            "${time(event.startDateTime, zone)} – ${time(event.endDateTime, zone)}"
        event.startDateTime > 0 -> time(event.startDateTime, zone)
        else -> ""
    }

    /** `MMM D · h:mm A` — a history row's stamp. */
    fun stamp(ms: Long, zone: TimeZone): String = "${monthDay(ms, zone)} · ${time(ms, zone)}"

    /** `MMM D, YYYY · h:mm A`. */
    fun fullStamp(ms: Long, zone: TimeZone): String = "${mediumDate(ms, zone)} · ${time(ms, zone)}"

    /** `ddd, MMM D · h:mm A` — a history detail's when. */
    fun dayStamp(ms: Long, zone: TimeZone): String = "${shortDay(ms, zone)} · ${time(ms, zone)}"

    /**
     * "14 day(s) · Sep 4 – Sep 18, 2026" — `scheduleSpanLabel`, the identity
     * line under a type name in the scope prompts.
     */
    fun spanLabel(block: ScheduleBlock, zone: TimeZone): String {
        val count = block.calendarDays.size.coerceAtLeast(1)
        if (block.calendarDays.isEmpty()) return "$count day(s)"
        val start = date(block.firstDay, zone)
        val end = date(block.lastDay, zone)
        val range = when {
            start == end -> mediumDate(start)
            start.year == end.year -> "${monthDay(start)} – ${mediumDate(end)}"
            else -> "${mediumDate(start)} – ${mediumDate(end)}"
        }
        return "$count day(s) · $range"
    }

    /** "Sep 4 – Sep 18, 2026", or the one long date for a single day. */
    fun blockRange(block: ScheduleBlock, zone: TimeZone): String {
        val start = date(block.firstDay, zone)
        val end = date(block.lastDay, zone)
        return if (block.calendarDays.size <= 1) longDate(start) else "${monthDay(start)} – ${mediumDate(end)}"
    }

    /** "Daily until Sep 30, 2026". */
    fun repeatLine(event: DiaryEvent, zone: TimeZone): String {
        val cadence = event.repeatStatus.replaceFirstChar { it.uppercase() }
        return if (event.repeatEndDate > 0) "$cadence until ${mediumDate(event.repeatEndDate, zone)}" else cadence
    }

    fun callTypeLabel(callType: String): String = when (callType) {
        "meet_in_person" -> "Meet In Person"
        "meet_in_person_call" -> "Meet in Person & Call"
        "audio" -> "Audio Call"
        "video" -> "Video Call"
        else -> callType.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    /** The shorter badge on a schedule's event row. */
    fun callBadge(callType: String): String = when (callType) {
        "meet_in_person" -> "In Person"
        "meet_in_person_call" -> "In Person & Call"
        "audio" -> "Audio Call"
        else -> "Video Call"
    }

    fun reminderLabel(reminder: String): String = REMINDERS.firstOrNull { it.first == reminder }?.second
        ?: reminder.replace('_', ' ')

    val REMINDERS = listOf(
        "none" to "No reminder",
        "at_time" to "At the time of event",
        "5min" to "5 minutes before",
        "15min" to "15 minutes before",
        "30min" to "30 minutes before",
        "1hr" to "1 hour before",
        "1day" to "1 day before",
    )

    val CALL_TYPES = listOf(
        "meet_in_person" to "Meet In Person",
        "meet_in_person_call" to "Meet in Person & Call",
        "audio" to "Audio Call",
        "video" to "Video Call",
    )

    val REPEATS = listOf("none" to "No repeat", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly")

    /** The web's short list, with the machine's own zone added when it is not on it. */
    val TIMEZONES = listOf(
        "Asia/Calcutta", "Asia/Kolkata", "Asia/Dubai", "Asia/Tokyo", "Asia/Shanghai", "Asia/Singapore",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Moscow",
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        "America/Toronto", "America/Sao_Paulo",
        "Australia/Sydney", "Australia/Melbourne",
        "Pacific/Auckland", "Africa/Cairo", "Africa/Johannesburg",
        "UTC",
    )

    fun timezoneLabel(zoneId: String): String = zoneId.replace('_', ' ')

    private fun date(ms: Long, zone: TimeZone): LocalDate = DiaryCalendar.dateOf(ms, zone)

    private fun Int.pad(): String = toString().padStart(2, '0')

    private const val ABBREVIATION = 3
    private const val HALF_DAY = 12
}
