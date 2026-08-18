package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends what the user did while the server could not be reached.
 *
 * One worker, one operation at a time, in the order [SyncScheduling] says.
 * It wakes for four reasons — something was enqueued, the network came back,
 * a backoff expired, someone asked — and otherwise sleeps. It runs only the
 * open production's operations (see [SyncScope]); the rest wait, counted, for
 * their production to be opened.
 *
 * What it does *not* do is know anything about cash, orders or timecards.
 * That knowledge is in the [SyncHandler]s the modules register.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One method per verb the shell and modules use; wired once.
class SyncEngine(
    private val store: OutboxStore,
    private val handlers: SyncHandlerRegistry,
    private val online: StateFlow<Boolean>,
    /** Whose operations run now — null before sign-in or a production is open. */
    private val currentScope: () -> SyncScope?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val newId: () -> String,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var lastSyncedAt: Long? = null

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            // Whatever was mid-flight when the process died did not finish.
            store.requeueInFlight(nowMillis())
            store.pruneDone(nowMillis() - DONE_RETENTION_MILLIS)
            online.onEach { refreshStatus(); if (it) wake() }.launchIn(this)
            store.changes().onEach { refreshStatus() }.launchIn(this)
            refreshStatus()
            loop()
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
    }

    /** Queues [operation] for the current scope and wakes the worker. */
    suspend fun enqueue(operation: NewOperation): SyncOperation? {
        val scope = currentScope() ?: return null
        val now = nowMillis()
        val row = SyncOperation(
            id = operation.id ?: newId(),
            scope = scope,
            kind = operation.kind,
            label = operation.label,
            payload = operation.payload,
            groupKey = operation.groupKey,
            dependsOn = operation.dependsOn,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now,
        )
        store.insert(row)
        wake()
        return row
    }

    /** A parked operation gets another go, now. */
    suspend fun retry(id: String) {
        val op = store.get(id) ?: return
        if (op.state != SyncState.Failed && op.state != SyncState.Pending) return
        val now = nowMillis()
        store.update(op.copy(state = SyncState.Pending, lastError = null, nextAttemptAt = now, updatedAt = now))
        wake()
    }

    /** Throws the operation away — and everything that was waiting on it. */
    suspend fun discard(id: String) {
        val op = store.get(id) ?: return
        val dependents = store.forScope(op.scope).dependentsOf(id)
        (dependents + id).forEach { store.delete(it) }
        wake()
    }

    /** The open production's queue, oldest first, for the pending list. */
    suspend fun operations(): List<SyncOperation> =
        currentScope()?.let { store.forScope(it) }.orEmpty()

    /** How much of this person's work is still unsent, across productions — the sign-out question. */
    suspend fun openCount(userId: String): Int =
        store.all().count { it.scope.userId == userId && it.isOpen }

    /** Look again now — a "Sync now" click, a production switch, a sign-in. */
    fun wake() {
        wake.trySend(Unit)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            drain()
            refreshStatus()
            val current = currentScope()?.let { store.forScope(it) }.orEmpty()
            val delay = current.nextWakeAt(nowMillis())?.let { it - nowMillis() }
            if (delay == null) wake.receive() else withTimeoutOrNull(delay.coerceAtLeast(1)) { wake.receive() }
        }
    }

    /** Runs everything runnable, one at a time, until nothing is or the network goes. */
    private suspend fun drain() {
        while (scope.isActive && online.value) {
            val scope = currentScope() ?: return
            val next = store.forScope(scope).nextRunnable(nowMillis()) ?: return
            execute(next)
        }
    }

    private suspend fun execute(operation: SyncOperation) {
        val now = nowMillis()
        val inFlight = operation.copy(state = SyncState.InFlight, attempts = operation.attempts + 1, updatedAt = now)
        store.update(inFlight)
        _status.value = _status.value.copy(syncing = true)

        val outcome = try {
            val handler = handlers[operation.kind]
            if (handler == null) {
                SyncOutcome.Failed(
                    ZillitError.Validation(NO_HANDLER_MESSAGE, "no handler registered for ${operation.kind}"),
                )
            } else {
                handler.execute(inFlight, context)
            }
        } catch (cancelled: CancellationException) {
            // Shutting down mid-attempt: back to pending, so the restart re-runs
            // it. Non-cancellable, or the write itself would be skipped — the
            // coroutine is already cancelled by the time we get here.
            withContext(NonCancellable) {
                store.update(inFlight.copy(state = SyncState.Pending, updatedAt = nowMillis()))
            }
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.e(TAG, throwable) { "handler for ${operation.kind} threw" }
            SyncOutcome.Failed(ZillitError.Validation(HANDLER_CRASHED_MESSAGE, throwable.message))
        }

        settle(inFlight, outcome)
        _status.value = _status.value.copy(syncing = false)
    }

    private suspend fun settle(operation: SyncOperation, outcome: SyncOutcome) {
        val now = nowMillis()
        val next = when (outcome) {
            is SyncOutcome.Done -> {
                lastSyncedAt = now
                operation.copy(state = SyncState.Done, result = outcome.result, lastError = null, updatedAt = now)
            }

            is SyncOutcome.RetryLater -> operation.copy(
                state = SyncState.Pending,
                lastError = outcome.error.userMessage,
                nextAttemptAt = now + retryPolicy.delayFor(operation.attempts),
                updatedAt = now,
            )

            is SyncOutcome.Failed -> operation.copy(
                state = SyncState.Failed,
                lastError = outcome.error.userMessage,
                updatedAt = now,
            )
        }
        ZillitLog.i(TAG) {
            val why = next.lastError?.let { " — $it" }.orEmpty()
            "${operation.kind} attempt ${operation.attempts}: ${next.state}$why"
        }
        store.update(next)
    }

    private suspend fun refreshStatus() {
        val all = store.all()
        val scope = currentScope()
        val mine = all.filter { it.scope == scope }
        _status.value = SyncStatus(
            online = online.value,
            pending = mine.count { it.state == SyncState.Pending || it.state == SyncState.InFlight },
            failed = mine.count { it.state == SyncState.Failed },
            syncing = _status.value.syncing,
            elsewhere = all.count { it.isOpen && it.scope != scope },
            lastSyncedAt = lastSyncedAt,
        )
    }

    private val context = object : SyncContext {
        override suspend fun dependencyResult(operation: SyncOperation): String? =
            operation.dependsOn?.let { store.get(it)?.result }

        override suspend fun updatePayload(operation: SyncOperation, payload: String) =
            store.updatePayload(operation.id, payload, nowMillis())

        override fun nowMillis(): Long = this@SyncEngine.nowMillis()
    }

    companion object {
        private const val TAG = "Sync"
        private const val DONE_RETENTION_MILLIS = 7 * 24 * 60 * 60_000L
        const val NO_HANDLER_MESSAGE = "This change needs a newer version of Zillit to send."
        const val HANDLER_CRASHED_MESSAGE = "Something went wrong sending this change."
    }
}
