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
 * Whether this user may edit or delete a reply.
 *
 * The web's `canSelectMessage`, reduced to what it actually enforces: an admin
 * may touch anything; everyone else only their own replies, and only within
 * thirty minutes of writing — after that the thread is a record, not a draft.
 * One rule for both operations because the web applies the same two checks to
 * both.
 */
fun NoticeComment.canBeModifiedBy(
    userId: String?,
    isAdmin: Boolean,
    nowMillis: Long,
): Boolean = when {
    isAdmin -> true
    userId == null || authorId != userId -> false
    else -> nowMillis - createdAtMillis <= MODIFY_WINDOW_MILLIS
}

/**
 * The same rule, for a post.
 *
 * One extra gate: only a post the server has taken can be edited or deleted —
 * an optimistic card still in flight has no server id to address, and its
 * failure path (retry) is its own affordance.
 */
fun Notice.canBeModifiedBy(
    userId: String?,
    isAdmin: Boolean,
    nowMillis: Long,
): Boolean = when {
    sendState != NoticeSendState.Sent -> false
    isAdmin -> true
    userId == null || authorId != userId -> false
    else -> nowMillis - createdAtMillis <= MODIFY_WINDOW_MILLIS
}

/** Thirty minutes, matching the web's `isWithin30MinRange`. */
const val MODIFY_WINDOW_MILLIS: Long = 30 * 60 * 1000L

/**
 * Orders a feed for display: pinned first, then oldest to newest.
 *
 * Newest **last**, like a conversation rather than a news feed — both clients
 * scroll to the bottom on open, and reversing that here would put the tail of
 * the board at the top.
 *
 * ## Why the ordering key differs by mode
 *
 * The live board orders by `updated`, so an edited post moves to the bottom
 * where people will notice it changed. **History** orders by `created`, because
 * it is a record of when things were published and an edit must not reshuffle
 * it (`NoticesV2:78-82`; Android `HomeVm:434`).
 */
fun List<Notice>.forDisplay(history: Boolean = false): List<Notice> =
    sortedWith(
        compareByDescending<Notice> { it.isPinned }
            .thenBy { if (history) it.createdAtMillis else it.orderingTimestamp },
    )

/** `updated` when the server sent one, else `created` — never 0, which would sort to the top. */
private val Notice.orderingTimestamp: Long
    get() = if (updatedAtMillis > 0) updatedAtMillis else createdAtMillis
