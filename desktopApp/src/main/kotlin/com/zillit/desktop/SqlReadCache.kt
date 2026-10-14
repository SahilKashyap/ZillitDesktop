package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.database.ZillitDatabase
import com.zillit.desktop.core.network.CachedRead
import com.zillit.desktop.core.network.ReadCache
import com.zillit.desktop.core.network.ReadScope
import kotlinx.coroutines.CancellationException
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
 *
 * Reads go on [readDispatcher], away from the writes: see [CacheReadLane].
 */
class SqlReadCache(
    private val database: ZillitDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readDispatcher: CoroutineDispatcher = CacheReadLane,
) : ReadCache {

    override suspend fun get(key: String): CachedRead? = withContext(readDispatcher) {
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
    suspend fun hasAnything(scope: ReadScope): Boolean = withContext(readDispatcher) {
        database.readCacheQueries.countForProject(scope.userId, scope.projectId).executeAsOne() > 0
    }

    /**
     * Guarded, unlike [get] and [put]: those are reached through `ApiClient`,
     * which already reads a throw from a store as a miss, while this runs in a
     * startup coroutine of its own, where a throw has no caller to answer to
     * and reaches the uncaught handler instead. Rows that could not be dropped
     * are merely stale — the next start asks again.
     */
    suspend fun prune(now: Long): Unit = withContext(dispatcher) {
        try {
            database.readCacheQueries.deleteOlderThan(now - RETENTION_MILLIS)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "could not drop old answers: ${throwable.message}" }
        }
    }

    companion object {
        private const val TAG = "ReadCache"
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60_000
    }
}

/**
 * Where the caches' reads run: a lane of their own, not the IO pool their
 * writes share with everything else.
 *
 * A production switch lands a hundred-odd answers in a couple of seconds,
 * and every one is a write to this database on `Dispatchers.IO`. A screen's
 * "what do we already know" read joined the back of that queue, and waited
 * for it — measured at ~180 ms for a kept board against ~5 ms on its own
 * lane, twice per switch (the tab strip, then the posts), which is the
 * spinner people saw every time they switched production. SQLite in WAL
 * mode reads beside a writer without waiting for it; only the thread queue
 * was in the way.
 *
 * A view of `Dispatchers.IO`, so it borrows that pool's threads without
 * counting against its limit, and a few wide: these are short reads.
 */
internal val CacheReadLane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CACHE_READ_THREADS)

private const val CACHE_READ_THREADS = 4
