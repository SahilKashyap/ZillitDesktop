package com.zillit.desktop.feature.home.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A board row: either a post or the date separator above it.
 *
 * Android inserts the separator as a view type in the same adapter
 * (`ppDateTv` in `chat_text_layout`), which is the right model — a separator is
 * part of the list, not a decoration on a card.
 */
sealed interface BoardRow {
    data class Separator(val label: String) : BoardRow
    data class Post(val notice: Notice) : BoardRow
}

/**
 * Inserts a date separator whenever the day changes.
 *
 * Grouped in the **viewer's** timezone, not UTC: a call sheet posted at 23:30
 * local belongs to that day for the person reading it, and grouping by UTC
 * would push it onto tomorrow for anyone west of Greenwich.
 *
 * [todayMillis] is passed in rather than read from the clock so the "Today" and
 * "Yesterday" labels are testable.
 */
fun List<Notice>.withDateSeparators(
    todayMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<BoardRow> {
    if (isEmpty()) return emptyList()

    val today = todayMillis.toLocalDate(zone)
    var lastDate: kotlinx.datetime.LocalDate? = null

    return buildList {
        this@withDateSeparators.forEach { notice ->
            val date = notice.createdAtMillis.toLocalDate(zone)
            if (date != lastDate) {
                add(BoardRow.Separator(date.label(today)))
                lastDate = date
            }
            add(BoardRow.Post(notice))
        }
    }
}

/**
 * The posts the banner over the board points at: pinned, on the server,
 * most recently touched first. Pinned posts keep their place in the board's
 * chronology — the banner is how they stay in view without shuffling the
 * conversation, and clicking it scrolls to the post.
 */
fun List<Notice>.pinnedForBanner(): List<Notice> =
    filter { it.isPinned && it.sendState == NoticeSendState.Sent }
        .sortedByDescending { if (it.updatedAtMillis > 0) it.updatedAtMillis else it.createdAtMillis }

/** `HH:mm`, zero-padded — the time under each post. */
fun Long.toClockTime(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (this <= 0) return ""
    val time = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).time
    return "${time.hour.pad()}:${time.minute.pad()}"
}

/** "12 August 2026, 14:32" — the full stamp, for read receipts. */
fun Long.toDateTimeLabel(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (this <= 0) return ""
    val date = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "${date.day.pad()} $month ${date.year}, ${toClockTime(zone)}"
}

private fun Long.toLocalDate(zone: TimeZone) =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date

/**
 * "Today" and "Yesterday" beat a date for the two days people are usually
 * looking at; anything older gets the date so it can be placed.
 */
private fun kotlinx.datetime.LocalDate.label(today: kotlinx.datetime.LocalDate): String {
    val daysAgo = today.toEpochDays() - toEpochDays()  // Long in kotlinx-datetime 0.8
    return when (daysAgo) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "${day.pad()} ${month.name.lowercase().replaceFirstChar { it.uppercase() }} $year"
    }
}

private fun Int.pad(): String = if (this < TWO_DIGITS) "0$this" else "$this"

/** Below this a leading zero is needed for `HH:mm` and `dd`. */
private const val TWO_DIGITS = 10
