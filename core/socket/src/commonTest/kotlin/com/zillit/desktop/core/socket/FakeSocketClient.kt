package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Test double for [SocketClient].
 *
 * `replay = 1` so a test can emit before subscribing without racing the
 * collector — the real client is `replay = 0`, which is correct for production
 * but makes tests flaky.
 */
class FakeSocketClient : SocketClient {

    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<SocketMessage>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: Flow<SocketMessage> = _messages.asSharedFlow()

    val emitted = mutableListOf<Pair<SocketEventName, String?>>()
    var connectCount = 0
        private set

    override suspend fun connect(config: SocketConfig) {
        connectCount++
        _connectionState.value = SocketConnectionState.Connected("fake-socket")
    }

    override suspend fun disconnect() {
        _connectionState.value = SocketConnectionState.Disconnected
    }

    override suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit> {
        if (!_connectionState.value.isConnected) {
            return ZillitResult.Failure(ZillitError.NoConnection("fake is disconnected"))
        }
        emitted += event to Json.encodeToString(serializer, payload)
        return ZillitResult.Success(Unit)
    }

    override suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ZillitResult<kotlinx.serialization.json.JsonElement> =
        ZillitResult.Success(kotlinx.serialization.json.JsonNull)

    override suspend fun emit(event: SocketEventName): ZillitResult<Unit> {
        if (!_connectionState.value.isConnected) {
            return ZillitResult.Failure(ZillitError.NoConnection("fake is disconnected"))
        }
        emitted += event to null
        return ZillitResult.Success(Unit)
    }

    // -- test controls ----------------------------------------------------

    suspend fun deliver(event: SocketEventName, payloadJson: String) {
        _messages.emit(SocketMessage(event, Json.parseToJsonElement(payloadJson)))
    }

    suspend fun deliverRaw(event: SocketEventName, payload: JsonElement?) {
        _messages.emit(SocketMessage(event, payload))
    }

    fun setState(state: SocketConnectionState) {
        _connectionState.value = state
    }
}
