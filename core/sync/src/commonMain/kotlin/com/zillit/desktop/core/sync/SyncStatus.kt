package com.zillit.desktop.core.sync

/** What the shell shows about the queue, recomputed on every change. */
data class SyncStatus(
    val online: Boolean = true,
    /** Waiting or in flight, in the open production. */
    val pending: Int = 0,
    /** Parked for the user, in the open production. */
    val failed: Int = 0,
    /** An operation is executing right now. */
    val syncing: Boolean = false,
    /** Open operations that belong to other productions or people on this machine. */
    val elsewhere: Int = 0,
    val lastSyncedAt: Long? = null,
) {
    val hasWork: Boolean get() = pending > 0 || failed > 0
}
