package com.zillit.desktop.feature.costreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CostReportExporter
import com.zillit.desktop.feature.costreport.domain.CostReportFiles
import com.zillit.desktop.feature.costreport.domain.CostReportRepository
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrWrite
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.EtcVersionLine
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.PostCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotPost
import com.zillit.desktop.feature.costreport.domain.SnapshotTotals
import com.zillit.desktop.feature.costreport.domain.currentWeek
import com.zillit.desktop.feature.costreport.ui.worksheet.CrPhase
import com.zillit.desktop.feature.costreport.ui.worksheet.ProgressStatus
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetEffect
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetEvent
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetModal
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetPane
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wednesday 20 May 2026, 15:00 UTC. */
private const val NOW = 1_779_289_200_000L
private const val DAY = 86_400_000L

private class WorksheetRepository : CostReportRepository {
    val syncFlow = MutableSharedFlow<CostReportSync>(extraBufferCapacity = 4)
    override val syncs: Flow<CostReportSync> get() = syncFlow

    var coa: ZillitResult<List<CoaRow>> = ZillitResult.Success(COA)
    var budgets: List<BudgetVersion> = listOf(
        BudgetVersion("bv-draft", "v2", "Draft v2", "DRAFT"),
        BudgetVersion("bv-live", "v1", "Locked v1", "LIVE"),
    )
    var currencies = CurrencyOptions(
        listOf(CrCurrency("GBP", "Pound", "£", 1.0), CrCurrency("USD", "Dollar", "$", 1.25)),
        "GBP",
    )
    var posted: List<SnapshotHeader> = listOf(
        SnapshotHeader(
            "prior", cadence = SnapshotCadence.Weekly, name = "Wk 20", currency = "GBP",
            periodStartMs = NOW - 16 * DAY, periodEndMs = NOW - 9 * DAY, publishedAtMs = NOW - 8 * DAY,
        ),
    )
    var detailLines: Map<String, List<CostLine>> = mapOf(
        "prior" to listOf(CostLine("1110", variance = 30.0, level = "nominal")),
    )
    var versions: Map<String, List<EtcVersion>> = emptyMap()
    var versionLines: Map<String, List<EtcVersionLine>> = emptyMap()
    var lock = CrLockState(loaded = true)
    var lockResult: ZillitResult<CrWrite<Unit>> = ZillitResult.Success(CrWrite(Unit))
    var postResult: ZillitResult<CrWrite<SnapshotHeader?>> =
        ZillitResult.Success(CrWrite(SnapshotHeader("new", reference = "CR-D-2026-05-20")))

    val liveCalls = mutableListOf<List<Any?>>()
    val snapshotReads = mutableListOf<String>()
    val versionListCalls = mutableListOf<String>()
    val created = mutableListOf<Triple<String, String, List<EtcVersionLine>>>()
    val updated = mutableListOf<Pair<String, List<EtcVersionLine>>>()
    val createdCurrencies = mutableListOf<String?>()
    val locks = mutableListOf<Long>()
    val posts = mutableListOf<SnapshotPost>()

    override suspend fun chartOfAccounts() = coa
    override suspend fun budgets(): ZillitResult<List<BudgetVersion>> = ZillitResult.Success(budgets)
    override suspend fun companies(): ZillitResult<List<CrCompany>> = ZillitResult.Success(
        listOf(CrCompany("co-1", "Prod Co")),
    )
    override suspend fun currencies(): ZillitResult<CurrencyOptions> = ZillitResult.Success(currencies)
    override suspend fun currencyCatalogue(): ZillitResult<List<CrCurrency>> = ZillitResult.Success(emptyList())

    override suspend fun live(
        periodStartMs: Long,
        periodEndMs: Long,
        budgetVersionId: String?,
        companyId: String?,
        currency: String?,
    ): ZillitResult<LiveReport> {
        liveCalls += listOf(periodStartMs, periodEndMs, budgetVersionId, companyId, currency)
        return ZillitResult.Success(LiveReport(LINES, currency = currency))
    }

    override suspend fun snapshots(cadence: SnapshotCadence?): ZillitResult<List<SnapshotHeader>> =
        ZillitResult.Success(posted.filter { cadence == null || it.cadence == cadence })

    override suspend fun snapshot(id: String): ZillitResult<SnapshotDetail> {
        snapshotReads += id
        val header = posted.firstOrNull { it.id == id } ?: return ZillitResult.Failure(ZillitError.Http(404, "missing"))
        return ZillitResult.Success(SnapshotDetail(header, SnapshotTotals(), detailLines[id].orEmpty()))
    }

    override suspend fun accountLineItems(code: String, type: LedgerType?, source: String?, currency: String?) =
        ZillitResult.Success(LedgerResult(code))

    override suspend fun etcVersions(weekEnding: String): ZillitResult<List<EtcVersion>> {
        versionListCalls += weekEnding
        return ZillitResult.Success(versions[weekEnding].orEmpty())
    }

    override suspend fun etcVersion(versionId: String): ZillitResult<List<EtcVersionLine>> =
        ZillitResult.Success(versionLines[versionId].orEmpty())

    override suspend fun createEtcVersion(
        weekEnding: String,
        label: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<String?>> {
        created += Triple(weekEnding, label, lines)
        createdCurrencies += currency
        versions = versions + (weekEnding to (versions[weekEnding].orEmpty() + EtcVersion(
            "ver-new",
            label,
            lines.size,
        )))
        return ZillitResult.Success(CrWrite("ver-new", "version_created"))
    }

    override suspend fun updateEtcVersion(
        versionId: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<Unit>> {
        updated += versionId to lines
        return ZillitResult.Success(CrWrite(Unit))
    }

    override suspend fun lockState(): ZillitResult<CrLockState> = ZillitResult.Success(lock)

    override suspend fun lockPeriod(asOfMs: Long): ZillitResult<CrWrite<Unit>> {
        locks += asOfMs
        return lockResult
    }

    override suspend fun postSnapshot(post: SnapshotPost): ZillitResult<CrWrite<SnapshotHeader?>> {
        posts += post
        return postResult
    }
}

private class RecordingExporter : CostReportExporter {
    val reports = mutableListOf<Pair<ExportFormat, JsonObject>>()
    override suspend fun export(snapshotId: String, format: ExportFormat, body: JsonObject) =
        ZillitResult.Success(byteArrayOf(1))

    override suspend fun exportReport(format: ExportFormat, body: JsonObject): ZillitResult<ByteArray> {
        reports += format to body
        return ZillitResult.Success(byteArrayOf(1, 2))
    }
}

private class SavedFiles : CostReportFiles {
    val names = mutableListOf<String>()
    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        names += fileName
        return ZillitResult.Success(Unit)
    }
}

/**
 * The accountant's worksheet end to end against a fake service: what Compute
 * re-fetches and what it does not, what a typed value does to the overrides,
 * and the exact calls Save, Save Version, Publish, Lock and Export make.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorksheetViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val week = currentWeek(NOW)

    private fun model(
        repository: WorksheetRepository = WorksheetRepository(),
        exporter: RecordingExporter = RecordingExporter(),
        files: SavedFiles = SavedFiles(),
    ) = WorksheetViewModel(
        repository = repository,
        exporter = exporter,
        files = files,
        resolveViewer = { CostReportViewer(userId = "me", ready = true) },
        projectName = { "Sunset Boulevard" },
        companyName = { "Sunset Films Ltd" },
        signedInUser = { "Ada Lovelace · Accountant" },
        resolveUser = { null },
        nowMillis = { NOW },
    )

    private fun TestScope.started(repository: WorksheetRepository = WorksheetRepository()): WorksheetViewModel {
        val vm = model(repository)
        vm.start()
        advanceUntilIdle()
        return vm
    }

    private fun WorksheetViewModel.nominal(code: String): CrNominal =
        state.value.ws.sections.flatMap { it.headers }.flatMap { it.nominals }.first { it.code == code }

    private fun TestScope.notices(vm: WorksheetViewModel): MutableList<WorksheetEffect.Notice> {
        val out = mutableListOf<WorksheetEffect.Notice>()
        backgroundScope.launch(dispatcher) { vm.effects.collect { if (it is WorksheetEffect.Notice) out += it } }
        runCurrent()
        return out
    }

    @Test
    fun `both panes open on this week, the live budget and the project currency`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        val state = vm.state.value
        assertTrue(state.reference.loaded)
        for (pane in listOf(state.ws, state.live)) {
            assertEquals("v1", pane.applied.budgetKey)
            assertEquals("GBP", pane.applied.currency)
            assertEquals(pane.pending, pane.applied)
            assertEquals(week, pane.week)
            assertTrue(pane.loadedOnce)
            assertNull(pane.phase)
            assertTrue(pane.baseline.hasPrior, "the prior weekly post is the VTP baseline")
        }
        assertEquals(2, repository.liveCalls.size, "one fetch per pane")
        assertEquals(listOf(week.startMs, week.endMs, "bv-live", null, "GBP"), repository.liveCalls.first())
        assertEquals(listOf(week.weekEnding, week.weekEnding), repository.versionListCalls)
        assertEquals(1, state.snapshots.rows.size)
    }

    @Test
    fun `a dropdown only marks the pane dirty and Compute is what re-fetches`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        vm.onEvent(WorksheetEvent.SetCompany(WorksheetPane.Worksheet, "co-1"))
        vm.onEvent(WorksheetEvent.SetBudget(WorksheetPane.Worksheet, "v2"))
        advanceUntilIdle()
        assertTrue(vm.state.value.ws.dirty)
        assertFalse(vm.state.value.live.dirty, "the Live CR keeps its own filters")
        assertEquals(2, repository.liveCalls.size)

        vm.onEvent(WorksheetEvent.Compute(WorksheetPane.Worksheet))
        advanceUntilIdle()
        assertFalse(vm.state.value.ws.dirty)
        assertFalse(vm.state.value.ws.computing)
        assertEquals(listOf(week.startMs, week.endMs, "bv-draft", "co-1", "GBP"), repository.liveCalls.last())
        assertEquals(3, repository.liveCalls.size)
    }

    @Test
    fun `a version is applied on top without a re-fetch, zeros skipped and amounts converted`() = runTest(dispatcher) {
        val repository = WorksheetRepository().apply {
            versions = mapOf(currentWeek(NOW).weekEnding to listOf(EtcVersion("ver-1", "End of week", 2)))
            versionLines = mapOf(
                "ver-1" to listOf(
                    EtcVersionLine("1110", etcAmount = 400.0, vtpAmount = 0.0, currency = "USD"),
                    EtcVersionLine("2120", etcAmount = 0.0, vtpAmount = -50.0),
                ),
            )
        }
        val vm = started(repository)
        val notices = notices(vm)
        vm.onEvent(WorksheetEvent.SetVersion(WorksheetPane.Worksheet, "ver-1"))
        vm.onEvent(WorksheetEvent.Compute(WorksheetPane.Worksheet))
        advanceUntilIdle()
        // The effect collector is a background worker, which advanceUntilIdle does not run.
        runCurrent()

        val overrides = vm.state.value.ws.overrides
        assertEquals(mapOf("1110" to 400.0 * 1.0 / 1.25), overrides.etc, "typed in dollars, shown in pounds")
        assertEquals(mapOf("2120" to -50.0), overrides.vtp)
        assertTrue(overrides.efc.isEmpty())
        assertEquals(2, repository.liveCalls.size, "a version alone fetches nothing")
        assertEquals("Loaded End of week · 2 override rows", notices.last().text)

        vm.onEvent(WorksheetEvent.SetVersion(WorksheetPane.Worksheet, null))
        vm.onEvent(WorksheetEvent.Compute(WorksheetPane.Worksheet))
        advanceUntilIdle()
        assertTrue(vm.state.value.ws.overrides.isEmpty, "Latest (unsaved) clears what the version brought")
    }

    @Test
    fun `a currency change re-expresses what was typed`() = runTest(dispatcher) {
        val vm = started()
        vm.onEvent(WorksheetEvent.CommitCell(vm.nominal("1110"), CrColumn.Etc, 1000.0))
        vm.onEvent(WorksheetEvent.SetCurrency(WorksheetPane.Worksheet, "USD"))
        vm.onEvent(WorksheetEvent.Compute(WorksheetPane.Worksheet))
        advanceUntilIdle()
        assertEquals(1250.0, vm.state.value.ws.overrides.etc["1110"])
        assertEquals("USD", vm.state.value.ws.serverCurrency)
    }

    @Test
    fun `typing follows the web's clamps and a locked week takes nothing`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        val notices = notices(vm)
        val writers = vm.nominal("1110") // budget 1200, atd 300

        vm.onEvent(WorksheetEvent.CommitCell(writers, CrColumn.Etc, -10.0))
        runCurrent()
        assertEquals(0.0, vm.state.value.ws.overrides.etc["1110"])
        assertEquals("ETC for 1110 can't be negative. Clamped to £0.", notices.single().text)

        vm.onEvent(WorksheetEvent.CommitCell(writers, CrColumn.Efc, 1000.0))
        assertEquals(700.0, vm.state.value.ws.overrides.etc["1110"], "EFC becomes the ETC that produces it")
        assertTrue(vm.state.value.ws.overrides.efc.isEmpty())

        vm.onEvent(WorksheetEvent.CommitCell(writers, CrColumn.Vtp, -25.0))
        assertEquals(-25.0, vm.state.value.ws.overrides.vtp["1110"])

        repository.lock = CrLockState(lockedDate = "2026-12-31", loaded = true)
        vm.onEvent(WorksheetEvent.RefreshSource)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLocked)
        vm.onEvent(WorksheetEvent.CommitCell(writers, CrColumn.Etc, 5.0))
        assertEquals(700.0, vm.state.value.ws.overrides.etc["1110"], "unchanged inside a locked week")
    }

    @Test
    fun `save needs a picked version and save version files one under this week's Sunday`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        vm.onEvent(WorksheetEvent.CommitCell(vm.nominal("1110"), CrColumn.Etc, 500.0))
        vm.onEvent(WorksheetEvent.Save)
        advanceUntilIdle()
        assertTrue(repository.updated.isEmpty(), "no version picked, nothing to overwrite")

        vm.onEvent(WorksheetEvent.OpenSaveVersion)
        vm.onEvent(WorksheetEvent.SetVersionLabel("   "))
        vm.onEvent(WorksheetEvent.ConfirmSaveVersion)
        advanceUntilIdle()
        val (weekEnding, label, lines) = repository.created.single()
        assertEquals(week.weekEnding, weekEnding)
        assertEquals("Untitled", label)
        assertEquals(listOf(EtcVersionLine("1110", etcAmount = 500.0)), lines)
        assertEquals("GBP", repository.createdCurrencies.single())
        val ws = vm.state.value.ws
        assertEquals("ver-new", ws.pending.versionId)
        assertEquals("ver-new", ws.applied.versionId)
        assertNull(vm.state.value.modal)

        vm.onEvent(WorksheetEvent.CommitCell(vm.nominal("1110"), CrColumn.Vtp, 12.0))
        vm.onEvent(WorksheetEvent.Save)
        advanceUntilIdle()
        assertEquals("ver-new" to listOf(EtcVersionLine("1110", 500.0, 12.0)), repository.updated.single())
    }

    @Test
    fun `publish is seeded from the worksheet and a custom post names its own window`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        vm.onEvent(WorksheetEvent.OpenPublish)
        advanceUntilIdle()
        val form = assertIs<WorksheetModal.Publish>(vm.state.value.modal).form
        assertEquals(PostCadence.Daily, form.cadence)
        assertEquals("v1", form.budgetKey)
        assertEquals("GBP", form.currency)
        assertEquals("2026-05-14", form.startDate)
        assertEquals("2026-05-20", form.endDate)

        val notices = notices(vm)
        vm.onEvent(
            WorksheetEvent.EditPublish(
                form.copy(cadence = PostCadence.Custom, startDate = "2026-05-19", endDate = "2026-05-18"),
            ),
        )
        vm.onEvent(WorksheetEvent.ConfirmPublish)
        advanceUntilIdle()
        runCurrent()
        assertEquals("Period end must be on or after period start", notices.last().text)
        assertTrue(repository.posts.isEmpty())

        vm.onEvent(
            WorksheetEvent.EditPublish(
                form.copy(
                    cadence = PostCadence.Custom,
                    startDate = "2026-05-18",
                    endDate = "2026-05-19",
                    note = " Wk 21 ",
                ),
            ),
        )
        vm.onEvent(WorksheetEvent.ConfirmPublish)
        advanceUntilIdle()
        val post = repository.posts.single()
        assertEquals(PostCadence.Custom, post.cadence)
        assertEquals("bv-live", post.budgetVersionId)
        assertEquals("Wk 21", post.note)
        val start = assertNotNull(post.periodStartMs)
        val end = assertNotNull(post.periodEndMs)
        assertTrue(end > start)
        val progress = assertNotNull(vm.state.value.progress)
        assertEquals(ProgressStatus.Success, progress.status)
        assertEquals("Posted: CR-D-2026-05-20", progress.title)
        assertNull(vm.state.value.posting)
    }

    @Test
    fun `lock moves the boundary through the week end and posts the lock snapshot`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        vm.onEvent(WorksheetEvent.OpenLock)
        vm.onEvent(WorksheetEvent.ConfirmLock)
        advanceUntilIdle()
        assertEquals(listOf(week.endMs), repository.locks)
        val post = repository.posts.single()
        assertEquals(PostCadence.Custom, post.cadence)
        assertEquals("Period Lock — ${week.label}", post.name)
        assertEquals("Auto-generated on period lock (${week.label})", post.note)
        assertEquals(week.startMs, post.periodStartMs)
        assertEquals(week.endMs, post.periodEndMs)
        assertEquals("Locked ${week.label}", vm.state.value.progress?.title)

        repository.lockResult = ZillitResult.Failure(ZillitError.Http(409, "Period already locked"))
        vm.onEvent(WorksheetEvent.OpenLock)
        vm.onEvent(WorksheetEvent.ConfirmLock)
        advanceUntilIdle()
        assertEquals(ProgressStatus.Error, vm.state.value.progress?.status)
        assertEquals("Lock failed", vm.state.value.progress?.title)
        assertEquals(1, repository.posts.size, "no snapshot when the lock itself was refused")
    }

    @Test
    fun `export sends the week with every override and saves the file`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val exporter = RecordingExporter()
        val files = SavedFiles()
        val vm = model(repository, exporter, files)
        vm.start()
        advanceUntilIdle()
        vm.onEvent(WorksheetEvent.CommitCell(vm.nominal("2120"), CrColumn.Etc, 250.0))
        vm.onEvent(WorksheetEvent.OpenExport)
        vm.onEvent(WorksheetEvent.SetExportFormat(ExportFormat.Xlsx))
        vm.onEvent(WorksheetEvent.ConfirmExport)
        advanceUntilIdle()
        val (format, body) = exporter.reports.single()
        assertEquals(ExportFormat.Xlsx, format)
        assertEquals(week.startMs.toString(), body["period_start"]?.jsonPrimitive?.content)
        assertEquals("bv-live", body["budget_version_id"]?.jsonPrimitive?.content)
        assertEquals(week.label, body["period_label"]?.jsonPrimitive?.content)
        assertEquals("Ada Lovelace · Accountant", body["generated_by"]?.jsonPrimitive?.content)
        assertEquals("Sunset Films Ltd", body["company_name"]?.jsonPrimitive?.content)
        assertEquals("250.0", body["etc_overrides"]?.jsonObject?.get("2120")?.jsonPrimitive?.content)
        assertEquals("cost-report-wk${week.number}-${week.weekEnding}.xlsx", files.names.single())
        assertTrue(vm.state.value.exportDone)
    }

    @Test
    fun `a week with its own post in this currency is read from the post`() = runTest(dispatcher) {
        val repository = WorksheetRepository().apply {
            posted = posted + SnapshotHeader(
                "this-week", cadence = SnapshotCadence.Weekly, name = "Wk 21", currency = "GBP",
                periodStartMs = currentWeek(NOW).startMs, periodEndMs = currentWeek(NOW).endMs, generatedAtMs = NOW,
            )
            detailLines = detailLines + ("this-week" to listOf(CostLine("1110", budget = 10.0, atd = 4.0)))
        }
        val vm = started(repository)
        assertTrue(repository.liveCalls.isEmpty(), "nothing computed live")
        val ws = vm.state.value.ws
        assertEquals("this-week", ws.fromSnapshot?.id)
        assertEquals(4.0, vm.nominal("1110").line.atd)

        vm.onEvent(WorksheetEvent.SetCurrency(WorksheetPane.Worksheet, "USD"))
        vm.onEvent(WorksheetEvent.Compute(WorksheetPane.Worksheet))
        assertEquals(CrPhase.Live, vm.state.value.ws.phase, "another currency is computed live")
        advanceUntilIdle()
        assertNull(vm.state.value.ws.fromSnapshot)
        assertEquals(1, repository.liveCalls.size)
    }

    @Test
    fun `the week steps back freely and never past this week`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        vm.onEvent(WorksheetEvent.StepWeek(WorksheetPane.Worksheet, forward = true))
        advanceUntilIdle()
        assertEquals(week, vm.state.value.ws.week)
        assertEquals(2, repository.liveCalls.size)

        vm.onEvent(WorksheetEvent.StepWeek(WorksheetPane.Worksheet, forward = false))
        advanceUntilIdle()
        val previous = week.previous()
        assertEquals(previous, vm.state.value.ws.week)
        assertEquals(week, vm.state.value.live.week, "the Live CR's stepper is its own")
        assertEquals(previous.startMs, repository.liveCalls.last()[0])
        assertEquals(previous.weekEnding, repository.versionListCalls.last())

        vm.onEvent(WorksheetEvent.GoToCurrentWeek(WorksheetPane.Worksheet))
        advanceUntilIdle()
        assertEquals(week, vm.state.value.ws.week)
    }

    @Test
    fun `a source change raises the pill and a lock elsewhere re-pulls without a loader`() = runTest(dispatcher) {
        val repository = WorksheetRepository()
        val vm = started(repository)
        repository.syncFlow.emit(CostReportSync.Source)
        runCurrent()
        assertTrue(vm.state.value.sourceStale)
        assertEquals(2, repository.liveCalls.size)

        repository.syncFlow.emit(CostReportSync.Report)
        runCurrent()
        advanceTimeBy(WorksheetViewModel.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(vm.state.value.ws.silent || vm.state.value.ws.phase == null)
        advanceUntilIdle()
        assertEquals(4, repository.liveCalls.size)

        vm.onEvent(WorksheetEvent.RefreshSource)
        assertFalse(vm.state.value.sourceStale)
    }

    @Test
    fun `no chart or no budget leaves both panes unavailable and fetches nothing`() = runTest(dispatcher) {
        val repository = WorksheetRepository().apply { budgets = emptyList() }
        val vm = started(repository)
        assertTrue(vm.state.value.reference.metaMissing)
        assertEquals("Please upload Budget to continue.", vm.state.value.reference.metaMissingMessage)
        assertTrue(repository.liveCalls.isEmpty())
        assertTrue(vm.state.value.ws.loadedOnce)
    }
}
