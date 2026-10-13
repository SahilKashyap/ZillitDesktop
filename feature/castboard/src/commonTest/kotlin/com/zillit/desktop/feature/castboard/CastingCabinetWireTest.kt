package com.zillit.desktop.feature.castboard

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.castboard.cabinet.castingCabinetViewers
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the File Cabinet's casting and wardrobe rows ask for.
 *
 * The web adds no query parameter in cabinet mode — it stops dropping deleted
 * rows (`CommonCasting.jsx:642`, `CommonWardrobe.jsx:583`). These pin both
 * halves: the request is the live board's, and the answer keeps the tombstones.
 */
class CastingCabinetWireTest {

    private val calls = mutableListOf<String>()

    private val body = """
        {"status":1,"message":"ok","data":[
          {"_id":"live","character_name":"Kept","deleted":0},
          {"_id":"gone","character_name":"Archived","deleted":1}]}
    """.trimIndent()

    private fun apiClient(): ApiClient {
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.host}${request.url.encodedPath}?${request.url.encodedQuery}"
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return ApiClient(
            httpClient = HttpClientFactory.create({ EngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private fun repository(board: BoardTool, fileCabinet: Boolean) =
        CastingRepositoryImpl(apiClient(), config, board, fileCabinet = fileCabinet)

    @Test
    fun `the cabinet reads the same route as the live board`() = runTest {
        repository(BoardTool.Casting, fileCabinet = true).entries("u1", CastingStatus.Selected)
        repository(BoardTool.Casting, fileCabinet = false).entries("u1", CastingStatus.Selected)

        assertEquals(2, calls.size)
        assertEquals(calls[0], calls[1])
        assertEquals("GET casting.test/api/v2/casting/casting-info/u1?status=selected", calls[0])
    }

    @Test
    fun `wardrobe's cabinet reads wardrobe's route`() = runTest {
        repository(BoardTool.Wardrobe, fileCabinet = true).entries("w1", CastingStatus.Shortlisted)

        assertEquals("GET wardrobe.test/api/v2/wardrobe/wardrobe-info/w1?status=shortlisted", calls.single())
    }

    @Test
    fun `the cabinet keeps deleted rows and the live board drops them`() = runTest {
        val cabinet = repository(BoardTool.Casting, fileCabinet = true).entries("u1", CastingStatus.Selected)
        val live = repository(BoardTool.Casting, fileCabinet = false).entries("u1", CastingStatus.Selected)

        assertEquals(listOf("Kept", "Archived"), (cabinet as ZillitResult.Success).data.map { it.characterName })
        assertEquals(listOf("Kept"), (live as ZillitResult.Success).data.map { it.characterName })
    }

    /** The four rows of the cabinet, by the web's identifiers. */
    @Test
    fun `one viewer per row`() {
        val viewers = castingCabinetViewers(
            apiClient = apiClient(),
            config = config,
            permissions = { null },
            encrypt = { it },
            decrypt = { it },
            myUserId = { null },
        )

        assertEquals(
            listOf("casting_main", "casting_background", "wardrobe_main", "wardrobe_background"),
            viewers.map { it.identifier },
        )
    }
}

private class EngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
