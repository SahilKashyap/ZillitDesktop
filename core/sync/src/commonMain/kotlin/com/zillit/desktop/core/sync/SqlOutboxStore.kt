package com.zillit.desktop.core.sync

import com.zillit.desktop.core.database.sync.SyncDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import com.zillit.desktop.core.database.sync.SyncOperation as Row

/**
 * The outbox on the encrypted durable store.
 *
 * Every call hops to [dispatcher]: SQLDelight's JDBC driver blocks, and the
 * engine calls this from whatever scope it was given.
 */
class SqlOutboxStore(
    database: SyncDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OutboxStore {

    private val queries = database.outboxQueries

    // Conflated on purpose: a burst of writes is one "something changed".
    private val changed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun insert(operation: SyncOperation) = write {
        queries.insert(
            id = operation.id,
            userId = operation.scope.userId,
            projectId = operation.scope.projectId,
            kind = operation.kind,
            label = operation.label,
            payload = operation.payload,
            groupKey = operation.groupKey,
            dependsOn = operation.dependsOn,
            status = operation.state.wire,
            attempts = operation.attempts.toLong(),
            lastError = operation.lastError,
            result = operation.result,
            nextAttemptAt = operation.nextAttemptAt,
            createdAt = operation.createdAt,
            updatedAt = operation.updatedAt,
        )
    }

    override suspend fun get(id: String): SyncOperation? = withContext(dispatcher) {
        queries.selectById(id).executeAsOneOrNull()?.toOperation()
    }

    override suspend fun forScope(scope: SyncScope): List<SyncOperation> = withContext(dispatcher) {
        queries.selectForScope(scope.userId, scope.projectId).executeAsList().map { it.toOperation() }
    }

    override suspend fun all(): List<SyncOperation> = withContext(dispatcher) {
        queries.selectAll().executeAsList().map { it.toOperation() }
    }

    override fun changes(): Flow<Unit> = changed.asSharedFlow()

    override suspend fun update(operation: SyncOperation) = write {
        queries.updateStatus(
            status = operation.state.wire,
            attempts = operation.attempts.toLong(),
            lastError = operation.lastError,
            result = operation.result,
            nextAttemptAt = operation.nextAttemptAt,
            updatedAt = operation.updatedAt,
            id = operation.id,
        )
    }

    override suspend fun updatePayload(id: String, payload: String, updatedAt: Long) = write {
        queries.updatePayload(payload = payload, updatedAt = updatedAt, id = id)
    }

    override suspend fun requeueInFlight(now: Long) = write { queries.requeueInFlight(now) }

    override suspend fun delete(id: String) = write { queries.deleteById(id) }

    override suspend fun pruneDone(before: Long) = write { queries.deleteDoneBefore(before) }

    private suspend fun write(block: () -> Unit) {
        withContext(dispatcher) { block() }
        changed.tryEmit(Unit)
    }

    private fun Row.toOperation() = SyncOperation(
        id = id,
        scope = SyncScope(userId = userId, projectId = projectId),
        kind = kind,
        label = label,
        payload = payload,
        groupKey = groupKey,
        dependsOn = dependsOn,
        state = SyncState.fromWire(status),
        attempts = attempts.toInt(),
        lastError = lastError,
        result = result,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** The column value for each state — fixed for ever, unlike the enum's name. */
internal val SyncState.wire: String
    get() = when (this) {
        SyncState.Pending -> "PENDING"
        SyncState.InFlight -> "IN_FLIGHT"
        SyncState.Failed -> "FAILED"
        SyncState.Done -> "DONE"
    }

internal fun SyncState.Companion.fromWire(value: String): SyncState =
    SyncState.entries.firstOrNull { it.wire == value } ?: SyncState.Failed
