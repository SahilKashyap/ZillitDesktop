package com.zillit.desktop.feature.chat.domain

import kotlinx.serialization.Serializable

/** One direct message, decrypted and ready to draw. */
data class ChatMessage(
    val id: String,
    val uniqueId: String,
    val senderId: String,
    val receiverId: String,
    val body: String,
    val timestampMillis: Long,
    val isMine: Boolean,
    val sendState: ChatSendState = ChatSendState.Sent,
    /** The wire's cipher-hex body, kept for the at-rest cache. */
    val bodyCipher: String = "",
    /** True when this rode `group_chat` — the receiver is a room, not a person. */
    val isGroup: Boolean = false,
    /** Every reaction on this message, one row per person. */
    val reactions: List<ChatReaction> = emptyList(),
    val attachment: ChatAttachment? = null,
    /**
     * The place this message shares, when its `message_type` is `location`;
     * null for every other kind. Rides the envelope beside [attachment], not
     * inside it — see [ChatLocation].
     */
    val location: ChatLocation? = null,
    /** Changed after delivery — the bubble says so beside the time. */
    val isEdited: Boolean = false,
    /** The line this one quotes, when it is a reply; null for a plain message. */
    val replyTo: ChatReplyRef? = null,
)

/**
 * A shared place — the wire's own `location` object, field for field.
 *
 * ## Where it rides
 *
 * TOP LEVEL on the message, a sibling of `attachment`, never inside it:
 * Android's `ChatAndGroupModel.location: LocationInfo?`
 * (`chatAndGroupChat/model/ChatAndGroupRequestModelHandler.kt:42`), set from
 * the `location` parameter of `postDataInChat`
 * (`baseUtils/CommonApis.kt:1878,1889` and the top-level twin at `:2366,:2377`).
 * The web builds the same key at `pages/cnc_latest/cncUtil.js:312-315`.
 *
 * ## The field names
 *
 * `LocationInfo` (`bottomNav/home/models/HomeChatRequest.kt:228-239`) is
 * `{lat, long, address, imageLink, height, width}` — the longitude is spelled
 * **`long`**, Zillit's habit, and the web agrees (`cncUtil.js:314`
 * `long: location?.lng`). `imageLink/height/width` describe the map
 * SCREENSHOT the phones take (`mapView/MapsActivity.kt:205-224`) and upload as
 * the message's attachment; this desktop has no map raster to take, so it
 * carries the three fields that describe the place itself and nothing else.
 *
 * ## The message body
 *
 * A location message DOES carry an encrypted body like any other — the phones
 * send the picked place's description, which defaults to its address
 * (`utils/MediaExtension.kt:308-322` builds the gallery item with
 * `description = address`, and `ChatAndGroupVM.uploadingDataMapper` passes it
 * as `mMessage` at `ChatAndGroupVM.kt:605-615`, encrypted at `:399-404`). Both
 * phones then HIDE that body when they draw the bubble
 * (`viewholders/HoldersViewhandler.kt:359,371`), and the web's caption is
 * commented out (`components/sendMessage/RenderLocation.jsx:98-103`) — so the
 * body is the label this desktop is free to show, and the address in
 * [address] is the durable truth about the place.
 *
 * `@Serializable` for one reason: a location written with no network waits in
 * the outbox as JSON (`QueuedChatSend`).
 */
@Serializable
data class ChatLocation(
    /**
     * The place's address as the sender's picker resolved it. Optional on the
     * wire — the web sends `{lat, long}` alone (`cncUtil.js:312-315`), so a
     * message from a browser arrives with nothing but the pin.
     */
    val address: String = "",
    val lat: Double,
    /**
     * The longitude. Named `lng` here because that is what the shared picker
     * answers (`core:locationpicker`'s `PickedLocation`); the rename to the
     * wire's `long` happens once, in `ChatWire`, where every other wire
     * spelling lives.
     */
    val lng: Double,
) {
    /**
     * Where "Open in Maps" goes — the URL every reference client builds from
     * the pair: web `pages/cnc_latest/Util.jsx:27` and
     * `components/unit-chat/message-types/LocationMessage.jsx:43`
     * (`https://www.google.com/maps?q=lat,long`), Android's
     * `openLocationFromCoordinates` off the same two fields
     * (`viewholders/HoldersViewhandler.kt:306-318`).
     */
    val mapsUrl: String get() = "https://www.google.com/maps?q=$lat,$lng"
}

/**
 * The quoted parent a reply carries — Android's `Reply_chat`
 * (`chatAndGroupChat/model/ChatAndGroupRequestModelHandler.kt:113-122`),
 * built from the parent message at `ChatAndGroupVM.kt:451-459`. The wire's
 * `message` field travels encrypted like any body; here it is already the
 * plain words, decrypted on read and encrypted again on send.
 */
data class ChatReplyRef(
    /** The parent's server `_id` — what a tap on the quote jumps to. */
    val messageId: String,
    val senderId: String,
    /** The parent's plain words; empty when it was a bare file. */
    val body: String,
    /** The parent's `message_type` — text, image, video, audio, document. */
    val kind: String = "text",
    /** The parent attachment's file name, for a file-only quote line. */
    val attachmentName: String = "",
)

/** A file riding a message — the storage key and enough to fetch it back. */
data class ChatAttachment(
    val media: String,
    val name: String,
    val contentType: String = "",
    val bucket: String = "",
    val region: String = "",
    /** The poster frame's own storage key; empty when there is none. */
    val thumbnail: String = "",
    val widthPx: Long = 0,
    val heightPx: Long = 0,
    val durationMillis: Long = 0,
) {
    /** Android's `message_type` word for this file. */
    val kind: String
        get() = when {
            contentType.startsWith("image") -> "image"
            contentType.startsWith("video") -> "video"
            contentType.startsWith("audio") -> "audio"
            else -> "document"
        }
}

/** One person's emoji on one message — the wire's `reactions` rows. */
data class ChatReaction(val userId: String, val emoji: String)

/** One group room, as `GET chat-room` lists them. */
data class GroupRoom(
    val id: String,
    val name: String,
    /** The creator's user id (`owned_by`) — deleting a group is theirs alone. */
    val ownedBy: String? = null,
    /** The owning department, when the room is a department's — see `hasStanding`. */
    val departmentId: String? = null,
    /**
     * The room's newest-message stamp, from the `chat-room` row itself
     * (Android `GetRoomsModel.sorting_activity`). Durable where the
     * notification backlog is not: a fully read room's backlog rows age out,
     * but this survives, so the room keeps its place and its standing.
     */
    val sortingActivity: Long = 0L,
)

/**
 * How far a message has got, as the wire counts it.
 *
 * The server's `status` ladder, Android's `HoldersViewhandler` for icon: -1/0
 * still leaving this device, 1 accepted by the server, 2 on the recipient's
 * device, 3 opened by them. [Failed] is ours alone — the wire has no word for
 * a message that never left.
 */
enum class ChatSendState(val wire: Int) {
    Sending(PENDING),
    /**
     * Ours alone, like [Failed]: written with no network and kept on this
     * computer to go automatically when it is back. Wears the clock the way
     * the phones' pending messages do.
     */
    Queued(PENDING),
    Sent(ACCEPTED),
    Delivered(ON_DEVICE),
    Read(OPENED),
    Failed(NEVER_LEFT),
    ;

    /** Later states never fall back: a read message must not become delivered. */
    fun atLeast(other: ChatSendState): Boolean = wire >= other.wire

    companion object {
        /** The wire's number, tolerantly — an unknown value counts as sent. */
        fun ofWire(status: Int?): ChatSendState = when (status) {
            null, QUEUED, PENDING -> Sending
            ON_DEVICE -> Delivered
            OPENED -> Read
            else -> Sent
        }
    }
}

// The server's own numbers; see Android's `HoldersViewhandler` for the icons
// each one draws.
private const val QUEUED = -1
private const val PENDING = 0
private const val ACCEPTED = 1
private const val ON_DEVICE = 2
private const val OPENED = 3

/** Ours, not the wire's: below every real state so it never wins a comparison. */
private const val NEVER_LEFT = -2
