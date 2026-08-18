package com.zillit.desktop.feature.costreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.LedgerItem
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotTotals
import com.zillit.desktop.feature.costreport.ui.CostReportEffect
import com.zillit.desktop.feature.costreport.ui.CostReportEvent
import com.zillit.desktop.feature.costreport.ui.CostReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wednesday 20 May 2026, 15:00 UTC. */
private const val NOW_MS = 1_779_289_200_000L

private class FakeRepository : CostReportRepository {
    var coa: ZillitResult<List<CoaRow>> = ZillitResult.Success(COA)
    var budgets: ZillitResult<List<BudgetVersion>> = ZillitResult.Success(
        listOf(
            BudgetVersion("bv-draft", "v2", "Draft v2", "DRAFT"),
            BudgetVersion("bv-live", "v1", "Locked v1", "LIVE"),
        ),
    )
    var companies: ZillitResult<List<CrCompany>> = ZillitResult.Success(listOf(CrCompany("co-1", "Prod Co")))
    var currencies: ZillitResult<CurrencyOptions> = ZillitResult.Success(
        CurrencyOptions(listOf(CrCurrency("GBP", "Pound", "£"), CrCurrency("USD", "Dollar", "$")), "GBP"),
    )
    var live: ZillitResult<LiveReport> = ZillitResult.Success(LiveReport(LINES, currency = "GBP"))
    var snapshotList: List<SnapshotHeader> = listOf(
        SnapshotHeader(
            "prev", cadence = SnapshotCadence.Weekly, name = "Wk 20", periodStartMs = NOW_MS - 14 * DAY,
            periodEndMs = NOW_MS - 7 * DAY, publishedAtMs = NOW_MS - 6 * DAY, totalVariance = -10.0, currency = "GBP",
        ),
        SnapshotHeader("older", cadence = SnapshotCadence.Daily, name = "Tue", publishedAtMs = NOW_MS - 20 * DAY,
            totalVariance = -40.0),
    )
    var detailLines: List<CostLine> = listOf(CostLine("1110", budget = 1200.0, atd = 100.0, variance = 30.0))
    var ledger: ZillitResult<LedgerResult> = ZillitResult.Success(
        LedgerResult(
            "1110", "Writers",
            items = listOf(LedgerItem("INV", amount = 60.0), LedgerItem("PO", amount = 30.0)),
        ),
    )

    val liveCalls = mutableListOf<List<Any?>>()
    val snapshotCalls = mutableListOf<String>()
    val ledgerCalls = mutableListOf<List<Any?>>()

    override suspend fun chartOfAccounts() = coa
    override suspend fun budgets() = budgets
    override suspend fun companies() = companies
    override suspend fun currencies() = currencies
    override suspend fun currencyCatalogue(): ZillitResult<List<CrCurrency>> = ZillitResult.Success(emptyList())

    override suspend fun live(
        periodStartMs: Long,
        periodEndMs: Long,
        budgetVersionId: String?,
        companyId: String?,
        currency: String?,
    ): ZillitResult<LiveReport> {
        liveCalls += listOf(periodStartMs, periodEndMs, budgetVersionId, companyId, currency)
        return live
    }

    override suspend fun snapshots(cadence: SnapshotCadence?): ZillitResult<List<SnapshotHeader>> =
        ZillitResult.Success(snapshotList.filter { cadence == null || it.cadence == cadence })

    override suspend fun snapshot(id: String): ZillitResult<SnapshotDetail> {
        snapshotCalls += id
        val header = snapshotList.firstOrNull { it.id == id }
            ?: return ZillitResult.Failure(ZillitError.Http(404, "not found"))
        return ZillitResult.Success(SnapshotDetail(header, SnapshotTotals(budget = 1200.0, atd = 100.0), detailLines))
    }

    override suspend fun accountLineItems(
        code: String,
        type: LedgerType?,
        source: String?,
        currency: String?,
    ): ZillitResult<LedgerResult> {
        ledgerCalls += listOf(code, type, source, currency)
        return ledger
    }

    companion object {
        const val DAY = 86_400_000L
    }
}

private class FakeExporter : CostReportExporter {
    var result: ZillitResult<ByteArray> = ZillitResult.Success(byteArrayOf(1, 2, 3))
    val calls = mutableListOf<Triple<String, ExportFormat, JsonObject>>()
    override suspend fun export(snapshotId: String, format: ExportFormat, body: JsonObject): ZillitResult<ByteArray> {
        calls += Triple(snapshotId, format, body)
        return result
    }
}

private class FakeFiles : CostReportFiles {
    val saved = mutableListOf<Pair<String, Int>>()
    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        saved += fileName to bytes.size
        return ZillitResult.Success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CostReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: FakeRepository = FakeRepository(),
        exporter: FakeExporter = FakeExporter(),
        files: FakeFiles = FakeFiles(),
    ) = CostReportViewModel(
        repository = repository,
        exporter = exporter,
        files = files,
        resolveViewer = { CostReportViewer(userId = "me", canView = true, ready = true) },
        projectName = { "Sunset Boulevard" },
        resolveUser = { id -> if (id == "u1") "Ada Lovelace · Accountant" else null },
        nowMillis = { NOW_MS },
    )

    @Test
    fun startLoadsReferenceThenLiveForTheCurrentWeekWithTheLiveBudget() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()

        val state = model.state.value
        assertNull(state.referenceError)
        assertFalse(state.current.unavailable)
        assertEquals("v1", state.current.budgetKey)
        assertEquals("GBP", state.current.currencyCode)
        assertEquals("£", state.current.symbol)
        assertTrue(state.current.loaded)
        assertNull(state.current.phase)
        assertEquals(3, state.current.sections.size)
        assertEquals("Sunset Boulevard", state.projectName)

        val call = repository.liveCalls.single()
        assertEquals("bv-live", call[2])
        assertNull(call[3])
        assertEquals("GBP", call[4])
        val week = assertNotNull(state.current.week)
        assertEquals(call[0], week.startMs)
        assertEquals(call[1], week.endMs)
        assertTrue(week.startMs <= NOW_MS && NOW_MS <= week.endMs)

        // The prior weekly snapshot fed the VTP baseline.
        assertTrue(state.current.hasPrior)
        assertEquals(30.0, state.current.priorVariance["1110"])
        assertEquals(listOf("prev"), repository.snapshotCalls)
    }

    @Test
    fun emptyChartOrBudgetsMeansUnavailableAndNoLiveCall() = runTest(dispatcher) {
        val repository = FakeRepository().apply { budgets = ZillitResult.Success(emptyList()) }
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        assertTrue(model.state.value.current.unavailable)
        assertTrue(repository.liveCalls.isEmpty())
    }

    @Test
    fun referenceFailureSurfacesAndRetryReloads() = runTest(dispatcher) {
        val repository = FakeRepository().apply { coa = ZillitResult.Failure(ZillitError.Http(500, "boom")) }
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        assertEquals("boom", model.state.value.referenceError)
        repository.coa = ZillitResult.Success(COA)
        model.onEvent(CostReportEvent.Refresh)
        advanceUntilIdle()
        assertNull(model.state.value.referenceError)
        assertTrue(model.state.value.current.loaded)
    }

    @Test
    fun changingAFilterReloadsLiveWithTheNewQuery() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(CostReportEvent.SelectCompany("co-1"))
        advanceUntilIdle()
        model.onEvent(CostReportEvent.SelectCurrency("USD"))
        advanceUntilIdle()
        model.onEvent(CostReportEvent.SelectBudget("v2"))
        advanceUntilIdle()
        assertEquals(4, repository.liveCalls.size)
        assertEquals("co-1", repository.liveCalls[1][3])
        assertEquals("USD", repository.liveCalls[2][4])
        assertEquals("bv-draft", repository.liveCalls[3][2])
        model.onEvent(CostReportEvent.SelectCompany(null))
        advanceUntilIdle()
        assertNull(repository.liveCalls.last()[3])
    }

    @Test
    fun liveFailureKeepsTheFiltersAndShowsTheMessage() = runTest(dispatcher) {
        val repository = FakeRepository().apply { live = ZillitResult.Failure(ZillitError.Http(200, "cr_live_failed")) }
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        val current = model.state.value.current
        assertEquals("cr_live_failed", current.error)
        assertFalse(current.loaded)
        assertNull(current.phase)
        assertEquals("v1", current.budgetKey)
    }

    @Test
    fun postedTabLoadsOnceAndOpensASnapshot() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(CostReportEvent.SelectTab(CostReportTab.Posted))
        advanceUntilIdle()
        val posted = model.state.value.posted
        assertTrue(posted.loadedOnce)
        assertEquals(listOf("prev", "older"), posted.rows.map { it.id })
        assertEquals(30.0, posted.shown.first().delta)

        model.onEvent(CostReportEvent.OpenSnapshot(posted.rows.first()))
        advanceUntilIdle()
        val view = assertNotNull(model.state.value.snapshot)
        assertFalse(view.loading)
        assertNotNull(view.detail)
        assertEquals("£", view.symbol)
        assertTrue(view.sections.isNotEmpty())

        model.onEvent(CostReportEvent.CloseSnapshot)
        assertNull(model.state.value.snapshot)
    }

    @Test
    fun exportPostsTheDisplayBodyAndSavesUnderTheReference() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            snapshotList = listOf(snapshotList.first().copy(reference = "CR-W-2026-W20", publishedBy = "u1"))
        }
        val exporter = FakeExporter()
        val files = FakeFiles()
        val model = viewModel(repository, exporter, files)
        model.start()
        advanceUntilIdle()
        model.onEvent(CostReportEvent.SelectTab(CostReportTab.Posted))
        advanceUntilIdle()
        model.onEvent(CostReportEvent.OpenSnapshot(model.state.value.posted.rows.first()))
        advanceUntilIdle()

        val notices = mutableListOf<String>()
        val collector = launch { model.effects.collect { if (it is CostReportEffect.Notice) notices += it.text } }
        model.onEvent(CostReportEvent.Export(ExportFormat.Xlsx))
        advanceUntilIdle()

        val (id, format, body) = exporter.calls.single()
        assertEquals("prev", id)
        assertEquals(ExportFormat.Xlsx, format)
        assertEquals("Sunset Boulevard", body["project_name"]?.jsonPrimitive?.content)
        assertEquals("Ada Lovelace · Accountant", body["generated_by"]?.jsonPrimitive?.content)
        assertEquals("CR-W-2026-W20.xlsx" to 3, files.saved.single())
        assertEquals(listOf("Excel downloaded"), notices)
        assertNull(model.state.value.snapshot?.exporting)
        collector.cancel()
    }

    @Test
    fun ledgerOpensForARealCodeWithTypeAndSourceFromTheColumn() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.start()
        advanceUntilIdle()
        val sections = model.state.value.current.sections
        val direct = sections.flatMap { it.headers }.flatMap { it.nominals }.first { it.code == "2100.direct" }
        model.onEvent(CostReportEvent.OpenLedger(direct, CrColumn.Card))
        advanceUntilIdle()
        val ledger = assertNotNull(model.state.value.ledger)
        assertFalse(ledger.loading)
        assertEquals(2, ledger.result?.items?.size)
        assertEquals(listOf("2100", LedgerType.Commits, "card", "GBP"), repository.ledgerCalls.single())

        model.onEvent(CostReportEvent.CloseLedger)
        val bucket = sections.first { it.isUncoded }.headers.single().nominals.first { it.isBucket }
        val notice = async { model.effects.first() }
        model.onEvent(CostReportEvent.OpenLedger(bucket, null))
        advanceUntilIdle()
        assertNull(model.state.value.ledger)
        assertTrue(notice.await() is CostReportEffect.Notice)
        assertEquals(1, repository.ledgerCalls.size)
    }
}
