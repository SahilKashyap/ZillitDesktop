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
        /**
         * Their microphone and camera, as their own client last recorded them.
         *
         * A seed rather than the truth: on the Agora line it is the only mute
         * signal that exists for someone who was already muted before this
         * device joined, because the engine reports mute as a change and never
         * as a standing state. Whatever the media stack says afterwards wins.
         */
        val muted: Boolean = false,
        val hasVideo: Boolean = false,
    ) : PlaneEvent

    /** The call document's global status became `End Call`. */
    data class Ended(val callUuid: String) : PlaneEvent

    /**
     * Someone else took the call from one of this user's devices — a row of
     * theirs raised `joined_from_other_device` and another client wrote it
     * (`updated_from` is not ours). The web's `isForeignTakeover`, as an
     * edge: only a flag that went up after the watch first looked, so a
     * stale one left by an earlier switch never ends the next call.
     */
    data class Takeover(val deviceId: String, val userId: String) : PlaneEvent
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

    /**
     * A running Line 2 call, read for joining it without having been rung —
     * Android's `isCallGoingWithUUID`: the call document's channel and token,
     * and whether anyone is actually in it. Null when the plane cannot say
     * (no Firestore, a refused or failed read); the join then goes ahead on
     * what the active-calls row carried, and the server's answer to the
     * accept still turns away a call that has ended.
     */
    suspend fun readJoin(callUuid: String, selfUserId: String): PlaneJoin? = null

    /**
     * Switch here, on Line 2: raises the web's takeover flag
     * (`joined_from_other_device`) on our own rows that another device of
     * ours holds, so that device tears down. Line 1's SFU does the same by
     * itself; Agora has only this row.
     */
    suspend fun claimFromOtherDevices(callUuid: String, selfUserId: String) {}
}

/** What [CallStatusPlane.readJoin] found on the call document and its roster. */
data class PlaneJoin(
    /** Over, or nobody in it any more — nothing to join. */
    val ended: Boolean,
    val channelName: String = "",
    val token: String = "",
    /** Our own row's Agora uid, where the backend seeded one; 0 lets Agora assign. */
    val selfAgoraUid: Int = 0,
)

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
