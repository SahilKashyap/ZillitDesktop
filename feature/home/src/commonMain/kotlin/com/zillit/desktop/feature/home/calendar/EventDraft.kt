package com.zillit.desktop.feature.home.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Who an event is for.
 *
 * The web's `type` radio, and the server's integer. A members event is
 * circulated to people and has to say how they are meeting; a personal one is
 * a note to yourself and carries neither invitees nor a call.
 */
enum class EventAudience(val wireValue: Int, val label: String) {
    Members(0, "Members"),
    Personal(1, "Personal"),
    ;

    companion object {
        fun of(wireValue: Int?): EventAudience =
            entries.firstOrNull { it.wireValue == wireValue } ?: Members
    }
}

/**
 * How the people on an event are meant to meet.
 *
 * The web's `CALL_TYPE_OPTIONS`. Strings, not integers, because that is what
 * the server stores — and [InPersonAndCall] is the one that also needs a
 * location, since half the attendees are travelling to it.
 */
enum class CallType(val wireValue: String, val label: String) {
    Audio("audio", "Audio call"),
    Video("video", "Video call"),
    InPerson("meet_in_person", "Meet in person"),
    InPersonAndCall("meet_in_person_call", "Meet in person & call"),
    ;

    /** Somewhere to actually turn up to. */
    val needsLocation: Boolean get() = this == InPersonAndCall

    companion object {
        /** Null for an unset or unrecognised value — including the web's "none". */
        fun of(wireValue: String?): CallType? =
            entries.firstOrNull { it.wireValue == wireValue }
    }
}

/** Why an event could not be saved. */
enum class EventFieldError {
    TitleBlank,
    TitleTooShort,
    DateInvalid,
    DateInPast,
    StartTimeInvalid,
    EndTimeInvalid,
    TooShort,
    ReminderPassed,
    CallTypeMissing,
    LocationMissing,
    NoInvitees,
}

/** What to show the user. */
val EventFieldError.message: String
    get() = when (this) {
        EventFieldError.TitleBlank -> "Give the event a name."
        EventFieldError.TitleTooShort -> "Titles are at least $MIN_TITLE_LENGTH characters."
        EventFieldError.DateInvalid -> "Use a date like 2026-08-04."
        EventFieldError.DateInPast -> "That day has already passed."
        EventFieldError.StartTimeInvalid -> "Use a time like 09:00."
        EventFieldError.EndTimeInvalid -> "Use a time like 17:30."
        EventFieldError.TooShort -> "An event runs for at least $MIN_DURATION_MINUTES minutes."
        EventFieldError.ReminderPassed ->
            "That reminder has already passed. Pick a shorter one, or a later start."
        EventFieldError.CallTypeMissing -> "Say how people are meeting."
        EventFieldError.LocationMissing -> "Meeting in person needs somewhere to meet."
        EventFieldError.NoInvitees -> "Invite someone, or make this a personal event."
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
    /** Set only by the map picker; typing a location clears them. */
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val description: String = "",
    val reminderMinutes: Int = 0,
    /** Project user ids to invite. */
    val inviteeIds: Set<String> = emptySet(),
    /** People outside the production, invited by address. */
    val externalEmails: List<String> = emptyList(),
    val recurrence: Recurrence = Recurrence(),
    /** The event's colour, `#rrggbb`. Blank sends the web's white default. */
    val colorHex: String = "",
    /** IANA id the typed times are meant in. Blank means the device's zone. */
    val timezoneId: String = "",
    /** Members by default, exactly as the web's form opens. */
    val audience: EventAudience = EventAudience.Members,
    /** Only asked of a members event; null until chosen. */
    val callType: CallType? = null,
    /** The web's `createUser_exclude` — a meeting the organiser is not at. */
    val excludeOrganiser: Boolean = false,
) {
    val isEdit: Boolean get() = id != null

    val formTitle: String get() = if (isEdit) "Edit event" else "New event"

    /** A members event is circulated; a personal one is nobody else's business. */
    val isForMembers: Boolean get() = audience == EventAudience.Members

    /** Somebody, anybody — crew or an outside address. */
    val hasGuests: Boolean get() = inviteeIds.isNotEmpty() || externalEmails.isNotEmpty()
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
 *
 * [now] is passed rather than read: two of these rules are about whether
 * something has already happened, and a check that consults the clock itself
 * is a test that passes until the afternoon.
 */
fun EventDraft.validate(zone: TimeZone, now: Instant): ValidatedEvent {
    // A "9:00" typed for an LA scout means 9:00 in LA — the times resolve in
    // the chosen zone, exactly as the web's `.tz(tz, true)` keeps wall time.
    val resolved = effectiveZone(zone)
    val errors = linkedSetOf<EventFieldError>()

    errors += titleErrors()

    val date = dateText.trim().toLocalDateOrNull()
    errors += dateErrors(date, resolved, now)

    // All-day events have no times to read: the whole day is the event.
    val start = if (isAllDay) DAY_START else startText.trim().toLocalTimeOrNull()
    val end = if (isAllDay) DAY_END else endText.trim().toLocalTimeOrNull()
    if (start == null) errors += EventFieldError.StartTimeInvalid
    if (end == null) errors += EventFieldError.EndTimeInvalid

    errors += audienceErrors()

    // The remaining rules are about the span itself, which needs both ends.
    if (date == null || start == null || end == null) return ValidatedEvent(errors, null)

    val times = timesFor(date, start, end, resolved)
    errors += durationErrors(times)
    errors += reminderErrors(times, now)

    return if (errors.isEmpty()) ValidatedEvent(emptySet(), times) else ValidatedEvent(errors, null)
}

private fun EventDraft.titleErrors(): Set<EventFieldError> {
    val trimmed = title.trim()
    return when {
        trimmed.isEmpty() -> setOf(EventFieldError.TitleBlank)
        trimmed.length < MIN_TITLE_LENGTH -> setOf(EventFieldError.TitleTooShort)
        else -> emptySet()
    }
}

/**
 * The date, and whether it has been and gone.
 *
 * The past-date rule applies to new events only. The web disables past days in
 * its picker, which stops one being *chosen* — but applied to an edit it would
 * also stop last week's call sheet having its typo fixed, and a form that
 * refuses to save a change it did not ask about is worse than the rule is
 * worth.
 */
private fun EventDraft.dateErrors(
    date: LocalDate?,
    zone: TimeZone,
    now: Instant,
): Set<EventFieldError> = when {
    date == null -> setOf(EventFieldError.DateInvalid)
    !isEdit && date < now.toLocalDateTime(zone).date -> setOf(EventFieldError.DateInPast)
    else -> emptySet()
}

/** What a members event owes the people on it. */
private fun EventDraft.audienceErrors(): Set<EventFieldError> = buildSet {
    if (!isForMembers) return@buildSet
    if (callType == null) add(EventFieldError.CallTypeMissing)
    if (!hasGuests) add(EventFieldError.NoInvitees)
    if (callType?.needsLocation == true && location.isBlank()) add(EventFieldError.LocationMissing)
}

/**
 * The span, with an overnight end rolled into the next day.
 *
 * All-day runs 00:00:00 to 23:59:59 rather than midnight to midnight: ending
 * on the next day's midnight makes the event overlap that day too, and it then
 * draws a second time in every grid that asks which events touch a date.
 */
private fun EventDraft.timesFor(
    date: LocalDate,
    start: LocalTime,
    end: LocalTime,
    zone: TimeZone,
): EventTimes {
    val startMillis = LocalDateTime(date, start).toInstant(zone).toEpochMilliseconds()
    val endDate = if (!isAllDay && end <= start) date.plusDays(1) else date
    val endMillis = LocalDateTime(endDate, end).toInstant(zone).toEpochMilliseconds()
    return EventTimes(startMillis, endMillis)
}

/** The web's fifteen-minute floor, checked after the overnight roll. */
private fun EventDraft.durationErrors(times: EventTimes): Set<EventFieldError> =
    if (!isAllDay && times.endMillis - times.startMillis < MIN_DURATION_MILLIS) {
        setOf(EventFieldError.TooShort)
    } else {
        emptySet()
    }

/**
 * Refuses a reminder that would have to fire in the past.
 *
 * Start 10:40, now 10:35, "5 minutes before" — the reminder is due at 10:35
 * and will never arrive. Saving it looks like it worked and then quietly does
 * nothing, which is the failure mode a reminder exists to prevent.
 *
 * New events only, for the same reason as [dateErrors].
 */
private fun EventDraft.reminderErrors(times: EventTimes, now: Instant): Set<EventFieldError> {
    if (isEdit || reminderMinutes <= 0) return emptySet()
    val fires = times.startMillis - reminderMinutes * MILLIS_PER_MINUTE
    return if (fires <= now.toEpochMilliseconds()) setOf(EventFieldError.ReminderPassed) else emptySet()
}

/**
 * Fills a draft from an existing event, for editing.
 *
 * Text fields are rendered back to the formats the inputs expect, so a reopened
 * event reads the same as one just typed. Its audience, guests and call type
 * come back too: rebuilding them from defaults would turn every edited personal
 * event into a members one, and quietly uninvite everybody on a members event
 * whose organiser only meant to fix the title.
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
        locationLat = locationLat,
        locationLng = locationLng,
        description = description.orEmpty(),
        reminderMinutes = reminderMinutes,
        inviteeIds = inviteeIds,
        externalEmails = externalEmails,
        recurrence = recurrence,
        colorHex = colorHex.orEmpty(),
        timezoneId = timezone.orEmpty(),
        audience = audience,
        callType = callType,
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

/** Midnight, and the last second before the next one. */
private val DAY_START = LocalTime(0, 0)
private val DAY_END = LocalTime(MAX_HOUR, MAX_MINUTE, MAX_SECOND)

private const val HOUR_AND_MINUTE = 2
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59
private const val MAX_SECOND = 59
private const val TEN = 10
private const val MILLIS_PER_MINUTE = 60_000L

/** The web's `{ min: 3 }` on the title field. */
private const val MIN_TITLE_LENGTH = 3

/** The web's fifteen-minute floor on an event's span. */
private const val MIN_DURATION_MINUTES = 15
private const val MIN_DURATION_MILLIS = MIN_DURATION_MINUTES * MILLIS_PER_MINUTE
