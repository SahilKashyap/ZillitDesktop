package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which production a call is placed on, and as whom.
 *
 * The failure this guards against is silent and expensive: user ids are
 * project-scoped, and the header's project and user are set **together** from
 * the active production. Overriding only the project ships a valid project
 * paired with a user id that does not exist in it — the server refuses the
 * write, or worse, answers for the wrong person. So the pair is asserted at
 * the header boundary rather than trusted.
 */
class CallScopeWireTest {

    private val asked = mutableListOf<Pair<String?, String?>>()

    private fun api(): CallApi {
        val engine = MockEngine {
            respond(
                """{"status":1,"data":{"call_uuid":"u"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return CallApi(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ScopeMockEngineFactory(engine) }),
                headerProvider = { _, _, projectId, userId ->
                    asked += projectId to userId
                    emptyMap()
                },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = mapOf(ZillitService.Calling to "https://calls.test"),
                realtime = emptyMap(),
            ),
        )
    }

    @Test
    fun `a call on another production names that production and the caller's id there`() = runTest {
        api().createCall(
            chatRoomId = "",
            receiverDeviceId = "device-1",
            mode = CallMode.Private,
            type = CallType.Audio,
            selfUserId = "me-here",
            selfDeviceId = "d",
            projectId = "other-project",
            callerUserId = "me-there",
        )

        assertEquals(
            "other-project" to "me-there",
            asked.single(),
            "the project and the id on it must travel together",
        )
    }

    @Test
    fun `a call in the open production names neither, so the ambient pair stands`() = runTest {
        api().createCall(
            chatRoomId = "room",
            receiverDeviceId = "",
            mode = CallMode.Group,
            type = CallType.Video,
            selfUserId = "me",
            selfDeviceId = "d",
            projectId = null,
        )

        val (project, user) = asked.single()
        assertNull(project, "the open project is the ambient one, not a named override")
        // Blank is never sent: an empty string would be a user id the server
        // cannot resolve, which is worse than saying nothing.
        assertNull(user, "a blank caller id must not reach the headers")
    }

    @Test
    fun `the second line carries the same pair`() = runTest {
        api().createMediasoupCall(
            chatRoomId = "room",
            receiverUserIds = listOf("them"),
            mode = CallMode.Group,
            type = CallType.Audio,
            selfUserId = "me-here",
            selfDeviceId = "d",
            projectId = "other-project",
            callerUserId = "me-there",
        )

        assertEquals("other-project" to "me-there", asked.single())
    }

    @Test
    fun `history reads the production it is asked for`() = runTest {
        api().callLogs(
            cursorMillis = 1_000L,
            selfUserId = "me-there",
            projectId = "other-project",
            callerUserId = "me-there",
        )

        assertEquals("other-project" to "me-there", asked.single())
    }
}

/** The mock engine as a factory, since `HttpClientFactory` takes one. */
private class ScopeMockEngineFactory(private val engine: MockEngine) :
    io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit) = engine
}
