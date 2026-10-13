package com.zillit.desktop.feature.castboard

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardAttachment
import com.zillit.desktop.feature.castboard.domain.BoardRecordDraft
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adding a record to a cast board, and reading the cast a costume files
 * under.
 *
 * Both boards post to one route under their own noun, and each sends the body
 * its own create dialog sends — casting's `createCastingModal(value,
 * 'create')` and wardrobe's `handleCreateWardrobe` payload. These pin the
 * difference, because it is the only thing the shared engine cannot read off
 * the descriptor.
 */
class CastingCreateWireTest {

    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<JsonObject?>()

    private val ok = """{"status":1,"message":"ok","data":[]}"""

    private val casts = """
        {"status":1,"message":"ok","data":[
          {"cast_id":"k1","character_name":"Inspector Rao","talent_name":"Ravi Menon"},
          {"cast_id":"k2","character_name":"Villager 2"},
          {"character_name":"Nobody"}]}
    """.trimIndent()

    private val still = BoardAttachment(
        media = "chat/door.jpg",
        contentType = "image",
        contentSubtype = "jpg",
        name = "door.jpg",
        bucket = "b",
        region = "r",
        fileSize = "2048",
    )

    private fun repository(board: BoardTool, body: String = ok): CastingRepositoryImpl {
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.host}${request.url.encodedPath}" +
                request.url.encodedQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            bodies += (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()
                ?.decodeToString()
                ?.takeIf { it.isNotBlank() }
                ?.let { Json.parseToJsonElement(it).jsonObject }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CastingRepositoryImpl(
            ApiClient(
                httpClient = HttpClientFactory.create({ CreateEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config,
            board,
        )
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    /** `createCasting`: the candidate is typed, and the names go up title-cased. */
    @Test
    fun `a casting record posts the character and whoever is up for it`() = runTest {
        val sent = repository(BoardTool.Casting).create(
            unitId = "u1",
            draft = BoardRecordDraft(
                uniqueId = "uid-1",
                status = CastingStatus.Finalized,
                characterName = "inspector rao",
                talentName = "ravi menon",
                gender = "male",
                characterNumber = "12",
                description = "the market scene",
                episodes = listOf("3", "4"),
                attachment = still,
            ),
        )

        assertTrue(sent is ZillitResult.Success)
        assertEquals("POST casting.test/api/v2/casting/u1", calls.single())
        val body = bodies.single()!!
        assertEquals("uid-1", body["unique_id"]?.jsonPrimitive?.content)
        assertEquals("finalized", body["status"]?.jsonPrimitive?.content)
        assertEquals("Inspector Rao", body["character_name"]?.jsonPrimitive?.content)
        assertEquals("Ravi Menon", body["talent_name"]?.jsonPrimitive?.content)
        assertEquals("12", body["character_number"]?.jsonPrimitive?.content)
        assertEquals(listOf("3", "4"), body["episode"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("chat/door.jpg", body["attachment"]?.jsonObject?.get("media")?.jsonPrimitive?.content)
        // A still with no thumbnail of its own stands in for one, as every
        // film tool's `dataObj` has it.
        assertEquals("chat/door.jpg", body["attachment"]?.jsonObject?.get("thumbnail")?.jsonPrimitive?.content)
        // The wardrobe halves of the draft are wardrobe's alone.
        assertFalse("cast_id" in body)
        assertFalse("scene_number" in body)
    }

    /** `createWardrobe`: a costume belongs to a character who already exists. */
    @Test
    fun `a wardrobe record posts the cast it belongs to and the scene`() = runTest {
        repository(BoardTool.Wardrobe).create(
            unitId = "w1",
            draft = BoardRecordDraft(
                uniqueId = "uid-2",
                status = CastingStatus.Shortlisted,
                castId = "k1",
                sceneNumber = "12A",
                characterName = "ignored when the cast is known",
                attachment = still,
            ),
        )

        assertEquals("POST wardrobe.test/api/v2/wardrobe/w1", calls.single())
        val body = bodies.single()!!
        assertEquals("k1", body["cast_id"]?.jsonPrimitive?.content)
        assertEquals("12A", body["scene_number"]?.jsonPrimitive?.content)
        assertEquals("shortlisted", body["status"]?.jsonPrimitive?.content)
        assertFalse("character_name" in body, "the character travels as the cast's id")
        assertFalse("cast" in body, "and `cast` is only for a character with no id")
    }

    /** Blank answers are absent rather than empty — the web sets each key under an `if`. */
    @Test
    fun `nothing answered is nothing sent`() = runTest {
        repository(BoardTool.Casting).create(
            unitId = "u1",
            draft = BoardRecordDraft(uniqueId = "uid-3", status = CastingStatus.Selected, attachment = still),
        )

        val body = bodies.single()!!
        assertEquals(setOf("unique_id", "status", "attachment"), body.keys)
        assertNull(body["episode"])
    }

    /** `get-casts`, read whole, as the picker searches it in the browser. */
    @Test
    fun `the cast list is read whole, and a row with no id is not a choice`() = runTest {
        val answer = repository(BoardTool.Wardrobe, body = casts).casts("w1")

        assertEquals("GET wardrobe.test/api/v2/wardrobe/get-casts/w1?page=0&limit=1000", calls.single())
        val list = (answer as ZillitResult.Success).data
        assertEquals(listOf("k1", "k2"), list.map { it.castId })
        assertEquals("Inspector Rao · Ravi Menon", list.first().label)
        assertEquals("Villager 2", list.last().label, "a character nobody is cast for yet reads as itself")
    }
}

private class CreateEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
