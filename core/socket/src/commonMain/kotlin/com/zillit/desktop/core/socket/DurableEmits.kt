package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Where held emits wait between sessions — one string the host keeps for the
 * signed-in person. Blank means nothing is held.
 */
interface HeldEmitStore {
    suspend fun read(): String

    suspend fun write(value: String)
}

/**
 * A [SocketClient] that does not lose the emits named in [durable].
 *
 * A badge read is applied to the ledger the moment it is made — the badge
 * falls as the screen draws — and told to the server over the socket. Made
 * while the socket was down, the emit failed, every caller ignored the
 * failure, and the read existed on this desktop alone: the server still had
 * the row unread, so it came back on the phones, on the picker after a
 * relaunch, and here whenever the ledger was fetched again. A production
 * switch reconnects the socket, and the screen that opens reads in that gap.
 *
 * So these events are sent now or held: kept in [store] exactly as they were
 * made — the read's own time inside, so the server learns when it was read,
 * not when it was told — and sent, oldest first, as soon as the connection
 * is up again. Every other event passes through untouched.
 *
 * Reads are idempotent on the server, which is what makes holding them safe:
 * one that did reach it before the failure, sent again, changes nothing.
 */
class DurableEmits(
    private val delegate: SocketClient,
    private val durable: Set<SocketEventName>,
    private val store: HeldEmitStore,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val json: Json = SocketEventBus.DefaultJson,
) : SocketClient by delegate {

    private val lock = Mutex()

    /**
     * Whether the store may hold something. Unknown at start (the last session
     * may have left reads behind), so true; false once a flush empties it.
     * Spares a store read on every read made while all is well.
     */
    @kotlin.concurrent.Volatile
    private var mayHold = true

    init {
        scope.launch {
            delegate.connectionState
                .filterIsInstance<SocketConnectionState.Connected>()
                .collect { flush() }
        }
    }

    /**
     * Sends [payload] now, or holds it when the event is durable and cannot be
     * sent. A held emit answers success: it will be sent, and no caller has
     * anything better to do with the failure.
     */
    override suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit> {
        if (event !in durable) return delegate.emit(event, payload, serializer)
        val sent = attempt { delegate.emit(event, payload, serializer) }
        if (sent is ZillitResult.Success) {
            // The line is up: anything held from before goes now, not at the
            // next reconnect, which may be a long way off.
            if (mayHold) scope.launch { flush() }
            return sent
        }
        hold(HeldEmit(event.value, json.encodeToString(serializer, payload), nowMillis()))
        return ZillitResult.Success(Unit)
    }

    /** Sends what is held, oldest first; stops at the first that will not go, and keeps it and the rest. */
    suspend fun flush(): Unit = lock.withLock {
        if (!mayHold) return@withLock
        val held = load()
        val live = held.filter { nowMillis() - it.heldAt <= MAX_AGE_MILLIS }
        var done = 0
        for (emit in live) {
            val payload = runCatching { json.parseToJsonElement(emit.payload) as JsonObject }.getOrNull()
            if (payload != null) {
                val sent = attempt { delegate.emit(SocketEventName(emit.event), payload, JsonObject.serializer()) }
                if (sent !is ZillitResult.Success) break
            }
            done++
        }
        val left = live.drop(done)
        if (left.size != held.size) save(left)
        mayHold = left.isNotEmpty()
        if (done > 0) {
            val waiting = if (left.isEmpty()) "" else "; ${left.size} still held"
            ZillitLog.i(TAG) { "sent $done held read(s)$waiting" }
        }
    }

    private suspend fun hold(emit: HeldEmit): Unit = lock.withLock {
        val held = load()
        mayHold = true
        // The same read made twice while offline is one read; the first keeps
        // its time, which is when the person actually saw it.
        if (held.any { it.event == emit.event && it.scopeOf() == emit.scopeOf() }) return@withLock
        save((held + emit).takeLast(MAX_HELD))
        ZillitLog.i(TAG) { "${emit.event} held until the connection is back (${held.size + 1} waiting)" }
    }

    private suspend fun load(): List<HeldEmit> {
        val text = runCatching { store.read() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(HELD_LIST, text) }.getOrElse {
            ZillitLog.w(TAG) { "held reads unreadable; starting over" }
            emptyList()
        }
    }

    private suspend fun save(emits: List<HeldEmit>) {
        runCatching { store.write(if (emits.isEmpty()) "" else json.encodeToString(HELD_LIST, emits)) }
            .onFailure { ZillitLog.w(TAG) { "held reads not saved: ${it::class.simpleName}" } }
    }

    /** What a read covers, without when it was made — two reads of one scope are one read. */
    private fun HeldEmit.scopeOf(): JsonObject? =
        runCatching { json.parseToJsonElement(payload) as JsonObject }.getOrNull()
            ?.let { body -> JsonObject(body.filterKeys { it !in TIME_KEYS }) }

    private suspend fun attempt(send: suspend () -> ZillitResult<Unit>): ZillitResult<Unit> =
        try {
            send()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            ZillitResult.Failure(ZillitError.NoConnection(failure.message ?: "emit failed"))
        }

    private companion object {
        const val TAG = "Socket"

        /** Enough for a long offline stretch; past it the oldest go, as they are the least likely to matter. */
        const val MAX_HELD = 500

        /** A read a month old describes rows the ledger prunes anyway. */
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60_000

        /** The fields that say when, not what — `timestamp`, and iOS's `read_time`. */
        val TIME_KEYS = setOf("timestamp", "read_time")

        val HELD_LIST = ListSerializer(HeldEmit.serializer())
    }
}

/** One emit waiting: the event, the payload exactly as it was made, and when it was held. */
@Serializable
internal data class HeldEmit(val event: String, val payload: String, val heldAt: Long)
