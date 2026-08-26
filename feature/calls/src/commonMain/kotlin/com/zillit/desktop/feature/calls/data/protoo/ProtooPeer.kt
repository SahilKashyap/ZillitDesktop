package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** A protoo request that did not succeed. [code] is the server's, or one of ours. */
class ProtooError(
    val code: Int,
    val reason: String,
    cause: Throwable? = null,
) : Exception("protoo $code: $reason", cause)

/**
 * The request/response half of protoo, over whatever carries the frames.
 *
 * Transport-agnostic on purpose: this owns correlation, timeouts and the
 * answering of server requests, and knows nothing about WebSockets. That makes
 * the fiddly part — which is all of the above — testable without a socket.
 *
 * The peer does not reconnect. A transport that drops is a transport that
 * drops; whoever owns the socket decides whether to dial again, and calls
 * [failAll] to reject everything still waiting.
 */
class ProtooPeer(
    private val scope: CoroutineScope,
    /** Puts one frame on the wire. Throws if it could not be sent. */
    private val send: suspend (String) -> Unit,
    /** Request ids. Injected so tests are not at the mercy of a random number. */
    private val nextId: () -> Long = { randomProtooId() },
) {
    private val pending = mutableMapOf<Long, Waiter>()
    private val lock = Mutex()
    private var closed = false

    private val _notifications = MutableSharedFlow<ProtooMessage.Notification>(extraBufferCapacity = 64)

    /** What the server tells us, unasked. */
    val notifications: SharedFlow<ProtooMessage.Notification> = _notifications.asSharedFlow()

    private val _requests = MutableSharedFlow<ProtooMessage.Request>(extraBufferCapacity = 16)

    /**
     * What the server asks of us. Every one of these MUST be answered with
     * [accept] or [reject] — the SFU abandons a request it gets no answer to,
     * and on `newConsumer` that means a track that never arrives.
     */
    val requests: SharedFlow<ProtooMessage.Request> = _requests.asSharedFlow()

    private class Waiter(val answer: CompletableDeferred<JsonObject>, var timer: Job? = null)

    /**
     * Asks the server something and waits for its answer.
     *
     * Ten seconds, flat. Not a tuned number — it is what the phones use, and a
     * request that has not been answered in ten seconds on a call that is
     * already up is not slow, it is lost.
     */
    suspend fun request(method: String, data: JsonObject = JsonObject(emptyMap())): JsonObject {
        val id = nextId()
        val waiter = Waiter(CompletableDeferred())

        lock.withLock {
            if (closed) throw ProtooError(CODE_CLOSED, "Peer is closed")
            pending[id] = waiter
        }

        waiter.timer = scope.launch {
            delay(REQUEST_TIMEOUT_MILLIS)
            // Only if we win the removal. See [take].
            take(id)?.answer?.completeExceptionally(
                ProtooError(CODE_TIMEOUT, "request timed out: $method"),
            )
        }

        runCatching { send(protooRequestFrame(id, method, data)) }.onFailure { thrown ->
            take(id)?.answer?.completeExceptionally(
                ProtooError(CODE_SEND_FAILED, "send failed: ${thrown.message ?: thrown::class.simpleName}"),
            )
        }

        return try {
            waiter.answer.await()
        } finally {
            waiter.timer?.cancel()
        }
    }

    /** Tells the server something. No id, no answer, no failure worth raising. */
    suspend fun notify(method: String, data: JsonObject = JsonObject(emptyMap())) {
        runCatching { send(protooNotificationFrame(method, data)) }.onFailure { thrown ->
            ZillitLog.w(TAG) { "notify $method not sent: ${thrown.message}" }
        }
    }

    /** Answers a server request. */
    suspend fun accept(id: Long, data: JsonObject = JsonObject(emptyMap())) {
        runCatching { send(protooAcceptFrame(id, data)) }.onFailure { thrown ->
            ZillitLog.w(TAG) { "accept($id) not sent: ${thrown.message}" }
        }
    }

    /** Refuses a server request. */
    suspend fun reject(id: Long, code: Int, reason: String) {
        runCatching { send(protooRejectFrame(id, code, reason)) }.onFailure { thrown ->
            ZillitLog.w(TAG) { "reject($id) not sent: ${thrown.message}" }
        }
    }

    /** Feeds one received frame in. Whoever owns the socket calls this. */
    suspend fun onFrame(raw: String) {
        when (val message = parseProtoo(raw)) {
            is ProtooMessage.Response -> {
                val waiter = take(message.id)
                if (waiter == null) {
                    // Already timed out, or never ours. Not an error: the
                    // timeout fired and the answer arrived afterwards.
                    ZillitLog.d(TAG) { "response ${message.id} has no waiter" }
                    return
                }
                if (message.ok) {
                    waiter.answer.complete(message.data)
                } else {
                    waiter.answer.completeExceptionally(
                        ProtooError(message.errorCode, message.errorReason),
                    )
                }
            }

            is ProtooMessage.Notification -> _notifications.emit(message)
            is ProtooMessage.Request -> _requests.emit(message)
            null -> Unit
        }
    }

    /**
     * Rejects everything still waiting, with one reason.
     *
     * Called when the socket goes away. Without it, a caller suspended on
     * [request] waits out its full timeout for an answer that provably cannot
     * come, and a join stalls ten seconds behind a socket that is already gone.
     */
    suspend fun failAll(code: Int, reason: String) {
        val waiters = lock.withLock {
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        waiters.forEach { waiter ->
            waiter.timer?.cancel()
            waiter.answer.completeExceptionally(ProtooError(code, reason))
        }
    }

    /** No further requests. Anything outstanding is failed. */
    suspend fun close(reason: String = "peer closed") {
        lock.withLock { closed = true }
        failAll(CODE_CLOSED, reason)
    }

    /**
     * Removes a pending request, and returns it only to the caller that won.
     *
     * This is the whole timeout/answer arbitration, and it is a removal rather
     * than a cancelled timer because a timer already dispatched cannot be
     * called back. Exactly one of {the answer, the timeout, the send failure,
     * the teardown} may complete a request; the others find nothing and do
     * nothing. The phones learned this the hard way — a double completion
     * unbalanced the group waiting on the two transport creations, and the
     * join hung with no error anywhere.
     */
    private suspend fun take(id: Long): Waiter? = lock.withLock { pending.remove(id) }

    companion object {
        /** Flat, and the same as the phones'. */
        const val REQUEST_TIMEOUT_MILLIS = 10_000L

        /** Ours, not the server's: nobody was there to answer. */
        const val CODE_TIMEOUT = 408
        const val CODE_CLOSED = -1
        const val CODE_SEND_FAILED = -2

        /** What we answer a method we do not implement. */
        const val CODE_UNKNOWN_METHOD = 403
    }
}

fun randomProtooId(): Long = (1 until PROTOO_ID_CEILING).random()

/** The phones' range. Small enough to read in a log, wide enough not to collide. */
private const val PROTOO_ID_CEILING = 10_000_000L

private const val TAG = "ProtooPeer"
