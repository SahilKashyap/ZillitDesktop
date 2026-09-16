package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTransfer
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.PdfPageImage
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Schedule Full & One Line behaviours the web's
 * `ScheduleDistributionMain.jsx` has and the engine gained for it: the tab
 * chips, a new page starting with no kind chosen and refusing to send
 * without one, the television episode rule on a new page, the paperclip
 * replacing the current schedule, and a typed scene dropping the colour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val tool = DistributionTool.ScheduleDistribution

    private val schedule = DistDocument(
        id = "s1",
        createdMs = 10L,
        createdBy = "u2",
        episode = "1",
        sceneNumber = "",
        pageNumber = "",
        colour = "",
        dateMs = 0,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "",
        originalName = "schedule.pdf",
        deleted = false,
        replaced = false,
        attachment = StoredPdf("k", "schedule.pdf", "b", "r", "1000"),
    )

    private class Repo(private val schedule: DistDocument) : DistributionRepository {
        val drafts = mutableListOf<UploadDraft>()
        var searches = 0
        override fun refreshes(tool: DistributionTool): Flow<Unit> = emptyFlow()
        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(if (tab.key == "full_script") listOf(schedule) else emptyList())
        override suspend fun folders(tab: DistributionTab, mode: ListMode) = ZillitResult.Success(
            listOf(DistFolder("f1", "12", 10L, 0L, ScheduleType.FullSchedulePages, "#FFFFFF", deleted = false)),
        )
        override suspend fun folderDocuments(
            tab: DistributionTab,
            folderKey: String,
            beforeMs: Long,
            next: Boolean,
            mode: ListMode,
        ) = ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun search(
            tab: DistributionTab,
            sceneNumber: String?,
            episode: String?,
            colour: PageColour?,
            mode: ListMode,
        ): ZillitResult<List<DistDocument>> {
            searches++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun document(tab: DistributionTab, id: String, action: ReadAction, mode: ListMode):
            ZillitResult<DistDocument> = ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun upload(tab: DistributionTab, draft: UploadDraft, stored: StoredPdf, nowMs: Long):
            ZillitResult<DistDocument?> {
            drafts += draft
            return ZillitResult.Success(null)
        }
        override suspend fun delete(tab: DistributionTab, id: String) = ZillitResult.Success(Unit)
        override suspend fun move(tab: DistributionTab, id: String, folderName: String) =
            ZillitResult.Success(Unit)
        override suspend fun counts(tab: DistributionTab, id: String) =
            ZillitResult.Success(emptyList<CountRow>())
        override suspend fun publish(
            tool: DistributionTool,
            tab: DistributionTab,
            document: DistDocument,
            todayYmd: String,
        ) = ZillitResult.Success(Unit)
    }

    private object Transfer : DistributionTransfer {
        override suspend fun upload(storagePath: String, fileName: String, bytes: ByteArray) =
            ZillitResult.Success(StoredPdf("key", "stored.pdf", "b", "r", bytes.size.toString()))
        override suspend fun fetch(stored: StoredPdf) = ZillitResult.Success(ByteArray(0))
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<PdfPageImage>())
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private fun model(
        repo: Repo = Repo(schedule),
        television: Boolean = false,
        tabUnread: Flow<Map<String, Int>> = emptyFlow(),
    ) = DistributionViewModel(
        tool = tool,
        repository = repo,
        transfer = Transfer,
        resolveViewer = {
            DistributionViewer(
                userId = "me",
                canPost = true,
                canDownload = true,
                isTelevision = television,
                ready = true,
            )
        },
        nowMillis = { 1_800_000_000_000L },
        tabUnread = tabUnread,
    )

    @Test
    fun `the tab chips follow the host's unread per tab`() = runTest(dispatcher) {
        val counts = MutableStateFlow(mapOf("full_script" to 2, "page" to 5, "oneline" to 0))
        val vm = model(tabUnread = counts)
        vm.start()
        runCurrent()
        assertEquals(5, vm.state.value.tabUnread["page"])

        counts.value = mapOf("page" to 0)
        runCurrent()
        assertEquals(0, vm.state.value.tabUnread["page"])
    }

    @Test
    fun `the paperclip on a single tab with a schedule already is a replace`() = runTest(dispatcher) {
        val repo = Repo(schedule)
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.PdfPicked("new.pdf", ByteArray(4), replaces = null))
        val editor = assertNotNull(vm.state.value.upload)
        assertEquals("s1", editor.replaces)
        assertEquals("1", editor.episode)

        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("s1", repo.drafts.single().replaces)
        assertNull(vm.state.value.upload)
    }

    @Test
    fun `a new page starts with no kind chosen and will not send without one`() = runTest(dispatcher) {
        val repo = Repo(schedule)
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()

        vm.onEvent(DistributionEvent.PdfPicked("p.pdf", ByteArray(4), replaces = null))
        val editor = assertNotNull(vm.state.value.upload)
        assertNull(editor.scheduleType)

        vm.onEvent(DistributionEvent.UploadChanged(sceneNumber = "12"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("Choose schedule pages or one line pages", vm.state.value.error)
        assertEquals(0, repo.drafts.size)

        vm.onEvent(DistributionEvent.DismissError)
        vm.onEvent(DistributionEvent.UploadChanged(scheduleType = ScheduleType.OneLinePages))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals(ScheduleType.OneLinePages, repo.drafts.single().scheduleType)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a new page on television needs an episode but a replaced page does not`() = runTest(dispatcher) {
        val repo = Repo(schedule)
        val vm = model(repo, television = true)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()

        vm.onEvent(DistributionEvent.PdfPicked("p.pdf", ByteArray(4), replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(sceneNumber = "12", scheduleType = ScheduleType.FullSchedulePages))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("An episode number is required", vm.state.value.error)

        vm.onEvent(DistributionEvent.CancelUpload)
        vm.onEvent(DistributionEvent.DismissError)
        val page = schedule.copy(id = "p1", sceneNumber = "12", colour = "#ADD8E6", episode = "")
        vm.onEvent(DistributionEvent.PdfPicked("p2.pdf", ByteArray(4), replaces = page))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertNull(vm.state.value.error)
        assertEquals("p1", repo.drafts.single().replaces)
        assertEquals(PageColour.Blue, repo.drafts.single().colour)
    }

    @Test
    fun `typing a scene drops a picked colour and the colour drops the typed scene`() = runTest(dispatcher) {
        val repo = Repo(schedule)
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()

        vm.onEvent(DistributionEvent.SearchColour(PageColour.Pink))
        runCurrent()
        assertEquals(1, repo.searches)
        assertNotNull(vm.state.value.searchResults)

        vm.onEvent(DistributionEvent.SearchChanged(scene = "1"))
        assertNull(vm.state.value.searchColour)
        assertEquals("1", vm.state.value.searchScene)

        vm.onEvent(DistributionEvent.SearchColour(PageColour.Blue))
        runCurrent()
        assertEquals("", vm.state.value.searchScene)
        assertEquals(2, repo.searches)

        vm.onEvent(DistributionEvent.ClearSearch)
        runCurrent()
        assertNull(vm.state.value.searchResults)
        assertNull(vm.state.value.searchColour)
    }
}
