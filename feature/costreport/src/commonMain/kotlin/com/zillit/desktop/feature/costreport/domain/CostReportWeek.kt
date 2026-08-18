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

/** The production week the Current CR runs over: Monday 00:00 → Sunday 23:59:59.999, local. */
data class WeekWindow(
    val startMs: Long,
    val endMs: Long,
    val number: Int,
    val monday: LocalDate,
    val sunday: LocalDate,
) {
    /** `Wk 21 · w/e 24 May 2026`. */
    val label: String get() = "Wk $number · w/e ${CrDates.dayMonthYear(sunday)}"
}

/**
 * Week helpers (spec §4.8): `mondayOf(now)` with Sunday as day 7, and
 * `weekN = (monday − mondayOf(Jan 1)) / 7d + 1`.
 */
fun currentWeek(nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): WeekWindow {
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    val monday = mondayOf(today)
    val sunday = monday.plus(DAYS_TO_SUNDAY, DateTimeUnit.DAY)
    val jan1Monday = mondayOf(LocalDate(today.year, 1, 1))
    return WeekWindow(
        startMs = monday.atStartOfDayIn(zone).toEpochMilliseconds(),
        endMs = sunday.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1,
        number = jan1Monday.daysUntil(monday) / DAYS_PER_WEEK + 1,
        monday = monday,
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

    /** `05 May 2026 → 11 May 2026`, either side blank when unknown. */
    fun range(startMs: Long?, endMs: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        "${date(startMs, zone).ifBlank { "—" }} → ${date(endMs, zone).ifBlank { "—" }}"

    private fun local(ms: Long, zone: TimeZone): LocalDateTime =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

    private fun Int.pad(): String = toString().padStart(2, '0')
}
