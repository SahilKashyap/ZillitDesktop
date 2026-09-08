package com.zillit.desktop.feature.calls.data.livekit

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One open presence connection: what the transport gives back once the upgrade succeeded. */
interface LiveKitSocket {
    /** False when the socket is already gone — the caller decides what that means. */
    fun send(frame: String): Boolean

    fun close(reason: String)
}

/** Opens presence connections. One implementation per platform; a fake in tests. */
interface LiveKitSocketFactory {
    /**
     * Connects, or throws. [onFrame] sees every text frame; [onClosed] fires
     * once, however the connection ended, and never before this returns.
     */
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
        onFrame: (String) -> Unit,
        onClosed: (reason: String) -> Unit,
    ): LiveKitSocket
}

/** A request the server refused, or one it never answered. */
class LiveKitSignalError(val code: String, message: String = code) : RuntimeException(message)

/**
 * Request correlation over one presence connection.
 *
 * The server answers a request by its `reqId`, and everything else it sends
 * is an event. This keeps the waiters, hands each its answer or its timeout,
 * and publishes the events — the same three jobs ProtooPeer does for Line 1,
 * for the same reason: exactly one path may complete each request, and a
 * request nobody is waiting on any more must not throw.
 */
class LiveKitPeer(
    private val scope: CoroutineScope,
    private val send: suspend (String) -> Unit,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) {
    private class Waiter(val answer: CompletableDeferred<JsonElement?>) {
        var timer: Job? = null
    }

    private val lock = Mutex()
    private val pending = mutableMapOf<String, Waiter>()
    private var closed = false
    private var sequence = 0

    private val _events = MutableSharedFlow<LiveKitEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<LiveKitEvent> = _events.asSharedFlow()

    /**
     * Sends `{type, reqId, …fields}` and returns the server's `data`; throws
     * [LiveKitSignalError] on refusal or silence.
     */
    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val waiter = Waiter(CompletableDeferred())
        val reqId = lock.withLock {
            if (closed) throw LiveKitSignalError(CODE_CLOSED, "presence socket is closed")
            (++sequence).toString().also { pending[it] = waiter }
        }
        waiter.timer = scope.launch {
            delay(timeoutMillis)
            take(reqId)?.answer?.completeExceptionally(LiveKitSignalError(CODE_TIMEOUT, "$type timed out"))
        }
        runCatching { send(liveKitRequestFrame(type, reqId, fields)) }.onFailure { thrown ->
            take(reqId)?.answer?.completeExceptionally(
                LiveKitSignalError(CODE_SEND_FAILED, "send failed: ${thrown.message ?: thrown::class.simpleName}"),
            )
        }
        return try {
            waiter.answer.await()
        } finally {
            waiter.timer?.cancel()
        }
    }

    /** Every text frame off the socket, in order. */
    suspend fun onFrame(raw: String) {
        ZillitLog.d(TAG) { "<- ${summarise(raw)}" }
        when (val frame = parseLiveKitFrame(raw)) {
            is LiveKitFrame.Response -> {
                val waiter = take(frame.reqId)
                if (waiter == null) {
                    ZillitLog.d(TAG) { "response ${frame.reqId} has no waiter" }
                    return
                }
                if (frame.ok) {
                    waiter.answer.complete(frame.data)
                } else {
                    waiter.answer.completeExceptionally(LiveKitSignalError(frame.error ?: "request failed"))
                }
            }
            is LiveKitFrame.Event -> _events.emit(frame.event)
            is LiveKitFrame.Unknown -> ZillitLog.d(TAG) { "unread event type=${frame.type}" }
            null -> ZillitLog.d(TAG) { "frame is not a JSON object" }
        }
    }

    /** The connection died: every waiter learns so at once, and nothing new may be sent. */
    suspend fun close(reason: String) {
        val waiters = lock.withLock {
            closed = true
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        waiters.forEach { waiter ->
            waiter.timer?.cancel()
            waiter.answer.completeExceptionally(LiveKitSignalError(CODE_CLOSED, reason))
        }
    }

    private suspend fun take(reqId: String): Waiter? = lock.withLock { pending.remove(reqId) }

    /**
     * The frame's identifying words for the log — never the whole frame,
     * which on an `incomingCall` carries the room token.
     */
    private fun summarise(raw: String): String {
        val obj = runCatching { LIVEKIT_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return "(not an object)"
        return LOGGED_FIELDS.mapNotNull { key -> obj.text(key)?.let { "$key=$it" } }.joinToString(" ")
            .ifBlank { "(no known fields) ${raw.take(RAW_PREVIEW)}" }
    }

    companion object {
        const val CODE_CLOSED = "notify_offline"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_SEND_FAILED = "send_failed"
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val EVENT_BUFFER = 64
        private const val RAW_PREVIEW = 120
        private val LOGGED_FIELDS =
            listOf("type", "reqId", "ok", "error", "callId", "userId", "state", "reason", "toUserId")
        private const val TAG = "LiveKitPeer"
    }
}
