package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.strings.S

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

/**
 * One person on one event — a row of `invitationlist`.
 *
 * Deliberately only what the wire carries: the name, designation and face are
 * resolved against the crew the session already holds, exactly as the web
 * resolves them against its `usersList`. An outside guest has no crew record,
 * so [email] is all there is to show and [isExternal] says to expect that.
 */
data class EventInviteeRow(
    val id: String,
    val userId: String? = null,
    val email: String? = null,
    val status: InvitationStatus = InvitationStatus.Pending,
    /** Why they said no, when they gave a reason. */
    val rejectionReason: String? = null,
    val isExternal: Boolean = false,
)

/** The server's four answers to "have they replied" — the web's tab set. */
enum class InvitationStatus(val wire: String, val label: String) {
    Pending("pending", S.pending),
    Accepted("accepted", S.accepted),
    Rejected("rejected", S.rejected),
    Expired("expired", S.expired),
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
