package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.home.cabinet.homeCabinetViewers
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.ui.HomeBoardContext
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the File Cabinet's notice-board viewers put on the wire: the cabinet
 * flag on every chat read and on the four unit lists, the web's `page` param,
 * the rows that expand and the ones that do not, and nothing but GETs.
 */
class CabinetViewersWireTest {

    private val calls = mutableListOf<String>()

    private val plain = object : NoticeDecryptor {
        override fun decryptFromHex(cipherHex: String): ZillitResult<String> = ZillitResult.Success(cipherHex)
        override fun encryptToHex(plaintext: String): ZillitResult<String> = ZillitResult.Success(plaintext)
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private fun api(answer: (String) -> String = { """{"status":1,"message":"ok","data":[]}""" }): ApiClient {
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.host}${request.url.encodedPath}" +
                request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
            respond(
                answer(request.url.encodedPath),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return ApiClient(
            httpClient = HttpClientFactory.create({ CabinetEngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
    }

    private class FakeTools(private val permissions: ProjectPermissions) : ToolsRepository {
        override suspend fun loadPermissions() = ZillitResult.Success(permissions)
        override suspend fun loadGroups() = ZillitResult.Success(emptyList<ToolGroup>())
    }

    private fun viewers(
        apiClient: ApiClient,
        permissions: ProjectPermissions = ProjectPermissions(emptyList()),
    ) = homeCabinetViewers(
        apiClient = apiClient,
        config = config,
        decrypt = plain,
        homeFeed = HomeFeedRepositoryImpl(apiClient, config, plain, isAdmin = { false }),
        tools = FakeTools(permissions),
        board = HomeBoardContext(),
        nowMillis = { 1_000L },
        isAdmin = { false },
        currentUserId = { "me" },
    ).associateBy { it.identifier }

    // -- the chat read ------------------------------------------------------

    private suspend fun cabinetRepository(
        board: String,
        service: ZillitService,
        chatSegment: String = "chat",
    ): HomeFeedRepositoryImpl = HomeFeedRepositoryImpl(
        api(), config, plain, isAdmin = { false }, board = board, service = service,
        chatSegment = chatSegment, fileCabinet = true,
    ).also {
        it.loadNotices("u1", beforeMillis = 5)
        it.loadOlderNotices("u1", beforeMillis = 4)
    }

    @Test
    fun `every board's chat read carries the cabinet flag, page 0 where the web sends it`() = runTest {
        cabinetRepository("home", ZillitService.Units)
        cabinetRepository("info", ZillitService.Units)
        cabinetRepository("confidentialinfo", ZillitService.Units)
        cabinetRepository("account", ZillitService.Units)
        cabinetRepository("catering", ZillitService.Units)
        cabinetRepository("reports", ZillitService.ScriptNotes)
        cabinetRepository("production-report", ZillitService.ProductionReport, chatSegment = "")
        // Script Notes' own fetch sends the flag and nothing else.
        cabinetRepository("script-notes", ZillitService.ScriptNotes)

        val query = "?page=0&isFileCabinet=true"
        assertEquals(
            listOf(
                "GET units.test/api/v2/home/chat/u1/5/previous$query",
                "GET units.test/api/v2/home/chat/u1/4/previous$query",
                "GET units.test/api/v2/info/chat/u1/5/previous$query",
                "GET units.test/api/v2/info/chat/u1/4/previous$query",
                "GET units.test/api/v2/confidentialinfo/chat/u1/5/previous$query",
                "GET units.test/api/v2/confidentialinfo/chat/u1/4/previous$query",
                "GET units.test/api/v2/account/chat/u1/5/previous$query",
                "GET units.test/api/v2/account/chat/u1/4/previous$query",
                "GET units.test/api/v2/catering/chat/u1/5/previous$query",
                "GET units.test/api/v2/catering/chat/u1/4/previous$query",
                "GET scriptnotes.test/api/v2/reports/chat/u1/5/previous$query",
                "GET scriptnotes.test/api/v2/reports/chat/u1/4/previous$query",
                "GET productionreport.test/api/v2/production-report/u1/5/previous$query",
                "GET productionreport.test/api/v2/production-report/u1/4/previous$query",
                "GET scriptnotes.test/api/v2/script-notes/chat/u1/5/previous?isFileCabinet=true",
                "GET scriptnotes.test/api/v2/script-notes/chat/u1/4/previous?isFileCabinet=true",
            ),
            calls,
        )
    }

    @Test
    fun `the live board's reads stay exactly as they were`() = runTest {
        val live = HomeFeedRepositoryImpl(api(), config, plain, isAdmin = { false }, board = "info")
        live.loadNotices("u1", beforeMillis = 5)
        live.loadOlderNotices("u1", beforeMillis = 4)

        assertEquals(
            listOf(
                "GET units.test/api/v2/info/chat/u1/5/previous",
                "GET units.test/api/v2/info/chat/u1/4/previous",
            ),
            calls,
        )
    }

    // -- the rows -----------------------------------------------------------

    @Test
    fun `eight rows, four expanding into their own unit lists and Home into its tabs`() {
        val rows = viewers(api())

        assertEquals(
            setOf(
                "home", "info", "confidential_info", "accounting", "catering",
                "script_notes", "reports", "production_report",
            ),
            rows.keys,
        )
        listOf("home", "accounting", "catering", "script_notes", "reports").forEach {
            assertTrue(rows.getValue(it).expands, "$it should expand")
        }
        listOf("info", "confidential_info", "production_report").forEach {
            assertFalse(rows.getValue(it).expands, "$it opens straight onto its tool's unit")
        }
    }

    @Test
    fun `the four unit lists ask for the cabinet's units`() = runTest {
        val rows = viewers(api())
        listOf("accounting", "catering", "script_notes", "reports").forEach { rows.getValue(it).entries() }

        assertEquals(
            listOf(
                "GET units.test/api/v2/account/unit?isFileCabinet=true",
                "GET units.test/api/v2/catering/unit?isFileCabinet=true",
                "GET scriptnotes.test/api/v2/script-notes/unit?isFileCabinet=true",
                "GET scriptnotes.test/api/v2/reports/unit/?isFileCabinet=true",
            ),
            calls,
        )
    }

    @Test
    fun `Catering lists only its units that stand`() = runTest {
        val rows = viewers(
            api {
                """{"status":1,"data":[
                    {"_id":"b","unit_name":"breakfast_label","deleted":0},
                    {"_id":"x","unit_name":"old_label","deleted":1700000000000},
                    {"_id":"l","unit_name":"lunch_label","deleted":0}]}"""
            },
        )

        assertEquals(listOf("b", "l"), rows.getValue("catering").entries().map { it.key })
    }

    @Test
    fun `Home lists the units this person can view, without the calendar`() = runTest {
        val rows = viewers(
            api {
                """{"status":1,"data":[
                    {"unit_id":"g","unit_name":"general_label","identifier":"home_unit_general","view_access":true},
                    {"unit_id":"c","unit_name":"calendar_label","identifier":"home_unit_calendar","view_access":true},
                    {"unit_id":"h","unit_name":"hidden_label","identifier":"home_unit_x","view_access":false},
                    {"unit_id":"s","unit_name":"call_sheet_label","identifier":"home_unit_call_sheet",
                     "view_access":true}]}"""
            },
        )

        assertEquals(listOf("g", "s"), rows.getValue("home").entries().map { it.key })
        assertEquals(listOf("GET units.test/api/v2/home/unit/user"), calls)
    }

    @Test
    fun `a failed unit list is an empty submenu, not an exception`() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
        val failing = ApiClient(
            httpClient = HttpClientFactory.create({ CabinetEngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )

        val rows = viewers(failing)

        listOf("home", "accounting", "catering", "script_notes", "reports").forEach {
            assertEquals(emptyList(), rows.getValue(it).entries(), it)
        }
    }

    @Test
    fun `viewers are read-only on the wire - listing them issues GETs and nothing else`() = runTest {
        val rows = viewers(api())
        rows.values.forEach { it.entries() }

        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.startsWith("GET ") }, calls.toString())
        assertNotNull(rows["production_report"])
        assertNull(rows["drive"])
    }
}

private class CabinetEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
