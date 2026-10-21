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
    /**
     * Refused handshakes, for the server error log. Diagnostics only — see
     * [SocketFailureReporter], and [reportConnectFailure] for what is sent.
     */
    private val reportFailure: SocketFailureReporter = SocketFailureReporter.None,
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

    /**
     * Consecutive failed attempts, and so where the backoff currently stands.
     *
     * A field rather than a parameter carried through the listeners, because
     * it has to be **reset by a success**. It used to ride [openConnection] as
     * an argument, which every listener closed over: a socket that came up on
     * the fifth attempt kept telling the next disconnect it was the fifth, so
     * a connection that had been healthy for hours reconnected on the
     * 32-second step and then the 60-second cap. After a night of sleeping,
     * that is the minute of stale screen the reader actually sees.
     *
     * Volatile because the socket.io thread writes it and the reconnect
     * coroutine reads it. A stale read would only mis-time one delay, but the
     * barrier is free.
     */
    @Volatile
    private var attempts = 0

    override suspend fun connect(config: SocketConfig) = lock.withLock {
        this.config = config
        deliberateDisconnect = false
        attempts = 0
        openConnection()
    }

    /**
     * Come back **now**, whatever the backoff was about to do.
     *
     * For the one case the transport cannot work out for itself: the machine
     * slept. The socket on the other side of a lid closing is dead, but this
     * side does not know it until the heartbeat gives up — and if the backoff
     * had already walked out to its cap before the machine went down, the
     * first re-read after the lid opens is another minute away. A wake redials
     * instead (see `WakeWatcher`), and the attempt count starts over because
     * the network this is dialling is a new one.
     *
     * [teardown] inside [openConnection] is what makes this safe on a socket
     * that still *claims* to be connected, which is exactly the post-sleep
     * state.
     *
     * Silently ignored with nothing to dial — no configuration yet, or a
     * deliberate disconnect — so a wake while signed out does nothing.
     */
    suspend fun reconnectNow(reason: String) = lock.withLock {
        if (config == null || deliberateDisconnect) return@withLock
        ZillitLog.i(TAG) { "redialling: $reason" }
        reconnectJob?.cancel()
        reconnectJob = null
        attempts = 0
        openConnection()
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

        // The counterpart of the `<-` line below. Only inbound frames were
        // traced, so an emit that the server silently dropped — or one a
        // feature never actually made — left no record at all, and a wire
        // contract could only be checked by reading the code that sends it.
        // The NAME only: these frames carry chat bodies and financials.
        ZillitLog.d(TAG) { "-> ${event.value}" }
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

        ZillitLog.d(TAG) { "-> ${event.value} (awaiting ack)" }
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
                // A dropped ack is indistinguishable from a refusal to every
                // caller, and both used to be silent. Named here so the one
                // that needs a server-side answer can be told apart from the
                // one that needs a retry.
                ZillitLog.w(TAG) { "no ack for ${event.value}: ${it::class.simpleName}" }
                ZillitResult.Failure(ZillitError.Unknown("ack failed: ${it::class.simpleName}"))
            },
        )
    }

    override suspend fun emit(event: SocketEventName): ZillitResult<Unit> {
        val active = socket?.takeIf { it.connected() }
            ?: return ZillitResult.Failure(ZillitError.NoConnection("socket is not connected"))
        ZillitLog.d(TAG) { "-> ${event.value}" }
        active.emit(event.value)
        return ZillitResult.Success(Unit)
    }

    // -- connection --------------------------------------------------------

    private suspend fun openConnection() {
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
            on(Socket.EVENT_DISCONNECT) { args -> onDisconnected(args) }
            on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args) }
            // One listener for everything. No per-feature registration exists,
            // so none can be missed on reconnect.
            onAnyIncoming(anyIncomingListener)
            connect()
        }
    }

    private val anyIncomingListener = Emitter.Listener { args ->
        val name = args.firstOrNull() as? String ?: return@Listener
        val payload = args.getOrNull(1)?.toJsonElement()

        // The event name only, never the payload: these carry message bodies,
        // call participants and crew names. Without this line a socket that
        // connects and then receives nothing is indistinguishable from one
        // that is working — the connect log says "connected" either way, and
        // every realtime feature (presence, call status, the notifications
        // raised from these events) fails silently and identically.
        ZillitLog.d(TAG) { "<- $name" }

        scope.launch { _messages.emit(SocketMessage(SocketEventName(name), payload)) }
    }

    private fun onConnected() {
        val id = socket?.id()
        ZillitLog.i(TAG) { "connected (id=$id)" }
        // The backoff starts over from here: a connection that worked is not
        // evidence about the next failure. See [attempts].
        attempts = 0
        _connectionState.value = SocketConnectionState.Connected(id)
    }

    private fun onDisconnected(args: Array<out Any?>) {
        val reason = args.firstOrNull()?.toString().orEmpty()
        ZillitLog.i(TAG) { "disconnected: $reason" }
        if (deliberateDisconnect) {
            _connectionState.value = SocketConnectionState.Disconnected
        } else {
            scheduleReconnect(ZillitError.NoConnection(reason))
        }
    }

    private fun onConnectError(args: Array<out Any?>) {
        val detail = args.firstOrNull()?.toString().orEmpty()

        // A refused token is the credential's problem, not the device's: say
        // so, and let the retry present a different one.
        if (isSocketTokenRejected(detail)) {
            ZillitLog.w(TAG) { "handshake refused the token; retrying with another credential" }
            reportConnectFailure(detail, KIND_TOKEN_REJECTED)
            config?.onAuthRejected?.invoke(detail)
            scheduleReconnect(ZillitError.NoConnection(detail))
            return
        }

        // A rejected handshake is not a network problem — retrying cannot fix a
        // revoked device, and hammering the server while signed out is worse
        // than stopping. The app signs out on this.
        if (isHandshakeUnauthorized(detail)) {
            ZillitLog.w(TAG) { "handshake rejected — not retrying" }
            reportConnectFailure(detail, KIND_UNAUTHORIZED)
            _connectionState.value = SocketConnectionState.Failed(ZillitError.Unauthorized(detail))
            return
        }

        reportConnectFailure(detail, KIND_NETWORK)
        scheduleReconnect(ZillitError.NoConnection(detail))
    }

    /**
     * One line in the server error log per refused handshake, as the web sends
     * from its own `connect_error`.
     *
     * [kind] is what a reader needs first: a revoked device, a refused
     * credential and a dead network all read as "the socket is down" from the
     * outside, and only two of them are ours to fix. The attempt number rides
     * along because the first failure and the fortieth mean different things —
     * it is this attempt's, so [scheduleReconnect] has not counted it yet.
     *
     * Diagnostics only: wrapped, because a socket must not fall over on the
     * way to describing why it could not connect, and [detail] is the
     * library's own reason string, capped — never a header or a token.
     */
    private fun reportConnectFailure(detail: String, kind: String) {
        runCatching {
            reportFailure.report(
                SocketFailureReporter.CONNECT_ERROR,
                detail.take(SocketFailureReporter.MESSAGE_MAX).ifBlank { kind },
                mapOf("kind" to kind, "attempt" to (attempts + 1).toString()),
            )
        }
    }

    private fun scheduleReconnect(cause: ZillitError) {
        val policy = config?.reconnect ?: return
        val attempt = attempts + 1
        attempts = attempt
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
            lock.withLock { if (!deliberateDisconnect) openConnection() }
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

        /** The server refused the credential we presented; another one may work. */
        const val KIND_TOKEN_REJECTED = "token_rejected"

        /** The handshake was rejected outright — this device is not welcome. */
        const val KIND_UNAUTHORIZED = "unauthorized"

        /** Nothing refused us; the connection simply did not happen. */
        const val KIND_NETWORK = "network"
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
