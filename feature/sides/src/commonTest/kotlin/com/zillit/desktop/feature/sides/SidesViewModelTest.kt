package com.zillit.desktop.feature.sides

import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.ui.DocKind
import com.zillit.desktop.feature.sides.ui.PickPurpose
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.feature.sides.ui.SidesDestination
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesEffect
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.SidesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The flows the web pins: the socket refetch, the two generate gates, the
 * manual and call-sheet runs through the poller, the rights refusals on
 * every posting/download act, and the page editor's upload-then-JSON.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model(
        repo: FakeSidesRepository,
        viewer: SidesViewer = SidesViewer(userId = "u1", canPost = true, canDownload = true, ready = true),
        rights: RightsRequestBus? = null,
        transfer: FakeSidesTransfer = FakeSidesTransfer(),
    ) = SidesViewModel(repository = repo, transfer = transfer, resolveViewer = { viewer }, rights = rights)

    @Test
    fun `a generated pulse re-runs the visible list load once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeSidesRepository(refreshes = events)
        val model = model(repo)

        model.start()
        runCurrent()
        assertEquals(1, repo.calls.count { it == "sides" }, "start loads the sides list once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.calls.count { it == "sides" }, "the pulse re-runs exactly one load")

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(4, repo.calls.count { it == "sides" }, "a second start must not stack a second collector")
    }

    @Test
    fun `a poster's list load also fetches the three generate gates`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        model(repo).start()
        runCurrent()
        assertTrue("active" in repo.calls && "scriptsHistory" in repo.calls && "schedules" in repo.calls)

        val viewerOnly = FakeSidesRepository()
        model(viewerOnly, viewer = SidesViewer(canPost = false, ready = true)).start()
        runCurrent()
        assertFalse("active" in viewerOnly.calls, "a viewer without posting rights skips the gates")
    }

    @Test
    fun `autogenerate refuses without a published script and opens with one`() = runTest(dispatcher) {
        val repo = FakeSidesRepository().apply { active = null }
        val model = model(repo)
        val notices = mutableListOf<String>()
        backgroundScope.launch { model.effects.collect { if (it is SidesEffect.Notice) notices += it.message } }
        model.start()
        runCurrent()

        model.onEvent(SidesEvent.OpenAutogenerate)
        runCurrent()
        assertNull(model.state.value.auto)
        assertTrue(notices.any { it.startsWith("No published script") })

        repo.active = repo.scripts.first()
        model.onEvent(SidesEvent.Refresh)
        runCurrent()
        model.onEvent(SidesEvent.OpenAutogenerate)
        runCurrent()
        val auto = assertNotNull(model.state.value.auto)
        assertEquals("sc1", auto.scriptId)
        assertEquals("cs1", auto.selectedCallSheetId, "defaults to the latest call sheet")
        assertEquals(listOf("1", "2"), auto.scenes)
    }

    @Test
    fun `a call sheet run polls to ready, then publish closes and refetches`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        val model = model(repo)
        model.start()
        runCurrent()
        model.onEvent(SidesEvent.OpenAutogenerate)
        runCurrent()
        model.onEvent(SidesEvent.AutoRearrange(true))
        model.onEvent(SidesEvent.AutoOrderText("2, 1"))
        model.onEvent(SidesEvent.AutoGenerate)
        advanceUntilIdle()

        val plan = assertNotNull(repo.lastAutoPlan)
        assertEquals(listOf("2", "1"), plan.sceneNumbers)
        assertEquals("Sides - Day 1", plan.title)
        val result = assertNotNull(model.state.value.auto?.result)
        assertEquals(SidesStatus.Ready, result.status)

        model.onEvent(SidesEvent.AutoPublish)
        advanceUntilIdle()
        assertEquals(listOf("g2"), repo.published)
        assertNull(model.state.value.auto, "publishing closes the dialog")
        assertEquals(2, repo.calls.count { it == "sides" }, "and the list reloads")
    }

    @Test
    fun `the manual form defaults to the active script and regroups a custom order`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        val model = model(repo)
        model.start()
        runCurrent()
        model.onEvent(SidesEvent.OpenGenerate)
        advanceUntilIdle()

        val form = assertNotNull(model.state.value.generate)
        assertEquals("sc1", form.scriptId)
        assertTrue("v1" in form.openVersions, "the active script's current version opens expanded")
        assertEquals(3, form.scenesByVersion["v1"]?.size)
        assertFalse(form.readyToSubmit)

        model.onEvent(SidesEvent.GenSetScenes("v1", listOf("1", "2", "3")))
        model.onEvent(SidesEvent.GenRearrange(true))
        model.onEvent(SidesEvent.GenOrder(listOf("3", "1", "2")))
        model.onEvent(SidesEvent.GenToggleScene("v1", "2"))
        runCurrent()
        assertEquals(listOf("3", "1"), model.state.value.generate?.order, "deselecting drops the scene from the order")

        model.onEvent(SidesEvent.GenSubmit)
        advanceUntilIdle()
        val plan = assertNotNull(repo.lastManualPlan)
        assertEquals(listOf("3", "1"), plan.sceneOrder)
        assertEquals(listOf("3", "1"), plan.wireVersionScenes.single().sceneNumbers)
        assertEquals(SidesStatus.Ready, model.state.value.generate?.result?.status)

        model.onEvent(SidesEvent.GenBackToForm)
        runCurrent()
        assertNull(model.state.value.generate?.result)
        assertEquals(listOf("1", "3"), model.state.value.generate?.versionPicks?.get("v1"), "picks survive Back")
    }

    @Test
    fun `switching the primary script clears every pick`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        val model = model(repo)
        model.start()
        runCurrent()
        model.onEvent(SidesEvent.OpenGenerate)
        advanceUntilIdle()
        model.onEvent(SidesEvent.GenSetScenes("v1", listOf("1")))
        model.onEvent(SidesEvent.GenToggleWholePage("p1"))
        model.onEvent(SidesEvent.GenPickScript("sc2"))
        advanceUntilIdle()

        val form = assertNotNull(model.state.value.generate)
        assertTrue(form.versionPicks.isEmpty() && form.wholePages.isEmpty())
        assertEquals("sc2", form.scriptId)
    }

    @Test
    fun `posting and download acts are refused without the right and ask an admin`() = runTest(dispatcher) {
        val repo = FakeSidesRepository().apply { sidesRows = listOf(SidesRecord("s1", "Day", SidesStatus.Ready)) }
        val bus = RightsRequestBus()
        val asked = mutableListOf<String>()
        backgroundScope.launch { bus.requests.collect { asked += it.kind.name } }
        val model = model(repo, viewer = SidesViewer(canPost = false, canDownload = false, ready = true), rights = bus)
        model.start()
        runCurrent()

        model.onEvent(SidesEvent.OpenGenerate)
        model.onEvent(SidesEvent.AskDeleteSides(repo.sidesRows.first()))
        model.onEvent(SidesEvent.AskAddScript)
        model.onEvent(SidesEvent.DownloadSides(repo.sidesRows.first()))
        runCurrent()

        assertNull(model.state.value.generate)
        assertNull(model.state.value.dialog)
        assertEquals(listOf("Post", "Post", "Post", "Download"), asked)
        assertFalse(repo.calls.any { it.startsWith("download") }, "no download URL is fetched without the right")

        // In-app View stays open to every viewer.
        model.onEvent(SidesEvent.ViewSides(repo.sidesRows.first()))
        advanceUntilIdle()
        assertEquals(1, model.state.value.pdf?.pages?.size)
        assertTrue("download:s1:false" in repo.calls, "a View never counts as a download")
    }

    @Test
    fun `a page is uploaded to storage first and then posted as JSON`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        val transfer = FakeSidesTransfer()
        val model = model(repo, transfer = transfer)
        val picks = mutableListOf<PickPurpose>()
        backgroundScope.launch { model.effects.collect { if (it is SidesEffect.PickFile) picks += it.purpose } }
        model.start()
        model.onEvent(SidesEvent.Open(SidesDestination.Scripts))
        runCurrent()

        model.onEvent(SidesEvent.AskAddPage("sc1"))
        model.onEvent(SidesEvent.DialogSceneNumber("12A"))
        model.onEvent(SidesEvent.DialogSubmit)
        runCurrent()
        assertTrue(model.state.value.dialog is SidesDialog.PageEditor, "no file → the form stays open")

        model.onEvent(SidesEvent.DialogPickFile)
        runCurrent()
        assertEquals(listOf<PickPurpose>(PickPurpose.Dialog), picks)
        model.onEvent(SidesEvent.FilePicked(PickPurpose.Dialog, PickedDoc("scene.txt", ByteArray(1))))
        runCurrent()
        assertNull((model.state.value.dialog as SidesDialog.PageEditor).file, "a .txt is refused")
        model.onEvent(SidesEvent.FilePicked(PickPurpose.Dialog, PickedDoc("scene.pdf", ByteArray(1))))
        model.onEvent(SidesEvent.DialogSubmit)
        advanceUntilIdle()

        assertEquals(listOf("scene.pdf"), transfer.uploads)
        assertTrue("createPage:sc1:12A" in repo.calls)
        assertNull(model.state.value.dialog)
        assertEquals(2, repo.calls.count { it == "pages:sc1" }, "the script's pages reload after the save")
    }

    @Test
    fun `an uploaded call sheet becomes the selection`() = runTest(dispatcher) {
        val repo = FakeSidesRepository()
        val model = model(repo)
        model.start()
        runCurrent()
        model.onEvent(SidesEvent.OpenAutogenerate)
        runCurrent()
        model.onEvent(SidesEvent.AutoUpload(DocKind.CallSheet))
        model.onEvent(SidesEvent.DialogFileDropped(PickedDoc("Day 9.pdf", ByteArray(1))))
        runCurrent()
        assertEquals(
            "Day 9",
            (model.state.value.dialog as SidesDialog.UploadDoc).title,
            "the title defaults to the file name",
        )
        model.onEvent(SidesEvent.DialogSubmit)
        advanceUntilIdle()

        assertTrue("uploadCallSheet:Day 9" in repo.calls)
        assertEquals("cs9", model.state.value.auto?.selectedCallSheetId)
    }

    @Test
    fun `a failed generate leaves the form usable`() = runTest(dispatcher) {
        val repo = FakeSidesRepository().apply { failGenerate = true }
        val model = model(repo)
        model.start()
        runCurrent()
        model.onEvent(SidesEvent.OpenGenerate)
        advanceUntilIdle()
        model.onEvent(SidesEvent.GenSetScenes("v1", listOf("1")))
        model.onEvent(SidesEvent.GenSubmit)
        advanceUntilIdle()

        val form = assertNotNull(model.state.value.generate)
        assertFalse(form.running)
        assertNull(form.result)
        assertEquals(listOf("1"), form.versionPicks["v1"])
    }

    @Test
    fun `history opens with its own fetch and filters by name or creator`() = runTest(dispatcher) {
        val repo = FakeSidesRepository().apply {
            history = listOf(
                SidesRecord("h1", "Day 1", SidesStatus.Archived, generatedByName = "Aisha"),
                SidesRecord("h2", "Day 2", SidesStatus.Archived, generatedByName = "Ben"),
            )
        }
        val model = model(repo)
        model.start()
        runCurrent()
        assertFalse("history" in repo.calls)

        model.onEvent(SidesEvent.ToggleHistory)
        runCurrent()
        assertEquals(2, model.state.value.list.history.size)
        model.onEvent(SidesEvent.HistorySearch("ben"))
        assertEquals(listOf("h2"), model.state.value.list.filteredHistory.map { it.id })
    }

    @Test
    fun `the first effect after a refused press names the module`() = runTest(dispatcher) {
        val model = model(FakeSidesRepository(), viewer = SidesViewer(canPost = false, ready = true))
        val notices = mutableListOf<String>()
        backgroundScope.launch { model.effects.collect { if (it is SidesEffect.Notice) notices += it.message } }
        runCurrent()
        model.onEvent(SidesEvent.AskAddScript)
        runCurrent()
        val message = notices.single()
        assertTrue(message.startsWith("You do not have post rights on Sides"), message)
    }
}
