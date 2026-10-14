package com.zillit.desktop.feature.home.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Unread invitations, split as iOS's Received Events splits them
 * (`getCalendarUnreadCounts`, ZL-18722): an invitation whose event is over is
 * expired, every other one pending. Unread ledger rows, not invitations
 * awaiting an answer — an invitation read on the phone badges nothing here.
 */
data class InvitationUnread(val pending: Int = 0, val expired: Int = 0) {
    /** What the Invitations button wears — iOS's `receivedTotalBadgeCount`. */
    val total: Int get() = pending + expired
}

/**
 * The calendar's share of the badge ledger, supplied by the host.
 *
 * iOS reads invitations in exactly two places, and this is both: one
 * invitation when it is answered (`readCalendarMessage(.pending, eventId)`,
 * `ReceivedEventsViewModel.swift:346-353`), and every expired one when the
 * Expired tab is shown (`ReceivedEventsView.clearBadgeForCurrentTab`). Opening
 * the calendar reads nothing.
 */
class InvitationBadges(
    val unread: Flow<InvitationUnread> = emptyFlow(),
    val readAnswered: suspend (eventId: String) -> Unit = {},
    val readExpired: suspend () -> Unit = {},
)
