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

/**
 * The same, keyed by user — the shape Line 1 speaks, where the protoo
 * `peerRaisedHand` names a composite peer whose stable half is the user id.
 */
fun List<CallParticipant>.withHandByUser(userId: String, raised: Boolean): List<CallParticipant> =
    map { if (it.userId == userId && it.handRaised != raised) it.copy(handRaised = raised) else it }

/**
 * Fills what the invite never carried, from that person's own Firestore row.
 *
 * Fill-only, never overwrite: the row heals a BLANK name and a MISSING uid,
 * because the roster's own values — when they exist — came from the server
 * that owns them. The uid especially is what ties a roster row to a media
 * stream; without it the stream falls off the roster and is drawn as an
 * anonymous "Guest" tile even while its owner sits named in the roster panel.
 */
fun List<CallParticipant>.healedFromPlane(
    deviceId: String,
    userId: String,
    name: String,
    agoraUid: Int,
): List<CallParticipant> = map { row ->
    val matches = (deviceId.isNotBlank() && row.deviceId == deviceId) ||
        (userId.isNotBlank() && row.userId == userId)
    if (!matches) return@map row
    var healed = row
    if (row.name.isBlank() && name.isNotBlank()) healed = healed.copy(name = name)
    if (row.numericUid == 0 && agoraUid != 0) healed = healed.copy(agoraUid = agoraUid.toString())
    healed
}
