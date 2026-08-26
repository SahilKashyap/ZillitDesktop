package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Something the Firestore side of a call said.
 *
 * Line 2 (Agora) runs a second status channel beside the socket: every client
 * mirrors its ring state into `calls/{uuid}/call_users/{deviceId}`, and the
 * call's own document carries the global verdict. Android and web treat it as
 * the authoritative plane for cross-device ring dismissal — the socket's
 * `call:update` can lose the race with a phone that answered on cellular.
 */
sealed interface PlaneEvent {

    /** One participant's mirrored status moved. */
    data class UserStatus(
        val deviceId: String,
        val userId: String,
        val status: CallStatus,
        /** Their platform's own name for itself — `Android`, `web`, `iOS`. */
        val updatedFrom: String = "",
    ) : PlaneEvent

    /**
     * The live flags on one participant's row — the things that change
     * during a call rather than moving it between phases.
     *
     * Separate from [UserStatus] because they change independently: a hand
     * goes up without the status moving, and a status-keyed change detector
     * would never notice. On the Agora line this row IS the transport — the
     * phones send no socket event for any of it (`AgoraCallEngine`'s peer
     * messaging is a deliberate no-op, "Agora uses Firebase for state sync").
     */
    data class UserFlags(
        val deviceId: String,
        val userId: String,
        /** Their media uid, so the picture can be matched to the row. */
        val agoraUid: Int,
        val sharing: Boolean,
        val handRaised: Boolean,
        /** They are recording the call — the row's `isRecording`. */
        val recording: Boolean = false,
        /**
         * The name their own client stamped on the row (`user_name`).
         *
         * Every platform writes it to self-heal call documents, and it is the
         * one name source that exists for a caller the invite never named —
         * the exact case that used to render a live participant as "Guest".
         */
        val userName: String = "",
    ) : PlaneEvent

    /** The call document's global status became `End Call`. */
    data class Ended(val callUuid: String) : PlaneEvent
}

/**
 * The Firestore status mirror, behind an interface so the coordinator never
 * knows whether one exists — a production without Firebase config runs
 * socket-only, exactly as before.
 *
 * Writes mirror Android's `CallFirebaseManager` field for field, including
 * the `updated_from` stamp; a desktop that writes different keys is invisible
 * to every phone watching the same documents.
 */
interface CallStatusPlane {

    /** Mirrors our own status into this device's `call_users` row. */
    suspend fun announceSelf(
        session: CallSession,
        status: CallStatus,
        extra: Map<String, Any> = emptyMap(),
    )

    /** Declares the whole call over on the call document. */
    suspend fun announceCallEnded(session: CallSession)

    /**
     * Writes onto ANOTHER participant's row.
     *
     * Only the recording announcement needs this: the phones stamp
     * `call_is_being_recorded`/`recording_by` on every row so each client
     * finds the fact on its own row without scanning the roster.
     */
    suspend fun updateUserFields(session: CallSession, deviceId: String, fields: Map<String, Any>) {}

    /**
     * Watches the call while collected: the roster's mirrored statuses and
     * the global verdict. The flow completes only by cancellation — the
     * caller owns the call's lifetime, not the plane.
     */
    fun watch(session: CallSession): Flow<PlaneEvent>
}

/** The socket-only fallback: absent config, absent plane. */
class NoopCallStatusPlane : CallStatusPlane {
    override suspend fun announceSelf(
        session: CallSession,
        status: CallStatus,
        extra: Map<String, Any>,
    ) = Unit

    override suspend fun announceCallEnded(session: CallSession) = Unit

    override fun watch(session: CallSession): Flow<PlaneEvent> = emptyFlow()
}
