package com.zillit.desktop.feature.email.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The list's time column, the way every desktop mail client compresses it:
 * a clock for today, a date for this year, month-and-year beyond. The full
 * timestamp belongs to the reading pane; the list only needs enough to scan.
 */
fun mailTimeLabel(
    receivedAtMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (receivedAtMillis <= 0) return ""

    val received = Instant.fromEpochMilliseconds(receivedAtMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[received.date.monthNumber - 1]

    return when {
        received.date == today ->
            "${received.hour.pad()}:${received.minute.pad()}"
        received.date.year == today.year ->
            "${received.date.dayOfMonth} $month"
        else ->
            "$month ${received.date.year}"
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
