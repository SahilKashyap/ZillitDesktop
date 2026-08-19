package com.zillit.desktop.feature.home.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What the form does as it is filled in.
 *
 * The web's small conveniences, each a pure function on the draft rather than
 * something the dialog does on the way past: they are the difference between
 * the commonest event taking two fields and taking six, and they are the sort
 * of thing that quietly stops working when it lives inside a composable.
 */

/**
 * True when the typed end is at or before the typed start.
 *
 * A night shoot running 22:00–04:00 is the ordinary case, not a mistake, so
 * this is a fact about the event rather than an error: the end simply belongs
 * to the following day.
 */
val EventDraft.isOvernight: Boolean
    get() {
        if (isAllDay) return false
        val start = startText.trim().toLocalTimeOrNull() ?: return false
        val end = endText.trim().toLocalTimeOrNull() ?: return false
        return end <= start
    }

/** The date the event ends on — the next day when it runs overnight. */
val EventDraft.endDateText: String
    get() {
        val date = dateText.trim().toLocalDateOrNull() ?: return ""
        return if (isOvernight) date.plusDays(1).isoText() else date.isoText()
    }

/**
 * A fresh draft for [date].
 *
 * Starts at the next quarter hour and runs an hour, as the web's
 * `buildInitialValues` does — an empty pair of time fields makes the commonest
 * case, "a meeting shortly, for an hour", something to type out.
 */
fun newEventDraft(date: LocalDate, now: Instant, zone: TimeZone): EventDraft {
    val start = now.toLocalDateTime(zone).time.roundUpToQuarter()
    return EventDraft(
        dateText = date.isoText(),
        startText = start.hhmmText(),
        endText = start.plusHour().hhmmText(),
    )
}

/**
 * Typing a start time drags the end an hour after it.
 *
 * The web's `handleStartTimeChange`. Only once the text parses — rewriting the
 * end field while the start is still half-typed would move it four times.
 */
fun EventDraft.withStartTime(text: String): EventDraft {
    val start = text.trim().toLocalTimeOrNull() ?: return copy(startText = text)
    return copy(startText = text, endText = start.plusHour().hhmmText())
}

/**
 * Moving the event moves the end of its repeat with it.
 *
 * The web's `handleDateChange`: a weekly event dragged into next month keeps
 * repeating for a week from its new date, rather than having already stopped.
 */
fun EventDraft.withDate(text: String): EventDraft {
    if (!recurrence.repeats) return copy(dateText = text)
    val date = text.trim().toLocalDateOrNull() ?: return copy(dateText = text)
    val end = defaultRecurrenceEnd(recurrence.frequency, date) ?: return copy(dateText = text)
    return copy(dateText = text, recurrence = recurrence.copy(endDateText = end.isoText()))
}

/**
 * Re-reads the typed date and times in another zone.
 *
 * The web's `convertFormTimezone`. Changing the zone keeps the *moment* and
 * moves the clock, so a 09:00 call in London becomes 01:00 when the zone is
 * switched to Los Angeles — the alternative, keeping the digits, silently
 * moves the call eight hours and nobody sees it happen.
 */
fun EventDraft.inTimezone(zoneId: String, fallback: TimeZone): EventDraft {
    val from = effectiveZone(fallback)
    val to = copy(timezoneId = zoneId).effectiveZone(fallback)
    val moved = if (from == to) null else movedBetween(from, to)
    return (moved ?: this).copy(timezoneId = zoneId)
}

/**
 * The same draft read in another zone, or null when there is nothing readable
 * to move.
 */
private fun EventDraft.movedBetween(from: TimeZone, to: TimeZone): EventDraft? {
    val date = dateText.trim().toLocalDateOrNull() ?: return null
    return if (isAllDay) movedDate(date, from, to) else movedTimes(date, from, to)
}

/** An all-day event has no clock to reinterpret; only its date can shift. */
private fun EventDraft.movedDate(date: LocalDate, from: TimeZone, to: TimeZone): EventDraft =
    copy(
        dateText = LocalDateTime(date, MIDNIGHT).toInstant(from).toLocalDateTime(to).date.isoText(),
    )

private fun EventDraft.movedTimes(date: LocalDate, from: TimeZone, to: TimeZone): EventDraft? {
    val start = startText.trim().toLocalTimeOrNull() ?: return null
    val end = endText.trim().toLocalTimeOrNull() ?: return null

    val startIn = LocalDateTime(date, start).toInstant(from).toLocalDateTime(to)
    val endIn = LocalDateTime(date, end).toInstant(from).toLocalDateTime(to)

    return copy(
        dateText = startIn.date.isoText(),
        startText = startIn.time.hhmmText(),
        endText = endIn.time.hhmmText(),
    )
}

/**
 * Up to the next quarter hour — the web's `roundToNext15Min`.
 *
 * Rolls to 00:00 rather than past the end of the day; only the clock face is
 * being suggested, and the date is chosen separately.
 */
internal fun LocalTime.roundUpToQuarter(): LocalTime {
    val minutes = hour * MINUTES_PER_HOUR + minute
    val rounded = ((minutes + QUARTER - 1) / QUARTER) * QUARTER
    return minutesToTime(rounded)
}

internal fun LocalTime.plusHour(): LocalTime =
    minutesToTime(hour * MINUTES_PER_HOUR + minute + MINUTES_PER_HOUR)

/**
 * What happened when an outside guest was added.
 *
 * Refusals are reported rather than swallowed: an address that silently fails
 * to appear reads as a broken button, and one accepted here and dropped by the
 * server is worse — nobody finds out until the invitation never arrives.
 */
internal sealed interface GuestAddition {
    /** Nothing typed, so the button has nothing to do. */
    data object Empty : GuestAddition

    data class Added(val draft: EventDraft) : GuestAddition

    data class Refused(val reason: String) : GuestAddition
}

/** The web's `ExternalEmailModal` rules: a real address, and not one already on. */
internal fun EventDraft.addingGuest(typed: String): GuestAddition {
    val address = typed.trim()
    return when {
        address.isEmpty() -> GuestAddition.Empty
        !address.looksLikeGuestEmail() -> GuestAddition.Refused("That is not an email address.")
        externalEmails.any { it.equals(address, ignoreCase = true) } ->
            GuestAddition.Refused("That guest is already on the list.")
        else -> GuestAddition.Added(copy(externalEmails = externalEmails + address))
    }
}

/**
 * Enough of an address to be worth sending an invitation to.
 *
 * The web's `emailRegex`, in the terms this codebase states such things: one
 * `@` with something either side, a dot in the domain, and no whitespace
 * anywhere.
 */
internal fun String.looksLikeGuestEmail(): Boolean {
    val at = indexOf('@')
    if (at <= 0 || at != lastIndexOf('@')) return false
    if (any { it.isWhitespace() }) return false

    val domain = substring(at + 1)
    val dot = domain.lastIndexOf('.')
    return dot > 0 && dot < domain.length - 1
}


/** Wraps at midnight, so an hour after 23:30 reads 00:30. */
private fun minutesToTime(minutes: Int): LocalTime {
    val wrapped = minutes % (HOURS_PER_DAY * MINUTES_PER_HOUR)
    return LocalTime(wrapped / MINUTES_PER_HOUR, wrapped % MINUTES_PER_HOUR)
}

private val MIDNIGHT = LocalTime(0, 0)

private const val QUARTER = 15
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
