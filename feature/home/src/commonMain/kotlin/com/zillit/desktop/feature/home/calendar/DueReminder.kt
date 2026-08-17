package com.zillit.desktop.feature.home.calendar

/**
 * A reminder that has come due.
 *
 * Carries the occurrence's own start rather than a pointer to the event, so a
 * repeating event reminds once per occurrence instead of once ever.
 */
data class DueReminder(
    val eventId: String,
    val eventTitle: String,
    val startMillis: Long,
    val minutesBefore: Int,
    val location: String? = null,
) {
    /** Identifies this occurrence, so each repeat is reminded about once. */
    val key: String get() = reminderKey(eventId, startMillis)

    /** Never prints the title: this ends up in logs, and titles are schedule. */
    override fun toString(): String = "DueReminder(eventId=$eventId, start=$startMillis)"
}

/**
 * The identity of one occurrence's reminder.
 *
 * The start is part of the key because the event id is shared across a repeating
 * series — keyed on the id alone, Monday's reminder would suppress Tuesday's.
 */
fun reminderKey(eventId: String, startMillis: Long): String = "$eventId@$startMillis"

/** The start embedded in a [reminderKey], for ageing old keys out. */
internal fun String.reminderKeyStartMillis(): Long? = substringAfterLast('@').toLongOrNull()

/**
 * When an event's reminder should fire, or null if it should not.
 *
 * Three events are deliberately silent:
 *  - no reminder chosen (`0` is "None" everywhere in this client),
 *  - cancelled, since the alert would be for something not happening,
 *  - declined, because being reminded of a thing you said no to is a bug.
 *
 * An event still merely *pending* does remind: that is exactly when a nudge is
 * useful, because the invitation is still waiting on the user.
 */
fun CalendarEvent.reminderDueAt(): Long? = when {
    reminderMinutes <= 0 -> null
    isCancelled -> null
    inviteStatus == InviteStatus.Rejected -> null
    else -> startMillis - reminderMinutes * MILLIS_PER_MINUTE
}

/**
 * The reminders that should fire right now.
 *
 * ## Why a window rather than an instant
 *
 * The scheduler polls; it does not sleep until each reminder's exact moment. A
 * laptop that suspends for the afternoon does not run timers while it is shut,
 * and one that wakes up owing four hours of alerts should not deliver them. So
 * a reminder fires only inside [graceMillis] of coming due, and is otherwise
 * dropped: something an hour late is not a reminder, it is an apology.
 *
 * [fired] carries the occurrences already notified about, so a reminder that
 * stays inside its window across several ticks still only arrives once.
 */
fun dueReminders(
    events: List<CalendarEvent>,
    nowMillis: Long,
    fired: Set<String>,
    graceMillis: Long = REMINDER_GRACE_MILLIS,
): List<DueReminder> = events
    .mapNotNull { event -> event.dueReminderOrNull(nowMillis, graceMillis) }
    .filterNot { it.key in fired }
    // Soonest first: if two land on the same tick, the nearer one is read first.
    .sortedBy { it.startMillis }

private fun CalendarEvent.dueReminderOrNull(nowMillis: Long, graceMillis: Long): DueReminder? {
    val dueAt = reminderDueAt() ?: return null
    val isDue = nowMillis >= dueAt && nowMillis <= dueAt + graceMillis
    return if (isDue) DueReminder(id, title, startMillis, reminderMinutes, location) else null
}

/**
 * The bold line of the notification: how long until it starts.
 *
 * Counted from *now* rather than from the reminder setting, because the two
 * differ whenever a reminder fires late. Telling someone an event starts in
 * fifteen minutes when it starts in two is worse than not telling them.
 *
 * Rounded up, so a reminder set for fifteen minutes reads "in 15 minutes"
 * rather than "in 14" — the delay between coming due and being delivered is
 * not the user's problem to reason about.
 */
fun DueReminder.headline(nowMillis: Long): String {
    val minutes = ceilDiv(startMillis - nowMillis, MILLIS_PER_MINUTE)
    val hours = ceilDiv(minutes, MINUTES_PER_HOUR)

    return when {
        minutes <= 0 -> "Starting now"
        minutes < MINUTES_PER_HOUR -> "Starts in ${plural(minutes, "minute")}"
        hours < HOURS_PER_DAY -> "Starts in ${plural(hours, "hour")}"
        else -> "Starts in ${plural(ceilDiv(hours, HOURS_PER_DAY), "day")}"
    }
}

/**
 * The detail line: which event it is, and where, when there is a where.
 *
 * The location earns its place — a reminder for a call across town is only
 * useful if it says the call is across town.
 */
val DueReminder.body: String
    get() = if (location.isNullOrBlank()) eventTitle else "$eventTitle · $location"

private fun plural(count: Long, noun: String): String =
    if (count == 1L) "1 $noun" else "$count ${noun}s"

/** Rounds away from zero, so a part-minute still counts as a minute. */
private fun ceilDiv(value: Long, by: Long): Long = (value + by - 1) / by

/**
 * How late a reminder may be delivered and still be worth delivering.
 *
 * Five minutes covers a tick landing slightly late or a short suspend, and
 * stops a machine waking from a long sleep spraying the afternoon's alerts.
 */
const val REMINDER_GRACE_MILLIS: Long = 5 * 60 * 1000L

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
