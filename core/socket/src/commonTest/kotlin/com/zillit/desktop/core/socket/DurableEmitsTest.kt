package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A badge read made while the socket is down is held and sent when it comes
 * back — the read the desktop applied at once must reach the server, or the
 * badge returns everywhere else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DurableEmitsTest {

    @Serializable
    private data class Read(
        @SerialName("project_id") val projectId: String,
        val segment: String,
        val timestamp: Long,
    )

    private class MemoryStore : HeldEmitStore {
        var value = ""
        override suspend fun read() = value
        override suspend fun write(value: String) {
            this.value = value
        }
    }

    private val read = SocketEventName("notification:read")
    private val other = SocketEventName("user:join")
    private val config = SocketConfig(url = "wss://test", authHeaders = { emptyMap() })
    private var now = 1_000L

    private fun durable(client: SocketClient, store: HeldEmitStore, scope: CoroutineScope) =
        DurableEmits(client, durable = setOf(read), store = store, scope = scope, nowMillis = { now })

    @Test
    fun `a read made while disconnected is held, then sent when the socket is back`() = runTest {
        val socket = FakeSocketClient()
        val store = MemoryStore()
        val client = durable(socket, store, backgroundScope)

        val answer = client.emit(read, Read("p1", "unit-1", timestamp = 500), Read.serializer())

        assertIs<ZillitResult.Success<Unit>>(answer, "a held read is not a failure the caller must handle")
        assertTrue(socket.emitted.isEmpty())
        assertTrue(store.value.isNotBlank(), "held where a restart will find it")

        socket.connect(config)
        runCurrent()

        assertEquals(1, socket.emitted.size)
        assertEquals(read, socket.emitted.single().first)
        assertTrue(
            socket.emitted.single().second.orEmpty().contains("\"timestamp\":500"),
            "sent with the time it was read, not the time it was sent",
        )
        assertEquals("", store.value, "nothing left once it went")
    }

    @Test
    fun `other events are not held`() = runTest {
        val socket = FakeSocketClient()
        val store = MemoryStore()
        val client = durable(socket, store, backgroundScope)

        val answer = client.emit(other, Read("p1", "x", 1), Read.serializer())

        assertIs<ZillitResult.Failure>(answer, "a join is the caller's to retry, not ours")
        assertEquals("", store.value)
    }

    @Test
    fun `the same read made twice offline is sent once, with the first time`() = runTest {
        val socket = FakeSocketClient()
        val client = durable(socket, MemoryStore(), backgroundScope)

        client.emit(read, Read("p1", "unit-1", timestamp = 500), Read.serializer())
        client.emit(read, Read("p1", "unit-1", timestamp = 900), Read.serializer())
        client.emit(read, Read("p1", "unit-2", timestamp = 950), Read.serializer())
        socket.connect(config)
        runCurrent()

        assertEquals(2, socket.emitted.size)
        assertTrue(socket.emitted[0].second.orEmpty().contains("\"timestamp\":500"))
        assertTrue(socket.emitted[1].second.orEmpty().contains("unit-2"), "oldest first")
    }

    @Test
    fun `held reads survive a restart`() = runTest {
        val store = MemoryStore()
        durable(FakeSocketClient(), store, backgroundScope)
            .emit(read, Read("p1", "unit-1", timestamp = 500), Read.serializer())

        val nextLaunch = FakeSocketClient()
        durable(nextLaunch, store, backgroundScope)
        nextLaunch.connect(config)
        runCurrent()

        assertEquals(1, nextLaunch.emitted.size)
        assertEquals("", store.value)
    }

    @Test
    fun `a read held past its month is let go`() = runTest {
        val socket = FakeSocketClient()
        val store = MemoryStore()
        val client = durable(socket, store, backgroundScope)
        client.emit(read, Read("p1", "unit-1", timestamp = 500), Read.serializer())

        now += 31L * 24 * 60 * 60_000
        socket.connect(config)
        runCurrent()

        assertTrue(socket.emitted.isEmpty())
        assertEquals("", store.value)
    }

    @Test
    fun `a read sent while connected goes straight out and holds nothing`() = runTest {
        val socket = FakeSocketClient()
        val store = MemoryStore()
        socket.connect(config)
        val client = durable(socket, store, backgroundScope)
        runCurrent()

        client.emit(read, Read("p1", "unit-1", timestamp = 500), Read.serializer())

        assertEquals(1, socket.emitted.size)
        assertEquals("", store.value)
    }
}
