package com.zillit.desktop.feature.addashboard.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The AD dashboard's three kinds of time value, and the rule for each.
 *
 * The module works in **UTC wall-clock** throughout, deliberately: a 07:30
 * call is stored as `…T07:30:00Z` and shows 07:30 to the AD, to the artiste
 * and to payroll, whatever zone each of them is in. The alternative — render
 * through the record's own `timezone` field — shows the AD's zone to the
 * artiste, which the web stopped doing on 2026-08-04.
 *
 * | Kind | Fields | Write | Read |
 * |---|---|---|---|
 * | Wall-clock time | `call_time`, `wrap_time`, meal `start_time`/`end_time` | [hhmmToUtcEpoch] | [epochToHhMm] |
 * | Calendar date | `shoot_date` | [utcMidnight] | [formatDayShort] |
 * | Audit instant | `signed_at`, `submitted_at`, `created_at` | the real clock | [formatInstant] — viewer-local |
 *
 * Never format an audit instant with a calendar-date helper: those are
 * UTC-pinned and would show the UTC day rather than the viewer's.
 */
@Suppress("TooManyFunctions") // One reader or writer per kind of value; the table above is the index.
object AdDates {

    const val MILLIS_PER_DAY = 86_400_000L
    private const val HOURS_PER_DAY = 24
    private const val MINUTES_PER_HOUR = 60
    private const val TWO_DIGITS = 2
    private const val YEAR_DIGITS = 4
    private const val YMD_PARTS = 3
    private const val MONTHS_IN_YEAR = 12
    private const val MAX_DAY_OF_MONTH = 31

    /** The stamp every write carries, so a time reads back the same for everyone. */
    const val TIMEZONE = "UTC"

    /**
     * [millis] flattened to the UTC midnight that contains it.
     *
     * `floorMod` rather than `%` so a pre-epoch date floors downwards instead
     * of jumping a day forward.
     */
    fun utcMidnight(millis: Long): Long {
        val remainder = ((millis % MILLIS_PER_DAY) + MILLIS_PER_DAY) % MILLIS_PER_DAY
        return millis - remainder
    }

    /** Today at UTC midnight — the web's `todayMidnightTz('UTC')`. */
    fun todayUtc(now: Long): Long = utcMidnight(now)

    /** [days] UTC days after [shootDate], still at UTC midnight. */
    fun addDays(shootDate: Long, days: Int): Long = shootDate + days * MILLIS_PER_DAY

    /**
     * `"HH:mm"` anchored on the day being edited → the epoch that reads back as
     * that same `"HH:mm"` under UTC.
     *
     * Anchored on [shootDate], never on today: a helper anchored on today
     * stamps a past day's edit onto today's date, so the wrap falls before the
     * call and the pay engine pays basic only.
     */
    fun hhmmToUtcEpoch(shootDate: Long?, hhmm: String?): Long? {
        if (shootDate == null || hhmm.isNullOrBlank()) return null
        val parts = hhmm.trim().split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0 until HOURS_PER_DAY }
        val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until MINUTES_PER_HOUR }
        if (hour == null || minute == null) return null
        val date = Instant.fromEpochMilliseconds(shootDate).toLocalDateTime(TimeZone.UTC).date
        return LocalDateTime(date, LocalTime(hour, minute))
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
    }

    /**
     * A stored wall-clock epoch back to `"HH:mm"`, rendered in UTC.
     *
     * A legacy value already saved as `"HH:mm"` passes through unchanged, as
     * the web's `epochToHHMM` does for a string.
     */
    fun epochToHhMm(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        if (':' in value) return value
        val epoch = value.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return ""
        return epochToHhMm(epoch)
    }

    /** As [epochToHhMm], from the epoch itself. */
    fun epochToHhMm(epoch: Long?): String {
        val millis = epoch?.takeIf { it > 0 } ?: return ""
        val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).time
        return "${time.hour.pad()}:${time.minute.pad()}"
    }

    /**
     * "Now" as a mark, re-stamped onto the shoot day.
     *
     * The clock read is the viewer's own — an AD marking 07:30 locally means
     * 07:30 on the sheet — and it is then written as that wall-clock time on
     * the day being run. Letting the server stamp it instead records a real
     * instant, so a 07:30 IST mark renders as 02:00.
     */
    fun nowAsWallClock(now: Long, shootDate: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).time
        return hhmmToUtcEpoch(shootDate, "${local.hour.pad()}:${local.minute.pad()}")
    }

    /** "Now" as the `HH:mm` a field shows, from the viewer's own clock. */
    fun nowHhMm(now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).time
        return "${local.hour.pad()}:${local.minute.pad()}"
    }

    /** `shoot_date` as `yyyy-MM-dd`, UTC-pinned — what a date field shows. */
    fun formatYmd(shootDate: Long?): String {
        val date = shootDate?.let { utcDate(it) } ?: return ""
        return "${date.year.pad(YEAR_DIGITS)}-${date.monthNumber.pad()}-${date.dayOfMonth.pad()}"
    }

    /** `yyyy-MM-dd` back to UTC midnight; anything else reads as nothing. */
    @Suppress("ReturnCount") // One guard per part of the date; each refuses for itself.
    fun parseYmd(text: String?): Long? {
        val parts = text?.trim()?.split('-')?.takeIf { it.size == YMD_PARTS } ?: return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..MONTHS_IN_YEAR } ?: return null
        val day = parts[2].toIntOrNull()?.takeIf { it in 1..MAX_DAY_OF_MONTH } ?: return null
        return runCatching {
            LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        }.getOrNull()
    }

    /** `yyyymmdd`, UTC-pinned — the PO reference's date part. */
    fun formatCompactYmd(shootDate: Long?): String {
        val date = shootDate?.let { utcDate(it) } ?: return ""
        return "${date.year.pad(YEAR_DIGITS)}${date.monthNumber.pad()}${date.dayOfMonth.pad()}"
    }

    /** `dd MMM` — the schedule row's date, UTC-pinned. */
    fun formatDayShort(shootDate: Long?): String {
        val date = shootDate?.let { utcDate(it) } ?: return ""
        return "${date.dayOfMonth.pad()} ${MONTHS[date.monthNumber - 1]}"
    }

    /** `EEE dd MMM yyyy` — a day header's date, UTC-pinned. */
    fun formatDayFull(shootDate: Long?): String {
        val date = shootDate?.let { utcDate(it) } ?: return ""
        val weekday = WEEKDAYS[date.dayOfWeek.ordinal]
        return "$weekday ${date.dayOfMonth.pad()} ${MONTHS[date.monthNumber - 1]} ${date.year}"
    }

    /** `EEE` — the schedule's weekday chip, UTC-pinned. */
    fun formatWeekday(shootDate: Long?): String {
        val date = shootDate?.let { utcDate(it) } ?: return ""
        return WEEKDAYS[date.dayOfWeek.ordinal]
    }

    /**
     * An audit instant — signed at, submitted at — in the **viewer's** zone.
     *
     * Deliberately not UTC: unlike a shoot date, "when this was signed" is a
     * real moment, and the person reading it wants their own clock.
     */
    fun formatInstant(epoch: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val millis = epoch?.takeIf { it > 0 } ?: return ""
        val moment = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${moment.dayOfMonth.pad()} ${MONTHS[moment.monthNumber - 1]} " +
            "${moment.hour.pad()}:${moment.minute.pad()}"
    }

    /** Whether two epochs fall on the same UTC day. */
    fun sameDay(left: Long?, right: Long?): Boolean =
        left != null && right != null && utcMidnight(left) == utcMidnight(right)

    /** Days between two UTC midnights, [from] − [to]; used to rank a schedule. */
    fun daysBetween(from: Long, to: Long): Long = (utcMidnight(from) - utcMidnight(to)) / MILLIS_PER_DAY

    /** Monday of [shootDate]'s UTC week, for a week-scoped read. */
    fun weekStart(shootDate: Long): Long {
        val date = utcDate(shootDate)
        val back = date.dayOfWeek.ordinal
        return date.plus(-back, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }

    private fun utcDate(millis: Long): LocalDate =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

    private fun Int.pad(width: Int = TWO_DIGITS): String = toString().padStart(width, '0')

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** `DayOfWeek.ordinal` is Monday-first, which is the order here. */
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}
