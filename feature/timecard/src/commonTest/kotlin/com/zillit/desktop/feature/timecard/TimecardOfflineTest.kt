package com.zillit.desktop.feature.timecard

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.timecard.data.QueuedTimecardSave
import com.zillit.desktop.feature.timecard.data.QueuedTimecardSubmit
import com.zillit.desktop.feature.timecard.data.TIMECARD_SAVE_KIND
import com.zillit.desktop.feature.timecard.data.TIMECARD_SUBMIT_KIND
import com.zillit.desktop.feature.timecard.data.TimecardSaveHandler
import com.zillit.desktop.feature.timecard.data.TimecardSubmitHandler
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardConfirmAction
import com.zillit.desktop.feature.timecard.ui.TimecardDestination
import com.zillit.desktop.feature.timecard.ui.TimecardEvent
import com.zillit.desktop.feature.timecard.ui.TimecardPrompt
import com.zillit.desktop.feature.timecard.ui.TimecardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Filing a week with no network: it queues, shows as waiting, its submit
 * rides behind it, the grid survives a restart, and the handlers never
 * create a second week for the same Monday.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimecardOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)
    private val scope = SyncScope("user-1", "p1")
    private val outbox = InMemoryOutboxStore()
    private val drafts = InMemoryDraftStore()
    private var now = 2_000_000L
    private val monday = 1_754_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.support(): OfflineSupport {
        val engine = SyncEngine(
            store = outbox,
            handlers = SyncHandlerRegistry(),
            online = online,
            currentScope = { scope },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op-${now++}" },
        )
        engine.start()
        return OfflineSupport(engine, drafts, online) { scope }
    }

    private val crew = TimecardViewer(userId = "user-1", departmentIdentifier = "camera", designationIdentifier = null)

    private fun TestScope.viewModel(repository: FakeWeeks, support: OfflineSupport?) =
        TimecardViewModel(repository, { crew }, { monday }, support, nowMillis = { now }).also {
            it.start()
            runCurrent()
        }

    private fun worked(hours: Double = 10.0) = TimecardDay(
        date = monday, dayType = DayType.Worked, callTime = "07:00", wrapTime = "19:00",
        workedHours = hours, note = null,
    )

    private fun week(id: String = "tc-1", updatedAt: Long? = null, status: TimecardStatus = TimecardStatus.Draft) =
        Timecard(
            id = id, userId = "user-1", crewName = "Ada", departmentId = null, designation = null,
            weekStarting = monday, weekNumber = 3, status = status, currency = "GBP",
            days = listOf(worked()), notes = null, queryNote = null, rejectionReason = null,
            lastApprovedBy = null, paidAt = null, updatedAt = updatedAt,
        )

    /** Opens the editor on a fresh week and enters Monday's hours. */
    private fun TestScope.openAndFill(vm: TimecardViewModel) {
        vm.onEvent(TimecardEvent.Open(TimecardDestination.Edit))
        runCurrent()
        vm.onEvent(TimecardEvent.EditDay(0, worked(9.5)))
        runCurrent()
    }

    @Test
    fun `offline, saving a week queues it and shows it as waiting to send`() = runTest(dispatcher) {
        val repository = FakeWeeks()
        val vm = viewModel(repository, support())
        online.value = false
        runCurrent()

        openAndFill(vm)
        vm.onEvent(TimecardEvent.SaveDraft)
        runCurrent()

        assertEquals(0, repository.saves, "nothing is sent while offline")
        val queued = outbox.all().single()
        assertEquals(TIMECARD_SAVE_KIND, queued.kind)
        assertEquals("timecard:$monday", queued.groupKey)
        assertEquals(TimecardViewModel.QUEUED_SAVE_NOTICE, vm.state.value.notice)

        vm.onEvent(TimecardEvent.Open(TimecardDestination.MyWeeks))
        runCurrent()
        val row = vm.state.value.rows.first()
        assertTrue(row.isLocalOnly)
        assertEquals(9.5, row.workedHours)
        assertEquals(monday, row.weekStarting)
    }

    @Test
    fun `a submit queued behind an unsent week depends on its save`() = runTest(dispatcher) {
        val vm = viewModel(FakeWeeks(), support())
        online.value = false
        runCurrent()
        openAndFill(vm)
        vm.onEvent(TimecardEvent.SaveDraft)
        runCurrent()
        vm.onEvent(TimecardEvent.Open(TimecardDestination.MyWeeks))
        runCurrent()
        val local = vm.state.value.rows.first()

        vm.onEvent(TimecardEvent.Select(local.id))
        vm.onEvent(TimecardEvent.Ask(TimecardPrompt.Confirm(TimecardConfirmAction.Submit, local.id, "t", "m")))
        vm.onEvent(TimecardEvent.ConfirmPrompt)
        runCurrent()

        val save = outbox.all().first { it.kind == TIMECARD_SAVE_KIND }
        val submit = outbox.all().first { it.kind == TIMECARD_SUBMIT_KIND }
        assertEquals(save.id, submit.dependsOn)
        assertEquals(save.groupKey, submit.groupKey)
        assertEquals(TimecardViewModel.QUEUED_SUBMIT_NOTICE, vm.state.value.notice)
        assertTrue(vm.state.value.rows.first().local?.submitQueued == true)
    }

    @Test
    fun `online, a save that never left the machine is queued rather than lost`() = runTest(dispatcher) {
        val repository = FakeWeeks(saveAnswer = ZillitResult.Failure(ZillitError.NoConnection()))
        val vm = viewModel(repository, support())

        openAndFill(vm)
        vm.onEvent(TimecardEvent.SaveDraft)
        runCurrent()

        assertEquals(1, repository.saves, "it was tried")
        assertEquals(1, outbox.all().size, "and then queued")
    }

    @Test
    fun `the week is kept on disk as it is typed and comes back on reopen`() = runTest(dispatcher) {
        val support = support()
        val first = viewModel(FakeWeeks(), support)
        openAndFill(first)
        advanceTimeBy(1_000)
        runCurrent()

        val second = viewModel(FakeWeeks(), support)
        second.onEvent(TimecardEvent.Open(TimecardDestination.Edit))
        runCurrent()
        assertEquals(9.5, second.state.value.draft?.days?.first()?.workedHours)

        // But not over a server copy saved after the local one.
        now += 10_000
        val third = viewModel(FakeWeeks(mine = listOf(week(updatedAt = now))), support)
        third.onEvent(TimecardEvent.Open(TimecardDestination.Edit))
        runCurrent()
        assertEquals(10.0, third.state.value.draft?.days?.first()?.workedHours, "the server's newer week wins")
    }

    @Test
    fun `a list that cannot be fetched is shown from its saved copy, dated`() = runTest(dispatcher) {
        val support = support()
        viewModel(FakeWeeks(mine = listOf(week("tc-7"))), support)
        val fetchedAt = now

        now += 60_000
        val cut = viewModel(FakeWeeks(mineAnswer = ZillitResult.Failure(ZillitError.NoConnection())), support)
        assertEquals(listOf("tc-7"), cut.state.value.timecards.map { it.id })
        assertEquals(fetchedAt, cut.state.value.staleSince)
        assertNull(cut.state.value.error)
        assertNotNull(cut.state.value.viewer.metadata, "metadata is served from its saved copy too")
    }

    // -- handlers ------------------------------------------------------------

    private val context = object : SyncContext {
        var dependency: String? = null
        override suspend fun dependencyResult(operation: SyncOperation): String? = dependency
        override suspend fun updatePayload(operation: SyncOperation, payload: String) = Unit
        override fun nowMillis(): Long = now
    }

    private fun saveOp(timecardId: String? = null) = SyncOperation(
        id = "save-1", scope = scope, kind = TIMECARD_SAVE_KIND, label = "l",
        payload = json.encodeToString(
            QueuedTimecardSave.serializer(),
            QueuedTimecardSave(TimecardDraft(timecardId, monday, listOf(worked(9.5))), "user-1", now),
        ),
        nextAttemptAt = 0, createdAt = now, updatedAt = now,
    )

    private fun submitOp(timecardId: String? = null) = SyncOperation(
        id = "submit-1", scope = scope, kind = TIMECARD_SUBMIT_KIND, label = "l",
        payload = json.encodeToString(QueuedTimecardSubmit.serializer(), QueuedTimecardSubmit(timecardId, monday)),
        dependsOn = "save-1", nextAttemptAt = 0, createdAt = now, updatedAt = now,
    )

    @Test
    fun `the save handler patches a week the server already has for that Monday`() = runTest(dispatcher) {
        val repository = FakeWeeks(mine = listOf(week("tc-3")))

        val outcome = TimecardSaveHandler(repository, json).execute(saveOp(), context)

        assertEquals<SyncOutcome>(SyncOutcome.Done(result = "tc-3"), outcome)
        assertEquals(listOf<String?>("tc-3"), repository.savedIds, "PATCHed the existing week, no POST")
    }

    @Test
    fun `the save handler creates a week the server does not have and adopts its id`() = runTest(dispatcher) {
        val repository = FakeWeeks()
        repository.onSave = { draft -> if (draft.timecardId == null) repository.mine = listOf(week("tc-new")) }

        val outcome = TimecardSaveHandler(repository, json).execute(saveOp(), context)

        assertEquals(listOf<String?>(null), repository.savedIds, "POSTed once")
        assertEquals<SyncOutcome>(SyncOutcome.Done(result = "tc-new"), outcome)
    }

    @Test
    fun `the submit handler uses the id its save left behind, and parks without one`() = runTest(dispatcher) {
        val repository = FakeWeeks()
        context.dependency = "tc-new"
        val sent = TimecardSubmitHandler(repository, json).execute(submitOp(), context)
        assertEquals<SyncOutcome>(SyncOutcome.Done(result = "tc-new"), sent)
        assertEquals(listOf("tc-new"), repository.submitted)

        context.dependency = null
        val parked = TimecardSubmitHandler(repository, json).execute(submitOp(), context)
        assertIs<SyncOutcome.Failed>(parked)
        assertEquals(TimecardSubmitHandler.NOT_SAVED_MESSAGE, parked.error.userMessage)
    }

    @Test
    fun `the handlers retry on the network`() = runTest(dispatcher) {
        val cut = FakeWeeks(mineAnswer = ZillitResult.Failure(ZillitError.NoConnection()))
        assertIs<SyncOutcome.RetryLater>(TimecardSaveHandler(cut, json).execute(saveOp(), context))
        assertIs<SyncOutcome.RetryLater>(TimecardSubmitHandler(cut, json).execute(submitOp(), context))
        assertEquals(SyncState.Pending, saveOp().state)
    }

    // -- fixture -------------------------------------------------------------

    @Suppress("TooManyFunctions") // One override per server operation.
    private class FakeWeeks(
        var mine: List<Timecard> = emptyList(),
        private val mineAnswer: ZillitResult<List<Timecard>>? = null,
        private val saveAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit),
    ) : TimecardRepository {
        var saves = 0
        val savedIds = mutableListOf<String?>()
        val submitted = mutableListOf<String>()
        var onSave: (TimecardDraft) -> Unit = {}

        override suspend fun metadata(): ZillitResult<TimecardMetadata> = when (mineAnswer) {
            is ZillitResult.Failure -> mineAnswer
            else -> ZillitResult.Success(TimecardMetadata())
        }

        override suspend fun myTimecards(): ZillitResult<List<Timecard>> = mineAnswer ?: ZillitResult.Success(mine)

        override suspend fun save(draft: TimecardDraft): ZillitResult<Unit> {
            saves++
            savedIds += draft.timecardId
            if (saveAnswer is ZillitResult.Success) onSave(draft)
            return saveAnswer
        }

        override suspend fun submit(id: String): ZillitResult<Unit> {
            submitted += id
            return ZillitResult.Success(Unit)
        }

        override suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>> = ZillitResult.Success(emptyList())
        override suspend fun approvalQueue(): ZillitResult<List<Timecard>> = ZillitResult.Success(emptyList())
        override suspend fun payrollProcessing(weekStarting: String): ZillitResult<List<Timecard>> = myTimecards()
        override suspend fun outstanding(): ZillitResult<List<Timecard>> = ZillitResult.Success(emptyList())
        override suspend fun timecard(id: String): ZillitResult<Timecard> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun approve(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun reject(id: String, reason: String): ZillitResult<Unit> = unsupported()
        override suspend fun query(id: String, note: String): ZillitResult<Unit> = unsupported()
        override suspend fun finalApprove(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun lock(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun markPaid(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun addDeduction(
            id: String,
            label: String,
            amount: Double,
            reason: String?,
        ): ZillitResult<Unit> = unsupported()
        override suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit> = unsupported()
        override suspend fun approveAll(ids: List<String>): ZillitResult<Unit> = unsupported()
        override suspend fun lockAll(ids: List<String>): ZillitResult<Unit> = unsupported()

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
