package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The requests a mediasoup join makes, in the order it makes them.
 *
 * Builders rather than inline literals because the order and the field names
 * are the protocol: `producing`/`consuming` decide which direction a transport
 * carries, and a pair created with the wrong flags connects, reports healthy,
 * and moves no media.
 */
object MediasoupJoin {

    /** Step 1. The router's codecs, which the device is then loaded from. */
    const val GET_ROUTER_CAPABILITIES = "getRouterRtpCapabilities"

    /** Step 2, twice: one transport out, one in. */
    const val CREATE_TRANSPORT = "createWebRtcTransport"

    /** Step 3, once both transports exist. */
    const val JOIN = "join"

    /** Fired by the page's transport callbacks, not by the join sequence. */
    const val CONNECT_TRANSPORT = "connectWebRtcTransport"
    const val PRODUCE = "produce"
    const val CLOSE_PRODUCER = "closeProducer"
    const val PAUSE_PRODUCER = "pauseProducer"
    const val RESUME_PRODUCER = "resumeProducer"
    const val RESUME_CONSUMER = "resumeConsumer"

    /**
     * The app-level Zillit extensions, exactly as iOS sends them
     * (`sendPeerRequest`, fire-and-forget): a hand moving is
     * `{"raisedHand": Bool}`; recording start/stop carry the full identity
     * quartet because the server relays them to web clients verbatim.
     */
    const val TOGGLE_HAND_RAISE = "toggleHandRaise"
    const val START_CLIENT_RECORDING = "startClientRecording"
    const val STOP_CLIENT_RECORDING = "stopClientRecording"

    /** The recording NOTIFICATION beside the request — iOS sends both. */
    const val RECORDING_NOTIFY = "recording"

    fun handRaise(raised: Boolean): JsonObject = buildJsonObject { put("raisedHand", raised) }

    fun clientRecording(userId: String, deviceId: String, recording: Boolean): JsonObject =
        buildJsonObject {
            // On mediasoup the broadcast routing key is the USER id — the
            // receiver's participant rows carry userIds in both identity
            // fields, and iOS's ZL-18123 fix documents that a deviceId here
            // silently matches nobody.
            put("peerId", userId)
            put("userId", userId)
            put("deviceId", deviceId)
            put("recording", recording)
        }

    fun recordingNotification(
        userId: String,
        deviceId: String,
        callUuid: String,
        recording: Boolean,
    ): JsonObject = buildJsonObject {
        put("peerId", userId)
        put("recording", recording)
        put("callUuid", callUuid)
        put("deviceId", deviceId)
        put("userId", userId)
    }

    /**
     * Asks for one transport.
     *
     * The two are requested concurrently: each is an independent round-trip and
     * doing them in sequence adds a whole RTT to every join, on the one
     * exchange a user is actually waiting through.
     */
    fun createTransport(producing: Boolean, sctpCapabilities: JsonObject?): JsonObject =
        buildJsonObject {
            // TCP is a fallback the SFU offers per-candidate; forcing it here
            // would give up UDP for everyone, including the majority for whom
            // it works.
            put("forceTcp", false)
            put("producing", producing)
            put("consuming", !producing)
            sctpCapabilities?.let { put("sctpCapabilities", it) }
        }

    /**
     * Announces this peer to the room.
     *
     * Sent only after both transports exist. The SFU starts offering consumers
     * the moment this lands, and an offer arriving before the receive transport
     * is built has nowhere to go.
     */
    fun join(displayName: String, rtpCapabilities: JsonObject, sctpCapabilities: JsonObject?): JsonObject =
        buildJsonObject {
            put("displayName", displayName)
            put("device", buildJsonObject {
                // What the SFU logs against this peer. Honest rather than
                // impersonating a phone: a support question about a desktop
                // call should not read as an iPhone.
                put("flag", "desktop")
                put("name", "Zillit Desktop")
                put("version", "1.0")
            })
            put("rtpCapabilities", rtpCapabilities)
            sctpCapabilities?.let { put("sctpCapabilities", it) }
        }

    fun producerId(id: String): JsonObject = buildJsonObject { put("producerId", id) }

    fun consumerId(id: String): JsonObject = buildJsonObject { put("consumerId", id) }
}

/**
 * The notifications the SFU sends that this client acts on.
 *
 * Named because the set is not obvious and the misses are silent: an unhandled
 * `consumerPaused` leaves a muted peer looking live, and an unhandled
 * `peerClosed` leaves a tile for somebody who has gone.
 */
object MediasoupNotification {
    const val NEW_PEER = "newPeer"
    const val PEER_CLOSED = "peerClosed"
    const val CONSUMER_CLOSED = "consumerClosed"
    const val CONSUMER_PAUSED = "consumerPaused"
    const val CONSUMER_RESUMED = "consumerResumed"
    const val ACTIVE_SPEAKER = "activeSpeaker"
    const val PEER_RAISED_HAND = "peerRaisedHand"
    const val PEER_LOWERED_HAND = "peerLoweredHand"
    const val PEER_SCREEN_SHARE_STARTED = "peerScreenShareStarted"
    const val PEER_SCREEN_SHARE_STOPPED = "peerScreenShareStopped"
    const val PEER_RECORDING_STARTED = "peerRecordingStarted"
    const val PEER_RECORDING_STOPPED = "peerRecordingStopped"
    const val RECORDING = "recording"
    const val END_CALL = "endCall"

    /**
     * The two the SFU sends as REQUESTS rather than notifications, and which
     * must therefore be answered.
     *
     * `newConsumer` especially: the SFU abandons a consumer whose request goes
     * unanswered, so the accept has to go out before any media work begins,
     * not after it.
     */
    const val NEW_CONSUMER = "newConsumer"
    const val NEW_DATA_CONSUMER = "newDataConsumer"
}

/**
 * The peer id this client presents: `userId:deviceId`.
 *
 * Both halves matter. The user half is what remote clients bind a tile to, so
 * it must survive a rejoin; the device half is what makes two of one person's
 * devices distinct peers rather than one evicting the other.
 */
fun mediasoupPeerId(userId: String, deviceId: String): String = "$userId:$deviceId"

/** The user half, which is the identity a tile is bound to. */
fun mediasoupIdentityOf(peerId: String): String = peerId.substringBefore(':')

/** The device half, or blank when the id carried none. */
fun mediasoupDeviceOf(peerId: String): String = peerId.substringAfter(':', "")

/**
 * A stable numeric uid for a Line 1 identity, because the desktop's media
 * picture and its tiles are keyed by uid.
 *
 * Derived from the USER half only, so a rejoin — which changes the device half
 * — keeps the same tile rather than appearing as somebody new. One derivation
 * shared by the session (which numbers protoo events) and the tile builder
 * (which numbers roster rows): two copies of this hash drifting apart is a
 * peer whose events land on nobody's tile.
 */
fun mediasoupUidOf(identity: String): Int {
    val hash = identity.hashCode()
    return (if (hash < 0) -hash else hash) % MEDIASOUP_UID_RANGE + MEDIASOUP_UID_FLOOR
}

private const val MEDIASOUP_UID_RANGE = 9_900_000
private const val MEDIASOUP_UID_FLOOR = 100_000

/**
 * A peer id for rejoining after the socket came back.
 *
 * It must NOT be the one that was used before. Rejoining under the same peer id
 * makes the SFU evict the old peer and broadcast `peerClosed` for it — and the
 * remote, which never lost anything, reads that as everybody having left and
 * ends the call. The local side cannot prevent it, because from the remote's
 * point of view nothing went wrong.
 *
 * The user half is preserved so remotes rebind the existing tile rather than
 * drawing a stranger; only the device half is freshened. The old peer is left
 * to the SFU's own inactivity timeout, which expires it without telling anyone.
 *
 * A second rejoin replaces the previous suffix rather than stacking on it, so a
 * flapping connection cannot grow an unbounded id.
 */
fun mediasoupRejoinPeerId(peerId: String, random: kotlin.random.Random = kotlin.random.Random): String {
    val base = peerId.substringBefore(REJOIN_MARKER)
    val suffix = (1..REJOIN_SUFFIX_LENGTH)
        .map { REJOIN_ALPHABET[random.nextInt(REJOIN_ALPHABET.length)] }
        .joinToString("")
    return "$base$REJOIN_MARKER$suffix"
}

private const val REJOIN_MARKER = "-r-"
private const val REJOIN_SUFFIX_LENGTH = 8
private const val REJOIN_ALPHABET = "0123456789abcdef"

/**
 * The join parameters for this session, whichever line it is on.
 *
 * A function rather than a block inside the coordinator because everything it
 * decides is worth a test: which of three candidates names the room, what this
 * client calls itself on the SFU, and the fact that a host arrives needing to
 * be stripped of whatever scheme the server wrapped it in.
 */
fun CallSession.toJoin(selfDeviceId: String, displayName: String): CallJoin = CallJoin(
    provider = provider,
    hasVideo = hasVideo,
    channel = channelName,
    token = token,
    uid = localUid,
    sfuHost = normaliseSfuHost(sfuHost),
    roomId = sfuRoomId,
    peerId = mediasoupPeerId(selfUserId, selfDeviceId),
    sfuToken = sfuToken,
    displayName = displayName,
)
