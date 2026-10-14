package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRead
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a list counts as seen — iOS's reads, not its bugs.
 *
 * A single list is read whole when it is opened and again whenever unread
 * lands on it while somebody is looking (iOS's tab-badge sinks); a folder
 * is read on opening, on leaving (iOS's D.O.D folder) and when unread lands
 * in it while watched. Nothing is read for a window nobody has on screen —
 * the view model outlives it, and the socket keeps reloading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistributionReadTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo : DistributionRepository {
        val pulses = MutableSharedFlow<Unit>()
        override fun refreshes(tool: DistributionTool): Flow<Unit> = pulses
        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun folders(tab: DistributionTab, mode: ListMode) = ZillitResult.Success(
            listOf(
                DistFolder("f1", "Week One", 10L, 0L, null, "", deleted = false),
                DistFolder("f2", "Week Two", 20L, 0L, null, "", deleted = false),
            ),
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
        ) = ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun document(tab: DistributionTab, id: String, action: ReadAction, mode: ListMode):
            ZillitResult<DistDocument> = ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun upload(tab: DistributionTab, draft: UploadDraft, stored: StoredPdf, nowMs: Long) =
            ZillitResult.Success(null)
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

    private class Harness(tool: DistributionTool, mode: ListMode = ListMode.Live) {
        val repo = Repo()
        val reads = mutableListOf<DistributionRead>()
        val tabUnread = MutableStateFlow<Map<String, Int>>(emptyMap())
        val folderUnread = MutableStateFlow<Map<String, Int>>(emptyMap())
        val vm = DistributionViewModel(
            tool = tool,
            repository = repo,
            transfer = Transfer,
            resolveViewer = { DistributionViewer(userId = "me", canPost = true, canDownload = true, ready = true) },
            nowMillis = { 1_800_000_000_000L },
            onListViewed = { reads += it },
            folderUnread = folderUnread,
            tabUnread = tabUnread,
            initialMode = mode,
        )
    }

    private fun DistributionTool.tab(key: String) = tabs.single { it.key == key }

    @Test
    fun `unread landing on the single list on show is read only while somebody is looking`() = runTest(dispatcher) {
        val tool = DistributionTool.ScheduleDistribution
        val full = DistributionRead.WholeTab(tool.tab("full_script"))
        val h = Harness(tool)
        h.vm.start()
        runCurrent()
        assertEquals(listOf<DistributionRead>(full), h.reads, "opening the tool reads the list on show")

        h.tabUnread.value = mapOf("full_script" to 1)
        runCurrent()
        assertEquals(1, h.reads.size, "a closed or background window sees nothing")

        h.vm.onEvent(DistributionEvent.Watching(true))
        runCurrent()
        assertEquals(listOf<DistributionRead>(full, full), h.reads, "coming back to the window reads what landed")

        h.tabUnread.value = emptyMap()
        runCurrent()
        h.tabUnread.value = mapOf("full_script" to 1)
        runCurrent()
        assertEquals(3, h.reads.size, "a schedule landing while watched is read as it lands")

        h.tabUnread.value = emptyMap()
        h.vm.onEvent(DistributionEvent.SelectTab("page"))
        runCurrent()
        h.tabUnread.value = mapOf("page" to 3)
        runCurrent()
        assertEquals(3, h.reads.size, "a folder grid is not a list anybody has read")

        h.vm.onEvent(DistributionEvent.SelectTab("oneline"))
        runCurrent()
        assertEquals(DistributionRead.WholeTab(tool.tab("oneline")), h.reads.last(), "a tab switch reads the new list")
    }

    @Test
    fun `a socket reload reads nothing for a window nobody is looking at`() = runTest(dispatcher) {
        val tool = DistributionTool.ScriptDistribution
        val h = Harness(tool)
        h.tabUnread.value = mapOf("full_script" to 2)
        h.vm.start()
        runCurrent()
        assertEquals(1, h.reads.size)

        h.repo.pulses.emit(Unit)
        runCurrent()
        assertEquals(1, h.reads.size, "the reload is the socket's, not the person's")

        h.vm.onEvent(DistributionEvent.Watching(true))
        runCurrent()
        h.repo.pulses.emit(Unit)
        runCurrent()
        assertEquals(3, h.reads.size, "watched: back in front, then the reload with unread still standing")
        assertTrue(h.reads.all { it == DistributionRead.WholeTab(tool.tab("full_script")) })
    }

    @Test
    fun `a dod folder is read on opening, while watched as unread lands, and on leaving`() = runTest(dispatcher) {
        val tool = DistributionTool.ScheduleDod
        val weekOne = DistributionRead.Folder(tool.tabs.single(), "Week One")
        val h = Harness(tool)
        h.vm.start()
        runCurrent()
        assertTrue(h.reads.isEmpty(), "the folder grid reads nothing")

        h.vm.onEvent(DistributionEvent.OpenFolder("Week One"))
        runCurrent()
        assertEquals(listOf<DistributionRead>(weekOne), h.reads)

        h.folderUnread.value = mapOf("Week One" to 1)
        runCurrent()
        assertEquals(1, h.reads.size, "not watched")

        h.vm.onEvent(DistributionEvent.Watching(true))
        runCurrent()
        assertEquals(2, h.reads.size, "back in front")

        h.repo.pulses.emit(Unit)
        runCurrent()
        assertEquals(3, h.reads.size, "a reload of the open folder re-reads what is still unread in it")

        h.folderUnread.value = mapOf("Week Two" to 2)
        runCurrent()
        assertEquals(3, h.reads.size, "another folder's unread is not this one's")

        h.vm.onEvent(DistributionEvent.CloseFolder)
        runCurrent()
        assertEquals(listOf<DistributionRead>(weekOne, weekOne, weekOne, weekOne), h.reads, "leaving reads it again")

        h.folderUnread.value = mapOf("Week Two" to 3)
        runCurrent()
        assertEquals(4, h.reads.size, "a card on the grid waits to be opened")
    }

    @Test
    fun `history and the cabinet never read`() = runTest(dispatcher) {
        val live = Harness(DistributionTool.ScriptDistribution)
        live.vm.start()
        runCurrent()
        live.vm.onEvent(DistributionEvent.Watching(true))
        live.vm.onEvent(DistributionEvent.ToggleHistory)
        runCurrent()
        live.tabUnread.value = mapOf("full_script" to 1)
        runCurrent()
        assertEquals(1, live.reads.size, "only the live list's first load")

        val cabinet = Harness(DistributionTool.ScriptDistribution, mode = ListMode.Cabinet)
        cabinet.tabUnread.value = mapOf("full_script" to 1)
        cabinet.vm.start()
        cabinet.vm.onEvent(DistributionEvent.Watching(true))
        runCurrent()
        assertTrue(cabinet.reads.isEmpty(), "a cabinet look is not a read receipt")
    }
}
