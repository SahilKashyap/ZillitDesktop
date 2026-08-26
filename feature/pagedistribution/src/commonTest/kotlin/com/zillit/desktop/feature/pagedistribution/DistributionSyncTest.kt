package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.pagedistribution.data.distributionSyncEvents
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
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * A distribution wire event refetches the visible list once — the web
 * pages' refetch handlers (`RenderScript.jsx:1415-1521`,
 * `ScheduleDistributionMain.jsx:1636-1717`, `DoD.jsx:1114-1154`) as a
 * targeted reload, with the event set keyed per tool.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistributionSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the per-tool event sets -------------------------------------------

    @Test
    fun `each tool subscribes its own wire family`() {
        val script = distributionSyncEvents(DistributionTool.ScriptDistribution).map { it.value }
        val schedule = distributionSyncEvents(DistributionTool.ScheduleDistribution).map { it.value }
        val dod = distributionSyncEvents(DistributionTool.ScheduleDod).map { it.value }

        assertTrue("script:uploaded" in script)
        assertTrue("page:replaced" in script && "page:replaced" in schedule)
        assertTrue("oneline:uploaded" in schedule, "One Line rides the schedule tool's page")
        assertTrue("dod:page:moved" in dod)
        assertTrue(script.none { it.startsWith("schedule:") || it.startsWith("dod:") })
        assertTrue(dod.none { it.startsWith("script:") || it.startsWith("schedule:") })
    }

    // -- the view model ----------------------------------------------------

    private class FakeRepo(private val pulses: Flow<Unit>) : DistributionRepository {
        var listLoads = 0
        override fun refreshes(tool: DistributionTool): Flow<Unit> = pulses
        override suspend fun documents(tab: DistributionTab, mode: ListMode):
            ZillitResult<List<DistDocument>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun folders(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(emptyList<DistFolder>())
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
            ZillitResult<DistDocument> = ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Unknown("unused"),
        )
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

    private object NoTransfer : DistributionTransfer {
        override suspend fun upload(storagePath: String, fileName: String, bytes: ByteArray) =
            ZillitResult.Success(StoredPdf("k", "n", "b", "r", ""))
        override suspend fun fetch(stored: StoredPdf) = ZillitResult.Success(ByteArray(0))
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<PdfPageImage>())
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    @Test
    fun `a wire pulse re-runs the visible list load once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(pulses = events)
        val model = DistributionViewModel(
            tool = DistributionTool.ScheduleDistribution,
            repository = repo,
            transfer = NoTransfer,
            resolveViewer = { DistributionViewer(canView = true, canPost = true, ready = true) },
            nowMillis = { 0L },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.listLoads, "start loads the first tab once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.listLoads, "the pulse re-runs exactly one load")

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(4, repo.listLoads, "a second start must not stack a second collector")
    }
}
