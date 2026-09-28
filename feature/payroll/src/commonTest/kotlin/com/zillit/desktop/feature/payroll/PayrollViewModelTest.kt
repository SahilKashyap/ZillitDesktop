package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.TrackingNode
import com.zillit.desktop.feature.payroll.domain.TrackingSet
import com.zillit.desktop.feature.payroll.ui.HistoryEvent
import com.zillit.desktop.feature.payroll.ui.JournalEvent
import com.zillit.desktop.feature.payroll.ui.journalRows
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

    // -- journal ledger ---------------------------------------------------------------------

    /** A week with pay on it: the shared `card` has no lines, so it has no journal rows. */
    private fun paidWeek(id: String) = card(id, TimecardStatus.Locked).copy(
        days = listOf(
            TimecardDay(
                date = lastMonday,
                dayType = "SWD",
                rates = listOf(
                    PayLine(identifier = "basic", label = "Basic", rateAmount = 400.0),
                    PayLine(identifier = "overtime", label = "OT", rateAmount = 60.0),
                ),
            ),
        ),
    )

    /**
     * Splitting a line, coding one allocation elsewhere, and the two ending up
     * on the wire as the parent's children — the whole path the button starts.
     */
    @Test
    fun `a split line posts its allocations, each with its own coding`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(run = listOf(paidWeek("a")))
        repository.reference = JournalReference(
            trackingSets = listOf(TrackingSet("dept", "Department", listOf(TrackingNode("CAM", "Camera")))),
            assetTags = listOf("Recharge"),
        )
        val (model, _) = model(repository, viewer = controller, route = "/film-tools/payroll/run")
        val row = model.state.value.journalRows().first { !it.isTax }

        model.onEvent(JournalEvent.Split(row.id))
        advanceUntilIdle()
        val splits = model.state.value.run.journal.edits.getValue(row.id).splits.orEmpty()
        assertEquals(2, splits.size)

        model.onEvent(JournalEvent.SplitLayers(row.id, splits[0].id, mapOf("dept" to "CAM")))
        model.onEvent(JournalEvent.SplitTags(row.id, splits[1].id, listOf("Recharge")))
        model.onEvent(JournalEvent.Save)
        advanceUntilIdle()

        val lines = repository.submissions.single().timecards.getValue("a")
        val children = lines.filter { it.splitParentId == row.id }
        assertEquals(2, children.size)
        assertEquals(mapOf("dept" to "CAM"), children[0].trackingCodes)
        assertEquals(listOf("Recharge"), children[1].tags)
    }

    /** A tax line exists only once it is asked for, and goes away when it is not. */
    @Test
    fun `adding and removing a tax line shows and hides it`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(run = listOf(paidWeek("a")))
        val (model, _) = model(repository, viewer = controller, route = "/film-tools/payroll/run")
        assertTrue(model.state.value.journalRows().none { it.isTax })

        model.onEvent(JournalEvent.AddTax("a"))
        advanceUntilIdle()
        val tax = model.state.value.journalRows().single { it.isTax }
        // Its base is the sum of the timecard's own lines, and its money
        // follows the picked rate until someone types over it.
        assertEquals(model.state.value.journalRows().filterNot { it.isTax }.sumOf { it.amount ?: 0.0 }, tax.taxBase)

        model.onEvent(JournalEvent.TaxRate(tax.id, "20"))
        advanceUntilIdle()
        val edit = model.state.value.run.journal.edits[tax.id]
        assertEquals(20.0, edit?.taxRate)

        model.onEvent(JournalEvent.RemoveTax("a"))
        advanceUntilIdle()
        assertTrue(model.state.value.journalRows().none { it.isTax })
        // Its edits go with it, so a line that returns starts clean.
        assertTrue(model.state.value.run.journal.edits[tax.id] == null)
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
