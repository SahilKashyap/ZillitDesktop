package com.zillit.desktop.feature.chat.domain

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
    /** Changed after delivery — the bubble says so beside the time. */
    val isEdited: Boolean = false,
    /** The line this one quotes, when it is a reply; null for a plain message. */
    val replyTo: ChatReplyRef? = null,
)

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
