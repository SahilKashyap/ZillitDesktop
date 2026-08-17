package com.zillit.desktop.feature.calls.domain

/** Which way a logged call went, from this device's point of view. */
enum class CallLogDirection { Incoming, Outgoing }

/**
 * One row in the call history.
 *
 * A record of a call that is over, which is a different thing from a
 * [CallSession] — there is no channel, no token and nobody to talk to. What it
 * keeps is only what a list needs: who, which way, how long, and whether it was
 * answered.
 *
 * [peerUserId] and [roomId] are what a redial needs: a 1:1 row rings the
 * person, a group row rings the room. Either can be blank on old rows, and a
 * row that can name neither simply is not redialable.
 */
data class CallLogEntry(
    val callUuid: String,
    val direction: CallLogDirection,
    val mode: CallMode,
    val type: CallType,
    /** True when nobody picked up. Independent of direction — you can miss your own. */
    val missed: Boolean,
    /**
     * Milliseconds of connected call; 0 for one that never connected.
     *
     * Milliseconds because that is what the server sends, however the field is
     * named — see `formatDuration`.
     */
    val durationMillis: Long,
    val startedAtMillis: Long,
    /** The other person, for a 1:1 row. */
    val peerUserId: String = "",
    val peerDeviceId: String = "",
    /** The room, for a group row — and what a redial rings. */
    val roomId: String = "",
    val title: String = "",
    val projectId: String = "",
) {
    /** Nothing to ring means nothing to redial; the row is still worth showing. */
    val isRedialable: Boolean
        get() = when (mode) {
            CallMode.Group -> roomId.isNotBlank()
            else -> peerDeviceId.isNotBlank()
        }

    /**
     * Deliberately says nothing about who.
     *
     * A call log is a list of who a person has been talking to, which is the
     * kind of thing that should not end up in a log file on disk.
     */
    override fun toString(): String =
        "CallLogEntry(uuid=$callUuid, dir=$direction, missed=$missed, ms=$durationMillis)"
}
