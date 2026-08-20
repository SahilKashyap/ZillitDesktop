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

/**
 * The listing's time column as the web draws it (`NewEmailCard.jsx:82-96`):
 * a 12-hour clock for today, the word for yesterday, `Aug 19, 2026` beyond.
 */
fun mailListTimeLabel(
    receivedAtMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (receivedAtMillis <= 0) return ""

    val received = Instant.fromEpochMilliseconds(receivedAtMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date

    return when {
        received.date == today -> received.clock12h()
        received.date.toEpochDays() == today.toEpochDays() - 1 -> "Yesterday"
        else -> mailDate(receivedAtMillis, zone)
    }
}

/** `Aug 19, 2026` — the web's `OnlydateTimeFormat` (`datetimeUtil.js:34-37`). */
fun mailDate(atMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    return "${MONTHS[at.date.monthNumber - 1]} ${at.date.dayOfMonth.pad()}, ${at.date.year}"
}

/**
 * `Aug 19, 2026 at 05:44 PM` — the reading pane's stamp, the web's
 * `dateTimeFormat` (`datetimeUtil.js:19-22`).
 */
fun mailFullTimeLabel(
    atMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    return "${mailDate(atMillis, zone)} at ${at.clock12h()}"
}

private fun kotlinx.datetime.LocalDateTime.clock12h(): String {
    val hour = ((hour + 11) % 12) + 1
    val half = if (this.hour < 12) "AM" else "PM"
    return "${hour.pad()}:${minute.pad()} $half"
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
