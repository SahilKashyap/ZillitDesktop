package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.HistoryEvent
import com.zillit.desktop.feature.payroll.ui.PAYROLL_ENTRY_SETUP_ROUTE
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEffect
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollTile
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import com.zillit.desktop.feature.payroll.ui.ProcessingEvent
import com.zillit.desktop.feature.payroll.ui.RunEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * The payroll view model's rules — every gate re-checked in the handler, not
 * only on screen, and the web's behaviour where the earlier port differed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayrollViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Wednesday 2026-05-06 12:00 UTC. */
    private val now = 1_778_068_800_000L
    private val currentMonday = PayPeriod.startOf(now, PayPeriod.MONDAY)
    private val lastMonday = currentMonday - PayPeriod.WEEK_MILLIS

    private fun card(id: String, status: TimecardStatus) =
        PayrollTimecard(id = id, userId = "user-$id", status = status, weekStarting = lastMonday, currency = "GBP")

    private fun TestScope.model(
        repository: FakePayrollRepository,
        viewer: PayrollViewer = accountant,
        route: String = "/film-tools/payroll",
    ): Pair<PayrollViewModel, MutableList<PayrollEffect>> {
        // The approvers list is the server's word, so an approver in these
        // tests is one the metadata names — the view model reads it from there.
        if (viewer.onApproverList) repository.metadata = repository.metadata.copy(isFinalApprover = true)
        val model = PayrollViewModel(repository, { viewer.copy(onApproverList = false) }, now = { now })
        val effects = mutableListOf<PayrollEffect>()
        // Subscribed at once: a shared flow with no subscriber drops what it emits.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        model.start()
        model.onEvent(PayrollEvent.Route(route))
        advanceUntilIdle()
        return model to effects
    }

    // -- landing and routing --------------------------------------------------------------

    @Test
    fun `a tile navigates, and Entry Setup goes to the hub's payroll setup`() = runTest(dispatcher) {
        val (model, effects) = model(FakePayrollRepository())
        model.onEvent(PayrollEvent.OpenTile(PayrollTile.Run))
        advanceUntilIdle()
        assertEquals(PayrollEffect.Navigate("/film-tools/payroll/run"), effects.last())
        model.onEvent(PayrollEvent.OpenTile(PayrollTile.EntrySetup))
        advanceUntilIdle()
        assertEquals(PayrollEffect.Navigate(PAYROLL_ENTRY_SETUP_ROUTE), effects.last())
    }

    @Test
    fun `a crew member routed to an accountant screen lands on the landing, and a tile does nothing`() =
        runTest(dispatcher) {
            val (model, effects) = model(FakePayrollRepository(), viewer = crew, route = "/film-tools/payroll/run")
            assertEquals(PayrollDestination.Landing, model.state.value.destination)
            model.onEvent(PayrollEvent.OpenTile(PayrollTile.Run))
            advanceUntilIdle()
            assertTrue(effects.none { it is PayrollEffect.Navigate })
        }

    // -- history ----------------------------------------------------------------------------

    @Test
    fun `history opens on last week, on the production's own start day`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(metadata = PayrollMetadata(payPeriodStartDay = 3))
        val (model, _) = model(repository, route = "/film-tools/payroll/accountant-payroll")
        val wednesday = PayPeriod.startOf(now, 3)
        assertEquals(wednesday - PayPeriod.WEEK_MILLIS, model.state.value.history.weekStarting)
        assertEquals(listOf(wednesday - PayPeriod.WEEK_MILLIS), repository.paidWeeks)
    }

    @Test
    fun `history walks back freely but never past the current week`() = runTest(dispatcher) {
        val repository = FakePayrollRepository()
        val (model, _) = model(repository, route = "/film-tools/payroll/accountant-payroll")
        repeat(3) { model.onEvent(HistoryEvent.ShiftWeek(-1)) }
        advanceUntilIdle()
        assertEquals(lastMonday - 3 * PayPeriod.WEEK_MILLIS, model.state.value.history.weekStarting)
        model.onEvent(HistoryEvent.CurrentWeek)
        advanceUntilIdle()
        model.onEvent(HistoryEvent.ShiftWeek(1))
        advanceUntilIdle()
        assertEquals(currentMonday, model.state.value.history.weekStarting)
    }

    /** The web's `postIds`: nothing ticked posts every paid row. */
    @Test
    fun `posting with nothing ticked posts every ready row`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(
            paid = listOf(
                card("a", TimecardStatus.Paid),
                card("b", TimecardStatus.Paid),
                card("c", TimecardStatus.Posted),
            ),
        )
        val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/accountant-payroll")
        model.onEvent(HistoryEvent.OpenPost)
        model.onEvent(HistoryEvent.EditPost(bankId = "bank-1"))
        model.onEvent(HistoryEvent.ConfirmPost)
        advanceUntilIdle()
        val (ids, bank, date) = repository.posted.single()
        assertEquals(setOf("a", "b"), ids.toSet())
        assertEquals("bank-1", bank)
        assertEquals(PayPeriod.startOf(now, 3), date, "defaults to today, midnight UTC")
    }

    @Test
    fun `a ticked selection posts only the ticked paid rows, and a posted row cannot be ticked`() =
        runTest(dispatcher) {
            val repository = FakePayrollRepository(
                paid = listOf(
                    card("a", TimecardStatus.Paid),
                    card("b", TimecardStatus.Paid),
                    card("c", TimecardStatus.Posted),
                ),
            )
            val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/accountant-payroll")
            model.onEvent(HistoryEvent.ToggleCheck("b"))
            model.onEvent(HistoryEvent.ToggleCheck("c"))
            assertEquals(setOf("b"), model.state.value.history.checked)
            model.onEvent(HistoryEvent.OpenPost)
            model.onEvent(HistoryEvent.EditPost(bankId = "bank-1"))
            model.onEvent(HistoryEvent.ConfirmPost)
            advanceUntilIdle()
            assertEquals(listOf("b"), repository.posted.single().first)
        }

    /** The date may not fall on or before the cost-report lock; the dialog says so and the ticks stay. */
    @Test
    fun `a post dated inside the locked period is refused before it is sent`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(paid = listOf(card("a", TimecardStatus.Paid)), locked = "2026-05-10")
        val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/accountant-payroll")
        model.onEvent(HistoryEvent.ToggleCheck("a"))
        model.onEvent(HistoryEvent.OpenPost)
        assertEquals("2026-05-11", model.state.value.history.post?.effectiveDate, "defaults to the first open day")
        model.onEvent(HistoryEvent.EditPost(bankId = "bank-1", effectiveDate = "2026-05-09"))
        model.onEvent(HistoryEvent.ConfirmPost)
        advanceUntilIdle()
        assertTrue(repository.posted.isEmpty())
        assertNotNull(model.state.value.history.post?.error)
        assertEquals(setOf("a"), model.state.value.history.checked)
    }

    @Test
    fun `a refused post keeps the dialog and the ticks`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(paid = listOf(card("a", TimecardStatus.Paid)))
        repository.postResult = ZillitResult.Failure(ZillitError.Unknown("nope"))
        val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/accountant-payroll")
        model.onEvent(HistoryEvent.ToggleCheck("a"))
        model.onEvent(HistoryEvent.OpenPost)
        model.onEvent(HistoryEvent.EditPost(bankId = "bank-1"))
        model.onEvent(HistoryEvent.ConfirmPost)
        advanceUntilIdle()
        assertNotNull(model.state.value.history.post?.error)
        assertEquals(setOf("a"), model.state.value.history.checked)
    }

    @Test
    fun `an accountant who is not the approver cannot post from history`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(paid = listOf(card("a", TimecardStatus.Paid)))
        val (model, _) = model(repository, viewer = accountant, route = "/film-tools/payroll/accountant-payroll")
        model.onEvent(HistoryEvent.OpenPost)
        assertNull(model.state.value.history.post)
    }

    // -- run --------------------------------------------------------------------------------

    @Test
    fun `mark paid moves the ticked locked and unpaid rows only`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(
            run = listOf(
                card("l", TimecardStatus.Locked),
                card("u", TimecardStatus.Unpaid),
                card("a", TimecardStatus.Approved),
            ),
        )
        val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/run")
        listOf("l", "u", "a").forEach { model.onEvent(RunEvent.ToggleRow(it)) }
        model.onEvent(RunEvent.Ask(RunAction.MarkPaid))
        model.onEvent(RunEvent.Confirm)
        advanceUntilIdle()
        assertEquals(listOf("markPaidBatch:l,u"), repository.calls)
    }

    @Test
    fun `final approve and lock is two calls, the lock taking what was approved and what was already approved`() =
        runTest(dispatcher) {
            val repository = FakePayrollRepository(
                run = listOf(card("a", TimecardStatus.Approved), card("f", TimecardStatus.FinalApproved)),
            )
            val (model, _) = model(repository, viewer = controller, route = "/film-tools/payroll/run")
            model.onEvent(RunEvent.ToggleRow("a"))
            model.onEvent(RunEvent.ToggleRow("f"))
            model.onEvent(RunEvent.Ask(RunAction.FinalApproveAndLock))
            model.onEvent(RunEvent.Confirm)
            advanceUntilIdle()
            assertEquals(listOf("finalApprove:a", "lock:a,f"), repository.calls)
        }

    @Test
    fun `an approval the viewer's roles do not offer is never asked for`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(run = listOf(card("f", TimecardStatus.FinalApproved)))
        val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/run")
        model.onEvent(RunEvent.ToggleRow("f"))
        model.onEvent(RunEvent.Ask(RunAction.Lock))
        assertNull(model.state.value.run.confirm, "locking is the payroll accountant's")
    }

    @Test
    fun `a row event the row would not show is dropped`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(run = listOf(card("p", TimecardStatus.Paid)))
        val (model, _) = model(repository, viewer = accountant, route = "/film-tools/payroll/run")
        model.onEvent(RunEvent.Row("p", RowAction.MarkUnpaid))
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty(), "mark unpaid is the approver's")
    }

    // -- processing ---------------------------------------------------------------------------

    /** From the table, paying is the final approver's (`PayrollGridModule.jsx` 1973). */
    @Test
    fun `mark paid from the processing table is the approver's, on locked or unpaid weeks`() =
        runTest(dispatcher) {
            val repository = FakePayrollRepository(
                processing = listOf(card("l", TimecardStatus.Locked), card("a", TimecardStatus.Approved)),
            )
            val (plain, _) = model(repository, viewer = accountant, route = "/film-tools/payroll/processing")
            plain.onEvent(ProcessingEvent.MarkPaid("l"))
            advanceUntilIdle()
            assertTrue(repository.calls.isEmpty())

            val (model, _) = model(repository, viewer = approver, route = "/film-tools/payroll/processing")
            model.onEvent(ProcessingEvent.MarkPaid("a"))
            advanceUntilIdle()
            assertTrue(repository.calls.isEmpty(), "an approved week is not payable")
            model.onEvent(ProcessingEvent.MarkPaid("l"))
            advanceUntilIdle()
            assertEquals(listOf("markPaid:l"), repository.calls)
        }

    @Test
    fun `the drawer pays a locked week for anyone`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(processing = listOf(card("l", TimecardStatus.Locked)))
        val (model, _) = model(repository, viewer = accountant, route = "/film-tools/payroll/processing")
        model.onEvent(ProcessingEvent.DrawerMarkPaid("l"))
        advanceUntilIdle()
        assertEquals(listOf("markPaid:l"), repository.calls)
    }

    @Test
    fun `override is offered only on a week still in the approval chain`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(processing = listOf(card("s", TimecardStatus.Submitted)))
        val (model, _) = model(repository, viewer = accountant, route = "/film-tools/payroll/processing")
        model.onEvent(PayrollEvent.AskOverride(card("x", TimecardStatus.Locked)))
        assertNull(model.state.value.override)
        model.onEvent(PayrollEvent.AskOverride(card("s", TimecardStatus.Submitted)))
        model.onEvent(PayrollEvent.EditOverrideReason("HOD away"))
        model.onEvent(PayrollEvent.ConfirmOverride)
        advanceUntilIdle()
        assertEquals(listOf("override:s:HOD away"), repository.calls)
    }

    // -- socket -------------------------------------------------------------------------------

    @Test
    fun `a burst of payroll events is one reload of the open screen`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repository = FakePayrollRepository(refreshes = events)
        model(repository, route = "/film-tools/payroll/accountant-payroll")
        assertEquals(1, repository.paidWeeks.size)
        repeat(3) { events.emit(Unit) }
        runCurrent()
        advanceTimeBy(PayrollViewModel.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(2, repository.paidWeeks.size, "three frames collapse into one reload of the week on screen")
    }
}
