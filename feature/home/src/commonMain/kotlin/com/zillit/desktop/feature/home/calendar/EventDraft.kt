package com.zillit.desktop.feature.home.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Why an event could not be saved. */
enum class EventFieldError {
    TitleBlank,
    DateInvalid,
    StartTimeInvalid,
    EndTimeInvalid,
    EndBeforeStart,
}

/** What to show the user. */
val EventFieldError.message: String
    get() = when (this) {
        EventFieldError.TitleBlank -> "Give the event a name."
        EventFieldError.DateInvalid -> "Use a date like 2026-08-04."
        EventFieldError.StartTimeInvalid -> "Use a time like 09:00."
        EventFieldError.EndTimeInvalid -> "Use a time like 17:30."
        EventFieldError.EndBeforeStart -> "The end has to be after the start."
    }

/**
 * An event being written.
 *
 * Times are held as the *text the user typed*, not as parsed values. A field
 * that reformats or reverts while being typed into is the classic failure of
 * date entry — "1" becoming "01:00" the moment the second digit is expected.
 * Parsing happens on save, and on save only.
 */
data class EventDraft(
    /** Null when creating; the event being edited otherwise. */
    val id: String? = null,
    val title: String = "",
    val dateText: String = "",
    val startText: String = "",
    val endText: String = "",
    val isAllDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    val reminderMinutes: Int = 0,
    /** Project user ids to invite. */
    val inviteeIds: Set<String> = emptySet(),
    val recurrence: Recurrence = Recurrence(),
    /** The event's colour, `#rrggbb`. Blank sends the web's white default. */
    val colorHex: String = "",
    /** IANA id the typed times are meant in. Blank means the device's zone. */
    val timezoneId: String = "",
) {
    val isEdit: Boolean get() = id != null

    val formTitle: String get() = if (isEdit) "Edit event" else "New event"
}

/** The times an event actually covers, once its text has been read. */
data class EventTimes(val startMillis: Long, val endMillis: Long)

/**
 * The outcome of checking a draft.
 *
 * Errors and times together rather than one or the other: every failing field
 * is reported at once, because a form that surfaces one problem per submit
 * makes the user submit four times to learn about four.
 */
data class ValidatedEvent(
    val errors: Set<EventFieldError>,
    /** Null whenever [errors] is not empty. */
    val times: EventTimes?,
) {
    val isValid: Boolean get() = errors.isEmpty() && times != null

    operator fun contains(error: EventFieldError): Boolean = error in errors
}

/**
 * Checks a draft and works out its times.
 *
 * Returns either the errors, keyed by field so each sits under its own input,
 * or the resolved times. Both are needed at once — a form that reports one
 * error at a time makes the user submit four times to find out about four
 * problems.
 */
fun EventDraft.validate(zone: TimeZone): ValidatedEvent {
    // A "9:00" typed for an LA scout means 9:00 in LA — the times resolve in
    // the chosen zone, exactly as the web's `.tz(tz, true)` keeps wall time.
    val resolved = effectiveZone(zone)
    val errors = linkedSetOf<EventFieldError>()

    if (title.isBlank()) errors += EventFieldError.TitleBlank

    val date = dateText.trim().toLocalDateOrNull()
    if (date == null) errors += EventFieldError.DateInvalid

    // All-day events have no times to check: the whole day is the event.
    val start = if (isAllDay) LocalTime(0, 0) else startText.trim().toLocalTimeOrNull()
    val end = if (isAllDay) null else endText.trim().toLocalTimeOrNull()

    if (!isAllDay) errors += timeErrors(start, end)

    if (errors.isNotEmpty() || date == null || start == null) {
        return ValidatedEvent(errors, null)
    }

    val startMillis = LocalDateTime(date, start).toInstant(resolved).toEpochMilliseconds()
    val endMillis = if (isAllDay) {
        // Midnight to midnight, so the server's day-overlap check includes it.
        LocalDateTime(date.plusDays(1), LocalTime(0, 0)).toInstant(resolved).toEpochMilliseconds()
    } else {
        LocalDateTime(date, end!!).toInstant(resolved).toEpochMilliseconds()
    }

    return ValidatedEvent(emptySet(), EventTimes(startMillis, endMillis))
}

/** What is wrong with a timed event's start and end. */
private fun timeErrors(start: LocalTime?, end: LocalTime?): Set<EventFieldError> = buildSet {
    if (start == null) add(EventFieldError.StartTimeInvalid)
    if (end == null) add(EventFieldError.EndTimeInvalid)
    // Only meaningful once both parse — "end before start" on an unparsed field
    // is a second complaint about the same typo.
    if (start != null && end != null && end <= start) add(EventFieldError.EndBeforeStart)
}

/**
 * Fills a draft from an existing event, for editing.
 *
 * Text fields are rendered back to the formats the inputs expect, so a reopened
 * event reads the same as one just typed.
 */
fun CalendarEvent.toDraft(zone: TimeZone): EventDraft {
    val start = startMillis.toLocalDateTimeIn(zone)
    val end = (if (endMillis > startMillis) endMillis else startMillis).toLocalDateTimeIn(zone)

    return EventDraft(
        id = id,
        title = title,
        dateText = start.date.isoText(),
        startText = if (isAllDay) "" else start.time.hhmmText(),
        endText = if (isAllDay) "" else end.time.hhmmText(),
        isAllDay = isAllDay,
        location = location.orEmpty(),
        description = description.orEmpty(),
        reminderMinutes = reminderMinutes,
        recurrence = recurrence,
        colorHex = colorHex.orEmpty(),
        timezoneId = timezone.orEmpty(),
    )
}

/**
 * The zone the typed times are interpreted in: the picked one when it names a
 * real zone, the device's otherwise — the web's exact fallback
 * (`timeZoneExtracted || getCurrentTimezone()`).
 */
fun EventDraft.effectiveZone(fallback: TimeZone): TimeZone =
    timezoneId.takeIf { it.isNotBlank() }
        ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
        ?: fallback

/** `2026-08-04`. Strict: a half-typed date is not a date. */
internal fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

/**
 * `09:00` or `9:00`.
 *
 * Lenient on the leading zero because people type `9:00`, strict on everything
 * else — `0900` and `9am` are rejected rather than guessed at, since guessing
 * wrong puts a unit call at the wrong hour.
 */
internal fun String.toLocalTimeOrNull(): LocalTime? {
    val parts = split(':')
    if (parts.size != HOUR_AND_MINUTE) return null

    val hour = parts[0].trim().toIntOrNull()
    val minute = parts[1].trim().toIntOrNull()

    return if (hour in 0..MAX_HOUR && minute in 0..MAX_MINUTE) {
        LocalTime(hour!!, minute!!)
    } else {
        null
    }
}

internal fun LocalDate.isoText(): String = toString()

internal fun LocalTime.hhmmText(): String = "${hour.pad2()}:${minute.pad2()}"

private fun Int.pad2(): String = if (this < TEN) "0$this" else toString()

private fun Long.toLocalDateTimeIn(zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(zone)

private const val HOUR_AND_MINUTE = 2
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59
private const val TEN = 10
