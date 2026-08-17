package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * [SocketClient] on `io.socket:socket.io-client` — a pure-Java library that runs
 * unchanged on desktop, and the same one the Android app uses, so wire behaviour
 * matches.
 *
 * Two things it does that the Android client does not:
 *
 *  1. **Rebuilds the auth header on every attempt.** Android builds the
 *     encrypted `moduledata` header once in `initializeSocket()` and reuses it
 *     for the life of the process, so a reconnect after it goes stale fails in a
 *     way that looks like a network problem.
 *  2. **Routes every event through one catch-all listener.** Android registers
 *     ~30 per-feature listeners by hand at connect time. Here nothing is
 *     registered per feature, so nothing can be forgotten on reconnect.
 */
class SocketIoClient(
    private val scope: CoroutineScope,
    private val json: Json = SocketEventBus.DefaultJson,
) : SocketClient {

    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<SocketMessage>(
        replay = 0,
        extraBufferCapacity = MESSAGE_BUFFER,
        // A slow subscriber must not stall the socket thread. Dropping the
        // oldest is right for realtime: a stale presence update matters less
        // than keeping the connection responsive.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: Flow<SocketMessage> = _messages.asSharedFlow()

    private val lock = Mutex()
    private var socket: Socket? = null
    private var config: SocketConfig? = null
    private var reconnectJob: Job? = null
    private var deliberateDisconnect = false

    override suspend fun connect(config: SocketConfig) = lock.withLock {
        this.config = config
        deliberateDisconnect = false
        openConnection(attempt = 0)
    }

    override suspend fun disconnect() = lock.withLock {
        deliberateDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        teardown()
        _connectionState.value = SocketConnectionState.Disconnected
    }

    override suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit> {
        val active = socket?.takeIf { it.connected() }
            ?: return ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))

        return runCatching {
            // Argument shape matched to Android's emit(event, payload, null,
            // Ack): the CNC handlers read their callback positionally after
            // that null, and without this exact shape the server accepts the
            // packet but never processes it — found live when every chat emit
            // went unanswered.
            active.emit(
                event.value,
                arrayOf(JSONObject(json.encodeToString(serializer, payload)), null),
                io.socket.client.Ack { },
            )
            Unit
        }.fold(
            onSuccess = { ZillitResult.Success(Unit) },
            onFailure = { ZillitResult.Failure(ZillitError.Unknown("emit failed: ${it::class.simpleName}")) },
        )
    }

    override suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<JsonElement> {
        val active = socket?.takeIf { it.connected() }
            ?: return ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))

        return runCatching {
            val answer = CompletableDeferred<JsonElement>()
            active.emit(
                event.value,
                arrayOf(JSONObject(json.encodeToString(serializer, payload)), null),
                io.socket.client.Ack { args ->
                    answer.complete(args.firstOrNull()?.toJsonElement() ?: JsonNull)
                },
            )
            withTimeout(ACK_TIMEOUT_MILLIS) { answer.await() }
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = {
                ZillitResult.Failure(ZillitError.Unknown("ack failed: ${it::class.simpleName}"))
            },
        )
    }

    override suspend fun emit(event: SocketEventName): ZillitResult<Unit> {
        val active = socket?.takeIf { it.connected() }
            ?: return ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))
        active.emit(event.value)
        return ZillitResult.Success(Unit)
    }

    // -- connection --------------------------------------------------------

    private suspend fun openConnection(attempt: Int) {
        val config = this.config ?: return
        teardown()
        _connectionState.value = SocketConnectionState.Connecting

        val headers = runCatching { config.authHeaders() }.getOrElse { throwable ->
            ZillitLog.e(TAG, throwable) { "could not build socket auth headers" }
            _connectionState.value = SocketConnectionState.Failed(
                ZillitError.Crypto("socket auth headers unavailable"),
            )
            return
        }

        val options = IO.Options().apply {
            // Reconnect is ours: the library cannot rebuild the auth header,
            // and it has no jitter (see ReconnectPolicy).
            reconnection = false
            auth = headers
        }

        socket = IO.socket(config.url, options).apply {
            on(Socket.EVENT_CONNECT) { onConnected() }
            on(Socket.EVENT_DISCONNECT) { args -> onDisconnected(args, attempt) }
            on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args, attempt) }
            // One listener for everything. No per-feature registration exists,
            // so none can be missed on reconnect.
            onAnyIncoming(anyIncomingListener)
            connect()
        }
    }

    private val anyIncomingListener = Emitter.Listener { args ->
        val name = args.firstOrNull() as? String ?: return@Listener
        val payload = args.getOrNull(1)?.toJsonElement()
        scope.launch { _messages.emit(SocketMessage(SocketEventName(name), payload)) }
    }

    private fun onConnected() {
        val id = socket?.id()
        ZillitLog.i(TAG) { "connected (id=$id)" }
        _connectionState.value = SocketConnectionState.Connected(id)
    }

    private fun onDisconnected(args: Array<out Any?>, attempt: Int) {
        val reason = args.firstOrNull()?.toString().orEmpty()
        ZillitLog.i(TAG) { "disconnected: $reason" }
        if (deliberateDisconnect) {
            _connectionState.value = SocketConnectionState.Disconnected
        } else {
            scheduleReconnect(attempt + 1, ZillitError.NoConnection(reason))
        }
    }

    private fun onConnectError(args: Array<out Any?>, attempt: Int) {
        val detail = args.firstOrNull()?.toString().orEmpty()

        // A rejected handshake is not a network problem — retrying cannot fix a
        // revoked device, and hammering the server while signed out is worse
        // than stopping. The app signs out on this.
        if (isHandshakeUnauthorized(detail)) {
            ZillitLog.w(TAG) { "handshake rejected — not retrying" }
            _connectionState.value = SocketConnectionState.Failed(ZillitError.Unauthorized(detail))
            return
        }

        scheduleReconnect(attempt + 1, ZillitError.NoConnection(detail))
    }

    private fun scheduleReconnect(attempt: Int, cause: ZillitError) {
        val policy = config?.reconnect ?: return
        if (!policy.shouldRetry(attempt)) {
            _connectionState.value = SocketConnectionState.Failed(cause)
            return
        }

        val delayMillis = policy.delayFor(attempt)
        _connectionState.value = SocketConnectionState.Reconnecting(attempt, delayMillis)
        ZillitLog.i(TAG) { "reconnecting in ${delayMillis}ms (attempt $attempt)" }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMillis)
            lock.withLock { if (!deliberateDisconnect) openConnection(attempt) }
        }
    }

    private fun teardown() {
        socket?.apply {
            offAnyIncoming(anyIncomingListener)
            off()
            disconnect()
        }
        socket = null
    }

    private companion object {
        const val TAG = "Socket"
        const val MESSAGE_BUFFER = 256
    }
}

/**
 * Bridges `org.json` (what the Socket.IO client hands us) to `kotlinx.serialization`.
 *
 * Re-parsing through the string form rather than walking the tree by hand: it is
 * a few microseconds on messages that arrive at human speed, and a hand-written
 * walker is a reliable source of subtle bugs around nulls and nested arrays.
 */
internal fun Any.toJsonElement(): JsonElement? = when (this) {
    is JSONObject -> runCatching { SocketEventBus.DefaultJson.parseToJsonElement(toString()) }.getOrNull()
    is JSONArray -> runCatching { SocketEventBus.DefaultJson.parseToJsonElement(toString()) }.getOrNull()
    JSONObject.NULL -> JsonNull
    else -> runCatching { SocketEventBus.DefaultJson.parseToJsonElement(toString()) }.getOrNull()
}

private const val ACK_TIMEOUT_MILLIS = 10_000L
