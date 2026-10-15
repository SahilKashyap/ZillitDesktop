@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.crewdood

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.crewdood.data.CrewDoodRepositoryImpl
import com.zillit.desktop.feature.crewdood.domain.CrewDoodExport
import com.zillit.desktop.feature.crewdood.domain.CrewDoodExported
import com.zillit.desktop.feature.crewdood.domain.DayCode
import com.zillit.desktop.feature.crewdood.ui.CrewDoodEvent
import com.zillit.desktop.feature.crewdood.ui.CrewDoodViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The chart driven through its view model over a mock server: what it loads,
 * what fails it, what the filters narrow, and what a calendar save and an
 * export do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrewDoodFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private data class Call(val method: HttpMethod, val path: String, val body: JsonElement?)

    /** Every request, appended atomically — the client answers off the test thread. */
    private val calls = MutableStateFlow<List<Call>>(emptyList())

    private fun utc(stamp: String) = Instant.parse(stamp).toEpochMilliseconds()

    private val crewList = """[{"unit_name":"main_unit","departments":[
        {"department_name":"Camera","users":[
          {"user_id":"u1","full_name":"Ann","designation_name":"DOP"},
          {"user_id":"u2","full_name":"Ben","designation_name":"1st AC"}]},
        {"department_name":"Sound","users":[{"user_id":"u3","full_name":"Cat","designation_name":"Mixer"}]}]}]"""

    private val deals = """[
        {"_id":"d1","status":"active","user_id":"u1","deal":{"start_date":${utc("2026-04-06T00:00:00Z")},"end_date":${utc("2026-04-10T23:59:59Z")}}},
        {"_id":"d2","status":"issued","user_id":"u2","deal":{"start_date":${utc("2026-04-06T00:00:00Z")},"end_date":${utc("2026-04-10T23:59:59Z")}}}]"""

    private var crewListFails = false
    private var dealsFail = false
    private var calendarBody: String? = null
    private var calendarSaveStatus = 1
    private var calendarRead = """{"weekly_off":[0,6],"holidays":[]}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) }
        calls.update { it + Call(request.method, path, body) }
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val answer = when {
            path.endsWith("/crewlist/list") ->
                if (crewListFails) """{"status":0,"message":"crew_list_unavailable"}""" else """{"status":1,"data":$crewList}"""
            path.endsWith("/deal-memo/deals") ->
                if (dealsFail) """{"status":0,"message":"deals_unavailable"}""" else """{"status":1,"data":$deals}"""
            path.endsWith("/production/days") ->
                """{"status":1,"data":[{"identifier":"production_dates","production_dates":[]}]}"""
            path.endsWith("/departments") -> """{"status":1,"data":[]}"""
            path.endsWith("/crew-dood/calendar") && request.method == HttpMethod.Put -> {
                calendarBody = (request.body as? TextContent)?.text
                if (calendarSaveStatus == 1) {
                    """{"status":1,"message":"calendar_saved","data":${body?.jsonObject}}"""
                } else {
                    """{"status":0,"message":"no_permission_to_edit_calendar"}"""
                }
            }
            path.endsWith("/crew-dood/calendar") -> """{"status":1,"data":$calendarRead}"""
            else -> """{"status":1,"data":null}"""
        }
        respond(answer, HttpStatusCode.OK, json)
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private val apiClient = ApiClient(
        httpClient = HttpClientFactory.create({ DoodMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private var exported = 0
    private var exportSkipped = 0
    private var exportFails = false

    private fun viewModel() = CrewDoodViewModel(
        repository = CrewDoodRepositoryImpl(apiClient, config, labelOf = { it }),
        export = CrewDoodExport {
            exported += 1
            if (exportFails) {
                ZillitResult.Failure(ZillitError.Validation("export_not_allowed"))
            } else {
                ZillitResult.Success(CrewDoodExported(skipped = exportSkipped))
            }
        },
        today = { LocalDate.parse("2026-04-06") },
    )

    /** Runs every timer and waits on the mock server until [condition] holds. */
    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.loaded(): CrewDoodViewModel {
        val model = viewModel()
        model.start()
        settle { !model.state.value.loading }
        return model
    }

    private fun paths() = calls.value.map { it.path }

    @Test
    fun `it loads the four reads and draws the crew list's order`() = runTest(dispatcher) {
        val model = loaded()
        val state = model.state.value
        assertNull(state.error)
        assertTrue(paths().any { it.endsWith("/crewlist/list") })
        assertTrue(paths().any { it.endsWith("/deal-memo/deals") })
        assertTrue(paths().any { it.endsWith("/production/days") })
        assertTrue(paths().any { it.endsWith("/crew-dood/calendar") })
        assertEquals(listOf("Camera", "Sound"), state.groups.map { it.name })
        assertEquals(listOf("Ann", "Ben"), state.groups[0].crew.map { it.name })
        // Only the active deal books anyone: Ben's is `issued`.
        assertEquals(5, state.groups[0].crew[0].days)
        assertEquals(0, state.groups[0].crew[1].days)
        assertEquals(1, state.groups[0].booked)
        assertEquals(2, state.groups[0].total)
    }

    @Test
    fun `a failed crew list is the retry state`() = runTest(dispatcher) {
        crewListFails = true
        val model = loaded()
        assertNotNull(model.state.value.error)
        crewListFails = false
        model.onEvent(CrewDoodEvent.Reload)
        settle { model.state.value.error == null && !model.state.value.loading }
        assertTrue(model.state.value.groups.isNotEmpty())
    }

    @Test
    fun `a failed deals read is the retry state too`() = runTest(dispatcher) {
        dealsFail = true
        val model = loaded()
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a calendar that is not there yet leaves Saturday and Sunday off`() = runTest(dispatcher) {
        calendarRead = """null"""
        val model = loaded()
        assertEquals(setOf(0, 6), model.state.value.calendar.weeklyOff)
        // The chart still draws: the calendar is optional, the crew list is not.
        assertTrue(model.state.value.groups.isNotEmpty())
    }

    @Test
    fun `Find, the department picker and Booked narrow the rows`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(CrewDoodEvent.Search("mix"))
        assertEquals(listOf("Sound"), model.state.value.groups.map { it.name })

        model.onEvent(CrewDoodEvent.Search(""))
        val camera = model.state.value.departmentOptions.first { it.name == "Camera" }
        model.onEvent(CrewDoodEvent.SelectDepartment(camera.key))
        assertEquals(listOf("Camera"), model.state.value.groups.map { it.name })

        model.onEvent(CrewDoodEvent.SetBookedOnly(true))
        assertEquals(listOf("Ann"), model.state.value.groups.single().crew.map { it.name })
        // The department's own tally still counts everyone in it.
        assertEquals(2, model.state.value.groups.single().total)
    }

    @Test
    fun `the footer counts only the rows that passed the filters`() = runTest(dispatcher) {
        val model = loaded()
        val all = model.state.value.counts
        assertEquals(1, all.max())
        model.onEvent(CrewDoodEvent.Search("ben"))
        assertTrue(model.state.value.counts.all { it == 0 })
    }

    @Test
    fun `saving the calendar sends both fields, redraws the grid and toasts the server`() = runTest(dispatcher) {
        val model = loaded()
        val calendar = model.state.value.calendar.copy(
            weeklyOff = setOf(0),
            holidays = mapOf(LocalDate.parse("2026-04-07") to "Founders Day"),
        )
        model.onEvent(CrewDoodEvent.OpenCalendar)
        model.onEvent(CrewDoodEvent.SaveCalendar(calendar))
        settle { !model.state.value.saving }

        val sent = assertNotNull(calls.value.last { it.method == HttpMethod.Put }.body).jsonObject
        assertEquals(listOf(0), sent["weekly_off"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("2026-04-07", sent["holidays"]!!.jsonArray.single().jsonObject["date"]!!.jsonPrimitive.content)

        val state = model.state.value
        assertFalse(state.calendarOpen)
        // The server sends a message KEY; the toast shows what the
        // dictionary makes of it, humanised when it knows no better.
        assertEquals("Calendar Saved", state.toast?.text)
        assertEquals(true, state.toast?.success)
        // Redrawn from what was saved, with no second read: Tuesday is now a
        // holiday, so Ann's Monday-to-Friday booking works four days of five.
        val ann = state.groups[0].crew.first { it.name == "Ann" }
        assertEquals(DayCode.Hol, ann.cells[state.model.dates.indexOf(LocalDate.parse("2026-04-07"))])
        assertEquals(4, ann.days)
    }

    @Test
    fun `a refused save keeps the editor open and shows the server's words`() = runTest(dispatcher) {
        calendarSaveStatus = 0
        val model = loaded()
        model.onEvent(CrewDoodEvent.OpenCalendar)
        model.onEvent(CrewDoodEvent.SaveCalendar(model.state.value.calendar.copy(weeklyOff = setOf(0))))
        settle { !model.state.value.saving }
        val state = model.state.value
        assertTrue(state.calendarOpen)
        assertEquals(false, state.toast?.success)
        assertEquals("No Permission To Edit Calendar", state.toast?.text)
    }

    @Test
    fun `the export runs once and warns about the deals the service left out`() = runTest(dispatcher) {
        exportSkipped = 4
        val model = loaded()
        model.onEvent(CrewDoodEvent.Export)
        model.onEvent(CrewDoodEvent.Export) // ignored while one is running
        settle { !model.state.value.exporting }
        assertEquals(1, exported)
        assertEquals(false, model.state.value.toast?.success)
        assertTrue(model.state.value.toast!!.text.contains("4"))
    }

    @Test
    fun `a clean export says nothing at all`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(CrewDoodEvent.Export)
        settle { !model.state.value.exporting }
        assertNull(model.state.value.toast)
    }

    @Test
    fun `a refused export shows the server's words`() = runTest(dispatcher) {
        exportFails = true
        val model = loaded()
        model.onEvent(CrewDoodEvent.Export)
        settle { !model.state.value.exporting }
        assertEquals("export_not_allowed", model.state.value.toast?.text)
    }

    @Test
    fun `a production switch drops the previous production's chart`() = runTest(dispatcher) {
        val model = loaded()
        assertTrue(model.state.value.groups.isNotEmpty())
        model.onProjectChanged()
        assertTrue(model.state.value.loading)
        settle { !model.state.value.loading }
        assertTrue(model.state.value.groups.isNotEmpty())
    }

    private companion object {
        const val SETTLE_TRIES = 400
        const val SETTLE_STEP_MILLIS = 5L
    }
}

private class DoodMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
