package com.zillit.desktop.core.common

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The finance backends send timestamps as epoch milliseconds, sometimes as a
 * number and sometimes as a decimal string. Both clients render them the same
 * way — `04 Aug, 2026` — so this is the one reader.
 *
 * Ported from the web's `epochToDate` / `epochToFullDate`
 * (`cashExpenses/components/helpers.js`), which every cash and card surface
 * calls. Rendered in the *machine's* zone, matching the web: a coordinator in
 * London and one in Los Angeles each see the timestamp in their own day, which
 * is what makes "yesterday's float" mean the same thing to both.
 */
object EpochDate {

    private val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** `04 Aug, 2026`. Blank for a missing or unreadable stamp. */
    fun date(millis: Long?): String {
        val moment = millis.toLocalOrNull() ?: return ""
        return "${moment.dayOfMonth.pad()} ${months[moment.monthNumber - 1]}, ${moment.year}"
    }

    /** `04 Aug, 2026 | 4:35 PM`. */
    fun dateTime(millis: Long?): String {
        val moment = millis.toLocalOrNull() ?: return ""
        val hour = moment.hour % HALF_DAY
        val meridiem = if (moment.hour >= HALF_DAY) "PM" else "AM"
        return "${date(millis)} | ${if (hour == 0) HALF_DAY else hour}:${moment.minute.pad()} $meridiem"
    }

    /** `2026-08-04` — what the export and filter endpoints take. */
    fun isoDate(millis: Long?): String {
        val moment = millis.toLocalOrNull() ?: return ""
        return "${moment.year}-${moment.monthNumber.pad()}-${moment.dayOfMonth.pad()}"
    }

    /**
     * Whole days between [millis] and [now], floored at zero.
     *
     * Ageing indicators only ever count forward — a receipt dated tomorrow is
     * "today" rather than "-1 days old", which is what the web renders.
     */
    fun daysAgo(millis: Long?, now: Long): Int {
        if (millis == null || millis <= 0) return 0
        val elapsed = now - millis
        if (elapsed <= 0) return 0
        return (elapsed / DAY_MILLIS).toInt()
    }

    private fun Long?.toLocalOrNull() = this
        ?.takeIf { it > 0 }
        ?.let {
            runCatching {
                Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
            }.getOrNull()
        }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private const val HALF_DAY = 12
    private const val DAY_MILLIS = 86_400_000L
}

/**
 * Reads a wire timestamp, which the finance services send as `1754300000000`,
 * `"1754300000000"` or `"1754300000000.0"` depending on the column.
 */
fun String?.toEpochMillisOrNull(): Long? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
