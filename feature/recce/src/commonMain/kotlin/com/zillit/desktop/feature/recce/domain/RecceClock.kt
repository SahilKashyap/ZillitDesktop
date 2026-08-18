package com.zillit.desktop.feature.recce.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The recce's clock, mirroring the web's `dateDjsToEpoch` / `combineDayjsToEpoch`
 * exactly: every value on the wire is epoch milliseconds built from the
 * device's LOCAL calendar — the recce day at midnight, or that day plus a
 * typed wall clock. A missing time is `0`, never null; a time typed before
 * a date is pinned to 1970-01-01, as the web does.
 */
object RecceClock {

    private val YMD = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val HM = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    /** Local midnight of `YYYY-MM-DD`; 0 when it does not parse. */
    fun dayMillis(ymd: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        parseDate(ymd)?.atStartOfDayIn(zone)?.toEpochMilliseconds() ?: 0L

    /**
     * The recce day plus a `HH:mm` clock; 0 without a clock. Without a day the
     * clock lands on 1970-01-01 — the web's fallback for a draft typed out
     * of order, kept so a draft round-trips unchanged.
     */
    fun clockMillis(ymd: String, hm: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
        val t = HM.matchEntire(hm.trim()) ?: return 0L
        val date = parseDate(ymd) ?: LocalDate(EPOCH_YEAR, 1, 1)
        return runCatching {
            LocalDateTime(
                date.year, date.monthNumber, date.dayOfMonth,
                t.groupValues[HOUR].toInt(), t.groupValues[MINUTE].toInt(),
            ).toInstant(zone).toEpochMilliseconds()
        }.getOrDefault(0L)
    }

    fun ymd(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        return "${d.year}-${d.monthNumber.pad()}-${d.dayOfMonth.pad()}"
    }

    fun hm(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return "${t.hour.pad()}:${t.minute.pad()}"
    }

    /** "Fri, 6 Mar 2026" — the list's date column. */
    fun dateLabel(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val dow = d.dayOfWeek.name.title().take(ABBREV)
        val mon = d.month.name.title().take(ABBREV)
        return "$dow, ${d.dayOfMonth} $mon ${d.year}"
    }

    /** "Friday, 6th March 2026" — the detail header, the web's ordinal form. */
    fun longDateLabel(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        return "${d.dayOfWeek.name.title()}, ${ordinal(d.dayOfMonth)} ${d.month.name.title()} ${d.year}"
    }

    private fun parseDate(ymd: String): LocalDate? {
        val m = YMD.matchEntire(ymd.trim()) ?: return null
        return runCatching {
            LocalDate(m.groupValues[YEAR].toInt(), m.groupValues[MONTH].toInt(), m.groupValues[DAY].toInt())
        }.getOrNull()
    }

    private fun ordinal(day: Int): String {
        val suffix = when {
            day % HUNDRED in TEENS -> "th"
            day % TEN == 1 -> "st"
            day % TEN == 2 -> "nd"
            day % TEN == THIRD -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    private fun Int.pad() = toString().padStart(2, '0')
    private fun String.title() = lowercase().replaceFirstChar { it.uppercase() }

    private const val EPOCH_YEAR = 1970
    private const val YEAR = 1
    private const val MONTH = 2
    private const val DAY = 3
    private const val HOUR = 1
    private const val MINUTE = 2
    private const val ABBREV = 3
    private const val TEN = 10
    private const val THIRD = 3
    private const val HUNDRED = 100
    private val TEENS = 11..13
}
