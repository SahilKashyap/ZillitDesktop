package com.zillit.desktop.feature.costreport

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
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
    fun `report events reload, source events only flag`() {
        assertEquals(CostReportSync.Report, costReportSyncFor(SocketEventName("cost_report:locked")))
        assertEquals(CostReportSync.Report, costReportSyncFor(SocketEventName("cost_report:posted")))
        assertEquals(CostReportSync.Source, costReportSyncFor(SocketEventName("po:posted")))
        assertEquals(CostReportSync.Source, costReportSyncFor(SocketEventName("timecard:final_approved")))
    }

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            CrSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u2","data":{"po_id":"po-1"}}""",
        )
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another production's frame must drop")
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
        assertTrue(model.state.value.sourceStale, "a feeder tool's post raises the pill")
        assertEquals(2, repository.liveLoads, "and deliberately does not reload")

        model.onEvent(CostReportEvent.Refresh)
        advanceUntilIdle()
        assertFalse(model.state.value.sourceStale, "pressing Refresh answers the pill")
        assertEquals(3, repository.liveLoads)
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
