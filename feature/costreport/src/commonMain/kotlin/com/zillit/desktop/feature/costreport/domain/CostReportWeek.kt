package com.zillit.desktop.feature.costreport.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A production week: Monday 00:00 → Sunday 23:59:59.999, in the machine's zone like the web. */
data class WeekWindow(
    val startMs: Long,
    val endMs: Long,
    val number: Int,
    val monday: LocalDate,
    val sunday: LocalDate,
) {
    /**
     * `Wk 21 · w/e 23 May 2026`.
     *
     * The week *ends* on the Saturday in this label — the web formats
     * `end − 1 day` — while the window itself runs through Sunday. The two
     * are kept exactly as the web has them, because the label names locks,
     * posts and exports that the web wrote first.
     */
    val label: String get() = "Wk $number · w/e ${CrDates.dayMonthYear(sunday.minus(1, DateTimeUnit.DAY))}"

    /** `04 May–10 May 2026` — the period stepper, the loader and the lock dialog. */
    val range: String get() = "${CrDates.dayMonth(monday)}–${CrDates.dayMonth(sunday)} ${sunday.year}"

    /** `2026-05-17`: the Sunday, which is what a weekly ETC version is filed under. */
    val weekEnding: String get() = sunday.toString()

    fun contains(ms: Long): Boolean = ms in startMs..endMs

    fun previous(zone: TimeZone = TimeZone.currentSystemDefault()): WeekWindow =
        weekStarting(monday.minus(DAYS_PER_WEEK, DateTimeUnit.DAY), zone)

    fun next(zone: TimeZone = TimeZone.currentSystemDefault()): WeekWindow =
        weekStarting(monday.plus(DAYS_PER_WEEK, DateTimeUnit.DAY), zone)
}

/**
 * Week helpers (spec §4.8): `mondayOf(now)` with Sunday as day 7, and
 * `weekN = (monday − mondayOf(Jan 1)) / 7d + 1`.
 */
fun currentWeek(nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): WeekWindow {
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return weekStarting(mondayOf(today), zone)
}

/** The week that starts on [monday] (any date is snapped back to its Monday). */
fun weekStarting(monday: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): WeekWindow {
    val start = mondayOf(monday)
    val sunday = start.plus(DAYS_TO_SUNDAY, DateTimeUnit.DAY)
    val jan1Monday = mondayOf(LocalDate(start.year, 1, 1))
    return WeekWindow(
        startMs = start.atStartOfDayIn(zone).toEpochMilliseconds(),
        endMs = sunday.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1,
        number = jan1Monday.daysUntil(start) / DAYS_PER_WEEK + 1,
        monday = start,
        sunday = sunday,
    )
}

fun mondayOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

private const val DAYS_PER_WEEK = 7
private const val DAYS_TO_SUNDAY = 6

/** The tool's date renderings, all in the machine's zone like the web. */
object CrDates {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** `24 May 2026`. */
    fun dayMonthYear(date: LocalDate): String = "${date.day} ${MONTHS[date.month.number - 1]} ${date.year}"

    /** `04 May` — two-digit day, as the web's `{ day: "2-digit", month: "short" }`. */
    fun dayMonth(date: LocalDate): String = "${date.day.pad()} ${MONTHS[date.month.number - 1]}"

    /** `Tuesday, 19 May 2026`. */
    fun weekdayDate(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val date = local(ms, zone).date
        return "${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]}, ${dayMonthYear(date)}"
    }

    /** `05 May 2026`; blank when missing. */
    fun date(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val date = ms?.let { local(it, zone).date } ?: return ""
        return "${date.day.pad()} ${MONTHS[date.month.number - 1]} ${date.year}"
    }

    /** `05 May 2026, 14:07`; blank when missing. */
    fun dateTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val moment = ms?.let { local(it, zone) } ?: return ""
        return "${date(ms, zone)}, ${moment.hour.pad()}:${moment.minute.pad()}"
    }

    /** `05 May, 14:07` — the version picker's saved-at stamp. */
    fun dayMonthTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val moment = ms?.let { local(it, zone) } ?: return ""
        return "${dayMonth(moment.date)}, ${moment.hour.pad()}:${moment.minute.pad()}"
    }

    /** `05 May 2026 → 11 May 2026`, either side blank when unknown. */
    fun range(startMs: Long?, endMs: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        "${date(startMs, zone).ifBlank { "—" }} → ${date(endMs, zone).ifBlank { "—" }}"

    /** Local midnight of a date, and the last millisecond of it — a custom post's two bounds. */
    fun startOfDay(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        date.atStartOfDayIn(zone).toEpochMilliseconds()

    fun endOfDay(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1

    fun localDate(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = local(ms, zone).date

    private fun local(ms: Long, zone: TimeZone): LocalDateTime =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

    private fun Int.pad(): String = toString().padStart(2, '0')
}
