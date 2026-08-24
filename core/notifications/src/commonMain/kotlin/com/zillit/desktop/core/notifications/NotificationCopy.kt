package com.zillit.desktop.core.notifications

/**
 * The wording of every notification the app posts.
 *
 * Gathered in one file, away from the wiring, for two reasons. The obvious one
 * is that it is the only part that can be tested — the delivery half needs a
 * desktop and a socket. The less obvious one is that notification copy is read
 * by someone glancing at a corner of their screen mid-take, and keeping the
 * lines side by side is what stops them drifting into five different voices.
 *
 * Bodies here contain message text and people's names. Nothing in this file is
 * ever logged, and [DesktopNotification] redacts itself for the same reason.
 */
object NotificationCopy {

    /** Shown when the wire gave us an id but no name to go with it. */
    const val UNKNOWN_SENDER = "Someone"

    /**
     * A chat message.
     *
     * [room] names a group; a direct message passes null and is titled by its
     * sender. An attachment with no words shows as its filename, because "📎"
     * alone tells the reader nothing about whether to go and look.
     */
    fun message(
        sender: String,
        body: String,
        room: String? = null,
        attachmentName: String? = null,
    ): DesktopNotification {
        val who = sender.ifBlank { UNKNOWN_SENDER }
        return DesktopNotification(
            title = room?.takeIf(String::isNotBlank)?.let { "$who · $it" } ?: who,
            body = bodyOrAttachment(body, attachmentName),
        )
    }

    /**
     * A message that was edited after the fact.
     *
     * Carries the new text rather than only announcing the edit: the reason to
     * tell someone at all is that what they read a minute ago now says
     * something else.
     */
    fun messageEdited(sender: String, body: String, room: String? = null): DesktopNotification {
        val who = sender.ifBlank { UNKNOWN_SENDER }
        return DesktopNotification(
            title = room?.takeIf(String::isNotBlank)?.let { "$who · $it (edited)" } ?: "$who (edited)",
            body = body.ifBlank { "Message updated" },
        )
    }

    /** A post on a Home notice board. */
    fun notice(
        author: String,
        body: String,
        unitName: String? = null,
        attachmentName: String? = null,
    ): DesktopNotification {
        val who = author.ifBlank { UNKNOWN_SENDER }
        return DesktopNotification(
            title = unitName?.takeIf(String::isNotBlank)?.let { "$who · $it" } ?: who,
            body = bodyOrAttachment(body, attachmentName),
        )
    }

    /**
     * Mail arrived.
     *
     * Deliberately says nothing about who from or what about: the mail socket
     * event carries a folder name and nothing else, and inventing a subject
     * line the payload does not have is worse than a plain announcement.
     */
    fun mail(folder: String?): DesktopNotification = DesktopNotification(
        title = "New mail",
        body = folder?.takeIf(String::isNotBlank)?.let { "In $it" } ?: "You have new mail",
    )

    /**
     * A call is ringing this device.
     *
     * [Warning][NotificationKind.Warning] rather than Info so the platform
     * gives it the more insistent presentation — this one is time-limited in a
     * way that no other notification here is.
     */
    fun incomingCall(caller: String, room: String? = null, hasVideo: Boolean = false): DesktopNotification {
        val who = caller.ifBlank { UNKNOWN_SENDER }
        val kind = if (hasVideo) "Incoming video call" else "Incoming call"
        return DesktopNotification(
            title = kind,
            body = room?.takeIf { it.isNotBlank() && it != who }?.let { "$who · $it" } ?: who,
            kind = NotificationKind.Warning,
        )
    }

    /**
     * General production activity — the bell-list rows the phones banner via
     * push and the desktop banners straight off the socket.
     *
     * [area] is the decoded path ("Tools : Call Sheet"), which is the only
     * title the wire can offer: the record itself carries no headline, and on
     * the phones the server composes one into the push instead.
     */
    fun activity(area: String, body: String): DesktopNotification = DesktopNotification(
        title = area.ifBlank { "Zillit" },
        body = body,
    )

    /** Words if there are any, otherwise the file that came instead of them. */
    private fun bodyOrAttachment(body: String, attachmentName: String?): String {
        val text = body.trim()
        if (text.isNotEmpty()) return text
        val file = attachmentName?.trim().orEmpty()
        return if (file.isEmpty()) "Sent an attachment" else "📎 $file"
    }
}
