package com.zillit.desktop.feature.location

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.location.data.LocationRepositoryImpl
import com.zillit.desktop.feature.location.domain.LocationStatus
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
import kotlin.test.assertTrue

/**
 * What the File Cabinet's location viewer puts on the wire.
 *
 * The web's `getImagesLocations` adds `isFileCabinet` to the media list and
 * nothing else to the folder list; the rest of the cabinet-ness is which rows
 * are kept (`LocationPage.jsx:394`, `:1535`).
 */
class LocationCabinetWireTest {

    private val rows = """
        {"status":1,"data":[
          {"_id":"m1","file_name":"Hall","scene_number":"12","created":10,"deleted":0},
          {"_id":"m2","file_name":"Hall","scene_number":"12","created":9,"deleted":1}
        ]}
    """.trimIndent()

    private val folders = """
        {"status":1,"data":[
          {"location":"Hall","sceneNumbers":"12","lastUpdate":5,"delete":0},
          {"location":"Old Pier","sceneNumbers":"3","lastUpdate":4,"delete":1}
        ]}
    """.trimIndent()

    @Test
    fun `the cabinet's media list carries isFileCabinet after the web's own params`() = runTest {
        val (repo, sent) = repository(rows)

        repo.media(LocationStatus.Selected, "Hall", "12", "2", beforeMs = 99L, next = false, fileCabinet = true)

        val query = sent.single().url.parameters
        assertEquals("true", query["isFileCabinet"])
        assertEquals("selected", query["status"])
        assertEquals("Hall", query["file_name"])
        assertEquals("12", query["scene_number"])
        assertEquals("2", query["episode"])
        assertEquals("99", query["timestamp"])
        assertEquals("previous", query["nextPrevious"])
        assertEquals("50", query["limit"])
        assertTrue(sent.single().url.encodedPath.endsWith("/location"))
    }

    @Test
    fun `the tool's own media list sends no cabinet flag`() = runTest {
        val (repo, sent) = repository(rows)

        repo.media(LocationStatus.Selected, "Hall", "12", null, beforeMs = 99L, next = false)

        assertNull(sent.single().url.parameters["isFileCabinet"])
    }

    @Test
    fun `the cabinet's first page keeps deleted rows and a next page drops them`() = runTest {
        val (repo, _) = repository(rows)

        val first = repo.media(LocationStatus.Published, "Hall", "12", null, 99L, next = false, fileCabinet = true)
        val later = repo.media(LocationStatus.Published, "Hall", "12", null, 99L, next = true, fileCabinet = true)
        val live = repo.media(LocationStatus.Published, "Hall", "12", null, 99L, next = false)

        assertEquals(listOf("m1", "m2"), (first as ZillitResult.Success).data.map { it.id })
        assertEquals(listOf("m1"), (later as ZillitResult.Success).data.map { it.id })
        assertEquals(listOf("m1"), (live as ZillitResult.Success).data.map { it.id })
    }

    @Test
    fun `the folder list adds no param but the cabinet keeps its deleted folders`() = runTest {
        val (repo, sent) = repository(folders)

        val cabinet = repo.info(LocationStatus.Shortlisted, fileCabinet = true)
        val live = repo.info(LocationStatus.Shortlisted)

        assertEquals(setOf("status"), sent.first().url.parameters.names())
        assertEquals(listOf("Hall", "Old Pier"), (cabinet as ZillitResult.Success).data.map { it.location })
        assertEquals(listOf("Hall"), (live as ZillitResult.Success).data.map { it.location })
    }

    private fun repository(answer: String): Pair<LocationRepositoryImpl, MutableList<HttpRequestData>> {
        val sent = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request: HttpRequestData ->
            sent += request
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = LocationRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CabinetMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            newUniqueId = { "u" },
        )
        return repo to sent
    }
}

private class CabinetMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
