package com.zillit.desktop.feature.transportation.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Local-calendar epochs, as the web builds them (`dayjs(...).valueOf()`):
 * a date at local midnight, or a date plus a wall clock.
 */
internal object TransportClock {
    private val YMD = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val HM = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    fun dayMillis(ymd: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        parseDate(ymd)?.atStartOfDayIn(zone)?.toEpochMilliseconds() ?: 0L

    fun clockMillis(ymd: String, hm: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
        val d = parseDate(ymd) ?: return 0L
        val t = HM.matchEntire(hm.trim()) ?: return 0L
        return runCatching {
            LocalDateTime(d.year, d.monthNumber, d.dayOfMonth, t.groupValues[1].toInt(), t.groupValues[2].toInt())
                .toInstant(zone).toEpochMilliseconds()
        }.getOrDefault(0L)
    }

    fun ymd(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        return "${d.year}-${d.monthNumber.pad()}-${d.dayOfMonth.pad()}"
    }

    /** "Aug 20, 2026 08:30" */
    fun dateTime(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return "—"
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        return "$month ${t.dayOfMonth.pad()}, ${t.year} ${t.hour.pad()}:${t.minute.pad()}"
    }

    fun date(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return "—"
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        return "$month ${d.dayOfMonth.pad()}, ${d.year}"
    }

    /** The web's `pickup_time_string`, `h:mm a`. */
    fun clockText(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val hour12 = when (t.hour % HALF_DAY) { 0 -> HALF_DAY; else -> t.hour % HALF_DAY }
        return "$hour12:${t.minute.pad()} ${if (t.hour < HALF_DAY) "am" else "pm"}"
    }

    private fun parseDate(ymd: String): LocalDate? {
        val m = YMD.matchEntire(ymd.trim()) ?: return null
        val (year, month, day) = m.destructured
        return runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }

    private fun Int.pad() = toString().padStart(2, '0')
    private const val ABBREV = 3
    private const val HALF_DAY = 12
}
