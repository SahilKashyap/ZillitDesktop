package com.zillit.desktop.core.database

/**
 * Which board, on which production, a row belongs to.
 *
 * Grouped because these three always travel together and never apart — every
 * statement in `NoticeCache.sq` is keyed by all of them. Seven tools share the
 * notice-board engine, and unit ids are only unique within one, so a row that
 * named its unit and not its board would be a row that could land on the
 * wrong board.
 */
data class BoardScope(val projectId: String, val board: String, val unitId: String)

/**
 * One cached post: its identity, the server's own row, and when it was last
 * touched.
 *
 * [wire] stays as the server sent it — `message` still cipher-hex — and the
 * reader parses it. See `NoticeCache.sq` for why that is the shape rather than
 * a column per field.
 */
data class NoticeRow(val noticeId: String, val wire: String, val updatedAt: Long)

/**
 * The notice boards at rest.
 *
 * Deliberately dumb, like [ReadCache]: it remembers rows and hands them back.
 * What a post *is* lives in `feature:home`, which is the module that owns the
 * wire format — returning a `Notice` here would make `core:database` depend on
 * a feature, which is the dependency direction the module layout exists to
 * prevent (see [ProjectCache]).
 */
class NoticeCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.noticeCacheQueries

    /** Which productions are still in use — the prune's reprieve list. */
    private val visits = ProjectVisits(database)

    /**
     * Files a page, keeping everything else the unit already has.
     *
     * A merge, not a replace: an older page and the newest one are both true
     * at once, and a replace would make scrolling back forget where it came
     * from. The unit is trimmed to [KEEP_PER_UNIT] afterwards so a long shoot
     * cannot grow this table without end.
     */
    fun save(scope: BoardScope, rows: List<NoticeRow>) {
        if (rows.isEmpty()) return
        queries.transaction {
            rows.forEach { put(scope, it) }
            queries.trimUnit(
                projectId = scope.projectId,
                board = scope.board,
                unitId = scope.unitId,
                projectId_ = scope.projectId,
                board_ = scope.board,
                unitId_ = scope.unitId,
                value = KEEP_PER_UNIT,
            )
        }
    }

    /** One post, as it arrived — a socket delta's write-through. */
    fun put(scope: BoardScope, row: NoticeRow) {
        queries.upsertNotice(
            noticeId = row.noticeId,
            projectId = scope.projectId,
            board = scope.board,
            unitId = scope.unitId,
            wire = row.wire,
            updatedAt = row.updatedAt,
            cachedAt = nowMillis(),
        )
    }

    /**
     * The newest [limit] rows, newest first.
     *
     * The caller reorders: board order is `forDisplay`'s business, and it
     * depends on things this module cannot see (whether the board is being
     * read as history, where the pinned posts go).
     */
    fun newest(scope: BoardScope, limit: Int = KEEP_PER_UNIT.toInt()): List<String> =
        queries.selectNewest(scope.projectId, scope.board, scope.unitId, limit.toLong())
            .executeAsList()

    fun count(scope: BoardScope): Long =
        queries.countForUnit(scope.projectId, scope.board, scope.unitId).executeAsOne()

    /**
     * Forgets a post.
     *
     * A delete whose payload named no unit falls back to the whole board: a
     * notice id is unique within one, and leaving the row behind would mean a
     * deleted post reappearing on the next cold open.
     */
    fun delete(scope: BoardScope, noticeId: String) {
        queries.deleteNotice(scope.projectId, scope.board, scope.unitId, noticeId)
    }

    fun deleteAnywhereOnBoard(projectId: String, board: String, noticeId: String) {
        queries.deleteNoticeAnywhere(projectId, board, noticeId)
    }

    fun deleteUnit(scope: BoardScope) {
        queries.deleteUnit(scope.projectId, scope.board, scope.unitId)
    }

    fun clearProject(projectId: String) {
        queries.deleteForProject(projectId)
    }

    /**
     * Drops the boards of productions nobody has opened in [RETENTION_MILLIS].
     *
     * What bounds this table across productions. A switch deliberately leaves
     * rows behind — they are keyed by production, so they are safe, and a
     * production reopened next week still draws at once — which means this is
     * the only thing stopping a year of shoots accumulating on disk.
     *
     * Measured by the last open ([ProjectVisits]) and not by when a row was
     * written: on somebody carrying a dozen productions the two are nowhere
     * near the same, and the second threw a working production's board away
     * every month. A production with no recorded open is pruned by age, which
     * is what this did before and so what the first launch after this landed
     * does.
     */
    fun prune(now: Long) {
        val cutoff = now - RETENTION_MILLIS
        queries.deleteAged(before = cutoff, kept = visits.openedSince(cutoff))
    }

    private companion object {
        /**
         * How much of a board is worth keeping — a few pages, not a shoot.
         *
         * Enough that scrolling back works offline over the stretch anybody
         * actually revisits; bounded, because a long production's board runs to
         * thousands of posts and this is a cache, not an archive.
         */
        const val KEEP_PER_UNIT = 300L

        /** As `SqlReadCache` keeps its answers: a month. */
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60_000
    }
}
