package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.KSerializer

/**
 * Realtime transport.
 *
 * ## The one rule
 *
 * **This layer knows nothing about features.** It moves named payloads and
 * manages a connection; it has no idea what a call sheet or a purchase order is.
 *
 * That is a direct response to what the Android client became. `ChatSocketHelper`
 * (1,993 lines) and `BaseSocketListener` (~2,000) between them import from 40+
 * feature packages, and `initializeSocket()` calls thirty `listenForX()` methods
 * by hand — one per feature. Adding a feature means editing the socket layer,
 * and every feature is therefore compiled into it. In a module graph that is not
 * merely untidy, it is a dependency cycle: `core:socket` would have to depend on
 * every `feature:*` module that depends on it.
 *
 * Here the arrow points one way. Features call [events] and filter for what they
 * care about. Nothing registers itself with the socket.
 */
interface SocketClient {

    val connectionState: StateFlow<SocketConnectionState>

    /**
     * Every inbound message, from every event.
     *
     * A single hot stream rather than per-event registration, because the
     * underlying client exposes a catch-all listener — so there is no
     * bookkeeping to get wrong on reconnect, and no event can arrive with
     * nobody watching for it because someone forgot a `listenForX()` call.
     *
     * Subscribers filter. [SocketEventBus] provides the typed helpers.
     */
    val messages: Flow<SocketMessage>

    suspend fun connect(config: SocketConfig)

    suspend fun disconnect()

    /**
     * Sends [payload] as [event].
     *
     * Fails rather than silently dropping when disconnected — the caller has to
     * decide whether to queue (chat does, via the offline outbox) or report.
     * The Android client's emit is fire-and-forget, which is part of why
     * messages go missing after a reconnect.
     */
    suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit>

    /** Emit with no payload. */
    suspend fun emit(event: SocketEventName): ZillitResult<Unit>

    /**
     * Emit and wait for the server's acknowledgement payload — the CNC
     * handlers answer queries through the ack, not through events.
     */
    suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<JsonElement>
}
