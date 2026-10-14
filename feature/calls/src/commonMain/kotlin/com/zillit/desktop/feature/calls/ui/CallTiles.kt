package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf
import com.zillit.desktop.feature.calls.domain.CallDirectoryEntry
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.LinkQuality
import com.zillit.desktop.feature.calls.domain.agoraScreenOwner

/** What a tile knows about someone's media. */
data class TileMedia(
    val speaking: Boolean = false,
    val audioMuted: Boolean = false,
    val videoOn: Boolean = false,
    val sharing: Boolean = false,
    val quality: LinkQuality = LinkQuality.Unknown,
    /** Their camera is reported off — see [MediaPeer.cameraOff]. */
    val cameraOff: Boolean = false,
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
    /**
     * Line 2: this tile is showing a shared screen and takes the stage. Lines
     * 1 and 3 leave it false — the page learns theirs from the track it mounts.
     */
    val presenting: Boolean = false,
)

private val ON_STAGE = setOf(CallStatus.Caller, CallStatus.Ringing, CallStatus.InCall)

/** Rows whose owner is in the channel, so whose stream a leftover uid can be. */
private val IN_CHANNEL = setOf(CallStatus.Caller, CallStatus.InCall)

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
     * User id → what the app can tell us about them, from the production's
     * crew. Keep-name-private members are absent rather than mapped to a
     * blank, so a lookup can never reveal one.
     *
     * The ROSTER is the authority for both fields; this is the fallback for
     * what it left out, which is exactly what the web's `nameOfRoster` and
     * `desigOf` do. Line 1 group rows arrive with no name at all, and the 1:1
     * mitigation below cannot help them — there is no single "other person" to
     * borrow from — so without this every face on a group stage read "Guest".
     */
    directory: (String) -> CallDirectoryEntry? = { null },
): List<CallTile> {
    session ?: return emptyList()
    if (session.provider != CallProvider.Agora) {
        return personTiles(session, media, selfName, micMuted, cameraOn, selfHand, directory)
    }
    // A Line 2 share stream is drawn as a screen beside its owner, never as a
    // person. As a person it was an unclaimed stream named after its owner —
    // keyed by their user id, the same key as their own tile — so pinning the
    // screen pinned whichever tile carried that key first: their face. And it
    // was an orphan stream, which can break the one-row-one-stream pairing.
    val screens = agoraScreens(session, media)
    val people = if (screens.isEmpty()) media else media.copy(peers = media.peers - screens.keys)
    val tiles = personTiles(session, people, selfName, micMuted, cameraOn, selfHand, directory)
    return withScreens(tiles, screens, media)
}

/** [buildTiles] for the people alone — every stream in [media] is someone. */
private fun personTiles(
    session: CallSession,
    media: CallMedia,
    selfName: String,
    micMuted: Boolean,
    cameraOn: Boolean,
    selfHand: Boolean,
    directory: (String) -> CallDirectoryEntry?,
): List<CallTile> {
    val selfUid = media.selfUid.takeIf { it != 0 } ?: session.localUid
    val roster = session.participants
        .filter { it.userId != session.selfUserId && it.status in ON_STAGE }
        .oneRowPerUser(media)
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
    val unclaimed = media.peers.keys.filter { it != 0 && it !in claimed }.sorted()
    // A 1:1 whose roster has no row for the other person at all, and one
    // stream nobody claims: that stream IS them — there is nobody else it
    // could be — so it wears the name on the call's own header, not "Guest".
    val loneStranger = unclaimed.singleOrNull()
        ?.takeIf { session.mode == CallMode.Private && roster.isEmpty() && session.displayName.isNotBlank() }
    return buildList {
        add(selfTile(session, media, selfName, micMuted, cameraOn, selfUid, selfHand))
        roster.forEach {
            add(rosterTile(it, media, bound[it.userId] ?: 0, theOtherPerson, directory))
        }
        unclaimed.forEach { uid ->
            val owner = offStageOwner(session, uid)
            when {
                uid == loneStranger -> add(guestTile(uid, media, session.displayName, session.displayUserId))
                // Someone the roster knows, under a status that is not on the
                // stage (a "left" that beat their stream closing, say): still
                // them, by name — never a stranger called Guest.
                owner != null -> add(
                    guestTile(
                        uid,
                        media,
                        owner.name.ifBlank { directory(owner.userId)?.name.orEmpty() },
                        owner.userId,
                    ),
                )
                // Nobody the roster knows: who the room says they are — the
                // crew's name for that id, else the name they joined under.
                else -> {
                    val peer = media.peers.getValue(uid)
                    // No id, no lookup: a directory asked about nobody answers with somebody.
                    val known = peer.identity.takeIf(String::isNotBlank)?.let(directory)?.name.orEmpty()
                    val name = known.ifBlank { peer.displayName }
                    add(guestTile(uid, media, name, peer.identity))
                }
            }
        }
    }
        // A tile's key is its person, and both consumers key by it — the stage
        // grid and the users panel's list. Two tiles under one key is not a
        // cosmetic fault there: `LazyColumn` throws, on the measure pass, and
        // took the window's event queue with it (2026-10-12, "Key
        // in-67810b989dcdb99a84298bc8 was already used"). [oneRowPerUser]
        // settles which row speaks for a person; this is the guarantee the
        // consumers actually rely on, including against a guest tile drawn for
        // somebody the roster already placed.
        .distinctBy(CallTile::key)
}

/**
 * One tile per person, whoever they are signed in on.
 *
 * The roster is a list of sessions, not of people: somebody who answered on a
 * second device, or who rejoined before the old session's close caught up, is
 * two rows carrying one user id. A tile's key *is* that user id, so those two
 * rows crashed the users panel outright rather than merely listing them twice.
 *
 * The row kept is the one that can be drawn truthfully: in the channel ahead
 * of still ringing, and between two of those, the device whose stream the
 * engine is really carrying — otherwise a person with a live camera on their
 * laptop draws from the phone row that is only ringing, dark and muted.
 */
private fun List<CallParticipant>.oneRowPerUser(media: CallMedia): List<CallParticipant> {
    val perUser = groupBy(CallParticipant::userId)
    if (perUser.size == size) return this
    // `distinctBy` keeps first-appearance order, so the stage keeps roster
    // order — tiles that re-sort during a call are their own complaint.
    return distinctBy(CallParticipant::userId).map { first ->
        val rows = perUser.getValue(first.userId)
        rows.firstOrNull { it.status in IN_CHANNEL && it.numericUid != 0 && it.numericUid in media.peers }
            ?: rows.firstOrNull { it.status in IN_CHANNEL }
            ?: first
    }
}

/**
 * Line 2's dedicated screen streams: share uid → the camera uid it belongs to.
 *
 * A camera is any stream in the channel or any uid a roster row names — the
 * presenter's own stream may not have published yet when their screen does.
 */
private fun agoraScreens(session: CallSession, media: CallMedia): Map<Int, Int> {
    val cameras = media.peers.keys + session.participants.mapNotNull { it.numericUid.takeIf { uid -> uid != 0 } }
    return media.peers.keys.mapNotNull { uid -> agoraScreenOwner(uid, cameras)?.let { uid to it } }.toMap()
}

/**
 * Each screen right after its owner's tile, and the stage's presenters marked.
 *
 * The desktop shares on its own camera uid instead, so a person tile whose
 * stream says it is sharing presents too — unless their screen has a tile of
 * its own, which the web spotlights instead (`callStore.js`: an explicit pin
 * on the camera of a dedicated sharer would cover the screen with a face).
 */
private fun withScreens(tiles: List<CallTile>, screens: Map<Int, Int>, media: CallMedia): List<CallTile> {
    val owners = screens.values.toSet()
    val placed = mutableSetOf<Int>()
    val out = buildList {
        tiles.forEach { tile ->
            val sharing = !tile.isSelf && tile.media?.sharing == true && tile.uid !in owners
            add(if (sharing) tile.copy(presenting = true) else tile)
            if (tile.uid !in owners) return@forEach
            screens.filterValues { it == tile.uid }.keys.sorted().forEach { uid ->
                add(screenTile(uid, tile, media))
                placed += uid
            }
        }
    }
    // A screen whose owner is not on the stage still shows: it is the one
    // thing on it everybody is meant to be looking at.
    return out + (screens.keys - placed).sorted().map { screenTile(it, null, media) }
}

/** [uid]'s shared screen, named for [owner] so the "is presenting" pill can say whose. */
private fun screenTile(uid: Int, owner: CallTile?, media: CallMedia): CallTile {
    val peer = media.peers.getValue(uid)
    return CallTile(
        key = "$SCREEN_KEY_PREFIX$uid",
        name = owner?.name ?: peer.displayName.ifBlank { UNNAMED },
        userId = owner?.userId ?: peer.identity,
        uid = uid,
        presence = owner?.presence ?: CallStatus.InCall,
        // A screen has no microphone and no camera to switch off: never a
        // mute badge, never the frozen-frame guard that hides a camera.
        media = TileMedia(videoOn = peer.videoOn, sharing = true, quality = peer.quality),
        presenting = true,
    )
}

/** The roster row, in any status, whose identity a leftover stream [uid] is. */
private fun offStageOwner(session: CallSession, uid: Int): CallParticipant? {
    val hashed = session.provider == CallProvider.Mediasoup || session.provider == CallProvider.LiveKit
    return session.participants.firstOrNull { row ->
        row.userId.isNotBlank() && row.userId != session.selfUserId &&
            (if (hashed) mediasoupUidOf(row.userId) else row.numericUid) == uid
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
    if (session.provider == CallProvider.LiveKit) {
        return roster.associate { it.userId to mediasoupUidOf(it.userId) }
    }
    if (session.provider == CallProvider.Mediasoup) {
        val hashed = roster.associate { it.userId to mediasoupUidOf(it.userId) }
        // A phone can join the room under a different id than its row carries
        // (2026-10-05): its stream then matched nobody and drew as "Guest"
        // beside the person's own tile, empty. One row in the room with no
        // stream and one stream with no row are each other — beyond that this
        // refuses to guess, as Line 2's pairing below does.
        val silent = roster.filter { it.status in IN_CHANNEL && hashed.getValue(it.userId) !in media.peers }
        val orphan = media.peers.keys.filter { it != selfUid && it !in hashed.values }
        return if (silent.size == 1 && orphan.size == 1) {
            hashed + (silent.first().userId to orphan.first())
        } else {
            hashed
        }
    }
    val known = roster.filter { it.numericUid != 0 }.associate { it.userId to it.numericUid }
    // The caller counts as in the channel: on an incoming call the other
    // person's row reads `caller`, not `in_call`, and leaving them out of the
    // pairing left their video an unclaimed stream — a face named "Guest".
    val unbound = roster.filter { it.status in IN_CHANNEL && it.numericUid == 0 }
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
    directory: (String) -> CallDirectoryEntry? = { null },
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
            .ifBlank { directory(person.userId)?.name.orEmpty() }
            .ifBlank { fallbackName }
            .ifBlank { UNNAMED },
        userId = person.userId,
        uid = uid,
        presence = person.status,
        // peers never holds uid 0, so an unmapped row falls out as null here.
        media = media.peers[uid]?.let {
            TileMedia(
                uid in media.speaking, it.audioMuted, it.videoOn, it.sharing, it.quality,
                cameraOff = it.cameraOff,
            )
        },
        hand = person.handRaised,
        onHold = person.onHold && person.status.isConnected,
        isGuest = person.isGuest,
        // A guest has no job title in this production and never will; looking
        // one up would find whoever happens to share their id shape.
        designation = person.designation.ifBlank {
            if (person.isGuest) "" else directory(person.userId)?.designation.orEmpty()
        },
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
    name = selfName.ifBlank { str(S.you) },
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
private fun guestTile(uid: Int, media: CallMedia, name: String = "", userId: String = ""): CallTile {
    val peer = media.peers.getValue(uid)
    return CallTile(
        key = userId.ifBlank { "uid:$uid" },
        name = name.ifBlank { UNNAMED },
        userId = userId,
        uid = uid,
        media = TileMedia(
            uid in media.speaking, peer.audioMuted, peer.videoOn, peer.sharing, peer.quality,
            cameraOff = peer.cameraOff,
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
private const val SCREEN_KEY_PREFIX = "screen:"
private val UNNAMED: String get() = str(S.txt_badge_guest)
