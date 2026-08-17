package com.zillit.desktop.feature.home.calendar

/**
 * One row of `calendar/invite` — an invitation and the event it is for.
 *
 * iOS's `InvitationWithEvent`, field for field. The nested event can be
 * absent (the server keeps invitations whose event was since deleted), and a
 * row without one still renders — as a title-less entry the user can only
 * see, which is also what iOS shows for a cancelled event's invitation.
 */
data class EventInvitation(
    /** The invitation's own id, not the event's. */
    val id: String,
    val eventId: String,
    val status: InvitationStatus,
    val invitedById: String? = null,
    val rejectionReason: String? = null,
    val event: CalendarEvent? = null,
)

/** The server's four answers to "have they replied" — the web's tab set. */
enum class InvitationStatus(val wire: String, val label: String) {
    Pending("pending", "Pending"),
    Accepted("accepted", "Accepted"),
    Rejected("rejected", "Rejected"),
    Expired("expired", "Expired"),
    ;

    companion object {
        fun of(wire: String?): InvitationStatus =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: Pending
    }
}

/**
 * One row of `calendar/timezones` — iOS's `TimezoneItem`: the IANA
 * [identifier] the payload carries, and the display [label] people pick by.
 */
data class TimezoneOption(val identifier: String, val label: String)
