package com.zillit.desktop.feature.transporthub.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * The hub's dates, in one place.
 *
 * Three spellings are in the store and all three reach the same screens: an
 * instant in epoch millis, an epoch-millis *string* (the permanent-trip
 * routes), and a bare `YYYY-MM-DD` day (the maintenance validator). Each
 * conversion lives here so a card cannot end up showing a raw wire value
 * beside a formatted one.
 *
 * Everything is in the machine's own zone, which is the production's working
 * day — a shoot day is local, and the web reads these with
 * `toLocaleDateString()` for the same reason.
 */

private val zone: TimeZone get() = TimeZone.currentSystemDefault()

private val shortMonths = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val shortDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** `YYYY-MM-DD` — what the maintenance and trip validators take. */
internal fun Long.toIsoDate(): String {
    val date = instantOrNull()?.toLocalDateTime(zone)?.date ?: return ""
    return "${date.year}-${(date.month.ordinal + 1).pad()}-${date.day.pad()}"
}

/** Parses `YYYY-MM-DD`, an ISO instant, or an epoch-millis string. */
internal fun String.toEpochMillisOrNull(): Long? {
    val raw = trim()
    if (raw.isEmpty()) return null
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: parsers.firstNotNullOfOrNull { parse -> runCatching { parse(raw) }.getOrNull() }
}

/**
 * The three spellings a date reaches here in, tried in turn.
 *
 * A day first, because that is what the validators take; then a local instant,
 * which is what the time fields build; then a zoned one, which is what an
 * older handler may have stored.
 */
private val parsers: List<(String) -> Long> = listOf(
    { raw -> LocalDate.parse(raw).atStartOfDayIn(zone).toEpochMilliseconds() },
    { raw -> LocalDateTime.parse(raw).toInstant(zone).toEpochMilliseconds() },
    { raw -> Instant.parse(raw).toEpochMilliseconds() },
)

/** "Mon, Jun 15, 2026" — the web's `fmtHubDate`. Blank for an unset date. */
internal fun Long.hubDate(): String {
    val at = instantOrNull()?.toLocalDateTime(zone) ?: return ""
    val day = shortDays.getOrNull(at.dayOfWeek.ordinal).orEmpty()
    return "$day, ${shortMonths[at.month.ordinal]} ${at.day}, ${at.year}"
}

/** "HH:mm". Blank for an unset time. */
internal fun Long.hubTime(): String {
    val at = instantOrNull()?.toLocalDateTime(zone) ?: return ""
    return "${at.hour.pad()}:${at.minute.pad()}"
}

/** "Mon, Jun 15, 2026, 09:30" — the web's `fmtHubDateTime`. */
internal fun Long.hubDateTime(): String {
    val date = hubDate()
    if (date.isEmpty()) return ""
    return "$date, ${hubTime()}"
}

/** Midnight at the start of this instant's day, in the machine's own zone. */
internal fun Long.startOfDay(): Long {
    val date = instantOrNull()?.toLocalDateTime(zone)?.date ?: return 0
    return date.atStartOfDayIn(zone).toEpochMilliseconds()
}

/**
 * Whole days from [from]'s day to this one's — negative when already past.
 *
 * Counted between the two *days*, not the two instants, so something booked
 * for later this afternoon reads "today" rather than "in 0 days".
 */
internal fun Long.daysFrom(from: Long): Int? {
    if (this <= 0 || from <= 0) return null
    val diff = startOfDay() - from.startOfDay()
    return (diff / DAY_MS).toInt()
}

private fun Long.instantOrNull(): Instant? =
    takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }

private fun Int.pad(): String = if (this < TWO_DIGITS) "0$this" else toString()

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Below this a number needs a leading zero to stay two characters wide. */
private const val TWO_DIGITS = 10

/**
 * The machine's IANA zone — "Europe/London".
 *
 * Sent with every action-vehicle write: the shoot-day reminder cron computes
 * "N days before" in the requester's own zone, and without it the reminders
 * fall back to UTC and go out on the wrong day.
 */
internal fun timeZoneId(): String = zone.id
