package com.zillit.desktop.feature.home.domain

/**
 * One post as the server sent it, on its way to or from the board's offline
 * copy.
 *
 * [wire] is the row verbatim, `message` still AES cipher-hex — it is parsed by
 * `readNotice` on the way out, the same and only function that parses a live
 * answer. Keeping the row rather than a column per field means the replies,
 * the attachment and the location travel with it, and the body never lands on
 * disk in plaintext.
 */
data class NoticeWire(val noticeId: String, val wire: String, val updatedAtMillis: Long)

/**
 * Where a board is kept between launches.
 *
 * Narrowed to four methods and declared here rather than in `core:database`,
 * for the reason [NoticeDecryptor] is: this module can ask for its board back
 * and cannot reach the storage behind it. The host supplies an implementation
 * scoped to one board and one production, so neither appears in the calls —
 * and a host with no database supplies none, which simply makes every read a
 * miss, exactly as before this existed.
 */
interface NoticeStore {

    /** The newest window of this unit's board, newest first. */
    suspend fun newest(unitId: String): List<String>

    /**
     * Files a page, merging with whatever the unit already has.
     *
     * Both the newest page and the page behind it are true at once, which is
     * what makes scrolling back work on a cold start.
     */
    suspend fun save(unitId: String, rows: List<NoticeWire>)

    /** Files one post — a socket arrival, or an edit of one already kept. */
    suspend fun put(unitId: String, row: NoticeWire)

    /**
     * Forgets a post.
     *
     * A null [unitId] means the payload did not name one: the id is unique
     * within a board, and a row left behind is a deleted post reappearing on
     * the next cold open.
     */
    suspend fun forget(unitId: String?, noticeId: String)

    /**
     * This store, fixed to the production open now.
     *
     * A page is filed when its answer lands, and a production switch can
     * happen while it is on its way: resolved then, the page went to the next
     * production — or, mid-switch, nowhere, which left the kept board a
     * refresh behind. Taken when the read is asked, the page is filed under
     * the production that asked for it. A store with no production to
     * resolve is already fixed, and is itself.
     */
    fun pinned(): NoticeStore = this
}
