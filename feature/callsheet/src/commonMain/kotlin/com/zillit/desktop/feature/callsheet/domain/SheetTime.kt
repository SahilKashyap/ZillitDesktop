package com.zillit.desktop.feature.callsheet.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The call sheet's epoch codec — the OPPOSITE of the production report's
 * wall-clock strings, and deliberately never shared with it.
 *
 * - `date` cells and `shared.date` are epoch milliseconds at LOCAL midnight.
 * - `time` cells are epoch milliseconds: the sheet's date with the clock set
 *   in the local zone (today when the sheet has no date).
 * - The crew "In" column is a mode-prefixed STRING under a `text` column:
 *   `Per HOD` | `O/C` | `Time:<epoch ms>` | `Other:<text>` | "" — the web
 *   calls string methods on it, so a bare number there crashes its editor.
 */
@Suppress("TooManyFunctions") // One codec function per value shape the web reads and writes.
object SheetTime {

    /** 2000-01-01T00:00Z — the web's "this digit string is a date" floor. */
    const val EPOCH_FLOOR = 946_684_800_000L

    /** A bare `Time:` value below this is a legacy clock, not an epoch. */
    private const val DAY_MILLIS = 86_400_000L

    private val DIGITS = Regex("""\d+""")
    private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    private val SHORT_MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec",
    )

    fun zone(): TimeZone = TimeZone.currentSystemDefault()

    /** Pure digits at or after 2000 — what both web renderers format. */
    fun isEpoch(value: String): Boolean {
        val trimmed = value.trim()
        return DIGITS.matches(trimmed) && (trimmed.toLongOrNull() ?: 0L) >= EPOCH_FLOOR
    }

    /** A `time` cell on screen: `HH:mm` for an epoch, the text as written otherwise. */
    fun clockOf(value: String, zone: TimeZone = zone()): String {
        val trimmed = value.trim()
        if (!isEpoch(trimmed)) return trimmed
        val time = Instant.fromEpochMilliseconds(trimmed.toLong()).toLocalDateTime(zone)
        return "${time.hour.pad()}:${time.minute.pad()}"
    }

    /** A `date` cell on screen: `05 Mar 2025` for an epoch, the text as written otherwise. */
    fun dateOf(value: String, zone: TimeZone = zone()): String {
        val trimmed = value.trim()
        if (!isEpoch(trimmed)) return trimmed
        val date = Instant.fromEpochMilliseconds(trimmed.toLong()).toLocalDateTime(zone).date
        return shortDate(date)
    }

    fun shortDate(date: LocalDate): String = "${date.day.pad()} ${SHORT_MONTHS[date.month.ordinal]} ${date.year}"

    /** The (hour, minute) an epoch time cell holds, for the picker; null when it holds none. */
    fun clockParts(value: String, zone: TimeZone = zone()): Pair<Int, Int>? {
        val epoch = value.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        return time.hour to time.minute
    }

    /** The local calendar day an epoch date cell holds, for the picker. */
    fun dateParts(value: String, zone: TimeZone = zone()): LocalDate? {
        val epoch = value.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone).date
    }

    /**
     * `timeStrToEpoch`: the sheet's date (today when it has none) with the
     * clock set locally, as the epoch string a `time` cell stores.
     */
    fun encodeClock(hour: Int, minute: Int, sheetDateMs: Long?, nowMs: Long, zone: TimeZone = zone()): String {
        val anchor = sheetDateMs?.takeIf { it > 0 } ?: nowMs
        val day = Instant.fromEpochMilliseconds(anchor).toLocalDateTime(zone).date
        val moment = LocalDateTime(day, LocalTime(hour.coerceIn(0, MAX_HOUR), minute.coerceIn(0, MAX_MINUTE)))
        return moment.toInstant(zone).toEpochMilliseconds().toString()
    }

    /** A picked calendar day as the epoch a `date` cell (or `shared.date`) stores — local midnight. */
    fun encodeDate(date: LocalDate, zone: TimeZone = zone()): Long = date.atStartOfDayIn(zone).toEpochMilliseconds()

    /** Local midnight of the day [nowMs] falls on — the web's `todayMs()`. */
    fun todayMidnight(nowMs: Long, zone: TimeZone = zone()): Long =
        encodeDate(Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date, zone)

    fun localDate(epochMs: Long, zone: TimeZone = zone()): LocalDate =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

    // The crew "In" column -------------------------------------------------------------------------

    /** Which choice an In value holds. */
    enum class InMode(val label: String) {
        None("Select..."), Time("Time"), PerHod("Per HOD"), OnCall("O/C"), Other("Others"),
    }

    fun inModeOf(value: String): InMode = when {
        value == PER_HOD -> InMode.PerHod
        value == ON_CALL -> InMode.OnCall
        value.startsWith(TIME_PREFIX) -> InMode.Time
        value.startsWith(OTHER_PREFIX) -> InMode.Other
        else -> InMode.None
    }

    /** What choosing a mode writes — the previous detail is dropped, as on the web. */
    fun inValueFor(mode: InMode): String = when (mode) {
        InMode.None -> ""
        InMode.Time -> TIME_PREFIX
        InMode.PerHod -> PER_HOD
        InMode.OnCall -> ON_CALL
        InMode.Other -> OTHER_PREFIX
    }

    /** `formatInValue`: the preview's face of an In value; blank is blank (the caller prints `--`). */
    fun inDisplay(value: String, zone: TimeZone = zone()): String = when {
        value.startsWith(TIME_PREFIX) -> {
            val rest = value.removePrefix(TIME_PREFIX)
            val epoch = rest.trim().toLongOrNull()
            when {
                rest.isBlank() -> "Time"
                // Any post-1970-day epoch reads as a clock, not only the post-2000 ones clockOf formats.
                epoch != null && epoch > DAY_MILLIS -> rawClock(epoch, zone)
                else -> rest
            }
        }
        value.startsWith(OTHER_PREFIX) -> value.removePrefix(OTHER_PREFIX)
        else -> value
    }

    /** The (hour, minute) a `Time:` value holds — an epoch, or a legacy `Time:HH:MM`. */
    fun inClockParts(value: String, zone: TimeZone = zone()): Pair<Int?, Int?> {
        if (!value.startsWith(TIME_PREFIX)) return null to null
        val rest = value.removePrefix(TIME_PREFIX).trim()
        val epoch = rest.toLongOrNull()
        if (epoch != null && epoch > DAY_MILLIS) {
            val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
            return time.hour to time.minute
        }
        val match = CLOCK.matchEntire(rest) ?: return null to null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** `Time:` plus the epoch of the picked clock on the sheet's date. */
    fun encodeInTime(hour: Int, minute: Int, sheetDateMs: Long?, nowMs: Long, zone: TimeZone = zone()): String =
        TIME_PREFIX + encodeClock(hour, minute, sheetDateMs, nowMs, zone)

    const val PER_HOD = "Per HOD"
    const val ON_CALL = "O/C"
    const val TIME_PREFIX = "Time:"
    const val OTHER_PREFIX = "Other:"

    // Headers ----------------------------------------------------------------------------------------

    /** The title bar's date — `Saturday 13th September, 2026` in the local zone; blank without one. */
    fun headerDate(dateMs: Long?, zone: TimeZone = zone()): String {
        if (dateMs == null || dateMs <= 0) return ""
        return longDate(Instant.fromEpochMilliseconds(dateMs).toLocalDateTime(zone).date)
    }

    fun longDate(date: LocalDate): String {
        val day = date.day
        val suffix = when {
            day in TEENS -> "th"
            day % DECADE == 1 -> "st"
            day % DECADE == 2 -> "nd"
            day % DECADE == THIRD -> "rd"
            else -> "th"
        }
        val weekday = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$weekday $day$suffix $month, ${date.year}"
    }

    /**
     * `fmtDateTime` for the script / schedule strip: a positive number reads as
     * epoch ms and prints `Saturday 13th September, 2026, 9:05 AM`; anything
     * else prints as written.
     */
    fun stripDateTime(value: String, zone: TimeZone = zone()): String {
        val trimmed = value.trim()
        val epoch = trimmed.toLongOrNull()?.takeIf { it > 0 } ?: return trimmed
        val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        val hour12 = (time.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
        val meridiem = if (time.hour < HALF_DAY) "AM" else "PM"
        return "${longDate(time.date)}, $hour12:${time.minute.pad()} $meridiem"
    }

    // Column type conversions --------------------------------------------------------------------------

    /**
     * `updateHeader(type)`: leaving `time` folds epochs to `HH:MM`, leaving
     * `date` folds them to `05 Mar 2026`; non-numeric values are untouched.
     */
    fun convertOnTypeChange(value: String, from: String, to: String, zone: TimeZone = zone()): String {
        if (from == to || to == "time" || to == "date") return value
        val epoch = value.trim().toLongOrNull() ?: return value
        return when {
            from == "time" && epoch > DAY_MILLIS -> rawClock(epoch, zone)
            from == "date" && epoch > 0 -> shortDate(Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone).date)
            else -> value
        }
    }

    private fun rawClock(epoch: Long, zone: TimeZone): String {
        val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        return "${time.hour.pad()}:${time.minute.pad()}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private val TEENS = 11..13
    private const val DECADE = 10
    private const val THIRD = 3
    private const val HALF_DAY = 12
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
}
