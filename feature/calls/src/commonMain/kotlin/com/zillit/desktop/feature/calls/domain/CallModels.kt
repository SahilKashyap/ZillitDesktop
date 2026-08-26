package com.zillit.desktop.feature.calls.domain

/**
 * Where a call is in its life.
 *
 * Deliberately five states and no sub-states: Android reconstructs the same
 * five from a scattering of booleans (`isJoinCall`, `endCallRaised`,
 * `callNavigationTypeFlow`) that can disagree with each other, and most of its
 * call bugs are two of those booleans describing different calls.
 */
enum class CallPhase {
    /** No call. The only phase in which a new one may be started. */
    Idle,

    /** We placed it; the far side has not picked up. */
    Outgoing,

    /** Someone is calling us and the overlay is up. */
    Incoming,

    /** Media is expected to be flowing. */
    InCall,

    /** Teardown has begun — hang-up sent, waiting for the server to agree. */
    Ending,
}

/**
 * One participant's status, as the server spells it.
 *
 * The wire has two spellings for the same thing: Agora and Firebase write
 * `in_call`, Mediasoup and P2P write `incall`. [ofWire] folds them together,
 * because code that matches only one of them silently misses half the
 * platforms — on Android that showed up as a phone that kept ringing after the
 * user had already answered on web.
 */
enum class CallStatus(val wire: String) {
    /** The person who placed the call. */
    Caller("caller"),

    /** Invited, device is ringing. */
    Ringing("ringing"),

    /** Answered and joined the channel. */
    InCall("in_call"),

    /** Answered elsewhere, or pressed decline. */
    Declined("declined"),

    /** Was in the call and hung up. */
    Left("leave"),

    /** Rang out without an answer. */
    NotAnswered("not_answered"),

    /** The whole call is over for everyone. */
    Ended("End Call"),
    ;

    /** True while this participant is expected to have media flowing. */
    val isConnected: Boolean get() = this == InCall

    companion object {
        private const val IN_CALL_ALTERNATE = "incall"

        /**
         * Reads a status off the wire, tolerating case, padding and the
         * `in_call`/`incall` split. An unrecognised value is [Ringing] rather
         * than null: an invited participant whose status we cannot read is
         * still someone the roster must show.
         */
        fun ofWire(raw: String?): CallStatus {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return Ringing
            if (value == IN_CALL_ALTERNATE) return InCall
            return entries.firstOrNull { it.wire.lowercase() == value } ?: Ringing
        }

        /** The `in_call`/`incall` test on its own, for raw wire strings. */
        fun isInCall(raw: String?): Boolean = ofWire(raw) == InCall
    }
}

/**
 * Which media stack carries the call.
 *
 * The server picks, and says so in the call's `line` field. We honour it
 * rather than choosing: a client that joins the wrong stack does not fail
 * loudly, it just sits in a channel nobody else is in.
 */
/** The `connection_type` value that rules out a direct connection. */
const val CONNECTION_TYPE_SFU = "sfu"

enum class CallProvider(val wire: String) {
    /** "Line 2" — Agora, joined with a channel name, token and numeric uid. */
    Agora("agora"),

    /** "Line 1" — the Mediasoup SFU, joined with an invite code. */
    Mediasoup("mediasoup"),

    /** LiveKit, behind a room URL and token. */
    LiveKit("livekit"),
    ;

    companion object {
        /**
         * Absent or unknown resolves to [Agora].
         *
         * Not a preference so much as the safer of two failures: a line we
         * cannot name is more likely to be a spelling we have not seen than a
         * stack we do not implement, and Agora is the one every payload has
         * carried historically.
         */
        fun ofWire(raw: String?): CallProvider {
            val value = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Agora
        }
    }
}

/** One-to-one or a room. Decides where a decline's quick-reply is posted. */
enum class CallMode(val wire: String) {
    Private("private"),
    Group("group"),
    ;

    companion object {
        fun ofWire(raw: String?): CallMode =
            if (raw?.trim()?.lowercase() == Group.wire) Group else Private
    }
}

/** Audio or video. Separate from `hasVideo`, which the server sends too. */
enum class CallType(val wire: String) {
    Audio("audio"),
    Video("video"),
    ;

    companion object {
        fun ofWire(raw: String?): CallType =
            if (raw?.trim()?.lowercase() == Video.wire) Video else Audio
    }
}

/** Who started it — the one thing the payload never states outright. */
enum class CallDirection { Incoming, Outgoing }

/**
 * One person in a call.
 *
 * [agoraUid] arrives as a string on some payloads and a number on others, so
 * it is carried as the string the wire gave us and converted only at the point
 * the engine needs an int.
 */
data class CallParticipant(
    val userId: String,
    val deviceId: String = "",
    val agoraUid: String = "",
    val name: String = "",
    val image: String = "",
    val status: CallStatus = CallStatus.Ringing,
    val missedCall: Boolean = false,
    /** Their hand is up — the roster row's `raise_hand`. */
    val handRaised: Boolean = false,
) {
    /** The engine's numeric uid, or 0 when the server has not assigned one. */
    val numericUid: Int get() = agoraUid.trim().toIntOrNull() ?: 0
}

/**
 * Everything needed to join, display and end one call.
 *
 * Built once from the invite — whether that arrived on the socket or as the
 * response to our own initiate — and then carried whole. Android threads the
 * same fields through an Intent, a ViewModel and three companion objects, and
 * the fields drift apart; here there is one copy.
 */
@Suppress("LongParameterList")
data class CallSession(
    val callUuid: String,
    val roomId: String = "",
    val chatRoomId: String = "",
    val projectId: String = "",
    val direction: CallDirection = CallDirection.Incoming,
    val provider: CallProvider = CallProvider.Agora,
    val mode: CallMode = CallMode.Private,
    val type: CallType = CallType.Audio,
    val hasVideo: Boolean = false,
    /** Agora channel to join. Empty on a Line 1 call. */
    val channelName: String = "",
    /** Agora token for [channelName]. Never logged. */
    val token: String = "",
    /** This device's numeric uid in the channel, assigned by the server. */
    val localUid: Int = 0,
    val inviteCode: String = "",

    // ── Line 1 (mediasoup) ──────────────────────────────────────────────

    /**
     * Where the SFU lives, as the server elected it.
     *
     * `mediasoup_server_url` is canonical and `sfu_url` is its older alias;
     * the phones prefer the first and fall back to the second. A blank value
     * on a mediasoup call means the server did not elect a host, and there is
     * nothing to dial.
     */
    val sfuHost: String = "",

    /**
     * The SFU's own credential, appended to the dial URL as `token`.
     *
     * Empty is legitimate, not missing: the deployed backend still accepts a
     * tokenless dial, and iOS documents that as the backward-safe case.
     */
    val sfuToken: String = "",

    /**
     * `sfu` or `p2p` — but not the field to branch on. See [isPeerToPeer].
     */
    val connectionType: String = "",

    /**
     * The server's verdict that this call can go peer-to-peer.
     *
     * This, not [connectionType], is authoritative: the deployed backend
     * signals P2P eligibility here and sends no top-level `connection_type`
     * at all on the initiate response, so a client that branches on the
     * latter treats every elected P2P call as an SFU call.
     */
    val p2pEligible: Boolean = false,
    val callerUserId: String = "",
    val callerDeviceId: String = "",
    val callerName: String = "",
    val callerImage: String = "",
    /** Our own user id *in this call's production*, which may not be the open one. */
    val selfUserId: String = "",
    val selfDeviceId: String = "",
    /**
     * The device an outgoing call dialled.
     *
     * Kept because the roster can still be empty when the ring times out, and
     * the missed call has to be logged against the person who did not answer
     * rather than against the caller's own device.
     */
    val receiverDeviceId: String = "",
    val title: String = "",
    val participants: List<CallParticipant> = emptyList(),
    val isRandomCall: Boolean = false,
    val isCalendarCall: Boolean = false,
    val is247Call: Boolean = false,
    /** How many others besides us and the caller — drives "and 3 others". */
    val othersCount: Int = 0,
    val startedAtMillis: Long = 0,
) {
    /**
     * True once we have what Agora needs to join.
     *
     * Only Agora. The desktop implements Line 2 alone, and the other two
     * lines carry credentials this client cannot use — a Mediasoup invite
     * code, a LiveKit room URL. Reporting those joinable sent them into
     * `AgoraRTC.join("")`, which throws, and the ring the far side can hear
     * died on this end as "Call failed" a second after answering. Not
     * joinable is the honest answer: signalling still works, so the call
     * connects, statuses flow, and it is a call without media on this
     * device rather than no call at all.
     */
    val isJoinable: Boolean
        get() = when (provider) {
            CallProvider.Agora -> channelName.isNotBlank() && token.isNotBlank()
            // A dial needs a host and a room; the SFU's own token may be
            // empty, which is the backward-safe tokenless case rather than a
            // missing credential.
            CallProvider.Mediasoup -> sfuHost.isNotBlank() && sfuRoomId.isNotBlank()
            CallProvider.LiveKit -> false
        }

    /**
     * Which room this session names on the SFU.
     *
     * Three candidates in a fixed order, because the server populates
     * whichever it has: the phones read `invite_code || room_id || call_uuid`
     * and the desktop keeps the first two in separate fields. Collapsing this
     * to two puts the desktop in a different room from everyone else on any
     * payload that carries `room_id` without an `invite_code` — a call that
     * connects, reports itself healthy, and is silent.
     */
    val sfuRoomId: String
        get() = inviteCode.ifBlank { roomId }.ifBlank { callUuid }

    /**
     * Which room the `mediasoup-call` REST endpoints mean.
     *
     * NOT the same election as [sfuRoomId], which is why it has its own name.
     * The SFU dial prefers the invite code; the REST family never does — every
     * one of them takes the room or the call uuid, and the phones do the same.
     * The two look interchangeable and are not: sending the dial's answer to a
     * REST handler asks about a room it does not know, and `mediasoup-call`
     * answers that with HTTP 200 and `success: false`, so the mistake is
     * invisible.
     */
    val restRoomId: String
        get() = roomId.ifBlank { callUuid }

    /**
     * True when the server elected a direct connection rather than the SFU.
     *
     * Eligibility is the signal; `connection_type` is advisory and often
     * absent. A call is only NOT peer-to-peer when the server says `sfu`.
     */
    val isPeerToPeer: Boolean
        get() = p2pEligible && connectionType != CONNECTION_TYPE_SFU

    /** Participants with media, us included. */
    val connected: List<CallParticipant> get() = participants.filter { it.status.isConnected }

    /**
     * Whose name the call surface shows.
     *
     * Direction decides: an incoming ring is about the caller, an outgoing
     * one about who we are ringing — [title] carries that on outgoing calls,
     * because the server's own fields all describe the caller, which is us.
     */
    val displayName: String
        get() = when (direction) {
            CallDirection.Outgoing -> title.ifBlank { callerName }
            CallDirection.Incoming -> callerName.ifBlank { title }
        }

    /** Whose face goes with [displayName]. */
    val displayUserId: String
        get() = when (direction) {
            CallDirection.Outgoing ->
                participants.firstOrNull { it.userId != selfUserId && it.userId.isNotBlank() }
                    ?.userId ?: callerUserId
            CallDirection.Incoming -> callerUserId
        }

    /**
     * Redacted deliberately: [token] is a channel credential and
     * [callerImage] a signed URL, and both have a habit of ending up in
     * support tickets when a data class prints itself.
     */
    override fun toString(): String =
        "CallSession(uuid=$callUuid, room=$roomId, provider=$provider, mode=$mode, " +
            "type=$type, participants=${participants.size})"
}

/**
 * Someone who could be pulled into the current call — the add-people picker's
 * row. Built by the host from the production's crew, minus whoever is already
 * on the roster.
 */
data class CallCrewEntry(
    val userId: String,
    val deviceId: String,
    val name: String,
    val designation: String = "",
)

/** Ring and connect deadlines, matched to iOS so neither side gives up first. */
object CallTimeouts {
    const val OUTGOING_MS = 60_000L
    const val INCOMING_MS = 60_000L
    const val CONNECTION_MS = 15_000L
}
