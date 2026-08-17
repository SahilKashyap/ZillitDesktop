package com.zillit.desktop.core.notifications

/**
 * How loudly a notification asks to be read.
 *
 * Maps onto the platform's own levels. Deliberately small: a scale with six
 * steps gets used as a mood ring and everything ends up [Error].
 */
enum class NotificationKind { Info, Warning, Error }

/**
 * Something worth telling the user about while they are looking elsewhere.
 *
 * [title] is the line the OS shows in bold and [body] the detail under it.
 * Neither is ever logged — an event title is "Reshoot: ep4 hospital scene" and
 * a production's schedule is not something to leave in a log file.
 */
data class DesktopNotification(
    val title: String,
    val body: String,
    val kind: NotificationKind = NotificationKind.Info,
) {
    override fun toString(): String = "DesktopNotification(kind=$kind)"
}

/**
 * Somewhere to send notifications.
 *
 * An interface rather than a direct call into the tray for two reasons: the
 * calendar's reminder logic can then be tested without a desktop, and mail can
 * use the same route later without either feature knowing about the other.
 *
 * Implementations must not throw. A notification is an aside — a failure to
 * deliver one must never take down the work that raised it.
 */
interface Notifier {
    fun post(note: DesktopNotification)
}

/** For tests, and for platforms with nowhere to post to. */
object NoOpNotifier : Notifier {
    override fun post(note: DesktopNotification) = Unit
}
