package com.zillit.desktop

import com.zillit.desktop.core.database.BoardScope
import com.zillit.desktop.core.database.NoticeCache
import com.zillit.desktop.core.database.NoticeRow
import com.zillit.desktop.feature.home.domain.NoticeStore
import com.zillit.desktop.feature.home.domain.NoticeWire
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One board's offline copy, on the encrypted local database.
 *
 * The host half of [NoticeStore]: it supplies the two things the feature
 * cannot see — which production is open, and a thread to do the IO on.
 * SQLDelight's generated queries are blocking, and every caller here is a
 * view-model coroutine on the main dispatcher, so each call steps off it, as
 * [SqlReadCache] does — the read onto [readDispatcher], the cache's own lane,
 * because it is what a board waits on while a switch's writes are queued
 * (see [CacheReadLane]).
 *
 * Built per board ([board] being `home`, `info`, `reports`…) because unit ids
 * are only unique within one. A call made while no production is open is
 * dropped rather than guessed at: a row written under a blank production is a
 * row that would answer the wrong board later.
 */
class SqlNoticeStore(
    private val cache: NoticeCache,
    private val board: String,
    private val projectId: () -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readDispatcher: CoroutineDispatcher = CacheReadLane,
) : NoticeStore {

    override suspend fun newest(unitId: String): List<String> {
        val scope = scopeFor(unitId) ?: return emptyList()
        return withContext(readDispatcher) { cache.newest(scope) }
    }

    override suspend fun save(unitId: String, rows: List<NoticeWire>) {
        val scope = scopeFor(unitId) ?: return
        withContext(dispatcher) {
            cache.save(scope, rows.map { NoticeRow(it.noticeId, it.wire, it.updatedAtMillis) })
        }
    }

    override suspend fun put(unitId: String, row: NoticeWire) {
        val scope = scopeFor(unitId) ?: return
        withContext(dispatcher) {
            cache.put(scope, NoticeRow(row.noticeId, row.wire, row.updatedAtMillis))
        }
    }

    override suspend fun forget(unitId: String?, noticeId: String) {
        val project = projectId()?.takeIf { it.isNotBlank() } ?: return
        withContext(dispatcher) {
            if (unitId == null) {
                cache.deleteAnywhereOnBoard(project, board, noticeId)
            } else {
                cache.delete(BoardScope(project, board, unitId), noticeId)
            }
        }
    }

    /** The production open now, fixed — so a page that lands after a switch is filed where it was asked. */
    override fun pinned(): NoticeStore {
        val open = projectId()
        return SqlNoticeStore(cache, board, { open }, dispatcher, readDispatcher)
    }

    private fun scopeFor(unitId: String): BoardScope? =
        projectId()?.takeIf { it.isNotBlank() }?.let { BoardScope(it, board, unitId) }
}
