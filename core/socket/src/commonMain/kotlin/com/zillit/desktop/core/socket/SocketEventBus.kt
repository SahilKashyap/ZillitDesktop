package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Typed subscription over [SocketClient].
 *
 * This is what feature modules use. A feature declares the events it cares
 * about and the type it expects; it never touches the socket, and the socket
 * never learns the feature exists.
 *
 * ```kotlin
 * // in feature:calendar
 * eventBus.on(ZillitSocketEvents.Calendar.Created, CalendarEventDto.serializer())
 *     .onEach { dto -> repository.upsert(dto) }
 *     .launchIn(viewModelScope)
 * ```
 */
class SocketEventBus(
    private val client: SocketClient,
    private val json: Json = DefaultJson,
) {

    val connectionState = client.connectionState

    /** Raw payloads for one event. */
    fun on(event: SocketEventName): Flow<SocketMessage> =
        client.messages.filter { it.event == event }

    /**
     * Raw payloads for any of [events] — several events often share a handler.
     *
     * A collection rather than a vararg because Kotlin prohibits value classes
     * as vararg parameter types, and `SocketEventName` earns its value class.
     */
    fun onAny(events: Collection<SocketEventName>): Flow<SocketMessage> {
        val wanted = events.toSet()
        return client.messages.filter { it.event in wanted }
    }

    /**
     * Decoded payloads for one event.
     *
     * **A payload that fails to decode is dropped, not thrown.** One malformed
     * message must not tear down a subscription that the UI depends on for the
     * rest of the session — the failure is logged and the stream continues. This
     * is the same reasoning behind `coerceInputValues` on the HTTP client.
     */
    fun <T : Any> on(event: SocketEventName, serializer: KSerializer<T>): Flow<T> =
        on(event).mapNotNull { message -> decode(message, serializer) }

    fun <T : Any> onAny(
        events: Collection<SocketEventName>,
        serializer: KSerializer<T>,
    ): Flow<Pair<SocketEventName, T>> =
        onAny(events).mapNotNull { message ->
            decode(message, serializer)?.let { message.event to it }
        }

    /** Emits a signal each time [event] arrives, for handlers that only need the trigger. */
    fun signals(event: SocketEventName): Flow<Unit> = on(event).map { }

    /**
     * Sends [payload] as [event] — the bus is the one socket surface features
     * see, so the write path lives beside the read path. Fails rather than
     * queueing when disconnected; the caller decides what a lost emit costs.
     */
    suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
        client.emit(event, payload, serializer)

    /** Emit and wait for the ack payload; see [SocketClient.emitForAck]. */
    suspend fun <T> emitForAck(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
        client.emitForAck(event, payload, serializer)

    private fun <T : Any> decode(message: SocketMessage, serializer: KSerializer<T>): T? {
        val payload = message.payload ?: run {
            ZillitLog.w(TAG) { "${message.event.value} arrived with no payload" }
            return null
        }
        return runCatching { json.decodeFromJsonElement(serializer, payload) }
            .onFailure { throwable ->
                // The event name is safe to log; the payload is not — socket
                // messages carry chat bodies and financials (plan §8.4).
                ZillitLog.w(TAG) { "could not decode ${message.event.value}: ${throwable::class.simpleName}" }
            }
            .getOrNull()
    }

    companion object {
        private const val TAG = "SocketBus"

        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
