package com.zillit.desktop.core.sync

/**
 * Who the queued work belongs to.
 *
 * Every operation is scoped to the person who queued it and the production
 * they were in, because every finance call is signed with both. The engine
 * runs only the open production's operations: a request for another
 * production would carry the wrong `moduledata` and be refused — or worse,
 * accepted against the wrong production.
 */
data class SyncScope(val userId: String, val projectId: String)

/** Where an operation is in its life. */
enum class SyncState {
    /** Waiting for its turn, its dependency, or the network. */
    Pending,

    /** A handler is executing it right now. */
    InFlight,

    /**
     * Refused by the server, or by a handler, in a way that trying again will
     * not fix on its own. Holds its group until the user retries or discards.
     */
    Failed,

    /** The server accepted it. Kept briefly so dependents can read [SyncOperation.result]. */
    Done,
    ;

    companion object
}

/**
 * One unit of work the user did that the server has not yet accepted.
 *
 * The engine does not know what an operation *is* — [payload] is the owning
 * module's JSON, and a [SyncHandler] registered for [kind] turns it into
 * calls. What the engine owns is the bookkeeping: order, dependencies, retry,
 * and the words the pending list shows.
 */
data class SyncOperation(
    /** Client-minted, unique for ever; also the idempotency key where the server takes one. */
    val id: String,
    val scope: SyncScope,
    val kind: String,
    /** Human words for the pending list — "Float request: £250 for location petty cash". */
    val label: String,
    val payload: String,
    /**
     * Operations sharing a group run strictly in creation order, one at a
     * time, and a [SyncState.Failed] one holds the rest of the group. Use it
     * for anything about the same record: create, then attach, then submit.
     */
    val groupKey: String? = null,
    /** Must be [SyncState.Done] first; its [result] is readable by this one. */
    val dependsOn: String? = null,
    val state: SyncState = SyncState.Pending,
    val attempts: Int = 0,
    /** The last failure's user-facing message, for the pending list. */
    val lastError: String? = null,
    /** What the handler left for dependents — a server id, typically. */
    val result: String? = null,
    /** Not before this instant, in epoch millis — how backoff is expressed. */
    val nextAttemptAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isOpen: Boolean get() = state != SyncState.Done

    /** Never prints the payload — a production's figures are content. */
    override fun toString(): String =
        "SyncOperation(id=$id, kind=$kind, state=$state, attempts=$attempts, group=$groupKey)"
}

/** What a module hands the engine to enqueue. */
data class NewOperation(
    val kind: String,
    val label: String,
    val payload: String,
    val groupKey: String? = null,
    val dependsOn: String? = null,
    /** Supplied when the module already minted an id it put in the payload; else the engine mints one. */
    val id: String? = null,
)
