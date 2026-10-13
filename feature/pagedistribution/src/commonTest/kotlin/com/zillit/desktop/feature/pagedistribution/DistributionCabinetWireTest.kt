package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.pagedistribution.data.DistributionRepositoryImpl
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The File Cabinet's reads carry exactly the `isFileCabinet=true` the web's
 * api files add (`api/scriptdistribution/api.js`,
 * `api/scheduleDistribution/scheduleApiCalls.js`, `api/dodApi/dod.js`) —
 * on the lists, the folder headers, a folder's documents and a page read.
 *
 * The one read the web leaves unflagged is a single script's
 * (`getFullScriptList(param, isUserHistory)` in `RenderScript`), and the
 * port keeps that.
 */
class DistributionCabinetWireTest {

    private val sent = mutableListOf<HttpRequestData>()
    private val repo = DistributionRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClientFactory.create({ MockFactory(engine()) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        ),
        newUniqueId = { "uid" },
        nowMillis = { NOW },
    )

    private fun engine() = MockEngine { request ->
        sent += request
        respond(
            """{"status":1,"data":[]}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun tab(tool: DistributionTool, key: String): DistributionTab = tool.tabs.first { it.key == key }

    private fun HttpRequestData.flag(): String? = url.parameters["isFileCabinet"]

    @Test
    fun `script lists, scene folders and a folder's pages are flagged`() = runTest {
        val script = DistributionTool.ScriptDistribution
        repo.documents(tab(script, "full_script"), ListMode.Cabinet)
        repo.folders(tab(script, "page"), ListMode.Cabinet)
        repo.folderDocuments(tab(script, "page"), "12", NOW, next = false, mode = ListMode.Cabinet)

        assertEquals(
            listOf("/api/v2/script-distribution/script", "/api/v2/script-distribution/page-folders",
                "/api/v2/script-distribution/page-folders"),
            sent.map { it.url.encodedPath },
        )
        assertEquals(listOf("true", "true", "true"), sent.map { it.flag() })
        with(sent.last().url.parameters) {
            assertEquals("12", get("sceneNumber"))
            assertEquals("previous", get("nextPrevious"))
            assertNull(get("deleted"), "the cabinet is not the history switch")
        }
    }

    @Test
    fun `a page read is flagged but a single script read is not`() = runTest {
        val script = DistributionTool.ScriptDistribution
        repo.document(tab(script, "page"), "p1", ReadAction.View, ListMode.Cabinet)
        repo.document(tab(script, "full_script"), "s1", ReadAction.Download, ListMode.Cabinet)

        assertEquals("true", sent[0].flag())
        assertEquals("p1", sent[0].url.parameters["pageId"])
        assertNull(sent[1].flag(), "RenderScript drops the cabinet argument on a single script read")
        assertEquals("s1", sent[1].url.parameters["scriptId"])
        assertEquals("download", sent[1].url.parameters["action"])
    }

    @Test
    fun `schedule full, one line and pages are all flagged on every read`() = runTest {
        val schedule = DistributionTool.ScheduleDistribution
        repo.documents(tab(schedule, "full_script"), ListMode.Cabinet)
        repo.documents(tab(schedule, "oneline"), ListMode.Cabinet)
        repo.folders(tab(schedule, "page"), ListMode.Cabinet)
        repo.document(tab(schedule, "full_script"), "a", ReadAction.View, ListMode.Cabinet)
        repo.document(tab(schedule, "oneline"), "b", ReadAction.View, ListMode.Cabinet)
        repo.document(tab(schedule, "page"), "c", ReadAction.View, ListMode.Cabinet)

        assertEquals(
            listOf("schedule", "oneline", "page-folders", "schedule", "oneline", "page")
                .map { "/api/v2/schedule-distribution/$it" },
            sent.map { it.url.encodedPath },
        )
        assertEquals(List(sent.size) { "true" }, sent.map { it.flag() })
        assertEquals("a", sent[3].url.parameters["scheduleId"])
    }

    @Test
    fun `dod folders, a folder's documents and a document read are flagged`() = runTest {
        val dod = tab(DistributionTool.ScheduleDod, "dod")
        repo.folders(dod, ListMode.Cabinet)
        repo.folderDocuments(dod, "Week One", NOW, next = true, mode = ListMode.Cabinet)
        repo.document(dod, "d1", ReadAction.None, ListMode.Cabinet)

        assertEquals(
            listOf("dod-folders", "dod-folders", "dod").map { "/api/v2/schedule-distribution/$it" },
            sent.map { it.url.encodedPath },
        )
        assertEquals(listOf("true", "true", "true"), sent.map { it.flag() })
        assertEquals("Week One", sent[1].url.parameters["name"])
        assertEquals("next", sent[1].url.parameters["nextPrevious"])
    }

    @Test
    fun `live and history reads never carry the flag`() = runTest {
        val script = DistributionTool.ScriptDistribution
        repo.documents(tab(script, "full_script"), ListMode.Live)
        repo.folders(tab(script, "page"), ListMode.History)

        assertEquals(listOf<String?>(null, null), sent.map { it.flag() })
        assertEquals(NOW.toString(), sent[1].url.parameters["deleted"])
    }

    private class MockFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val NOW = 1_786_950_000_000L
    }
}
