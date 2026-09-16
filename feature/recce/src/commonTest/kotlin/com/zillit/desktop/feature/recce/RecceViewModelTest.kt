package com.zillit.desktop.feature.recce

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.recce.domain.GeocodeHit
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceDraft
import com.zillit.desktop.feature.recce.domain.RecceHost
import com.zillit.desktop.feature.recce.domain.RecceListPage
import com.zillit.desktop.feature.recce.domain.RecceQuery
import com.zillit.desktop.feature.recce.domain.ReccePdfPage
import com.zillit.desktop.feature.recce.domain.RecceReport
import com.zillit.desktop.feature.recce.domain.RecceRepository
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.RoutePin
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.ui.RecceEffect
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceField
import com.zillit.desktop.feature.recce.ui.RecceFilter
import com.zillit.desktop.feature.recce.ui.ReccePage
import com.zillit.desktop.feature.recce.ui.RecceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The container logic of the web's `ReccePage.jsx`, against fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the list is asked for a page with its limit, and the badges come from limit-one reads`() =
        runTest(dispatcher) {
            val repository = FakeRepository(all = 120, published = 45)
            val model = viewModel(repository)

            model.start()
            advanceUntilIdle()

            val first = repository.queries.first()
            assertEquals(RecceQuery(page = 1, limit = RecceQuery.DEFAULT_PAGE_SIZE), first)
            assertEquals(120, model.state.value.total)
            assertEquals(120, model.state.value.counts.all)
            assertEquals(45, model.state.value.counts.published)
            assertEquals(75, model.state.value.counts.draft)
            // "all" was answered by the list itself; only published needed its own read.
            assertEquals(
                listOf(RecceQuery(page = 1, limit = 1, status = RecceStatus.Published)),
                repository.queries.drop(1),
            )
        }

    @Test
    fun `switching to a tab whose badge reads zero fetches nothing`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 3, published = 3)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        val before = repository.queries.size

        model.onEvent(RecceEvent.Filter(RecceFilter.Drafts))
        advanceUntilIdle()

        assertEquals(before, repository.queries.size)
        assertTrue(model.state.value.recces.isEmpty())
        assertEquals(RecceFilter.Drafts, model.state.value.filter)
    }

    @Test
    fun `a tab switch sends its status and restarts at page one`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 120, published = 45)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.GoToPage(3))
        advanceUntilIdle()

        model.onEvent(RecceEvent.Filter(RecceFilter.Published))
        advanceUntilIdle()

        assertEquals(RecceQuery(page = 1, limit = 50, status = RecceStatus.Published), repository.queries.last())
        assertEquals(1, model.state.value.page)
    }

    @Test
    fun `typing is debounced into one search request`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 10, published = 4)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        val before = repository.queries.size

        model.onEvent(RecceEvent.Search("br"))
        model.onEvent(RecceEvent.Search("bri"))
        model.onEvent(RecceEvent.Search("bridge"))
        advanceTimeBy(100)
        assertEquals(before, repository.queries.size)
        advanceUntilIdle()

        val searches = repository.queries.drop(before)
        assertEquals(listOf("bridge"), searches.map { it.search })
        // A search narrows the list, not the badges.
        assertEquals(10, model.state.value.counts.all)
    }

    @Test
    fun `a page-size change restarts at page one`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 120, published = 45)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.GoToPage(2))
        advanceUntilIdle()

        model.onEvent(RecceEvent.PageSize(20))
        advanceUntilIdle()

        assertEquals(RecceQuery(page = 1, limit = 20), repository.queries.last())
        assertEquals(20, model.state.value.pageSize)
        assertEquals(6, model.state.value.pageCount)
    }

    @Test
    fun `a publish without the required fields marks them and scrolls up`() = runTest(dispatcher) {
        val model = viewModel(FakeRepository(all = 0, published = 0))
        model.start()
        advanceUntilIdle()
        val effects = mutableListOf<RecceEffect>()
        backgroundScope.launch { model.effects.toList(effects) }
        runCurrent()

        model.onEvent(RecceEvent.New)
        model.onEvent(RecceEvent.Publish)
        runCurrent()

        val errors = model.state.value.editor!!.errors
        assertEquals(
            setOf(RecceField.Title, RecceField.Date, RecceField.RdvTime, RecceField.RdvPlace),
            errors.keys,
        )
        assertTrue(effects.any { it is RecceEffect.ScrollToTop })

        // Typing into a field forgives it.
        model.onEvent(RecceEvent.EditorChanged(title = "Bridges"))
        assertFalse(RecceField.Title in model.state.value.editor!!.errors)
    }

    @Test
    fun `a draft saves without validation, then opens the new record`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 0, published = 0)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()

        model.onEvent(RecceEvent.New)
        model.onEvent(RecceEvent.EditorChanged(title = "Bridges"))
        model.onEvent(RecceEvent.SaveDraft)
        advanceUntilIdle()

        assertEquals("Bridges", repository.created.single().title)
        assertEquals(RecceStatus.Draft, repository.created.single().status)
        assertEquals(ReccePage.Detail("new-1"), model.state.value.route)
        assertNull(model.state.value.editor)
    }

    @Test
    fun `a press without posting rights asks an admin and opens nothing`() = runTest(dispatcher) {
        val bus = RightsRequestBus()
        val asked = mutableListOf<RightsRequest>()
        backgroundScope.launch { bus.requests.toList(asked) }
        runCurrent()
        val model = viewModel(
            FakeRepository(all = 1, published = 1),
            viewer = RecceViewer(canView = true, canPost = false, canDownload = false, ready = true),
            rights = bus,
        )
        model.start()
        advanceUntilIdle()

        model.onEvent(RecceEvent.New)
        runCurrent()

        assertEquals(ReccePage.Index, model.state.value.route)
        assertEquals(listOf(RightsRequest("Recce", RightsKind.Post)), asked)
    }

    @Test
    fun `rights not yet loaded permit a press, as the web's soft default does`() = runTest(dispatcher) {
        val model = viewModel(FakeRepository(all = 0, published = 0), viewer = RecceViewer(ready = false))
        model.start()
        advanceUntilIdle()

        model.onEvent(RecceEvent.New)
        runCurrent()

        assertEquals(ReccePage.Form(null), model.state.value.route)
    }

    @Test
    fun `leaving a dirty form asks first, and discard drops back to the detail`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 1, published = 1)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.Open("r1"))
        advanceUntilIdle()
        model.onEvent(RecceEvent.Edit("r1"))
        advanceUntilIdle()

        model.onEvent(RecceEvent.RequestCancel)
        assertFalse(model.state.value.leavePrompt) // untouched: leaves at once
        assertEquals(ReccePage.Detail("r1"), model.state.value.route)

        model.onEvent(RecceEvent.Edit("r1"))
        advanceUntilIdle()
        model.onEvent(RecceEvent.EditorChanged(station = "Waterloo"))
        model.onEvent(RecceEvent.RequestCancel)
        assertTrue(model.state.value.leavePrompt)
        model.onEvent(RecceEvent.DiscardChanges)
        advanceUntilIdle()
        assertEquals(ReccePage.Detail("r1"), model.state.value.route)
        assertNull(model.state.value.editor)
    }

    @Test
    fun `deleting the last row of the last page steps back a page`() = runTest(dispatcher) {
        val repository = FakeRepository(all = 51, published = 51)
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.GoToPage(2))
        advanceUntilIdle()
        assertEquals(1, model.state.value.recces.size)

        model.onEvent(RecceEvent.Delete(model.state.value.recces.single().id))
        model.onEvent(RecceEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(1, model.state.value.page)
        assertEquals(RecceQuery(page = 1, limit = 50), repository.queries.last { it.limit == 50 })
    }

    @Test
    fun `a recce deleted elsewhere bounces its reader back to the list`() = runTest(dispatcher) {
        val socket = ScriptedSocketClient()
        val repository = FakeRepository(all = 2, published = 2)
        val model = viewModel(repository, events = SocketEventBus(socket))
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.Open("r1"))
        advanceUntilIdle()
        val effects = mutableListOf<RecceEffect>()
        backgroundScope.launch { model.effects.toList(effects) }
        runCurrent()

        // The server has already dropped the row by the time it broadcasts.
        repository.rows.removeAll { it.id == "r1" }
        socket.deliver("recce:deleted", """{"project_id":"p1","ids":["r1"]}""")
        advanceUntilIdle()
        runCurrent()

        assertEquals(ReccePage.Index, model.state.value.route)
        assertTrue(effects.any { it is RecceEffect.Notice && it.text == "This recce was deleted" })
        assertTrue(model.state.value.recces.none { it.id == "r1" })
    }

    @Test
    fun `a broadcast for another production is ignored`() = runTest(dispatcher) {
        val socket = ScriptedSocketClient()
        val repository = FakeRepository(all = 2, published = 2)
        val model = viewModel(repository, events = SocketEventBus(socket))
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.Open("r1"))
        advanceUntilIdle()

        socket.deliver("recce:deleted", """{"project_id":"other","ids":["r1"]}""")
        advanceUntilIdle()

        assertEquals(ReccePage.Detail("r1"), model.state.value.route)
    }

    @Test
    fun `opening a recce plots its route once every stop is resolved`() = runTest(dispatcher) {
        val host = FakeHost()
        val model = viewModel(FakeRepository(all = 1, published = 1), host = host)
        model.start()
        advanceUntilIdle()

        model.onEvent(RecceEvent.Open("r1"))
        advanceUntilIdle()

        val route = assertNotNull(model.state.value.routeMap)
        assertFalse(route.loading)
        assertEquals(2, route.plotted)
        assertEquals(listOf("Waterloo Station"), host.geocoded)
        assertEquals(listOf(1, 2), host.plottedPins.single().map { it.number })
    }

    @Test
    fun `generate PDF fetches the report, renders its pages and opens the viewer`() = runTest(dispatcher) {
        val host = FakeHost()
        val model = viewModel(FakeRepository(all = 1, published = 1), host = host)
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.Open("r1"))
        advanceUntilIdle()

        model.onEvent(RecceEvent.GeneratePdf)
        advanceUntilIdle()

        val viewer = assertNotNull(model.state.value.pdf)
        assertFalse(viewer.loading)
        assertEquals(1, viewer.pages.size)
        assertEquals("RECCE Day one 1.pdf", viewer.name)

        model.onEvent(RecceEvent.PrintPdf)
        advanceUntilIdle()
        assertEquals(listOf("RECCE Day one 1.pdf"), host.printed)
    }

    @Test
    fun `reopening the tool lands on the list unless a form was left mid-edit`() = runTest(dispatcher) {
        val model = viewModel(FakeRepository(all = 1, published = 1))
        model.start()
        advanceUntilIdle()
        model.onEvent(RecceEvent.New)
        runCurrent()

        model.start() // an untouched form is dropped
        advanceUntilIdle()
        assertEquals(ReccePage.Index, model.state.value.route)

        model.onEvent(RecceEvent.New)
        model.onEvent(RecceEvent.EditorChanged(title = "Kept"))
        model.start() // a dirty one survives the reopen
        advanceUntilIdle()
        assertEquals(ReccePage.Form(null), model.state.value.route)
        assertEquals("Kept", model.state.value.editor?.title)
    }

    // ------------------------------------------------------------ fixtures

    private fun viewModel(
        repository: FakeRepository,
        viewer: RecceViewer = RecceViewer(canPost = true, canDownload = true, ready = true),
        rights: RightsRequestBus? = null,
        events: SocketEventBus? = null,
        host: FakeHost = FakeHost(),
    ) = RecceViewModel(
        repository = repository,
        host = host,
        units = { ZillitResult.Success(emptyList()) },
        resolveViewer = { viewer },
        newUniqueId = { "uid" },
        timezone = { "Europe/London" },
        currentProjectId = { "p1" },
        events = events,
        rights = rights,
    )

    private class FakeRepository(all: Int, published: Int) : RecceRepository {
        val queries = mutableListOf<RecceQuery>()
        val created = mutableListOf<RecceDraft>()
        val rows: MutableList<Recce> = (1..all).map { n ->
            recce("r$n", if (n <= published) RecceStatus.Published else RecceStatus.Draft)
        }.toMutableList()

        override suspend fun recces(query: RecceQuery): ZillitResult<RecceListPage> {
            queries += query
            val matching = rows
                .filter { query.status == null || it.status == query.status }
                .filter { query.search.isEmpty() || it.title.contains(query.search, ignoreCase = true) }
            val page = matching.drop((query.page - 1) * query.limit).take(query.limit)
            return ZillitResult.Success(RecceListPage(page, matching.size))
        }

        override suspend fun recce(id: String): ZillitResult<Recce> =
            rows.firstOrNull { it.id == id }?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("missing"))

        override suspend fun crew(): ZillitResult<List<RecceCrewMember>> = ZillitResult.Success(emptyList())

        override suspend fun create(draft: RecceDraft): ZillitResult<String> {
            created += draft
            val id = "new-${created.size}"
            rows += recce(id, draft.status).copy(title = draft.title)
            return ZillitResult.Success(id)
        }

        override suspend fun update(id: String, draft: RecceDraft): ZillitResult<Unit> = ZillitResult.Success(Unit)

        override suspend fun delete(id: String): ZillitResult<Unit> {
            rows.removeAll { it.id == id }
            return ZillitResult.Success(Unit)
        }

        override suspend fun report(id: String): ZillitResult<RecceReport> =
            ZillitResult.Success(
                RecceReport(url = null, media = "m", bucket = "b", region = "r", name = "RECCE Day one 1"),
            )
    }

    private class FakeHost : RecceHost {
        val geocoded = mutableListOf<String>()
        val plottedPins = mutableListOf<List<RoutePin>>()
        val printed = mutableListOf<String>()

        override suspend fun fetchReport(report: RecceReport): ZillitResult<ByteArray> =
            ZillitResult.Success(byteArrayOf(1, 2, 3))

        override suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<ReccePdfPage>> =
            ZillitResult.Success(listOf(ReccePdfPage(byteArrayOf(0), 10, 14)))

        override suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)

        override suspend fun printPdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
            printed += fileName
            return ZillitResult.Success(Unit)
        }

        override suspend fun routeMap(pins: List<RoutePin>, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray? {
            plottedPins += pins
            return byteArrayOf(9)
        }

        override suspend fun previewMap(pin: LatLng, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray? = null

        override suspend fun geocode(query: String, country: String?): GeocodeHit? {
            geocoded += query
            return GeocodeHit(LatLng(51.5, -0.11), "GB")
        }
    }

    private companion object {
        fun recce(id: String, status: RecceStatus) = Recce(
            id = id,
            uniqueId = "u-$id",
            title = "Day one",
            unit = "",
            dateMs = 1_700_000_000_000L,
            station = "",
            weather = "",
            crewNote = "",
            rdv = RecceStop(kind = StopKind.Rendezvous, place = "Gate"),
            itinerary = listOf(
                RecceStop(kind = StopKind.Start, place = "Bridge", lat = 51.5, long = -0.12),
                RecceStop(kind = StopKind.End, place = "Waterloo Station"),
            ),
            personnel = emptyList(),
            status = status,
            version = 1,
        )
    }
}

/** The smallest [SocketClient] that can carry a message — `core:socket`'s fake is not published. */
private class ScriptedSocketClient : SocketClient {

    private val state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    override val connectionState = state.asStateFlow()

    private val inbound = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 16)
    override val messages: Flow<SocketMessage> = inbound

    suspend fun deliver(event: String, payloadJson: String) {
        inbound.emit(SocketMessage(SocketEventName(event), Json.parseToJsonElement(payloadJson)))
    }

    override suspend fun connect(config: SocketConfig) {
        state.value = SocketConnectionState.Connected("scripted")
    }

    override suspend fun disconnect() {
        state.value = SocketConnectionState.Disconnected
    }

    override suspend fun <T> emit(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun <T> emitForAck(
        event: SocketEventName,
        payload: T,
        serializer: KSerializer<T>,
    ): ZillitResult<JsonElement> = ZillitResult.Success(JsonNull)
}
