package com.zillit.desktop.feature.calls.domain

/**
 * Link quality, folded from the SDK's 0..6 scale.
 *
 * Named rather than numeric because the only thing a UI does with a link score
 * is pick a colour and a word, and every SDK numbers its scale differently —
 * Agora's 1 is the best reading, WebRTC's is the worst.
 */
enum class LinkQuality {
    Unknown, Excellent, Good, Poor, Bad, Down;

    /** True when the user should be told. Silence is the good state. */
    val isTrouble: Boolean get() = this == Poor || this == Bad || this == Down

    companion object {
        private const val EXCELLENT = 1
        private const val GOOD = 2
        private const val POOR = 3
        private const val BAD = 4
        private const val VERY_BAD = 5
        private const val DOWN = 6

        /** The worse of the two legs: a call is only as good as its weaker one. */
        fun ofAgora(tx: Int, rx: Int): LinkQuality = when (maxOf(tx, rx)) {
            EXCELLENT -> Excellent
            GOOD -> Good
            POOR -> Poor
            BAD, VERY_BAD -> Bad
            DOWN -> Down
            else -> Unknown
        }
    }
}

/** One remote stream as the media stack sees it, keyed by engine uid. */
data class MediaPeer(
    val uid: Int,
    val audioMuted: Boolean = false,
    val videoOn: Boolean = false,
    val sharing: Boolean = false,
    val quality: LinkQuality = LinkQuality.Unknown,
)

/**
 * What the media stack is doing right now.
 *
 * Keyed by engine uid and nothing else. The stack speaks uids, the roster
 * speaks user ids, and the single place those vocabularies meet is
 * `buildTiles`. Doing the mapping here would make every event re-derive it.
 */
data class CallMedia(
    val connection: EngineConnection = EngineConnection.Connecting,
    val channel: String = "",
    val selfUid: Int = 0,
    val selfQuality: LinkQuality = LinkQuality.Unknown,
    val peers: Map<Int, MediaPeer> = emptyMap(),
    val speaking: Set<Int> = emptySet(),
    /** This machine is sharing its screen. */
    val selfSharing: Boolean = false,
)

/**
 * Folds one engine event into the media picture.
 *
 * Pure and total, so the whole live-state surface is testable without
 * Chromium, a channel, or a second person — which is the only way any of it
 * gets checked on one machine.
 */
@Suppress("CyclomaticComplexMethod")
fun CallMedia.reduce(event: CallEngineEvent): CallMedia = when (event) {
    is CallEngineEvent.Joined ->
        copy(channel = event.channel, selfUid = event.uid, connection = EngineConnection.Connected)
    is CallEngineEvent.Left -> CallMedia()
    is CallEngineEvent.PeerJoined -> withPeer(event.uid) { it }
    is CallEngineEvent.PeerLeft -> copy(peers = peers - event.uid, speaking = speaking - event.uid)
    is CallEngineEvent.PeerAudioMuted -> withPeer(event.uid) { it.copy(audioMuted = event.muted) }
    is CallEngineEvent.PeerVideoMuted -> withPeer(event.uid) { it.copy(videoOn = !event.muted) }
    // A chat line changes nobody's media; the coordinator carries it to the panel.
    is CallEngineEvent.ChatReceived -> this
    is CallEngineEvent.PeerScreenShare -> withPeer(event.uid) { it.copy(sharing = event.sharing) }
    is CallEngineEvent.ActiveSpeakers -> copy(speaking = event.uids.toSet())
    is CallEngineEvent.NetworkQuality -> withQuality(event.uid, LinkQuality.ofAgora(event.tx, event.rx))
    is CallEngineEvent.ConnectionChanged -> copy(connection = event.state)
    // The hardware list is not part of the media picture — the coordinator
    // holds it, because a picker is open outside any one call's lifetime.
    CallEngineEvent.TokenExpiring -> this
    CallEngineEvent.TokenExpired -> this
    is CallEngineEvent.ScreenShare -> copy(selfSharing = event.sharing)
    is CallEngineEvent.Devices -> this
    is CallEngineEvent.Failed -> this
    // The call carries on; only the UI has something to say about it.
    is CallEngineEvent.Degraded -> this
    // Hands and recording are roster facts keyed by user, not media facts
    // keyed by uid — the coordinator folds them into the participant list.
    is CallEngineEvent.PeerHand -> this
    is CallEngineEvent.PeerRecording -> this
    is CallEngineEvent.RecordingSaved -> this
}

/** Upsert, never ignore: an event for an unseen uid creates that peer. */
private fun CallMedia.withPeer(uid: Int, edit: (MediaPeer) -> MediaPeer): CallMedia {
    // uid 0 is both the engine's self marker and the roster's "never told us".
    // Admitting it would make this device a stranger in its own call.
    if (uid == 0 || uid == selfUid) return this
    return copy(peers = peers + (uid to edit(peers[uid] ?: MediaPeer(uid))))
}

private fun CallMedia.withQuality(uid: Int, quality: LinkQuality): CallMedia =
    if (uid == 0 || uid == selfUid) copy(selfQuality = quality)
    else withPeer(uid) { it.copy(quality = quality) }
