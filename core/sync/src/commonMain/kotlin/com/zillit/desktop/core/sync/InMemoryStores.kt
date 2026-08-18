package com.zillit.desktop.core.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The outbox in a list — for tests of the modules that enqueue, which should
 * not need a database to prove they queued the right thing.
 *
 * In main rather than test sources so every feature's `commonTest` can reach
 * it; it has no other caller in the app.
 */
class InMemoryOutboxStore : OutboxStore {
    private val rows = linkedMapOf<String, SyncOperation>()
    private val changed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun insert(operation: SyncOperation) {
        rows[operation.id] = operation
        changed.tryEmit(Unit)
    }

    override suspend fun get(id: String): SyncOperation? = rows[id]

    override suspend fun forScope(scope: SyncScope): List<SyncOperation> = rows.values.filter { it.scope == scope }

    override suspend fun all(): List<SyncOperation> = rows.values.toList()

    override fun changes(): Flow<Unit> = changed.asSharedFlow()

    override suspend fun update(operation: SyncOperation) {
        rows[operation.id] = operation
        changed.tryEmit(Unit)
    }

    override suspend fun updatePayload(id: String, payload: String, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(payload = payload, updatedAt = updatedAt) }
        changed.tryEmit(Unit)
    }

    override suspend fun requeueInFlight(now: Long) {
        rows.replaceAll { _, op ->
            if (op.state == SyncState.InFlight) op.copy(state = SyncState.Pending, updatedAt = now) else op
        }
        changed.tryEmit(Unit)
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        changed.tryEmit(Unit)
    }

    override suspend fun pruneDone(before: Long) {
        rows.values.removeAll { it.state == SyncState.Done && it.updatedAt < before }
        changed.tryEmit(Unit)
    }
}

/** The draft store in a map; same purpose as [InMemoryOutboxStore]. */
class InMemoryDraftStore : DraftStore {
    private val rows = linkedMapOf<String, LocalDraft>()

    override suspend fun save(draft: LocalDraft) {
        rows[draft.id] = draft
    }

    override suspend fun get(id: String): LocalDraft? = rows[id]

    override suspend fun forKind(scope: SyncScope, kind: String): List<LocalDraft> =
        rows.values.filter { it.scope == scope && it.kind == kind }.sortedByDescending { it.updatedAt }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun countForUser(userId: String): Int =
        rows.values.count { it.scope.userId == userId && !it.kind.startsWith(ScopedJsonCache.KIND_PREFIX) }
}
