package com.zillit.desktop.feature.costreport

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.costreport.data.CR_SOURCE_SYNC_EVENTS
import com.zillit.desktop.feature.costreport.data.CrSyncEnvelope
import com.zillit.desktop.feature.costreport.data.costReportSyncFor
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.ui.CostReportEvent
import com.zillit.desktop.feature.costreport.ui.CostReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The socket's cost-report announcements: a lock or post elsewhere silently
 * re-pulls the worksheet, while a feeder tool's approve/post only raises the
 * stale pill — the web's deliberate no-auto-refresh split.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CostReportSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the wire ----------------------------------------------------------

    @Test
    fun `report events reload at once, the one source event waits out its quiet window`() {
        assertEquals(CostReportSync.Report, costReportSyncFor(SocketEventName("cost_report:locked")))
        assertEquals(CostReportSync.Report, costReportSyncFor(SocketEventName("cost_report:posted")))
        // One event now, not the twelve feeder events it replaced (web PR #6543).
        assertEquals(CostReportSync.Source, costReportSyncFor(SocketEventName("cost_report:refresh")))
        assertEquals(listOf(SocketEventName("cost_report:refresh")), CR_SOURCE_SYNC_EVENTS)
    }

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            CrSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u2","data":{"po_id":"po-1"}}""",
        )
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another project's frame must drop")
        assertTrue(CrSyncEnvelope().inProject("p1"), "an unnamed frame passes rather than starving the screen")
    }

    // -- the view model ----------------------------------------------------

    @Test
    fun `a lock elsewhere re-pulls silently, a source change only raises the pill`() = runTest(dispatcher) {
        val events = MutableSharedFlow<CostReportSync>()
        val repository = SyncFakeRepository(events)
        val model = CostReportViewModel(
            repository = repository,
            exporter = NoExporter,
            files = NoFiles,
            resolveViewer = { CostReportViewer(userId = "me", canView = true, ready = true) },
            projectName = { "Sunset Boulevard" },
            resolveUser = { null },
            nowMillis = { NOW },
        )
        model.start()
        advanceUntilIdle()
        assertEquals(1, repository.liveLoads, "start computes the live report once")

        repeat(2) { events.emit(CostReportSync.Report) }
        runCurrent()
        assertEquals(1, repository.liveLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(CostReportViewModel.SYNC_DEBOUNCE_MILLIS)
        advanceUntilIdle()
        assertEquals(2, repository.liveLoads, "two frames collapse into one silent re-pull")
        assertFalse(model.state.value.sourceStale)

        events.emit(CostReportSync.Source)
        runCurrent()
        assertTrue(model.state.value.sourceStale, "a feeder tool's post raises the pill at once")
        assertEquals(2, repository.liveLoads, "and nothing is fetched yet")

        // A second event inside the window restarts it, so a burst settles
        // into one refresh rather than one per event.
        advanceTimeBy(CostReportViewModel.QUIET_MILLIS / 2)
        events.emit(CostReportSync.Source)
        runCurrent()
        advanceTimeBy(CostReportViewModel.QUIET_MILLIS / 2)
        runCurrent()
        assertEquals(2, repository.liveLoads, "the window was restarted, not run down")

        // runCurrent, not advanceUntilIdle: the latter would run the chip's
        // own window down too, and the chip is what is being checked.
        advanceTimeBy(CostReportViewModel.QUIET_MILLIS)
        runCurrent()
        assertFalse(model.state.value.sourceStale, "the report refreshed itself")
        assertEquals(3, repository.liveLoads, "once, for the whole burst")
        assertTrue(model.state.value.autoRefreshed, "and says so")

        advanceTimeBy(CostReportViewModel.CHIP_MILLIS)
        runCurrent()
        assertFalse(model.state.value.autoRefreshed, "the chip does not stay up")
    }

    @Test
    fun `a burst that never stops still refreshes, fifteen seconds after its first event`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<CostReportSync>()
            val repository = SyncFakeRepository(events)
            // A clock that follows the virtual one, or the cap cannot be
            // measured at all: with a fixed `nowMillis` the max-wait
            // arithmetic is a no-op and every wait is the full quiet window.
            val model = CostReportViewModel(
                repository = repository,
                exporter = NoExporter,
                files = NoFiles,
                resolveViewer = { CostReportViewer(userId = "me", canView = true, ready = true) },
                projectName = { "Sunset Boulevard" },
                resolveUser = { null },
                nowMillis = { NOW + dispatcher.scheduler.currentTime },
            )
            model.start()
            advanceUntilIdle()
            val before = repository.liveLoads

            // An event every two seconds: the quiet window never runs down,
            // so without the cap this would wait for ever.
            val step = CostReportViewModel.QUIET_MILLIS - 1_500
            repeat(12) {
                events.emit(CostReportSync.Source)
                runCurrent()
                advanceTimeBy(step)
                runCurrent()
            }

            assertEquals(
                before + 1,
                repository.liveLoads,
                "the cap fired once, ${CostReportViewModel.MAX_WAIT_MILLIS}ms after the first event",
            )
        }

    @Test
    fun `pressing the pill refreshes now, with no chip`() = runTest(dispatcher) {
        val events = MutableSharedFlow<CostReportSync>()
        val repository = SyncFakeRepository(events)
        val model = CostReportViewModel(
            repository = repository,
            exporter = NoExporter,
            files = NoFiles,
            resolveViewer = { CostReportViewer(userId = "me", canView = true, ready = true) },
            projectName = { "Sunset Boulevard" },
            resolveUser = { null },
            nowMillis = { NOW },
        )
        model.start()
        advanceUntilIdle()

        events.emit(CostReportSync.Source)
        runCurrent()
        model.onEvent(CostReportEvent.Refresh)
        advanceUntilIdle()
        assertFalse(model.state.value.sourceStale, "pressing Refresh answers the pill")
        assertEquals(2, repository.liveLoads)
        assertFalse(model.state.value.autoRefreshed, "a read the person asked for says nothing")

        // The waiting refresh was cancelled by that read, not left to fire.
        advanceTimeBy(CostReportViewModel.MAX_WAIT_MILLIS)
        advanceUntilIdle()
        assertEquals(2, repository.liveLoads)
    }

    private object NoExporter : CostReportExporter {
        override suspend fun export(
            snapshotId: String,
            format: ExportFormat,
            body: JsonObject,
        ): ZillitResult<ByteArray> = ZillitResult.Success(ByteArray(0))
    }

    private object NoFiles : CostReportFiles {
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private class SyncFakeRepository(
        override val syncs: Flow<CostReportSync>,
    ) : CostReportRepository {
        var liveLoads = 0

        override suspend fun chartOfAccounts(): ZillitResult<List<CoaRow>> = ZillitResult.Success(COA)
        override suspend fun budgets(): ZillitResult<List<BudgetVersion>> =
            ZillitResult.Success(listOf(BudgetVersion("bv-live", "v1", "Locked v1", "LIVE")))
        override suspend fun companies(): ZillitResult<List<CrCompany>> =
            ZillitResult.Success(listOf(CrCompany("co-1", "Prod Co")))
        override suspend fun currencies(): ZillitResult<CurrencyOptions> =
            ZillitResult.Success(CurrencyOptions(listOf(CrCurrency("GBP", "Pound", "£")), "GBP"))
        override suspend fun currencyCatalogue(): ZillitResult<List<CrCurrency>> = ZillitResult.Success(emptyList())

        override suspend fun live(
            periodStartMs: Long,
            periodEndMs: Long,
            budgetVersionId: String?,
            companyId: String?,
            currency: String?,
        ): ZillitResult<LiveReport> {
            liveLoads++
            return ZillitResult.Success(LiveReport(LINES, currency = "GBP"))
        }

        override suspend fun snapshots(cadence: SnapshotCadence?): ZillitResult<List<SnapshotHeader>> =
            ZillitResult.Success(emptyList())
        override suspend fun snapshot(id: String): ZillitResult<SnapshotDetail> =
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Http(404, "not in this test"))
        override suspend fun accountLineItems(
            code: String,
            type: LedgerType?,
            source: String?,
            currency: String?,
        ): ZillitResult<LedgerResult> = ZillitResult.Success(LedgerResult("", ""))
    }

    private companion object {
        /** Wednesday 20 May 2026, 15:00 UTC. */
        const val NOW = 1_779_289_200_000L
    }
}
