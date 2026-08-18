package com.zillit.desktop

import com.zillit.desktop.core.database.ZillitDatabase
import com.zillit.desktop.core.network.CachedRead
import com.zillit.desktop.core.network.ReadCache
import com.zillit.desktop.core.network.ReadScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The read cache on the encrypted local database — the `cachedRead` table.
 *
 * Cache semantics on purpose: every row is the server's own answer, so the
 * table is rebuilt on a schema change and gone with the key on sign-out, like
 * the rest of that file. Answers older than [RETENTION_MILLIS] are dropped at
 * start; a production nobody has opened in a month is not worth the disk.
 */
class SqlReadCache(
    private val database: ZillitDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReadCache {

    override suspend fun get(key: String): CachedRead? = withContext(dispatcher) {
        database.readCacheQueries.selectByKey(key).executeAsOneOrNull()?.let { CachedRead(it.body, it.fetchedAt) }
    }

    override suspend fun put(key: String, scope: ReadScope, body: String, fetchedAt: Long): Unit =
        withContext(dispatcher) {
            database.readCacheQueries.upsert(
                key = key,
                userId = scope.userId,
                projectId = scope.projectId,
                body = body,
                fetchedAt = fetchedAt,
            )
        }

    /** Whether anything at all was ever kept for this person in this production. */
    suspend fun hasAnything(scope: ReadScope): Boolean = withContext(dispatcher) {
        database.readCacheQueries.countForProject(scope.userId, scope.projectId).executeAsOne() > 0
    }

    suspend fun prune(now: Long): Unit = withContext(dispatcher) {
        database.readCacheQueries.deleteOlderThan(now - RETENTION_MILLIS)
    }

    companion object {
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60_000
    }
}
