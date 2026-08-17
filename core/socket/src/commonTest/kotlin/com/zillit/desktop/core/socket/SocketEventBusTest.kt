package com.zillit.desktop.core.socket

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class ChatMessage(val id: String, val body: String, val edited: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class SocketEventBusTest {

    private val client = FakeSocketClient()
    private val bus = SocketEventBus(client)

    @Test
    fun `delivers decoded payloads for a subscribed event`() = runTest {
        client.deliver(ZillitSocketEvents.PrivateChat.Message, """{"id":"m1","body":"hello"}""")

        val received = bus.on(ZillitSocketEvents.PrivateChat.Message, ChatMessage.serializer()).first()

        assertEquals(ChatMessage("m1", "hello"), received)
    }

    @Test
    fun `a subscriber only sees its own event`() = runTest {
        // The point of the bus: a feature cannot accidentally receive another
        // feature's traffic, which is what happens when one god object handles
        // everything.
        client.deliver(ZillitSocketEvents.GroupChat.Message, """{"id":"g1","body":"group"}""")
        client.deliver(ZillitSocketEvents.PrivateChat.Message, """{"id":"p1","body":"private"}""")

        val received = bus.on(ZillitSocketEvents.PrivateChat.Message, ChatMessage.serializer()).first()

        assertEquals("p1", received.id)
    }

    @Test
    fun `onAny delivers several events through one subscription`() = runTest {
        val events = listOf(
            ZillitSocketEvents.PrivateChat.Message,
            ZillitSocketEvents.PrivateChat.Edit,
        )
        client.deliver(ZillitSocketEvents.PrivateChat.Edit, """{"id":"m9","body":"edited","edited":true}""")

        val (event, message) = bus.onAny(events, ChatMessage.serializer()).first()

        assertEquals(ZillitSocketEvents.PrivateChat.Edit, event)
        assertTrue(message.edited)
    }

    @Test
    fun `an unknown field does not break decoding`() = runTest {
        client.deliver(
            ZillitSocketEvents.PrivateChat.Message,
            """{"id":"m1","body":"hi","server_added_field":{"nested":true}}""",
        )

        assertEquals("m1", bus.on(ZillitSocketEvents.PrivateChat.Message, ChatMessage.serializer()).first().id)
    }

    @Test
    fun `a malformed payload is dropped and the stream survives`() = runTest {
        // One bad message must not tear down a subscription the UI depends on
        // for the rest of the session.
        val messages = bus.on(ZillitSocketEvents.PrivateChat.Message, ChatMessage.serializer())
        val collector = collectAsync(messages, count = 1)

        client.deliver(ZillitSocketEvents.PrivateChat.Message, """{"id":42,"body":[]}""")
        client.deliver(ZillitSocketEvents.PrivateChat.Message, """{"id":"good","body":"ok"}""")

        assertEquals(listOf("good"), collector.await().map { it.id })
    }

    @Test
    fun `a payload-less event still signals`() = runTest {
        // Several server events carry no body — they are pure "something
        // changed, go refetch" triggers.
        client.deliverRaw(ZillitSocketEvents.ChatRoom.Updated, payload = null)

        assertEquals(Unit, bus.signals(ZillitSocketEvents.ChatRoom.Updated).first())
    }

    @Test
    fun `connection state is exposed for the UI`() = runTest {
        client.setState(SocketConnectionState.Reconnecting(attempt = 2, delayMillis = 4_000))

        val state = bus.connectionState.first()

        assertTrue(state.isTransient, "reconnecting must read as transient, not offline")
        assertEquals(2, (state as SocketConnectionState.Reconnecting).attempt)
    }

    @Test
    fun `emit fails rather than silently dropping while disconnected`() = runTest {
        // The Android emit is fire-and-forget, which is part of why messages go
        // missing across a reconnect. The caller must decide to queue or report.
        val result = client.emit(
            ZillitSocketEvents.PrivateChat.Message,
            ChatMessage("m1", "hello"),
            ChatMessage.serializer(),
        )

        assertTrue(result.errorOrNull() != null, "emit while disconnected must fail")
    }

    /**
     * Starts collecting before the emissions happen, so the test does not race
     * the producer.
     */
    private fun <T> CoroutineScope.collectAsync(flow: Flow<T>, count: Int) =
        async { flow.take(count).toList() }

    @Test
    fun `no two declared events share a wire name`() {
        // A duplicate would silently make two features share a stream.
        val names = ZillitSocketEvents.all.map { it.value }

        assertEquals(names.size, names.distinct().size, "duplicate socket event names declared")
    }
}
