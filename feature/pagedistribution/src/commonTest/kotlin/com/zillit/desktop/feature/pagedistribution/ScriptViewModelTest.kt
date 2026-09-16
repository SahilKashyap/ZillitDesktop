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
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEffect
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
 * The Script & Pages behaviours the web's `RenderScript.jsx` has: the
 * paperclip on Full Script replaces the script that is up, a page needs a
 * scene number and (on television) an episode, a colour search excludes the
 * scene, the tab chips follow the ledger, and the history lists the
 * replaced scripts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val script = DistributionTool.ScriptDistribution

    private val current = document("s1", createdMs = 10L)
    private val replaced = document("s0", createdMs = 5L, replaced = true)

    private class Repo(val live: List<DistDocument>, val history: List<DistDocument>) : DistributionRepository {
        val uploads = mutableListOf<UploadDraft>()
        val searches = mutableListOf<Triple<String?, String?, PageColour?>>()
        val folderRows = listOf(DistFolder("f1", "12", 10L, 0L, null, "", deleted = false))
        override fun refreshes(tool: DistributionTool): Flow<Unit> = emptyFlow()
        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(if (mode == ListMode.History) history else live)
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
        ): ZillitResult<List<DistDocument>> {
            searches += Triple(sceneNumber, episode, colour)
            return ZillitResult.Success(emptyList())
        }
        override suspend fun document(tab: DistributionTab, id: String, action: ReadAction, mode: ListMode):
            ZillitResult<DistDocument> = ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun upload(tab: DistributionTab, draft: UploadDraft, stored: StoredPdf, nowMs: Long):
            ZillitResult<DistDocument?> {
            uploads += draft
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
        repo: Repo = Repo(live = listOf(current), history = listOf(current, replaced)),
        television: Boolean = false,
        tabUnread: Flow<Map<String, Int>> = emptyFlow(),
    ) = DistributionViewModel(
        tool = script,
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
    fun `the paperclip on Full Script replaces the script that is up`() = runTest(dispatcher) {
        val repo = Repo(live = listOf(current), history = emptyList())
        val vm = model(repo)
        val effects = mutableListOf<DistributionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()
        assertEquals(listOf(current), vm.state.value.documents)

        vm.onEvent(DistributionEvent.PickPdf())
        runCurrent()
        val pick = effects.filterIsInstance<DistributionEffect.PickPdf>().single()
        assertNull(pick.replaces, "the web decides the replace when the file lands, not at the picker")

        vm.onEvent(DistributionEvent.PdfPicked("script_v2.pdf", ByteArray(4), replaces = null))
        runCurrent()
        assertEquals("s1", vm.state.value.upload?.replaces)

        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("s1", repo.uploads.single().replaces)
        assertTrue(effects.any { it is DistributionEffect.Notice && it.text == "Document replaced" })
    }

    @Test
    fun `an empty Full Script list uploads rather than replaces`() = runTest(dispatcher) {
        val repo = Repo(live = emptyList(), history = emptyList())
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("script.pdf", ByteArray(4), replaces = null))
        runCurrent()
        assertNull(vm.state.value.upload?.replaces)
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertNull(repo.uploads.single().replaces)
    }

    @Test
    fun `a page needs a scene number that starts with a digit`() = runTest(dispatcher) {
        val repo = Repo(live = emptyList(), history = emptyList())
        val vm = model(repo)
        vm.start()
        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("page.pdf", ByteArray(4), replaces = null))
        runCurrent()

        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("A scene number is required", vm.state.value.error)
        vm.onEvent(DistributionEvent.DismissError)

        vm.onEvent(DistributionEvent.UploadChanged(sceneNumber = "A12"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("The scene number must start with a digit", vm.state.value.error)
        vm.onEvent(DistributionEvent.DismissError)

        vm.onEvent(DistributionEvent.UploadChanged(sceneNumber = "12A", colour = PageColour.Pink))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertNull(vm.state.value.error)
        val draft = repo.uploads.single()
        assertEquals("12A", draft.sceneNumber)
        assertEquals(PageColour.Pink, draft.colour)
        assertTrue(vm.state.value.upload == null)
    }

    @Test
    fun `television asks for an episode on both tabs`() = runTest(dispatcher) {
        val repo = Repo(live = emptyList(), history = emptyList())
        val vm = model(repo, television = true)
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("script.pdf", ByteArray(4), replaces = null))
        runCurrent()
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("An episode number is required", vm.state.value.error)
        vm.onEvent(DistributionEvent.DismissError)
        vm.onEvent(DistributionEvent.UploadChanged(episode = "3"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("3", repo.uploads.single().episode)

        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()
        vm.onEvent(DistributionEvent.PdfPicked("page.pdf", ByteArray(4), replaces = null))
        vm.onEvent(DistributionEvent.UploadChanged(sceneNumber = "4"))
        vm.onEvent(DistributionEvent.SubmitUpload)
        runCurrent()
        assertEquals("An episode number is required", vm.state.value.error)
    }

    @Test
    fun `a colour search drops the typed scene and clearing reloads the folders`() = runTest(dispatcher) {
        val repo = Repo(live = emptyList(), history = emptyList())
        val vm = model(repo)
        vm.start()
        vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()
        assertEquals(1, vm.state.value.folders.size)

        vm.onEvent(DistributionEvent.SearchChanged(scene = "12"))
        vm.onEvent(DistributionEvent.RunSearch)
        runCurrent()
        assertEquals(Triple("12", null, null), repo.searches.last())
        assertNotNull(vm.state.value.searchResults, "the drawer opens on the first answer")

        vm.onEvent(DistributionEvent.SearchColour(PageColour.Blue))
        runCurrent()
        assertEquals(Triple(null, null, PageColour.Blue), repo.searches.last())
        assertEquals("", vm.state.value.searchScene)

        vm.onEvent(DistributionEvent.ClearSearch)
        runCurrent()
        assertNull(vm.state.value.searchResults)
        assertNull(vm.state.value.searchColour)
        assertEquals(1, vm.state.value.folders.size)
    }

    @Test
    fun `history lists the replaced scripts and switching back lists the live one`() = runTest(dispatcher) {
        val vm = model()
        vm.start()
        runCurrent()
        vm.onEvent(DistributionEvent.ToggleHistory)
        runCurrent()
        assertEquals(ListMode.History, vm.state.value.mode)
        assertEquals(listOf("s0", "s1"), vm.state.value.sortedDocuments.map { it.id })
        vm.onEvent(DistributionEvent.ToggleHistory)
        runCurrent()
        assertEquals(listOf("s1"), vm.state.value.documents.map { it.id })
    }

    @Test
    fun `the tab chips follow the ledger`() = runTest(dispatcher) {
        val unread = MutableStateFlow(mapOf("full_script" to 1, "page" to 4))
        val vm = model(tabUnread = unread)
        vm.start()
        runCurrent()
        assertEquals(mapOf("full_script" to 1, "page" to 4), vm.state.value.tabUnread)
        unread.value = mapOf("page" to 2)
        runCurrent()
        assertEquals(mapOf("page" to 2), vm.state.value.tabUnread)
    }

    private fun document(id: String, createdMs: Long, replaced: Boolean = false) = DistDocument(
        id = id,
        createdMs = createdMs,
        createdBy = "u2",
        episode = "",
        sceneNumber = "",
        pageNumber = "",
        colour = "",
        dateMs = 0,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "",
        originalName = "script.pdf",
        deleted = false,
        replaced = replaced,
        attachment = StoredPdf("k", "script.pdf", "b", "r", "100"),
    )
}
