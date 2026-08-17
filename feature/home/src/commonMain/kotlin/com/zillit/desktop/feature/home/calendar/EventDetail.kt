package com.zillit.desktop.feature.home.calendar

/**
 * Where this user stands on an event they were invited to.
 *
 * [None] covers both "not invited" and "invited but the server said nothing" —
 * the popover shows invitation controls only for [Pending], so the two are
 * indistinguishable in effect and inventing a difference would be inventing
 * behaviour.
 */
enum class InviteStatus { None, Pending, Accepted, Rejected }

/**
 * Reads the invitation status off whatever the server called it.
 *
 * The field is `status` on the event and `invitation_status` on an invite row,
 * and both carry the same words. Anything unrecognised is [InviteStatus.None]
 * rather than a guess: showing Accept and Decline for a state we do not
 * understand is worse than showing neither.
 */
fun inviteStatusOf(raw: String?): InviteStatus = when (raw?.lowercase()?.trim()) {
    "pending", "invited" -> InviteStatus.Pending
    "accepted", "accept" -> InviteStatus.Accepted
    "rejected", "reject", "declined" -> InviteStatus.Rejected
    else -> InviteStatus.None
}

/** What the status line says, or null when there is nothing to say. */
val InviteStatus.message: String?
    get() = when (this) {
        InviteStatus.Pending -> "Pending invitation"
        InviteStatus.Accepted -> "You accepted this event"
        InviteStatus.Rejected -> "You declined this event"
        InviteStatus.None -> null
    }

/**
 * How long an event runs, in words.
 *
 * "1 hr 30 min" rather than "90 min" past the hour: a call sheet is read at a
 * glance, and nobody converts minutes in their head on set.
 */
fun durationLabel(startMillis: Long, endMillis: Long): String {
    if (endMillis <= startMillis) return ""

    val minutes = (endMillis - startMillis) / MILLIS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val remainder = minutes % MINUTES_PER_HOUR

    return when {
        hours == 0L -> "$minutes min"
        remainder == 0L -> "$hours hr"
        else -> "$hours hr $remainder min"
    }
}

/**
 * The reminder, in the words the web uses.
 *
 * Arbitrary values are rendered rather than dropped — the server is not
 * limited to the six the picker offers, and a reminder set on another client
 * must not silently read as "None".
 */
fun reminderLabel(minutesBefore: Int): String = when {
    minutesBefore <= 0 -> "None"
    minutesBefore == MINUTES_PER_HOUR.toInt() -> "1 hour before"
    minutesBefore % MINUTES_PER_HOUR.toInt() == 0 ->
        "${minutesBefore / MINUTES_PER_HOUR} hours before"
    else -> "$minutesBefore minutes before"
}

/**
 * Whether an event has already finished.
 *
 * Used to hide Edit and Delete, matching the web: there is nothing useful to do
 * to yesterday, and offering it invites a server rejection the user cannot act
 * on.
 */
fun CalendarEvent.hasFinished(nowMillis: Long): Boolean {
    val end = if (endMillis > startMillis) endMillis else startMillis
    return end < nowMillis
}

/** Cancelled events are shown, struck through, rather than hidden. */
val CalendarEvent.isCancelled: Boolean
    get() = status?.equals("cancelled", ignoreCase = true) == true

/**
 * What this user may do with an event.
 *
 * Managing is the creator's right. Responding is an invitee's, and only while
 * the event is still ahead — accepting an invitation to something that already
 * happened does nothing but confuse whoever reads the attendee list.
 */
data class EventPermissions(
    val canManage: Boolean = false,
    val canRespond: Boolean = false,
)

fun permissionsFor(
    event: CalendarEvent,
    isCreator: Boolean,
    nowMillis: Long,
): EventPermissions {
    val finished = event.hasFinished(nowMillis)
    val cancelled = event.isCancelled

    return EventPermissions(
        canManage = isCreator && !finished && !cancelled,
        canRespond = !isCreator &&
            !finished &&
            !cancelled &&
            event.inviteStatus != InviteStatus.None,
    )
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
