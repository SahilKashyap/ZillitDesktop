package com.zillit.desktop.core.database

/**
 * When each production was last opened on this computer.
 *
 * ## Why this exists
 *
 * Both caches bound themselves by age, and both said in their own words that
 * they meant something else: "a production nobody has **opened** in a month is
 * not worth the disk". What they measured was when a row was *written*, which
 * is the same thing only for a production somebody opens as often as its posts
 * change. On a person carrying a dozen productions at once it is not: they open
 * each one every few weeks, every row in it is a month old, and the cache is
 * thrown away under a production they are still working on — so the board opens
 * cold, with a spinner, and the tab strip the posts need in order to be drawn
 * at all is the first thing to go.
 *
 * One row per production fixes it: age stays the floor, and a recent open is a
 * reprieve. A production still in use keeps its cache however old the rows are
 * — which costs nothing, because what is kept is bounded per board
 * (`NoticeCache.KEEP_PER_UNIT`) and per read (one row per cacheable call) — and
 * a production nobody has opened for a month loses it, exactly as before.
 *
 * ## Why `appMeta` and not a table of its own
 *
 * The cache database is `SchemaPolicy.RebuildOnChange`: a new table changes the
 * `CREATE` statements, and the next launch would drop every cached row on this
 * machine. A fix for the cache being thrown away must not begin by throwing
 * the cache away, so this rides in the key/value table that is already there.
 * `appMeta`'s own note sends *feature* modules to their own `.sq` file; this is
 * the cache's retention bookkeeping, which is what that table is for.
 */
class ProjectVisits(private val database: ZillitDatabase) {

    private val queries get() = database.appMetaQueries

    /** Records that [projectId] was opened at [atMillis]. */
    fun opened(projectId: String, atMillis: Long) {
        if (projectId.isBlank()) return
        // Positional: the generated parameter for `value` is spelled `value_`,
        // which is the generator's business and not worth naming here.
        queries.upsert(KEY_PREFIX + projectId, atMillis.toString())
    }

    /** When [projectId] was last opened, or null when this machine has no record of it. */
    fun lastOpened(projectId: String): Long? =
        queries.selectByKey(KEY_PREFIX + projectId).executeAsOneOrNull()?.toLongOrNull()

    /**
     * The productions opened at or since [cutoffMillis] — the ones a prune
     * must leave alone.
     *
     * A production with no row is simply absent, which leaves it to be pruned
     * by age as it always was. That is what makes this safe to ship: the first
     * launch after it lands knows of no opens at all and prunes precisely what
     * yesterday's build would have, and every open after that earns its
     * production a reprieve.
     *
     * A value that does not parse is treated as no record rather than as a
     * reprieve. This table is untyped and shared; a prune that could be
     * switched off by a stray row would be a disk leak nobody could see.
     */
    fun openedSince(cutoffMillis: Long): List<String> =
        queries.selectByPrefix(KEY_PREFIX).executeAsList().mapNotNull { row ->
            val at = row.metaValue.toLongOrNull() ?: return@mapNotNull null
            row.metaKey.removePrefix(KEY_PREFIX).takeIf { it.isNotEmpty() && at >= cutoffMillis }
        }

    /** Sign-out: which productions somebody opened is theirs, and goes with their rows. */
    fun forgetAll() {
        queries.deleteByPrefix(KEY_PREFIX)
    }

    private companion object {
        /**
         * Namespaced, because `appMeta` is one key space shared with the
         * database's own provenance — and because [forgetAll] and
         * [openedSince] both read the whole set by this prefix.
         */
        const val KEY_PREFIX = "visit.project."
    }
}
