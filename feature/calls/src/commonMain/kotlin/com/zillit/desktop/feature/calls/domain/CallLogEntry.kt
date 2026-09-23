package com.zillit.desktop.feature.calls.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Which way a logged call went, from this device's point of view. */
enum class CallLogDirection { Incoming, Outgoing }

/**
 * Which line carried a logged call, as the detail sheet names it.
 *
 * Android's `CallActivityDetailSheet.kt:94-100`: `line` "agora" is Line 2,
 * "livekit" is Line 3, and anything else — "mediasoup" or the field missing
 * on an older row — is Line 1. Not [CallProvider], whose absent-means-Agora
 * default is right for joining a live call and wrong for labelling history.
 */
enum class CallLine(private val labelKey: String) {
    One(S.txt_line_one),
    Two(S.txt_line_two),
    Three(S.txt_line_three),
    ;

    /** The line's name as the phones show it. */
    val label: String get() = str(labelKey)

    /** The plumbing a redial on this line takes — the row's tag turned back into a choice. */
    val provider: CallProvider
        get() = when (this) {
            One -> CallProvider.Mediasoup
            Two -> CallProvider.Agora
            Three -> CallProvider.LiveKit
        }

    companion object {
        fun ofWire(raw: String?): CallLine = when (raw?.trim()?.lowercase()) {
            "agora" -> Two
            "livekit" -> Three
            else -> One
        }

        /**
         * What every production offers a redial, in the order the thread
         * header lists them: Line 2 first (every deployment has it), then
         * Line 1. Line 3 is appended by the host where remote config lists
         * the production.
         */
        val DEFAULT: List<CallLine> = listOf(Two, One)
    }
}

/**
 * One person on a logged call — Android's `CallLogParticipant`
 * (`GetCallLogsModel.kt:70-85`) for Line 3 rows, and the thinner
 * `CallLogUser` `{user_id, current_status}` (`:55-58`) for the rest.
 *
 * The legacy shape carries only [userId] and [status]; every other field
 * keeps its default there, which is how the detail sheet tells the two apart
 * (it prefers the rich list when it is non-empty).
 */
data class CallLogParticipant(
    val userId: String,
    /** The wire status as written: "caller" (host), "incall", "left", "declined", "missed", "ringing"… */
    val status: String = "",
    /** The server's own verdict that this person never picked up. */
    val missed: Boolean = false,
    /** Server-sent name — the only name a guest has. */
    val displayName: String = "",
    val isGuest: Boolean = false,
    /** Whoever pulled this person into a call already running. */
    val invitedBy: String = "",
    val joinCount: Int = 0,
    val leaveCount: Int = 0,
    /** Time actually spent in the call, across rejoins. */
    val totalMillis: Long = 0,
    val answeredAtMillis: Long = 0,
) {
    val isCaller: Boolean get() = status.trim().lowercase() == CALLER_STATUS

    private companion object {
        const val CALLER_STATUS = "caller"
    }
}

/**
 * One row in the call history.
 *
 * A record of a call that is over, which is a different thing from a
 * [CallSession] — there is no channel, no token and nobody to talk to. What it
 * keeps is only what a list needs: who, which way, how long, and whether it was
 * answered — and, for the detail sheet, who else was on it.
 *
 * [peerUserId] and [roomId] are what a redial needs: a 1:1 row rings the
 * person, a group row rings the room. Either can be blank on old rows, and a
 * row that can name neither simply is not redialable.
 *
 * [peerDeviceId] is a bonus, not a requirement: Line 1 and Line 3 rows carry
 * NO `caller_device_id`/`receiver_device_id` at all (Android's
 * `RecentCallFragment.kt:527-539` says so and resolves the device from the
 * user id instead). Requiring it here made every Line 1/3 1:1 row unclickable.
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
    val line: CallLine = CallLine.One,
    /** Who placed the call — `from_user_id`, or the older rows' `user_id`. */
    val callerUserId: String = "",
    /** Who a 1:1 call was placed to — `to_user_id`. */
    val calleeUserId: String = "",
    /** The rich Line 3 roster (`participants`); empty on older rows and the legacy lines. */
    val participants: List<CallLogParticipant> = emptyList(),
    /** The legacy roster (`call_users`), status only. */
    val callUsers: List<CallLogParticipant> = emptyList(),
) {
    /**
     * Nothing to ring means nothing to redial; the row is still worth showing.
     * A 1:1 row needs a person OR a device — the host looks the device up
     * from the person when the row names only them (Android's
     * `getUserDetailByUserId()?.deviceId`).
     */
    val isRedialable: Boolean
        get() = when (mode) {
            CallMode.Group -> roomId.isNotBlank()
            else -> peerDeviceId.isNotBlank() || peerUserId.isNotBlank()
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
