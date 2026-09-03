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
    /** Set only on the row the server answers a replace post with: the message it retired (`replaced_chat_id`). */
    val replacedNoticeId: String? = null,
) {
    val commentCount: Int get() = comments.size

    /**
     * Whether the bubble draws [body] as its own paragraph.
     *
     * A bare location share posts its address *as* the body — every client
     * does, because that is the only field iOS and the web read a label out
     * of (see [GeoPoint]). The location card already shows that address, so
     * drawing the body underneath prints the same line twice. A location post
     * with a real caption keeps it: the caption is what someone chose to say
     * beyond the address.
     */
    val showsBody: Boolean
        get() = body.isNotBlank() && !(kind == NoticeKind.Location && body.trim() == location?.address)

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
    /** The same body-is-the-address rule as a post's — see [Notice.showsBody]. */
    val showsBody: Boolean
        get() = body.isNotBlank() && !(kind == NoticeKind.Location && body.trim() == location?.address)

    /** Never prints the body. */
    override fun toString(): String = "NoticeComment(id=$id, chars=${body.length}, kind=$kind)"
}

/**
 * A place a location post names: where it is, and what it is called.
 *
 * The wire calls the second half `long` — not `lng` — and both arrive as
 * whatever JSON type the sending client felt like that day.
 *
 * ## Why [address] is stored and [name] is derived
 *
 * The board's `location` object is **not** a rich place record. The three
 * clients agree on exactly two keys and disagree past them:
 *
 * - Android declares `LocationInfo(lat, long, address, imageLink, height,
 *   width)` (`bottomNav/home/models/HomeChatRequest.kt:228-239`) — the only
 *   client that writes a human string into the object at all, as `address`.
 * - iOS declares `LocationCoordinates(lat, long)` and nothing else
 *   (`Controller/Home/Production/model/ChatAPIModel.swift:184-197`).
 * - The web sends `location: { lat, long }`
 *   (`components/unit-chat/UnitChatMessageBox.jsx:1013-1016`) and renders only
 *   the map image and the link (`message-types/LocationMessage.jsx`).
 *
 * **There is no `name` key on this wire.** Inventing one would post a field
 * the server has no column for — the shape of failure this project has been
 * bitten by before (a 200 that stores nothing). So the label travels in the
 * two places every client already reads: `location.address`, and the post's
 * own body — which is where both phones put it (Android's
 * `HomeVm.getLocationAttachment` encrypts the address into `message` *and*
 * `message_translation`, `bottomNav/home/viewmodel/HomeVm.kt:919-928`; the
 * Catering board does the identical thing at
 * `bottomNav/tools/viewmodel/CateringVm.kt:697-706`).
 *
 * [name] and [detail] then split that one line the way the picker joined it —
 * the place's own name, then the rest of the address — so a card can show a
 * title and a second line without a field to carry them.
 */
data class GeoPoint(
    val lat: Double,
    val long: Double,
    /**
     * The one line this place reads as, `"Aria Hotel, 12 Marine Drive"`.
     *
     * Blank when the sender was the web or iOS and the post's body was empty
     * too — the point alone is still a place worth pinning, which is why this
     * defaults rather than being required.
     */
    val address: String = "",
) {
    /** Where a click goes — the web links exactly this (`LocationMessage.jsx:42`). */
    val mapsUrl: String get() = "https://www.google.com/maps?q=$lat,$long"

    /**
     * The card's title: the place's own name.
     *
     * The first segment of [address], because that is precisely what the
     * picker put there — `PickedLocation.name` is "the Places result's own
     * name when there was one, and otherwise the first line of the address"
     * (`core:locationpicker`, `LocationPicker.kt:16-19`), and the composer
     * joins it as `"$name, $address"`. Splitting on the first comma is the
     * inverse of that join, and on an address the picker never touched it
     * still yields a readable street line.
     */
    val name: String get() = address.substringBefore(',').trim()

    /** The rest of the address, under the title. Blank when there is no rest. */
    val detail: String get() = address.substringAfter(',', missingDelimiterValue = "").trim()

    /** The point itself, for the crew member who wants to type it into anything. */
    val coordinates: String get() = "$lat, $long"

    /**
     * The same point, labelled — used when a post carried its address in the
     * body rather than in the location object (every web and iOS post).
     * Never overwrites an address the wire did supply.
     */
    fun labelledWith(fallback: String): GeoPoint =
        if (address.isNotBlank() || fallback.isBlank()) this else copy(address = fallback.trim())
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
 * The edit rule for a post — the author, within the window; only a post the
 * server has taken (an optimistic card still in flight has no server id to
 * address).
 *
 * Owner-only because the **server** is: `PUT home/chat/{id}` — the route an
 * edit and a pin both ride — answers "You do not have access to this" to an
 * admin on someone else's post, and takes the author's own seven-day-old one
 * (found live, 2026-08-18). Android's client is looser (`Options.EditComment`
 * lets anyone with posting rights into the editor) and would meet the same
 * refusal on save; the desktop does not offer what the server will not take.
 * Replies: `canEditMessage`, owner-only there too.
 */
fun Notice.editVerdict(userId: String?, nowMillis: Long): ModifyVerdict = when {
    sendState != NoticeSendState.Sent -> ModifyVerdict.NotSent
    userId == null || authorId != userId -> ModifyVerdict.NotOwner
    nowMillis - createdAtMillis > MODIFY_WINDOW_MILLIS -> ModifyVerdict.WindowClosed
    else -> ModifyVerdict.Allowed
}

/**
 * Whether Edit — and Pin, which rides the same route — belongs in a post's
 * menu at all: the author's, on the server. Untimed, like [isActionableBy];
 * the click explains the clock.
 */
fun Notice.isEditableBy(userId: String?): Boolean =
    sendState == NoticeSendState.Sent && userId != null && authorId == userId

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
    sortedBy { it.displayTimestamp(history) }

/**
 * The stamp a mode orders by — and so the one its date separators group by.
 *
 * These have to be the same value. Grouping on `created` while the live board
 * sorts on `updated` reopens a day that already closed: an edited post sits at
 * the bottom carrying its creation date, a second separator appears for a day
 * already shown above, and both separators key off the same label. A
 * `LazyColumn` refuses a repeated key and takes the whole board down with it.
 */
fun Notice.displayTimestamp(history: Boolean): Long =
    if (history) createdAtMillis else orderingTimestamp

/** `updated` when the server sent one, else `created` — never 0, which would sort to the top. */
private val Notice.orderingTimestamp: Long
    get() = if (updatedAtMillis > 0) updatedAtMillis else createdAtMillis

/**
 * What a "Replace one document" upload may swap out: LIVE document messages
 * the server has acknowledged — never an image or text (the server acts on
 * nothing else), never a pending post (the server matches on `_id`). Newest
 * first. The web's `replaceableMessages` and Android's
 * `ReplaceableMessages.eligible`, on this board's rows.
 */
fun replaceTargets(notices: List<Notice>): List<Notice> = notices
    .filter { it.kind == NoticeKind.Document && !it.attachment?.media.isNullOrBlank() }
    .filter { it.localId == null && it.id.isNotBlank() }
    .sortedByDescending { maxOf(it.updatedAtMillis, it.createdAtMillis) }
