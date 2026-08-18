package com.zillit.desktop.feature.home.domain

/**
 * One post on a unit's notice board.
 *
 * `HomeChatInfo` on Android. [body] arrives **AES-encrypted** with the header
 * key and is decrypted client-side — see `NoticesRepositoryImpl`.
 */
data class Notice(
    val id: String,
    val body: String,
    val authorName: String,
    val authorId: String? = null,
    val createdAtMillis: Long = 0,
    /**
     * Last touched. Both clients order the **live** board by this
     * (`HomeVm:434`, `NoticesV2:81`), so an edited post moves to the bottom
     * where the people reading will see it changed.
     */
    val updatedAtMillis: Long = 0,
    val isEdited: Boolean = false,
    val isPinned: Boolean = false,
    /** How this post renders: text, image, document… The wire's `message_type`. */
    val kind: NoticeKind = NoticeKind.Text,
    /** At most one per post; see [NoticeAttachment]. */
    val attachment: NoticeAttachment? = null,
    /** Where a location post points. The attachment is its map screenshot. */
    val location: GeoPoint? = null,
    /** Replies, oldest first — rendered inside this post's bubble, as the web does. */
    val comments: List<NoticeComment> = emptyList(),
    /**
     * Local only — the server has no such field.
     *
     * Lets the board show a post the moment it is written instead of after a
     * round trip, and lets a failed one stay on screen with a retry rather than
     * vanishing with the text the user typed.
     */
    val sendState: NoticeSendState = NoticeSendState.Sent,
    /** Client-generated; how an optimistic post is matched to the server's copy. */
    val localId: String? = null,
) {
    val commentCount: Int get() = comments.size

    /** Never prints the body — a notice can carry production-confidential text. */
    override fun toString(): String =
        "Notice(id=$id, author=$authorName, chars=${body.length}, kind=$kind)"
}

/**
 * A reply to a notice.
 *
 * Message-shaped on the wire — encrypted body, its own type and attachment —
 * because a reply can be a photo or a document, not only words. Deleted rows
 * are dropped at the reader, matching the web's filter.
 */
data class NoticeComment(
    val id: String,
    val body: String,
    val authorId: String? = null,
    val createdAtMillis: Long = 0,
    val kind: NoticeKind = NoticeKind.Text,
    val attachment: NoticeAttachment? = null,
    val location: GeoPoint? = null,
    val isEdited: Boolean = false,
) {
    /** Never prints the body. */
    override fun toString(): String = "NoticeComment(id=$id, chars=${body.length}, kind=$kind)"
}

/**
 * A point on the earth, as a location post names one.
 *
 * The wire calls the second half `long` — not `lng` — and both arrive as
 * whatever JSON type the sending client felt like that day.
 */
data class GeoPoint(val lat: Double, val long: Double) {
    /** Where a click goes — the web links exactly this. */
    val mapsUrl: String get() = "https://www.google.com/maps?q=$lat,$long"
}

/**
 * Reads a location out of what a desktop user can actually provide: pasted
 * coordinates, or a Google Maps link copied from the browser or the phone.
 *
 * Accepted: `34.05, -118.24` (comma or space), a maps URL with `?q=lat,long`,
 * or one with `@lat,long,zoom`. Anything else is null — a location post with
 * a guessed point sends the crew to the wrong place with confidence.
 */
fun parseGeoInput(raw: String): GeoPoint? {
    val text = raw.trim()

    return when {
        text.isEmpty() -> null
        // Maps URLs first: their paths are full of number-comma-number pairs
        // that a bare-coordinate parse would happily misread.
        text.contains("://") ->
            (QUERY_POINT.find(text) ?: AT_POINT.find(text))?.toPoint()
        else -> PLAIN_POINT.matchEntire(text)?.toPoint()
    }
}

private fun MatchResult.toPoint(): GeoPoint? {
    val lat = groupValues[1].toDoubleOrNull() ?: return null
    val long = groupValues[2].toDoubleOrNull() ?: return null
    // Off the globe is a typo, not a place.
    if (lat !in -MAX_LATITUDE..MAX_LATITUDE || long !in -MAX_LONGITUDE..MAX_LONGITUDE) return null
    return GeoPoint(lat, long)
}

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

private const val COORD = "(-?\\d{1,3}(?:\\.\\d+)?)"
private val PLAIN_POINT = Regex("$COORD[,\\s]\\s*$COORD")
private val QUERY_POINT = Regex("[?&]q=$COORD(?:%2C|,)$COORD")
private val AT_POINT = Regex("@$COORD,$COORD")

/**
 * Why an edit or delete is refused — or [Allowed].
 *
 * Named outcomes rather than a boolean because the refusal is what the user
 * sees: both phones toast a different sentence for "not yours" and "too old",
 * and the desktop should say the same things (Android `Home.kt:2128-2136`,
 * iOS `ProductionVC+Ext.swift:1021-1030`).
 */
enum class ModifyVerdict {
    Allowed,

    /** Only the author may; admins get no exception for *editing*. */
    NotOwner,

    /** Past [MODIFY_WINDOW_MILLIS] since posting. */
    WindowClosed,

    /** Not on the server yet — nothing to address. Retry is its own affordance. */
    NotSent,
    ;

    val allowed: Boolean get() = this == Allowed
}

/**
 * Whether this user may edit a reply.
 *
 * Owner only, and only within thirty minutes of writing — after that the
 * thread is a record, not a draft. **No admin override**: an admin cannot
 * rewrite what someone else said, on either phone (Android `canEditMessage`,
 * `Home.kt:2343-2352`; iOS `ProductionVC+CommentAction.swift:124-128`).
 */
fun NoticeComment.editVerdict(userId: String?, nowMillis: Long): ModifyVerdict = when {
    userId == null || authorId != userId -> ModifyVerdict.NotOwner
    nowMillis - createdAtMillis > MODIFY_WINDOW_MILLIS -> ModifyVerdict.WindowClosed
    else -> ModifyVerdict.Allowed
}

/**
 * Whether this user may delete a reply.
 *
 * An admin may remove anything at any age — moderation has no clock. Anyone
 * else, only their own and only within the window (Android `canDeleteMessage`,
 * `Home.kt:2363-2384`; iOS `ProductionVC+CommentAction.swift:92-114`).
 */
fun NoticeComment.deleteVerdict(userId: String?, isAdmin: Boolean, nowMillis: Long): ModifyVerdict =
    when {
        isAdmin -> ModifyVerdict.Allowed
        userId == null || authorId != userId -> ModifyVerdict.NotOwner
        nowMillis - createdAtMillis > MODIFY_WINDOW_MILLIS -> ModifyVerdict.WindowClosed
        else -> ModifyVerdict.Allowed
    }

/**
 * The edit rule for a post — as for a reply, plus one gate: only a post the
 * server has taken can be edited; an optimistic card still in flight has no
 * server id to address.
 */
fun Notice.editVerdict(userId: String?, nowMillis: Long): ModifyVerdict = when {
    sendState != NoticeSendState.Sent -> ModifyVerdict.NotSent
    userId == null || authorId != userId -> ModifyVerdict.NotOwner
    nowMillis - createdAtMillis > MODIFY_WINDOW_MILLIS -> ModifyVerdict.WindowClosed
    else -> ModifyVerdict.Allowed
}

/** The delete rule for a post: admin any age, owner within the window, sent only. */
fun Notice.deleteVerdict(userId: String?, isAdmin: Boolean, nowMillis: Long): ModifyVerdict = when {
    sendState != NoticeSendState.Sent -> ModifyVerdict.NotSent
    isAdmin -> ModifyVerdict.Allowed
    userId == null || authorId != userId -> ModifyVerdict.NotOwner
    nowMillis - createdAtMillis > MODIFY_WINDOW_MILLIS -> ModifyVerdict.WindowClosed
    else -> ModifyVerdict.Allowed
}

/**
 * Whether the user has any business with this post's Edit and Delete items at
 * all — the author, or an admin. Untimed on purpose: the item stays in the
 * menu after the window closes and the click explains why it will not act,
 * which is how Android teaches the rule (its toast, not a vanished item).
 */
fun Notice.isActionableBy(userId: String?, isAdmin: Boolean): Boolean =
    sendState == NoticeSendState.Sent && (isAdmin || (userId != null && authorId == userId))

/** The same, for a reply. */
fun NoticeComment.isActionableBy(userId: String?, isAdmin: Boolean): Boolean =
    isAdmin || (userId != null && authorId == userId)

/**
 * Thirty minutes — `Constants.DIFFERENCE_IN_HOURS = 30` (minutes, despite the
 * name) on Android, `minuteDiffFromEpoch() <= 30` on iOS.
 */
const val MODIFY_WINDOW_MILLIS: Long = 30 * 60 * 1000L

/**
 * Orders a feed for display: oldest to newest.
 *
 * Newest **last**, like a conversation rather than a news feed — both clients
 * scroll to the bottom on open, and reversing that here would put the tail of
 * the board at the top. Pinned posts keep their place; the banner over the
 * board ([pinnedForBanner]) is what keeps them in view — floating them to the
 * top reshuffled the conversation and read as a second board.
 *
 * ## Why the ordering key differs by mode
 *
 * The live board orders by `updated`, so an edited post moves to the bottom
 * where people will notice it changed. **History** orders by `created`, because
 * it is a record of when things were published and an edit must not reshuffle
 * it (`NoticesV2:78-82`; Android `HomeVm:434`).
 */
fun List<Notice>.forDisplay(history: Boolean = false): List<Notice> =
    sortedBy { if (history) it.createdAtMillis else it.orderingTimestamp }

/** `updated` when the server sent one, else `created` — never 0, which would sort to the top. */
private val Notice.orderingTimestamp: Long
    get() = if (updatedAtMillis > 0) updatedAtMillis else createdAtMillis
