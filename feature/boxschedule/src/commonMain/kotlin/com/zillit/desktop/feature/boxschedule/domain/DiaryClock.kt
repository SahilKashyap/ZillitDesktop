package com.zillit.desktop.feature.boxschedule.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The diary's clock: the wire is epoch milliseconds at local midnight (dates)
 * or at the instant (times); the editor types `YYYY-MM-DD` and `HH:mm`.
 */
object DiaryClock {

    private val YMD = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val HM = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    fun midnightOf(ymd: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        val m = YMD.matchEntire(ymd.trim()) ?: return null
        val date = runCatching {
            LocalDate(m.groupValues[YEAR].toInt(), m.groupValues[MONTH].toInt(), m.groupValues[DAY].toInt())
        }.getOrNull() ?: return null
        return date.atStartOfDayIn(zone).toEpochMilliseconds()
    }

    /** A date and a clock into one instant, or null when either does not parse. */
    fun instantOf(ymd: String, hm: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        val d = YMD.matchEntire(ymd.trim()) ?: return null
        val t = HM.matchEntire(hm.trim()) ?: return null
        return runCatching {
            LocalDateTime(
                d.groupValues[YEAR].toInt(), d.groupValues[MONTH].toInt(), d.groupValues[DAY].toInt(),
                t.groupValues[HOUR].toInt(), t.groupValues[MINUTE].toInt(),
            ).toInstant(zone).toEpochMilliseconds()
        }.getOrNull()
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

    /** "Mon 14 Aug" — the row header. */
    fun dayLabel(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val dow = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        val mon = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        return "$dow ${d.dayOfMonth} $mon"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private const val YEAR = 1
    private const val MONTH = 2
    private const val DAY = 3
    private const val HOUR = 1
    private const val MINUTE = 2
    private const val ABBREV = 3
}
