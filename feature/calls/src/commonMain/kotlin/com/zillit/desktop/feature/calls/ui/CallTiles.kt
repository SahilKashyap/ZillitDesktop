package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.LinkQuality

/** What a tile knows about someone's media. */
data class TileMedia(
    val speaking: Boolean = false,
    val audioMuted: Boolean = false,
    val videoOn: Boolean = false,
    val sharing: Boolean = false,
    val quality: LinkQuality = LinkQuality.Unknown,
)

/**
 * One face on the stage.
 *
 * [media] is null when nothing the engine reported can be attributed to this
 * person — the roster row arrived without an `agora_uid`, which the server
 * often omits. Such a tile shows who they are and nothing about their
 * microphone: a mute badge on the wrong face is worse than no badge at all.
 */
data class CallTile(
    val key: String,
    val name: String,
    val userId: String = "",
    val uid: Int = 0,
    val isSelf: Boolean = false,
    val presence: CallStatus = CallStatus.InCall,
    val media: TileMedia? = null,
    /** Their hand is up — drawn on the tile as well as said in the banner. */
    val hand: Boolean = false,
    /** Line 3: on hold — in the room, sending and hearing nothing. */
    val onHold: Boolean = false,
    /** Line 3: a link guest, chipped as such. */
    val isGuest: Boolean = false,
    /** Job title under the name, where the roster reports one. */
    val designation: String = "",
)

private val ON_STAGE = setOf(CallStatus.Caller, CallStatus.Ringing, CallStatus.InCall)

/**
 * The stage's people, from the roster and the media stack together.
 *
 * Order is us, then roster order, then unclaimed streams — and it never
 * changes because somebody spoke. Tiles that re-sort on speech are the single
 * most disliked thing a call grid can do.
 */
fun buildTiles(
    session: CallSession?,
    media: CallMedia,
    selfName: String,
    micMuted: Boolean,
    cameraOn: Boolean,
    selfHand: Boolean = false,
    /**
     * User id → the name we are allowed to show, from the open production's
     * crew. Keep-name-private members are absent from the map rather than
     * mapped to a blank, so a lookup can never reveal one.
     *
     * Line 1 group rows arrive with no name at all, and the 1:1 mitigation
     * below cannot help them — there is no single "other person" to borrow
     * from. Without this every face on a group stage read "Guest".
     */
    nameFor: (String) -> String? = { null },
): List<CallTile> {
    session ?: return emptyList()
    val selfUid = media.selfUid.takeIf { it != 0 } ?: session.localUid
    val roster = session.participants
        .filter { it.userId != session.selfUserId && it.status in ON_STAGE }
    val bound = bindUids(session, roster, media, selfUid)
    val claimed = bound.values.toSet() + selfUid
    // On a 1:1 the invite names the other person even when their roster row
    // does not — it is the name already on the call's own header, so a tile
    // reading "Guest" beside a window titled "Samsung Device" is this client
    // knowing the answer and not using it. Line 1 is where it shows: nothing
    // there writes `user_name` onto the row for the healer to adopt.
    val theOtherPerson = session.displayName
        .takeIf { session.mode == CallMode.Private && roster.size == 1 }
        .orEmpty()
    return buildList {
        add(selfTile(session, media, selfName, micMuted, cameraOn, selfUid, selfHand))
        roster.forEach {
            add(rosterTile(it, media, bound[it.userId] ?: 0, theOtherPerson, nameFor))
        }
        media.peers.keys.filter { it != 0 && it !in claimed }.sorted()
            .forEach { add(guestTile(it, media)) }
    }
}

/**
 * Roster row → engine uid.
 *
 * Line 1 needs no guessing: nobody issues a uid there, the desktop numbers
 * peers by a stable hash of their user id, and the same derivation applied to
 * the roster binds every row deterministically.
 *
 * On Line 2, `agora_uid` is what every platform maps by, and it is often
 * absent from the invite: the desktop then joins with uid 0 and Agora issues
 * one, so even our own number is not the number in the payload. Where exactly
 * one row and exactly one stream are left over they must be each other —
 * beyond that this refuses to guess, because a wrong binding puts one
 * person's speaking ring on another person's face.
 */
private fun bindUids(
    session: CallSession,
    roster: List<CallParticipant>,
    media: CallMedia,
    selfUid: Int,
): Map<String, Int> {
    // Lines 1 and 3 number a peer by hashing their identity — the page and
    // Kotlin agree on the hash — so a roster row binds without a uid on it.
    if (session.provider == CallProvider.Mediasoup || session.provider == CallProvider.LiveKit) {
        return roster.associate { it.userId to mediasoupUidOf(it.userId) }
    }
    val known = roster.filter { it.numericUid != 0 }.associate { it.userId to it.numericUid }
    val unbound = roster.filter { it.status == CallStatus.InCall && it.numericUid == 0 }
    val orphan = media.peers.keys.filter { it != selfUid && it !in known.values }
    return if (unbound.size == 1 && orphan.size == 1) {
        known + (unbound.first().userId to orphan.first())
    } else {
        known
    }
}

/**
 * One person from the roster.
 *
 * [fallbackName] is what the call itself knows about them when their row does
 * not say — blank unless this is a 1:1, where there is exactly one candidate
 * and no chance of putting the wrong name on a face.
 */
private fun rosterTile(
    person: CallParticipant,
    media: CallMedia,
    uid: Int,
    fallbackName: String = "",
    nameFor: (String) -> String? = { null },
): CallTile =
    CallTile(
        // Keyed by user id, so a late `agora_uid` from the call-dump merge is
        // ADOPTED by the existing tile instead of appearing as a new arrival.
        key = person.userId.ifBlank { "uid:$uid" },
        // The server's own name wins, so a call that shows names today is
        // unchanged. `ifBlank` all the way down rather than `?:`: a directory
        // that declines to name somebody must fall through to Guest, never
        // render an empty caption.
        name = person.name
            .ifBlank { nameFor(person.userId).orEmpty() }
            .ifBlank { fallbackName }
            .ifBlank { UNNAMED },
        userId = person.userId,
        uid = uid,
        presence = person.status,
        // peers never holds uid 0, so an unmapped row falls out as null here.
        media = media.peers[uid]?.let {
            TileMedia(uid in media.speaking, it.audioMuted, it.videoOn, it.sharing, it.quality)
        },
        hand = person.handRaised,
        onHold = person.onHold && person.status.isConnected,
        isGuest = person.isGuest,
        designation = person.designation,
    )

private fun selfTile(
    session: CallSession,
    media: CallMedia,
    selfName: String,
    micMuted: Boolean,
    cameraOn: Boolean,
    selfUid: Int,
    selfHand: Boolean,
): CallTile = CallTile(
    key = SELF_KEY,
    name = selfName.ifBlank { "You" },
    userId = session.selfUserId,
    uid = selfUid,
    isSelf = true,
    // Our own mute and camera come from the coordinator, never from media: the
    // stack reports other people's tracks, and a self badge sourced from it
    // would lag the button the user just pressed.
    media = TileMedia(
        speaking = !micMuted && (selfUid in media.speaking || 0 in media.speaking),
        audioMuted = micMuted,
        videoOn = cameraOn,
        quality = media.selfQuality,
    ),
    hand = selfHand,
)

/**
 * A stream nobody in the roster claims.
 *
 * Guests join by link and never get a `call_users` row, and a roster snapshot
 * can simply be older than the channel. Their video is already on the stage,
 * so leaving them out would be a person on screen who does not exist in the UI.
 */
private fun guestTile(uid: Int, media: CallMedia): CallTile {
    val peer = media.peers.getValue(uid)
    return CallTile(
        key = "uid:$uid",
        name = UNNAMED,
        uid = uid,
        media = TileMedia(
            uid in media.speaking, peer.audioMuted, peer.videoOn, peer.sharing, peer.quality,
        ),
    )
}

/** One shape for both grids: the Compose stage and the page's own. */
fun columnsFor(count: Int): Int = when {
    count <= ONE_UP -> ONE_COLUMN
    count <= TWO_UP -> TWO_COLUMNS
    count <= THREE_UP -> THREE_COLUMNS
    else -> FOUR_COLUMNS
}

const val GRID_CAP = 12
private const val ONE_COLUMN = 1
private const val TWO_COLUMNS = 2
private const val THREE_COLUMNS = 3
private const val FOUR_COLUMNS = 4

/** The largest crowd each column count still gives a 16:9 cell. */
private const val ONE_UP = 1
private const val TWO_UP = 4
private const val THREE_UP = 9
private const val SELF_KEY = "self"
private const val UNNAMED = "Guest"
