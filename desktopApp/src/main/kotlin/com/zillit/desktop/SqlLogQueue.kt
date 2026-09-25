package com.zillit.desktop

import com.zillit.desktop.core.database.ZillitDatabase
import com.zillit.desktop.core.network.applog.LogQueue
import com.zillit.desktop.core.network.applog.QueuedLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The error log's waiting room on the encrypted local database — the `appLogQueue` table. */
class SqlLogQueue(
    private val database: ZillitDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LogQueue {

    override suspend fun save(log: QueuedLog): Unit = withContext(dispatcher) {
        database.appLogQueueQueries.insert(uniqueId = log.uniqueId, payload = log.data, queuedAt = nowMillis())
    }

    override suspend fun oldest(limit: Int): List<QueuedLog> = withContext(dispatcher) {
        database.appLogQueueQueries.oldest(limit.toLong()).executeAsList().map { QueuedLog(it.uniqueId, it.payload) }
    }

    override suspend fun count(): Long = withContext(dispatcher) {
        database.appLogQueueQueries.countAll().executeAsOne()
    }

    override suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(dispatcher) { database.appLogQueueQueries.deleteByIds(ids) }
    }

    override suspend fun trim(keep: Int): Unit = withContext(dispatcher) {
        database.appLogQueueQueries.keepNewest(keep.toLong())
    }
}

/** Where there is no local database: the events live for this session only. */
class InMemoryLogQueue : LogQueue {
    private val rows = ArrayList<QueuedLog>()

    override suspend fun save(log: QueuedLog): Unit = synchronized(rows) {
        rows.removeAll { it.uniqueId == log.uniqueId }
        rows += log
    }

    override suspend fun oldest(limit: Int): List<QueuedLog> = synchronized(rows) { rows.take(limit) }

    override suspend fun count(): Long = synchronized(rows) { rows.size.toLong() }

    override suspend fun delete(ids: List<String>): Unit = synchronized(rows) {
        val gone = ids.toSet()
        rows.removeAll { it.uniqueId in gone }
    }

    override suspend fun trim(keep: Int): Unit = synchronized(rows) {
        if (rows.size > keep) rows.subList(0, rows.size - keep).clear()
    }
}
