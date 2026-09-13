package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.crewlist.data.matchesProject
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.HeaderSection
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.SectionOffset
import com.zillit.desktop.feature.crewlist.ui.CanvasMode
import com.zillit.desktop.feature.crewlist.ui.CrewListEffect
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.crewlist.ui.DistributionPrompt
import com.zillit.desktop.feature.crewlist.ui.GenerateAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crew list's journeys, against fakes: the sheet, the PDF and its two
 * destinations, the designer, the admin editors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrewListFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val editor =
        CrewListViewer(canView = true, canPost = true, canPostInfo = true, canDistribute = true, ready = true)

    /** The page's message for the stacked arrangement. */
    private val stackedLayout = """{"type":"crewlist-layout","order":["title","logo","company"]}"""

    private class Harness(
        val model: CrewListViewModel,
        val repository: FakeCrewRepository,
        val host: FakeCrewHost,
        val effects: MutableList<CrewListEffect>,
        val asks: MutableList<RightsRequest>,
    )

    private fun TestScope.harness(
        viewer: CrewListViewer = editor,
        repository: FakeCrewRepository = FakeCrewRepository(),
        project: () -> String? = { "p1" },
    ): Harness {
        val host = FakeCrewHost()
        val bus = RightsRequestBus()
        val model = CrewListViewModel(
            repository = repository,
            resolveViewer = { viewer },
            host = host,
            selfUserId = { "me" },
            projectId = project,
            rights = bus,
        )
        val effects = mutableListOf<CrewListEffect>()
        val asks = mutableListOf<RightsRequest>()
        backgroundScope.launch { model.effects.collect { effects += it } }
        backgroundScope.launch { bus.requests.collect { asks += it } }
        runCurrent()
        model.start()
        runCurrent()
        return Harness(model, repository, host, effects, asks)
    }

    private fun Harness.send(event: CrewListEvent, scope: TestScope) {
        model.onEvent(event)
        scope.runCurrent()
    }

    @Test
    fun `a reorder frame reloads the roster once, and a second start does not stack`() = runTest(dispatcher) {
        val frames = MutableSharedFlow<Unit>()
        val h = harness(repository = FakeCrewRepository(refreshes = frames))
        assertEquals(1, h.repository.calls.count { it == "roster" }, "start loads once")
        frames.emit(Unit)
        runCurrent()
        assertEquals(2, h.repository.calls.count { it == "roster" })
        h.model.start()
        runCurrent()
        frames.emit(Unit)
        runCurrent()
        assertEquals(4, h.repository.calls.count { it == "roster" }, "start reloads, the frame reloads ONCE")
        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
    }

    @Test
    fun `edit mode needs the posting right, and asks an admin without it`() = runTest(dispatcher) {
        val h = harness(viewer = editor.copy(canPost = false))
        h.send(CrewListEvent.Sheet.StartEditing, this)
        assertFalse(h.model.state.value.editing)
        assertEquals("Crew List", h.asks.single().moduleLabel)

        h.send(CrewListEvent.Sheet.EditMember(Aisha, MemberOverride(phone = "1")), this)
        assertTrue(h.model.state.value.overrides.isEmpty(), "an edit outside edit mode is ignored")
    }

    @Test
    fun `a phone left without its dial code blocks Done and the PDF until fixed`() = runTest(dispatcher) {
        val h = harness()
        h.send(CrewListEvent.Sheet.StartEditing, this)
        h.send(CrewListEvent.Sheet.EditMember(Aisha, MemberOverride(countryCode = "")), this)
        assertNotNull(h.model.state.value.problems["u1"])

        h.send(CrewListEvent.Sheet.DoneEditing, this)
        assertTrue(h.model.state.value.editing, "Done is refused while a row is invalid")
        h.send(CrewListEvent.Document.OpenChooser, this)
        assertFalse(h.model.state.value.chooserOpen, "so is the chooser")

        h.send(CrewListEvent.Sheet.EditMember(Aisha, MemberOverride(countryCode = "+91")), this)
        assertTrue(h.model.state.value.problems.isEmpty())
        h.send(CrewListEvent.Sheet.DoneEditing, this)
        assertFalse(h.model.state.value.editing)
        assertEquals(MemberOverride(countryCode = "+91"), h.model.state.value.overrides["u1"])
    }

    @Test
    fun `publishing needs both rights and is refused before anything renders`() = runTest(dispatcher) {
        val h = harness(viewer = editor.copy(canPostInfo = false))
        h.send(CrewListEvent.Document.OpenChooser, this)
        h.send(CrewListEvent.Document.Run(GenerateAction.Publish), this)
        assertTrue("generate" !in h.repository.calls, "no PDF is rendered for a publish that will be refused")
        assertEquals("Info", h.asks.single().moduleLabel)
    }

    @Test
    fun `publish renders with the edits and the label choice, then posts to Info under the tool's name`() =
        runTest(dispatcher) {
            val h = harness()
            h.send(CrewListEvent.Sheet.StartEditing, this)
            h.send(CrewListEvent.Sheet.EditMember(Aisha, MemberOverride(email = "new@x.y")), this)
            h.send(CrewListEvent.Document.OpenChooser, this)
            h.send(CrewListEvent.Document.HideExternalLabel(true), this)
            h.send(CrewListEvent.Document.Run(GenerateAction.Publish), this)

            val request = h.repository.requests.single()
            assertEquals(true, request.hideExternalLabel)
            assertEquals(MemberOverride(email = "new@x.y"), request.overrides["u1"])
            assertTrue("publish:Crew List" in h.repository.calls)
            assertNull(h.model.state.value.working)
            assertFalse(h.model.state.value.chooserOpen)
            assertTrue(h.effects.any { it is CrewListEffect.Toast && it.text == "Crew List Published Successfully" })
        }

    @Test
    fun `filing in Document Distribution confirms first, and cancelling reopens the chooser`() = runTest(dispatcher) {
        val h = harness()
        h.send(CrewListEvent.Document.OpenChooser, this)
        h.send(CrewListEvent.Document.Run(GenerateAction.Distribute), this)
        val prompt = h.model.state.value.distribution
        assertEquals(DistributionPrompt.Stage.Confirm, prompt?.stage)
        assertTrue(h.host.calls.none { it.startsWith("distribute") }, "nothing is sent before the confirmation")

        h.send(CrewListEvent.Document.CancelDistribution, this)
        assertNull(h.model.state.value.distribution)
        assertTrue(h.model.state.value.chooserOpen, "cancelling from the chooser reopens it")

        h.send(CrewListEvent.Document.Run(GenerateAction.Distribute), this)
        h.send(CrewListEvent.Document.ConfirmDistribution, this)
        assertEquals("distribute:Crew List.pdf", h.host.calls.last())
        assertEquals(DistributionPrompt.Stage.Done, h.model.state.value.distribution?.stage)
    }

    @Test
    fun `view fetches and draws the PDF, and Download saves what was drawn`() = runTest(dispatcher) {
        val h = harness()
        h.send(CrewListEvent.Document.OpenChooser, this)
        h.send(CrewListEvent.Document.Run(GenerateAction.View), this)
        val viewer = h.model.state.value.pdf
        assertNotNull(viewer)
        assertFalse(viewer.loading)
        assertEquals(listOf("fetch", "render"), h.host.calls)

        h.send(CrewListEvent.Document.DownloadViewed, this)
        assertEquals("save:Crew List.pdf", h.host.calls.last())
        assertEquals(1, h.host.calls.count { it == "fetch" }, "the drawn bytes are saved, not fetched again")
    }

    @Test
    fun `the designer arranges locally, reloads only for what the page cannot show, and marks Preview stale`() =
        runTest(dispatcher) {
            val h = harness()
            h.send(CrewListEvent.Design.Open, this)
            val opened = h.model.state.value.customise
            assertEquals(CanvasMode.Design, opened?.mode)
            assertEquals(listOf("design"), h.repository.calls.filter { it == "design" || it == "preview" })
            val firstKey = opened?.design?.key
            assertNotNull(firstKey)
            assertTrue(opened.design.html.contains("window.__CL_LAYOUT__"), "the arranger is layered on")

            // A nudge from the page: recorded, undoable, but the page already shows it.
            val nudge = """{"type":"crewlist-offset","id":"logo","offset":{"x":-12,"y":6}}"""
            h.send(CrewListEvent.Design.CanvasMessage(nudge), this)
            assertEquals(SectionOffset(-12, 6), h.model.state.value.layout.current.offsetOf(HeaderSection.Logo))
            assertEquals(firstKey, h.model.state.value.customise?.design?.key, "a nudge does not reload the page")
            assertTrue(h.model.state.value.customise?.previewDirty == true)

            // A new arrangement: the document is recomposed (reloaded) with the new rows.
            val rows = """{"type":"crewlist-layout","order":["title",["logo","company"]]}"""
            h.send(CrewListEvent.Design.CanvasMessage(rows), this)
            val arranged = h.model.state.value.customise?.design
            assertTrue((arranged?.key ?: 0) > firstKey)
            assertEquals(
                listOf(listOf(HeaderSection.Title), listOf(HeaderSection.Logo, HeaderSection.Company)),
                h.model.state.value.layout.current.order,
            )

            // Undoing the nudge reseeds the page, which still shows it.
            h.send(CrewListEvent.Design.Undo, this)
            h.send(CrewListEvent.Design.Undo, this)
            assertEquals(SectionOffset(), h.model.state.value.layout.current.offsetOf(HeaderSection.Logo))

            // Preview asks the backend for the real render, every time.
            h.send(CrewListEvent.Design.Switch(CanvasMode.Preview), this)
            h.send(CrewListEvent.Design.Switch(CanvasMode.Design), this)
            h.send(CrewListEvent.Design.Switch(CanvasMode.Preview), this)
            assertEquals(2, h.repository.calls.count { it == "preview" })
            assertFalse(h.model.state.value.customise?.previewDirty == true)
        }

    @Test
    fun `someone who may not shape the document opens straight into Preview`() = runTest(dispatcher) {
        val h = harness(viewer = editor.copy(canPost = false))
        h.send(CrewListEvent.Design.Open, this)
        assertEquals(CanvasMode.Preview, h.model.state.value.customise?.mode)
        assertEquals(listOf("preview"), h.repository.calls.filter { it == "design" || it == "preview" })
        h.send(CrewListEvent.Design.CanvasMessage(stackedLayout), this)
        assertFalse(h.model.state.value.layout.canUndo, "and cannot change it from the page")
    }

    @Test
    fun `the department order saves every id in its new order, and leaving with changes asks first`() =
        runTest(dispatcher) {
            val h = harness(viewer = editor.copy(isAdmin = true))
            h.send(CrewListEvent.Admin.OpenDepartments, this)
            h.send(CrewListEvent.Admin.MoveDepartment(from = 2, to = 0), this)
            h.send(CrewListEvent.Admin.CloseDepartments, this)
            assertTrue(h.model.state.value.departments?.confirmDiscard == true)

            h.send(CrewListEvent.Admin.ResolveDiscard(save = true), this)
            assertEquals(listOf("d3", "d1", "d2"), h.host.reordered)
            assertNull(h.model.state.value.departments)
            assertEquals(2, h.repository.calls.count { it == "roster" }, "the sheet reloads in the new order")
        }

    @Test
    fun `a department's people load, move, and save in their new order without closing`() = runTest(dispatcher) {
        val h = harness(viewer = editor.copy(isAdmin = true))
        h.send(CrewListEvent.Admin.OpenDepartments, this)
        h.send(CrewListEvent.Admin.OpenPeople(OrderedDepartment("d1", "Camera")), this)
        assertTrue("people:d1" in h.repository.calls)
        assertEquals(2, h.model.state.value.departments?.people?.order?.current?.size)

        h.send(CrewListEvent.Admin.PositionPerson(index = 1, position = 1), this)
        h.send(CrewListEvent.Admin.SavePeople, this)
        assertEquals(listOf("p2", "p1"), h.repository.reorderedPeople)
        val people = h.model.state.value.departments?.people
        assertNotNull(people, "the list stays open after saving, as on the web")
        assertFalse(people.order.isChanged)
        assertEquals(2, h.repository.calls.count { it == "roster" }, "the sheet reloads in the new order")
    }

    @Test
    fun `admin editors stay shut to everyone else`() = runTest(dispatcher) {
        val h = harness()
        h.send(CrewListEvent.Admin.OpenDepartments, this)
        h.send(CrewListEvent.Admin.OpenCompany, this)
        assertNull(h.model.state.value.departments)
        assertNull(h.model.state.value.company)
    }

    @Test
    fun `company details refuse a half phone, then save and refresh the letterhead`() = runTest(dispatcher) {
        val h = harness(viewer = editor.copy(isAdmin = true))
        h.send(CrewListEvent.Design.Open, this)
        h.send(CrewListEvent.Admin.OpenCompany, this)
        val loaded = h.model.state.value.company?.details
        assertEquals("Take One", loaded?.name)

        h.send(CrewListEvent.Admin.EditCompany(CompanyDetails(name = "Take Two", phone = "2071234567")), this)
        h.send(CrewListEvent.Admin.SaveCompany, this)
        assertTrue(h.model.state.value.company?.problems?.isNotEmpty() == true)
        assertNull(h.repository.savedCompany)

        val whole = CompanyDetails(name = "Take Two", phone = "2071234567", countryCode = "+44")
        h.send(CrewListEvent.Admin.EditCompany(whole), this)
        h.send(CrewListEvent.Admin.SaveCompany, this)
        assertEquals("Take Two", h.repository.savedCompany?.first?.name)
        assertNull(h.model.state.value.company)
        assertEquals(2, h.repository.calls.count { it == "design" }, "the Design canvas renders the new letterhead")
    }

    @Test
    fun `a production switch forgets the last one's edits and letterhead`() = runTest(dispatcher) {
        var project = "p1"
        val h = harness(project = { project })
        h.send(CrewListEvent.Sheet.StartEditing, this)
        h.send(CrewListEvent.Sheet.EditMember(Aisha, MemberOverride(phone = "12345")), this)
        h.send(CrewListEvent.Design.Open, this)
        h.send(CrewListEvent.Design.CanvasMessage(stackedLayout), this)

        project = "p2"
        h.model.start()
        runCurrent()
        val after = h.model.state.value
        assertTrue(after.overrides.isEmpty())
        assertFalse(after.editing)
        assertFalse(after.layout.current.isCustomised)
        assertNull(after.customise)
    }
}
