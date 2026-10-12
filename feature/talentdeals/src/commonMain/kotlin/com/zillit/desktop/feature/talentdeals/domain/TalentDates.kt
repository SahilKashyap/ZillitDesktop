package com.zillit.desktop.feature.talentdeals.domain

import kotlin.math.roundToInt

/**
 * `yyyy-mm-dd` dates, as a date input holds them, and the epoch-millisecond
 * form every date on the talent-contract API takes.
 *
 * All calendar arithmetic is done on whole days, never through a time zone:
 * the web reads an input as a LOCAL date (so the day never shifts east of UTC)
 * but sends `Date.parse("2026-01-05")` — UTC midnight. Counting days directly
 * gives both: the same spans, and an epoch that round-trips on any machine.
 */
@Suppress("MagicNumber", "ReturnCount", "ComplexCondition") // Calendar arithmetic: Hinnant's civil-date algorithm.
object TalentDates {

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val DAYS_PER_WEEK = 7
    private const val DAYS_PER_ERA = 146_097
    private const val ERA_YEARS = 400
    private const val EPOCH_SHIFT = 719_468
    private const val MARCH_BASED_MONTH_OFFSET = 3
    private const val DAYS_IN_YEAR = 365

    data class Civil(val year: Int, val month: Int, val day: Int)

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")

    /** `yyyy-mm-dd` → its parts, or null when it is not a real calendar day. */
    fun parse(iso: String?): Civil? {
        val parts = iso.orEmpty().split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year <= 0 || month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
        return Civil(year, month, day)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % ERA_YEARS == 0)

    /** Days since 1970-01-01 (Howard Hinnant's `days_from_civil`). */
    fun epochDay(date: Civil): Long {
        val y = if (date.month <= 2) date.year - 1 else date.year
        val era = (if (y >= 0) y else y - (ERA_YEARS - 1)) / ERA_YEARS
        val yoe = y - era * ERA_YEARS
        val shifted = if (date.month > 2) date.month - MARCH_BASED_MONTH_OFFSET else date.month + 9
        val doy = (153 * shifted + 2) / 5 + date.day - 1
        val doe = yoe * DAYS_IN_YEAR + yoe / 4 - yoe / 100 + doy
        return era.toLong() * DAYS_PER_ERA + doe - EPOCH_SHIFT
    }

    /** The inverse of [epochDay]. */
    fun fromEpochDay(days: Long): Civil {
        val z = days + EPOCH_SHIFT
        val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
        val doe = (z - era * DAYS_PER_ERA).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / (DAYS_PER_ERA - 1)) / DAYS_IN_YEAR
        val doy = doe - (DAYS_IN_YEAR * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + MARCH_BASED_MONTH_OFFSET else mp - 9
        val year = (yoe + era * ERA_YEARS).toInt() + if (month <= 2) 1 else 0
        return Civil(year, month, day)
    }

    fun format(date: Civil): String =
        "${date.year.toString().padStart(4, '0')}-${date.month.toString().padStart(2, '0')}-" +
            date.day.toString().padStart(2, '0')

    /** yyyy-mm-dd (the form) → epoch ms (every date on the API). Blank or unreal → null. */
    fun toEpoch(iso: String?): Long? = parse(iso)?.let { epochDay(it) * MILLIS_PER_DAY }

    /** epoch ms (the API) → yyyy-mm-dd (a date input). Null / 0 → "". */
    fun fromEpoch(millis: Long?): String {
        if (millis == null || millis == 0L) return ""
        return format(fromEpochDay(millis.floorDiv(MILLIS_PER_DAY)))
    }

    /** End date for a run of whole weeks, counted INCLUSIVELY from the start. */
    fun endOfWeeks(start: String?, weeks: String?): String {
        val from = parse(start) ?: return ""
        val count = TalentNumbers.num(weeks)
        if (count <= 0) return ""
        val last = epochDay(from) + (count * DAYS_PER_WEEK).roundToInt() - 1
        return format(fromEpochDay(last))
    }

    /** Weeks the span covers, to one decimal so part-weeks stay visible; "" when it cannot say. */
    fun weeksBetween(start: String?, end: String?): String {
        val from = parse(start) ?: return ""
        val to = parse(end) ?: return ""
        val a = epochDay(from)
        val b = epochDay(to)
        if (b < a) return ""
        val days = b - a + 1
        val weeks = (days.toDouble() / DAYS_PER_WEEK * 10).roundToInt() / 10.0
        return TalentNumbers.plain(weeks).ifEmpty { "0" }
    }

    /** `2026-01-05` → `05 Jan 2026`, the register's date style. A string that is no date is returned as is. */
    fun display(iso: String?): String {
        val date = parse(iso) ?: return iso.orEmpty()
        return "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month - 1]} ${date.year}"
    }

    /** An epoch-ms shoot date as `Mon 05 Jan`, read in UTC like the AD dashboard. */
    fun shootDay(millis: Long): String {
        val days = millis.floorDiv(MILLIS_PER_DAY)
        val date = fromEpochDay(days)
        val weekday = WEEKDAYS[days.mod(DAYS_PER_WEEK.toLong()).toInt()]
        return "$weekday ${date.day.toString().padStart(2, '0')} ${MONTHS[date.month - 1]}"
    }
}
