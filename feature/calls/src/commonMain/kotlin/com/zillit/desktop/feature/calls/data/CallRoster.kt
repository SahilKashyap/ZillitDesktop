package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallStatus

/**
 * The roster's reducers, as plain functions.
 *
 * Who is on a call arrives from three places — the socket, the Firestore
 * mirror, and the REST fetch that follows an accept — and the rules for
 * folding each into the list are the same regardless of which spoke. Keeping
 * them here means [CallCoordinator] decides *when* the roster changes and
 * these decide *what it becomes*, and it makes the fiddly parts (blank names,
 * unchanged lists) testable without a socket.
 *
 * Each returns the list unchanged — the same instance — when nothing moved, so
 * a caller can skip publishing a state nobody would see differently.
 */

/** Server rows win on status; local rows win on names already resolved. */
fun mergeRoster(
    known: List<CallParticipant>,
    fetched: List<CallParticipant>,
): List<CallParticipant> {
    val local = known.associateBy(CallParticipant::userId)
    return fetched.map { row ->
        val previous = local[row.userId] ?: return@map row
        row.copy(
            name = row.name.ifBlank { previous.name },
            image = row.image.ifBlank { previous.image },
        )
    }
}

/** Applies one person's new status, by user. */
fun List<CallParticipant>.withStatus(userId: String, status: CallStatus): List<CallParticipant> =
    map { if (it.userId == userId) it.copy(status = status) else it }

/** Applies one device's hand, up or down. */
fun List<CallParticipant>.withHand(deviceId: String, raised: Boolean): List<CallParticipant> =
    map { if (it.deviceId == deviceId && it.handRaised != raised) it.copy(handRaised = raised) else it }
