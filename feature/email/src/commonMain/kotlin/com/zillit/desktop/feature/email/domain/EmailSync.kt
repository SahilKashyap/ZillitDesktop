package com.zillit.desktop.feature.email.domain

/**
 * Which uids to fetch, and in what order.
 *
 * ## Why this exists as its own thing
 *
 * The mail API has no "page 2" call. `get-folder-uids` returns *every* uid in
 * the folder — tens of thousands in a real mailbox — and `get-email-index`
 * turns a batch of those into summaries. Deciding which batch is the whole of
 * mail pagination here, so it is worth being able to test without a server.
 *
 * Two rules, both of which are visible to the user when broken:
 *
 *  1. **Newest first.** IMAP hands out uids in ascending order, so the last uid
 *     is the newest mail. Fetching from the front loads a folder's oldest mail
 *     and the inbox looks empty for a minute.
 *  2. **Skip what is cached.** Only uids we do not already hold are fetched,
 *     which is what makes a second visit to a folder instant.
 */
object EmailSync {

    /**
     * Batch size.
     *
     * 50, matching Android and the web. The server slows sharply on larger uid
     * lists — it is fetching each message from IMAP — and a smaller batch means
     * the first rows appear sooner.
     */
    const val BATCH = 50

    /**
     * The next batch of uids to fetch, newest first.
     *
     * Returns empty when the folder is fully cached, which is how the caller
     * knows to stop.
     */
    fun nextBatch(serverUids: List<Int>, cachedUids: Set<Int>, batch: Int = BATCH): List<Int> =
        serverUids.asSequence()
            .sortedDescending()
            .filter { it !in cachedUids }
            .take(batch)
            .toList()

    /**
     * Cached uids no longer on the server — mail deleted or moved elsewhere.
     *
     * Without this a deleted message stays in the list forever, because nothing
     * else ever revisits a uid we already hold.
     */
    fun staleUids(serverUids: List<Int>, cachedUids: Set<Int>): Set<Int> =
        cachedUids - serverUids.toSet()

    /** True once every uid the server lists is held locally. */
    fun isComplete(serverUids: List<Int>, cachedUids: Set<Int>): Boolean =
        serverUids.all { it in cachedUids }
}
