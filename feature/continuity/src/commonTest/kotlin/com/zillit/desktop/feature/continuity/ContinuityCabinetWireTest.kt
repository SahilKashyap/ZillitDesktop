package com.zillit.desktop.feature.continuity

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.continuity.data.ContinuityRepositoryImpl
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the File Cabinet's Continuity row asks the server for.
 *
 * The web mounts `<Continuity isContintuityFileCabinet />` and the flag stops
 * at the components: `continuityApi.js` never reads it, so the cabinet view is
 * the tool's own plain reads. This pins that — no `isFileCabinet`, no
 * `archived` — so a well-meaning "cabinet parameter" does not creep into the
 * shared repository and quietly empty both the cabinet and the live boards.
 */
class ContinuityCabinetWireTest {

    private val seen = mutableListOf<Url>()

    private val repo = ContinuityRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ CabinetMockEngineFactory(engine()) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        ),
    )

    private fun engine() = MockEngine { request: HttpRequestData ->
        seen += request.url
        respond(
            """{"status":1,"data":["12","7"]}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun `the folder lists are the tool's own reads with no cabinet parameter`() = runTest {
        assertTrue(repo.folders(ContinuityTab.MyDepartment) is ZillitResult.Success)
        assertTrue(repo.folders(ContinuityTab.AllDepartments) is ZillitResult.Success)

        val (mine, all) = seen
        assertEquals("/api/v2/continuity/scenes", mine.encodedPath)
        assertTrue(mine.parameters.isEmpty())
        assertEquals("true", all.parameters["showall"])
        seen.forEach { assertNoCabinetParameter(it) }
    }

    @Test
    fun `the cards of a folder page by timestamp and carry no cabinet parameter`() = runTest {
        repo.scenes(ContinuityTab.MyDepartment, "12", departmentId = null, beforeMs = 5_000L)
        repo.scenes(ContinuityTab.AllDepartments, "12", departmentId = "d2", beforeMs = 5_000L)

        val (mine, all) = seen
        assertEquals("12", mine.parameters["sceneNumber"])
        assertEquals("5000", mine.parameters["timestamp"])
        assertEquals("previous", mine.parameters["nextPrevious"])
        assertEquals("d2", all.parameters["departmentId"])
        seen.forEach { assertNoCabinetParameter(it) }
    }

    private fun assertNoCabinetParameter(url: Url) {
        assertFalse("isFileCabinet" in url.parameters, "unexpected isFileCabinet in $url")
        assertFalse("archived" in url.parameters, "unexpected archived in $url")
    }
}

private class CabinetMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
