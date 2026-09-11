package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.auth.data.PROJECT_LIFECYCLE_EVENTS
import com.zillit.desktop.feature.auth.data.ProjectLifecycle
import com.zillit.desktop.feature.auth.data.projectIdOf
import com.zillit.desktop.feature.auth.data.projectLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The open production, removed or renamed by somebody else.
 *
 * The id gate is the substance: acting on a frame that names a different
 * production would throw a working user out of theirs, which is what
 * Android's own in-production handler does (`BottomNavigationActivity.kt`).
 */
class ProjectLifecycleSyncTest {

    @Test
    fun `the four names are the ones both phones carry`() {
        assertEquals(
            listOf(
                "project:deleted",
                "project:marked:for:deletion",
                "project:unmarked:for:deletion",
                "project:update",
            ),
            PROJECT_LIFECYCLE_EVENTS.map { it.value },
        )
    }

    @Test
    fun `an id is read from the top level or from detail`() {
        assertEquals("p1", projectIdOf(Json.parseToJsonElement("""{"project_id":"p1"}""")))
        assertEquals("p2", projectIdOf(Json.parseToJsonElement("""{"detail":{"project_id":"p2"}}""")))
        assertEquals("p3", projectIdOf(Json.parseToJsonElement("""{"projectId":"p3"}""")))
        assertNull(projectIdOf(Json.parseToJsonElement("""{"project_id":""}""")))
        assertNull(projectIdOf(Json.parseToJsonElement("""{"name":"Untitled"}""")))
    }

    @Test
    fun `deletion sends the shell back and the rest re-reads the context`() = runTest {
        val bus = busOf(
            "project:deleted" to """{"project_id":"p1"}""",
            "project:update" to """{"project_id":"p1"}""",
            "project:marked:for:deletion" to """{"detail":{"project_id":"p1"}}""",
            "project:unmarked:for:deletion" to """{"project_id":"p1"}""",
        )

        assertEquals(
            listOf(
                ProjectLifecycle.Deleted,
                ProjectLifecycle.Changed,
                ProjectLifecycle.Changed,
                ProjectLifecycle.Changed,
            ),
            projectLifecycle(bus) { "p1" }.toList(),
        )
    }

    @Test
    fun `another production's deletion is ignored`() = runTest {
        val bus = busOf(
            "project:deleted" to """{"project_id":"p9"}""",
            "project:deleted" to """{"name":"no id at all"}""",
            "project:update" to """{"project_id":"p9"}""",
        )

        assertTrue(
            projectLifecycle(bus) { "p1" }.toList().isEmpty(),
            "a frame about somebody else's production is not about ours",
        )
    }

    /** No production open means nothing to lose, and nothing to act on. */
    @Test
    fun `nothing happens while the picker is showing`() = runTest {
        val bus = busOf("project:deleted" to """{"project_id":"p1"}""")

        assertTrue(projectLifecycle(bus) { null }.toList().isEmpty())
    }

    /** Names outside the four never reach the handler. */
    @Test
    fun `an unrelated frame is not a lifecycle change`() = runTest {
        val bus = busOf("project:tools:update" to """{"project_id":"p1"}""")

        assertTrue(projectLifecycle(bus) { "p1" }.toList().isEmpty())
    }

    private fun busOf(vararg frames: Pair<String, String>) = SocketEventBus(
        FinitePlayback(
            frames.map { (event, body) ->
                SocketMessage(SocketEventName(event), Json.parseToJsonElement(body))
            },
        ),
    )
}

/** Replays a fixed script and then ends, so a collector finishes on its own. */
private class FinitePlayback(private val frames: List<SocketMessage>) : SocketClient {
    override val connectionState =
        MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected(socketId = "test"))
    override val messages: Flow<SocketMessage> = frames.asFlow().map { it }

    override suspend fun connect(config: SocketConfig) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) =
        ZillitResult.Success(Unit)

    override suspend fun emit(event: SocketEventName) = ZillitResult.Success(Unit)
    override suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<JsonElement> = ZillitResult.Success(Json.parseToJsonElement("{}"))
}
