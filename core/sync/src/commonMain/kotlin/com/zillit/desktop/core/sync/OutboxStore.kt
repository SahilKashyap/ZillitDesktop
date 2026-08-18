package com.zillit.desktop.core.sync

import kotlinx.coroutines.flow.Flow

/**
 * Where queued operations live between attempts — and across restarts, which
 * is the point.
 *
 * Deliberately small: the engine decides what runs; the store only remembers.
 */
interface OutboxStore {
    suspend fun insert(operation: SyncOperation)

    suspend fun get(id: String): SyncOperation?

    /** Every operation for one person in one production, oldest first. */
    suspend fun forScope(scope: SyncScope): List<SyncOperation>

    /** Every operation on this machine, oldest first — for counts across productions. */
    suspend fun all(): List<SyncOperation>

    /** Emits whenever anything changes, so status can be derived without polling. */
    fun changes(): Flow<Unit>

    suspend fun update(operation: SyncOperation)

    suspend fun updatePayload(id: String, payload: String, updatedAt: Long)

    /** Crash recovery: whatever was in flight when the process died is pending again. */
    suspend fun requeueInFlight(now: Long)

    suspend fun delete(id: String)

    /** Housekeeping: done rows older than [before] have served their purpose. */
    suspend fun pruneDone(before: Long)
}
