package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitRealtimeEndpoint
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.boxschedule.data.BoxScheduleRepositoryImpl
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleHost
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryDepartment
import com.zillit.desktop.feature.boxschedule.domain.DiaryDirectory
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryPerson
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import com.zillit.desktop.feature.boxschedule.ui.PdfDestination
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleScope
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The diary driven through its view model over a mock engine and the real
 * repository — what each of the web's flows actually sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxScheduleFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val zone = TimeZone.of("Europe/London")
    private fun day(d: Int) = DiaryCalendar.startOf(LocalDate(2026, 9, d), zone)
    private val now = LocalDateTime(2026, 9, 13, 8, 0).toInstant(zone).toEpochMilliseconds()
    private val occurrenceStart = LocalDateTime(2026, 9, 17, 9, 0).toInstant(zone).toEpochMilliseconds()
    private val firstStart = LocalDateTime(2026, 9, 14, 9, 0).toInstant(zone).toEpochMilliseconds()

    private data class Sent(val method: HttpMethod, val path: String, val query: String, val body: String)

    private val sent = mutableListOf<Sent>()
    private var conflictOnce = true

    private fun engine() = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        val body = request.text()
        sent += Sent(request.method, path, request.url.encodedQuery, body)
        val create = request.method == HttpMethod.Post && path.endsWith("/days")
        val (status, answer) = if (create && conflictOnce && "conflictAction" !in body) {
            conflictOnce = false
            HttpStatusCode.Conflict to """{"status":0,"message":"schedule_conflict"}"""
        } else {
            HttpStatusCode.OK to answer(path, read = request.method == HttpMethod.Get)
        }
        respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    /** What the service answers on each route the flows touch; every other write is a bare success. */
    private fun answer(path: String, read: Boolean): String = when {
        path.endsWith("/types") -> """{"status":1,"data":[{"_id":"t1","title":"Shoot Day","color":"#E74C3C","systemDefined":true},
            {"_id":"t2","title":"Prep","color":"#3498DB"}]}"""
        read && path.endsWith("/days") -> """{"status":1,"data":[
            {"_id":"b1","typeId":"t1","typeName":"Shoot Day","color":"#E74C3C","calendarDays":[${day(14)},${day(15)},${day(16)}]},
            {"_id":"b2","typeId":"t2","typeName":"Prep","color":"#3498DB","calendarDays":[${day(18)}]}]}"""
        path.endsWith("/note-types") -> """{"status":1,"data":[{"value":"general","label":"General"},
            {"value":"crew_start","label":"Crew Start","hide_distribution":true}]}"""
        read && path.endsWith("/events") -> """{"status":1,"data":[
            {"_id":"m1","eventType":"event","title":"Daily call","startDateTime":$firstStart,"endDateTime":${firstStart + 3_600_000},
             "date":${day(14)},"repeatStatus":"daily","occurrenceId":"m1_$firstStart","masterEventId":"m1","isRecurringInstance":true},
            {"_id":"m1","eventType":"event","title":"Daily call","startDateTime":$occurrenceStart,"endDateTime":${occurrenceStart + 3_600_000},
             "date":${day(17)},"repeatStatus":"daily","occurrenceId":"m1_$occurrenceStart","masterEventId":"m1","isRecurringInstance":true},
            {"_id":"n1","eventType":"note","title":"Rain cover","notes":"Tarps","date":${day(15)},"scheduleDayId":"b1"}
            ]}"""
        path.endsWith("/remove-dates") -> """{"status":1,"message":"Schedule day dates removed successfully"}"""
        path.endsWith("/activity-log") -> """{"status":1,"data":{"logs":[{"_id":"l1","action":"created","targetType":"note",
            "targetId":"n1","targetTitle":"Rain cover","performedBy":"u1","createdAt":$now}]}}"""
        path.endsWith("/share/generate-link") -> """{"status":1,"data":{"shareUrl":"/box-schedule/share/abc"}}"""
        else -> """{"status":1,"data":[]}"""
    }

    private fun HttpRequestData.text(): String =
        (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private var historyReads = 0

    private val directory = object : DiaryDirectory {
        override fun people() = listOf(DiaryPerson("u1", "Asha Rao", "Camera", "DOP"))
        override suspend fun departments() = ZillitResult.Success(listOf(DiaryDepartment("d1", "Camera")))
    }

    private fun viewModel(canEdit: Boolean = true): BoxScheduleViewModel {
        val repository = BoxScheduleRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ DiaryMockEngineFactory(engine()) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = mapOf(ZillitRealtimeEndpoint.Call to "https://dev.web.test/"),
            ),
        )
        return BoxScheduleViewModel(
            repository = repository,
            calendar = MainCalendarLookup { _, _ -> emptyList() },
            resolveViewer = { BoxScheduleViewer(userId = "u1", canView = true, canEdit = canEdit, ready = true) },
            nowMillis = { now },
            host = BoxScheduleHost(
                directory = directory,
                zone = { zone },
                historyBadge = flowOf(3),
                onHistoryViewed = { historyReads++ },
                newId = { "guest-id" },
            ),
        )
    }

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.loaded(canEdit: Boolean = true): BoxScheduleViewModel {
        val model = viewModel(canEdit)
        model.start()
        settle { model.ui.loadedOnce && !model.ui.loading && model.ui.events.isNotEmpty() }
        sent.clear()
        return model
    }

    private val BoxScheduleViewModel.ui: BoxScheduleUiState get() = currentState

    @Test
    fun `a clash names the dates it hits, and the chosen action retries the same write`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(ScheduleEvent.NewSchedule())
        model.onEvent(ScheduleEvent.SetType("t2"))
        model.onEvent(ScheduleEvent.SetStart(LocalDate(2026, 9, 15)))
        model.onEvent(ScheduleEvent.SetCount("2"))
        model.onEvent(ScheduleEvent.Save)
        settle { model.ui.overlays.conflict != null }

        val prompt = assertNotNull(model.ui.overlays.conflict, "a 409 opens the conflict prompt")
        assertNull(model.ui.overlays.scheduleForm)
        assertEquals(listOf(day(15), day(16)), prompt.conflicts.map { it.date }, "named from the schedule on screen")
        assertEquals("Shoot Day", prompt.conflicts.first().existingType)

        model.onEvent(ScheduleEvent.ResolveConflict)
        advanceUntilIdle()
        assertEquals(1, sent.count { it.method == HttpMethod.Post }, "Done waits for a choice")

        model.onEvent(ScheduleEvent.PickConflict(ConflictAction.Replace))
        model.onEvent(ScheduleEvent.ResolveConflict)
        settle { model.ui.overlays.conflict == null }
        val retry = sent.last { it.method == HttpMethod.Post && it.path.endsWith("/days") }
        assertTrue(retry.body.contains("\"conflictAction\":\"replace\""))
        assertTrue(retry.body.contains("\"calendarDays\":[${day(15)},${day(16)}]"))
        assertTrue(retry.body.contains("\"typeId\":\"t2\""))
    }

    @Test
    fun `this date only removes one date, and the complete schedule deletes the block`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(ScheduleEvent.AskDeleteDay("b1", day(15)))
        assertEquals(
            ScheduleScope.Single,
            model.ui.overlays.scheduleScope?.scope,
            "a multi-day block asks, this date first",
        )
        model.onEvent(ScheduleEvent.ConfirmScope)
        settle { model.ui.overlays.scheduleScope == null }
        val removal = sent.single { it.path.endsWith("/remove-dates") }
        assertEquals("""{"entries":[{"id":"b1","dates":[${day(15)}]}]}""", removal.body)
        assertTrue(sent.none { it.method == HttpMethod.Delete }, "this date only never widens to the block")

        model.onEvent(ScheduleEvent.AskDeleteDay("b1", day(15)))
        model.onEvent(ScheduleEvent.ChooseScope(ScheduleScope.Complete))
        model.onEvent(ScheduleEvent.ConfirmScope)
        settle { model.ui.overlays.scheduleScope == null }
        assertTrue(sent.any { it.method == HttpMethod.Delete && it.path.endsWith("/days/b1") })

        model.onEvent(ScheduleEvent.AskDeleteDay("b2", day(18)))
        assertNull(model.ui.overlays.scheduleScope, "a one-day block is a plain confirm")
        assertNotNull(model.ui.overlays.deleteDay)
        model.onEvent(ScheduleEvent.ConfirmDeleteDay)
        settle { model.ui.overlays.deleteDay == null }
        assertTrue(sent.any { it.method == HttpMethod.Delete && it.path.endsWith("/days/b2") })
    }

    @Test
    fun `a recurring delete sends its scope and occurrence, a plain one sends nothing`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(EntryEvent.AskDelete("m1_$occurrenceStart"))
        model.onEvent(EntryEvent.ChooseDeleteScope(RecurrenceScope.ThisAndFollowing))
        model.onEvent(EntryEvent.ConfirmDelete)
        settle { sent.any { it.method == HttpMethod.Delete } }
        val series = sent.first { it.method == HttpMethod.Delete }
        assertTrue(series.path.endsWith("/events/m1"), "the master document, never the occurrence id")
        assertTrue(series.query.contains("delete_type=this_and_following"))
        assertTrue(series.query.contains("occurrence_date=$occurrenceStart"), "the occurrence's start, not its day")

        sent.clear()
        model.onEvent(EntryEvent.AskDelete("n1"))
        model.onEvent(EntryEvent.ConfirmDelete)
        settle { sent.any { it.method == HttpMethod.Delete } }
        val plain = sent.first { it.method == HttpMethod.Delete }
        assertTrue(plain.path.endsWith("/events/n1"))
        assertEquals("", plain.query)
    }

    @Test
    fun `an all-events edit starts from the first occurrence and sends no scope`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(EntryEvent.EditEntry("m1_$occurrenceStart"))
        assertNotNull(model.ui.overlays.updateScope, "a recurring edit asks its scope first")
        model.onEvent(EntryEvent.ChooseUpdateScope(RecurrenceScope.All))
        model.onEvent(EntryEvent.ConfirmUpdateScope)
        val form = assertNotNull(model.ui.overlays.entryForm)
        assertEquals(LocalDate(2026, 9, 14), form.startDate, "seeded from the series' first occurrence")
        assertEquals("Save all events", form.saveLabel)

        model.onEvent(EntryEvent.SetCallType("audio"))
        model.onEvent(EntryEvent.OpenAudience)
        settle { model.ui.overlays.entryForm?.audiencePicker?.departmentsLoading == false }
        model.onEvent(EntryEvent.AudienceToggleSelf)
        model.onEvent(EntryEvent.AudienceTab(AudienceMode.Self))
        model.onEvent(EntryEvent.AudienceDone)
        model.onEvent(EntryEvent.SetRepeatEnd(LocalDate(2026, 9, 30)))
        model.onEvent(EntryEvent.Save)
        settle { sent.any { it.method == HttpMethod.Put } }
        val put = sent.first { it.method == HttpMethod.Put }
        assertTrue(put.path.endsWith("/events/m1"))
        assertEquals("", put.query, "a whole-series rewrite carries no parameters")
        assertTrue(put.body.contains("\"distributeTo\":\"self\""))
        assertFalse(put.body.contains("createEventInCalendar"))
    }

    @Test
    fun `a new event asks about the Home calendar before anything is sent`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(EntryEvent.NewEntry(DiaryKind.Event, date = day(20)))
        model.onEvent(EntryEvent.SetTitle("Costume fitting"))
        model.onEvent(EntryEvent.SetCallType("video"))
        model.onEvent(EntryEvent.OpenAudience)
        settle { model.ui.overlays.entryForm?.audiencePicker?.presetsLoading == false }
        model.onEvent(EntryEvent.AudienceToggleUser("u1"))
        model.onEvent(EntryEvent.AudienceDone)
        model.onEvent(EntryEvent.Save)
        advanceUntilIdle()
        assertTrue(model.ui.overlays.entryForm?.askCalendar == true)
        assertTrue(sent.none { it.method == HttpMethod.Post && it.path.endsWith("/events") })

        model.onEvent(EntryEvent.AnswerCalendar(mirror = true))
        settle { sent.any { it.method == HttpMethod.Post && it.path.endsWith("/events") } }
        val post = sent.first { it.method == HttpMethod.Post && it.path.endsWith("/events") }
        assertTrue(post.body.contains("\"createEventInCalendar\":true"))
        assertTrue(post.body.contains("\"distributeUserIds\":[\"u1\"]"))
        assertTrue(post.body.contains("\"date\":${day(20)}"))
        settle { model.ui.overlays.entryForm == null }
        assertEquals(LocalDate(2026, 9, 20), model.ui.page.day, "the calendar follows the saved event")
    }

    @Test
    fun `a viewer without posting rights opens nothing that writes`() = runTest(dispatcher) {
        val model = loaded(canEdit = false)
        model.onEvent(ScheduleEvent.NewSchedule())
        model.onEvent(ScheduleEvent.AskDeleteDay("b1", day(15)))
        model.onEvent(EntryEvent.NewEntry(DiaryKind.Note))
        model.onEvent(EntryEvent.AskDelete("n1"))
        model.onEvent(PanelEvent.OpenTypes)
        model.onEvent(PanelEvent.OpenPdf(PdfDestination.Print))
        advanceUntilIdle()
        val overlays = model.ui.overlays
        assertNull(overlays.scheduleForm)
        assertNull(overlays.scheduleScope)
        assertNull(overlays.entryForm)
        assertNull(overlays.deleteEntry)
        assertNull(overlays.types)
        assertNull(overlays.pdf)
        assertTrue(sent.none { it.method != HttpMethod.Get })
    }

    @Test
    fun `history reads the log and reads the diary's notifications`() = runTest(dispatcher) {
        val model = loaded()
        settle { model.ui.historyBadge == 3 }
        model.onEvent(PanelEvent.OpenHistory)
        settle { model.ui.overlays.history?.loading == false }
        assertEquals(1, historyReads)
        val log = sent.first { it.path.endsWith("/activity-log") }
        assertTrue(log.query.contains("limit=200") && log.query.contains("page=0"))
        assertEquals("Rain cover", model.ui.overlays.history?.entries?.single()?.targetTitle)
    }

    @Test
    fun `a preset is refused without a name, then saves the trimmed name and its members`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(PanelEvent.OpenPresets)
        settle { model.ui.overlays.presets?.loading == false }
        model.onEvent(PanelEvent.NewPreset)
        model.onEvent(PanelEvent.TogglePresetUser("u1"))
        model.onEvent(PanelEvent.SavePreset)
        advanceUntilIdle()
        assertTrue(sent.none { it.method == HttpMethod.Post }, "a nameless preset never reaches the wire")

        model.onEvent(PanelEvent.SetPresetName("  Camera team "))
        model.onEvent(PanelEvent.SavePreset)
        settle { model.ui.overlays.presets?.form == null }
        val saved = sent.single { it.method == HttpMethod.Post }
        assertEquals("""{"preset_name":"Camera team","user_ids":["u1"]}""", saved.body)
    }

    @Test
    fun `a share link is made absolute on the web app`() = runTest(dispatcher) {
        val model = loaded()
        model.onEvent(PanelEvent.OpenShare)
        model.onEvent(PanelEvent.GenerateShareLink)
        settle { model.ui.overlays.share?.link != null }
        assertEquals("https://dev.web.test/box-schedule/share/abc", model.ui.overlays.share?.link)
    }
}

private class DiaryMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
