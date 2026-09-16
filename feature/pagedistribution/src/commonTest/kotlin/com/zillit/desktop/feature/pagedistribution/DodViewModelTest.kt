package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
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
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
import kotlin.test.assertTrue

/**
 * The D.O.D behaviours the web's `DoD.jsx` has and the engine gained for
 * it: a dropped file, a refused press asking an admin, the television
 * episode rule, the >25 MB admin notice, and the folder badges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DodViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val dod = DistributionTool.ScheduleDod

    private class Repo : DistributionRepository {
        var uploads = 0
        val folderRows = listOf(
            DistFolder("f1", "Week One", 10L, 0L, null, "", deleted = false),
            DistFolder("f2", "Week Two", 20L, 0L, null, "", deleted = false),
        )
        override fun refreshes(tool: DistributionTool): Flow<Unit> = emptyFlow()
        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun folders(tab: DistributionTab, mode: ListMode) = ZillitResult.Success(folderRows)
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
        ) = ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun document(tab: DistributionTab, id: String, action: ReadAction, mode: ListMode):
            ZillitResult<DistDocument> = ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun upload(tab: DistributionTab, draft: UploadDraft, stored: StoredPdf, nowMs: Long):
            ZillitResult<DistDocument?> {
            uploads++
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
        repo: Repo = Repo(),
        viewer: DistributionViewer = DistributionViewer(
            userId = "me",
            canPost = true,
            canDownload = true,
            ready = true,
        ),
        rights: RightsRequestBus? = null,
        folderUnread: Flow<Map<String, Int>> = emptyFlow(),
        onOversize: suspend (String, Long) -> Unit = { _, _ -> },
    ) = DistributionViewModel(
        tool = dod,
        repository = repo,
        transfer = Transfer,
        resolveViewer = { viewer },
        nowMillis = { 1_800_000_000_000L },
        folderUnread = folderUnread,
        rights = rights,
        onOversizeUpload = onOversize,
    )

    @Test
    fun `a dropped PDF opens the upload dialog and anything else is refused`() = runTest(dispatcher) {
        val vm = model()
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.FilesDropped(listOf("notes.txt" to ByteArray(3))))
        assertNull(vm.state.value.upload, "a text file opens nothing")
        assertEquals("Only PDF files are allowed", vm.state.value.error)

        vm.onEvent(DistributionEvent.DismissError)
        vm.onEvent(DistributionEvent.FilesDropped(listOf("dod week 1.pdf" to ByteArray(3))))
        val editor = assertNotNull(vm.state.value.upload, "a PDF opens the upload dialog")
        assertEquals("dod week 1.pdf", editor.fileName)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a drop without posting rights asks an admin and opens nothing`() = runTest(dispatcher) {
        val bus = RightsRequestBus()
        val asked = mutableListOf<RightsRequest>()
        backgroundScope.launch { bus.requests.collect { asked += it } }
        val vm = model(viewer = DistributionViewer(userId = "me", canPost = false, ready = true), rights = bus)
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.FilesDropped(listOf("a.pdf" to ByteArray(1))))
        runCurrent()

        assertNull(vm.state.value.upload)
        assertEquals(listOf(RightsRequest("Schedule D.O.D", RightsKind.Post)), asked)
        assertTrue(vm.state.value.error.orEmpty().contains("asking an administrator"), vm.state.value.error)
    }

    @Test
    fun `a refused download asks for the download right`() = runTest(dispatcher) {
        val bus = RightsRequestBus()
        val asked = mutableListOf<RightsRequest>()
        backgroundScope.launch { bus.requests.collect { asked += it } }
        val vm = model(
            viewer = DistributionViewer(userId = "me", canPost = true, canDownload = false, ready = true),
            rights = bus,
        )
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.Download(document("d1")))
        runCurrent()

        assertEquals(listOf(RightsRequest("Schedule D.O.D", RightsKind.Download)), asked)
    }

    @Test
    fun `television productions require an episode on a dod upload`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(
            repo = repo,
            viewer = DistributionViewer(userId = "me", canPost = true, isTelevision = true, ready = true),
        )
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("a.pdf", ByteArray(1), replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(name = "Week One", nameFromPick = true))

        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("An episode number is required", vm.state.value.error)
        assertEquals(0, repo.uploads)

        vm.onEvent(DistributionEvent.UploadChanged(episode = "3"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals(1, repo.uploads)
        assertNull(vm.state.value.upload, "the dialog closes on success")
    }

    @Test
    fun `a film production does not ask for an episode`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo = repo)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("a.pdf", ByteArray(1), replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(name = "week one"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()

        assertEquals(1, repo.uploads)
    }

    @Test
    fun `an upload past 25 MB tells the admins, a smaller one does not`() = runTest(dispatcher) {
        val told = mutableListOf<Pair<String, Long>>()
        val vm = model(onOversize = { name, size -> told += name to size })
        vm.start()
        runCurrent()

        vm.onEvent(DistributionEvent.PdfPicked("small.pdf", ByteArray(10), replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(name = "Week One"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertTrue(told.isEmpty(), "10 bytes is under the cap")

        val big = ByteArray(25 * 1024 * 1024 + 1)
        vm.onEvent(DistributionEvent.PdfPicked("big.pdf", big, replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(name = "Week One"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals(listOf("stored.pdf" to big.size.toLong()), told, "named as stored, sized as sent")
    }

    @Test
    fun `folder unread follows the host's flow`() = runTest(dispatcher) {
        val unread = MutableStateFlow(mapOf("Week One" to 2))
        val vm = model(folderUnread = unread)
        vm.start()
        runCurrent()
        assertEquals(2, vm.state.value.folderUnread["Week One"])

        unread.value = emptyMap()
        runCurrent()
        assertTrue(vm.state.value.folderUnread.isEmpty(), "a read clears the card")

        vm.start()
        runCurrent()
        unread.value = mapOf("Week Two" to 1)
        runCurrent()
        assertEquals(mapOf("Week Two" to 1), vm.state.value.folderUnread, "a second start does not stack collectors")
    }

    @Test
    fun `an upload from inside a folder is filed there, name kept verbatim`() = runTest(dispatcher) {
        val vm = model()
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.OpenFolder("Week Two"))
        runCurrent()

        vm.onEvent(DistributionEvent.PdfPicked("a.pdf", ByteArray(1), replaces = null))
        val editor = assertNotNull(vm.state.value.upload)
        assertEquals("Week Two", editor.name)
        assertTrue(editor.nameFromPick, "an existing folder's name is not re-cased")
    }

    private fun document(id: String) = DistDocument(
        id = id,
        createdMs = 1L,
        createdBy = "someone",
        episode = "",
        sceneNumber = "",
        pageNumber = "",
        colour = "#FFFFFF",
        dateMs = 0,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "Week One",
        originalName = "a.pdf",
        deleted = false,
        replaced = false,
        attachment = StoredPdf("k", "a.pdf", "b", "r", "10"),
    )
}
