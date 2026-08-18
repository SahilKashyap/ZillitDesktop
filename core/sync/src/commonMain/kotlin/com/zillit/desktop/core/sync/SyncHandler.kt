package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError

/** How one attempt at an operation ended. */
sealed interface SyncOutcome {
    /**
     * The server has it. [result] is kept on the row for dependents — hand
     * back the server's id here when the create returns one, so the next
     * operation in the group can address the record.
     */
    data class Done(val result: String? = null) : SyncOutcome

    /**
     * Not now: the network, a timeout, a server that fell over. The engine
     * backs off and tries again by itself.
     */
    data class RetryLater(val error: ZillitError) : SyncOutcome

    /**
     * Not like this: the server refused the content, or a handler found the
     * operation can no longer apply. The engine parks it for the user, who
     * can retry (after fixing the cause) or discard.
     */
    data class Failed(val error: ZillitError) : SyncOutcome
}

/**
 * What a handler may ask the engine while executing.
 *
 * Handed in rather than reached for, so a handler is a pure function of its
 * operation plus these few answers — which is what makes it testable without
 * an engine.
 */
interface SyncContext {
    /** The [SyncOutcome.Done.result] of the operation this one depends on, if any. */
    suspend fun dependencyResult(operation: SyncOperation): String?

    /**
     * Rewrites the operation's payload — for a handler that made partial
     * progress it must not repeat, such as an upload that succeeded before
     * the create that failed. Persisted before this returns.
     */
    suspend fun updatePayload(operation: SyncOperation, payload: String)

    fun nowMillis(): Long
}

/**
 * Executes operations of one [kind].
 *
 * ## The contract a handler signs
 *
 * 1. **Idempotent where it can be.** An attempt may have reached the server
 *    and timed out on the way back; the retry must not create a second
 *    record. Send [SyncOperation.id] as the server's idempotency key wherever
 *    the backend accepts one, and where it does not, look before you create.
 * 2. **Classify honestly.** [SyncOutcome.RetryLater] only for things time
 *    fixes; a 4xx is [SyncOutcome.Failed]. Retrying a refusal for ever hides
 *    it from the user.
 * 3. **Verify, don't trust.** Some Account Hub endpoints answer success and
 *    store nothing; when it matters, read the record back before returning
 *    [SyncOutcome.Done].
 * 4. **Say what it was.** The label given at enqueue time is what the
 *    pending list shows, with or without the module loaded — make it the
 *    sentence a user would recognise their own work by.
 */
interface SyncHandler {
    val kind: String

    suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome
}

/** The handlers the engine can dispatch to, by kind. */
class SyncHandlerRegistry(handlers: List<SyncHandler> = emptyList()) {
    private val byKind = handlers.associateBy { it.kind }.toMutableMap()

    fun register(handler: SyncHandler) {
        byKind[handler.kind] = handler
    }

    operator fun get(kind: String): SyncHandler? = byKind[kind]

    val kinds: Set<String> get() = byKind.keys
}
