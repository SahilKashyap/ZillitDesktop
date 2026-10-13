package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.Fixtures.closedPeriod
import com.zillit.desktop.feature.bankrec.Fixtures.invoice
import com.zillit.desktop.feature.bankrec.Fixtures.suggested
import com.zillit.desktop.feature.bankrec.Fixtures.unmatched
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.ServerCounts
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.WorkspaceState
import com.zillit.desktop.feature.bankrec.ui.workspaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unreconcile mode, the Manual Match search, Quick Add on every kind of line,
 * and the tab counts — what the workspace does that nothing on screen would
 * say was wrong if it did not.
 *
 * A match taken back is as invisible as one made wrongly, and a fraud line
 * returns to review rather than to unmatched, so the gap between asking and
 * confirming is the thing these pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankRecUnreconcileFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val matchedA = unmatched.copy(id = "t3", status = TxnStatus.Matched, matchedInvoiceIds = listOf("inv-1"))
    private val matchedB = unmatched.copy(id = "t4", status = TxnStatus.Matched)

    private fun repoWith(vararg lines: BankTransaction) =
        Fixtures.repo().apply {
            workspaceData = WorkspaceData(
                transactions = lines.toList(),
                ledger = listOf(invoice.copy(transactionIds = listOf("t3"))),
            )
        }

    private fun TestScope.opened(repo: FakeBankRecRepository): BankRecViewModel {
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()
        return model
    }

    private fun BankRecViewModel.view() = state.value.workspaceView()

    /** Nothing to take back means nothing to offer — until a line is matched, and then through the last undo. */
    @Test
    fun `the toggle shows only while a line is matched or the mode is on`() = runTest(dispatcher) {
        val none = opened(repoWith(unmatched, suggested))
        assertFalse(none.view().canToggleUnreconcile)

        val some = opened(repoWith(unmatched, matchedA))
        assertTrue(some.view().canToggleUnreconcile)

        // The mode keeps the toggle, so Done is reachable after the last match is undone.
        some.onEvent(BankRecEvent.SetUnreconcile(true))
        assertTrue(some.state.value.workspace.unreconcile)
        assertTrue(some.view().canToggleUnreconcile)
    }

    /** A view over the panels, not a filter: the user's own filter is exactly where it was. */
    @Test
    fun `the mode shows matched rows only and leaves the filter alone`() = runTest(dispatcher) {
        val model = opened(repoWith(unmatched, matchedA, matchedB))
        model.onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Unmatched))
        assertEquals(listOf("t1"), model.view().visibleBank.map { it.id })

        model.onEvent(BankRecEvent.SetUnreconcile(true))

        assertTrue(model.view().unreconcileMode)
        assertEquals(listOf("t3", "t4"), model.view().visibleBank.map { it.id })
        assertEquals(listOf("led-1"), model.view().visibleLedger.map { it.id })
        assertEquals(WorkspaceFilter.Unmatched, model.state.value.workspace.filter)

        model.onEvent(BankRecEvent.SetUnreconcile(false))

        assertEquals(listOf("t1"), model.view().visibleBank.map { it.id })
    }

    /** One line goes to the single route, behind a confirmation, and the toast is the server's message only. */
    @Test
    fun `one line is unreconciled through the single route after a confirmation`() = runTest(dispatcher) {
        val repo = repoWith(unmatched, matchedA)
        val model = opened(repo)
        model.onEvent(BankRecEvent.SetUnreconcile(true))
        val loads = repo.workspaceLoads

        model.onEvent(BankRecEvent.AskUnmatch(listOf("t3")))
        runCurrent()
        assertTrue(repo.unmatches.isEmpty())
        assertEquals(listOf("t3"), model.state.value.workspace.unmatch?.ids)

        model.onEvent(BankRecEvent.ConfirmUnmatch)
        runCurrent()

        assertEquals(listOf("t3"), repo.unmatches)
        assertTrue(repo.bulkUnmatches.isEmpty())
        assertNull(model.state.value.workspace.unmatch)
        assertNotNull(model.state.value.notice)
        // Re-read, not patched, and the mode stays on until Done.
        assertTrue(repo.workspaceLoads > loads)
        assertTrue(model.state.value.workspace.unreconcile)
    }

    /** The bulk route names no message yet, so there is no toast. */
    @Test
    fun `ticked lines go to the bulk route in one request`() = runTest(dispatcher) {
        val repo = repoWith(unmatched, matchedA, matchedB)
        val model = opened(repo)
        model.onEvent(BankRecEvent.SetUnreconcile(true))

        model.onEvent(BankRecEvent.ToggleUnmatchTick("t3"))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t4"))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t4"))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t4"))
        assertEquals(listOf("t3", "t4"), model.view().tickedRows.map { it.id })

        model.onEvent(BankRecEvent.AskUnmatch(model.view().tickedRows.map { it.id }))
        model.onEvent(BankRecEvent.ConfirmUnmatch)
        runCurrent()

        assertEquals(listOf(listOf("t3", "t4")), repo.bulkUnmatches)
        assertTrue(repo.unmatches.isEmpty())
        assertTrue(model.state.value.workspace.unmatchTicked.isEmpty())
        assertNull(model.state.value.notice)
    }

    @Test
    fun `dismissing the confirmation unreconciles nothing`() = runTest(dispatcher) {
        val repo = repoWith(matchedA)
        val model = opened(repo)
        model.onEvent(BankRecEvent.SetUnreconcile(true))

        model.onEvent(BankRecEvent.AskUnmatch(listOf("t3")))
        model.onEvent(BankRecEvent.DismissUnmatch)
        runCurrent()

        assertNull(model.state.value.workspace.unmatch)
        assertTrue(repo.unmatches.isEmpty() && repo.bulkUnmatches.isEmpty())
    }

    /** Only matched lines can be taken back, and only inside the mode. */
    @Test
    fun `an unmatched line or a request outside the mode is ignored`() = runTest(dispatcher) {
        val model = opened(repoWith(unmatched, matchedA))

        model.onEvent(BankRecEvent.AskUnmatch(listOf("t3")))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t3"))
        assertNull(model.state.value.workspace.unmatch)
        assertTrue(model.state.value.workspace.unmatchTicked.isEmpty())

        model.onEvent(BankRecEvent.SetUnreconcile(true))
        model.onEvent(BankRecEvent.AskUnmatch(listOf("t1")))
        assertNull(model.state.value.workspace.unmatch)

        model.onEvent(BankRecEvent.AskUnmatch(listOf("t1", "t3")))
        assertEquals(listOf("t3"), model.state.value.workspace.unmatch?.ids)
    }

    /** A tick whose line is no longer matched must not come back ticked if it is matched again. */
    @Test
    fun `a tick is dropped once its line is no longer matched`() = runTest(dispatcher) {
        val repo = repoWith(matchedA, matchedB)
        val model = opened(repo)
        model.onEvent(BankRecEvent.SetUnreconcile(true))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t3"))
        model.onEvent(BankRecEvent.ToggleUnmatchTick("t4"))

        // t3 is unreconciled elsewhere.
        repo.workspaceData = repo.workspaceData.copy(
            transactions = listOf(matchedA.copy(status = TxnStatus.Unmatched), matchedB),
        )
        model.onEvent(BankRecEvent.Refresh)
        runCurrent()

        assertEquals(setOf("t4"), model.state.value.workspace.unmatchTicked)
    }

    /** Sign-off cannot be undone, so the mode closes for good and so does anything open in it. */
    @Test
    fun `signing off ends the mode and any open confirmation`() = runTest(dispatcher) {
        val model = opened(repoWith(unmatched, matchedA))
        model.onEvent(BankRecEvent.SetUnreconcile(true))
        model.onEvent(BankRecEvent.AskUnmatch(listOf("t3")))
        model.onEvent(BankRecEvent.OpenSignOff)
        model.onEvent(BankRecEvent.EditSignOffNote("signing over an unmatched line"))

        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()

        val ws = model.state.value.workspace
        assertFalse(ws.unreconcile)
        assertNull(ws.unmatch)
        assertTrue(ws.unmatchTicked.isEmpty())
    }

    /** A signed-off period is never unreconciled, however the mode got switched on. */
    @Test
    fun `a signed-off period turns the mode off and disables the toggle`() {
        val state = BankRecUiState(
            periods = listOf(closedPeriod),
            workspace = WorkspaceState(
                periodId = closedPeriod.id,
                transactions = listOf(matchedA),
                unreconcile = true,
                unmatchTicked = setOf("t3"),
            ),
        )

        val view = state.workspaceView()

        assertTrue(view.periodComplete)
        assertFalse(view.unreconcileMode)
        assertTrue(view.canToggleUnreconcile) // shown (disabled, with its reason), because a line is matched
    }

    /** Tab counts are the backend's; the row tally only stands in. */
    @Test
    fun `the summary counts come from the backend when it sends them`() = runTest(dispatcher) {
        val repo = repoWith(unmatched, matchedA)
        repo.workspaceData = repo.workspaceData.copy(counts = ServerCounts(unmatched = 9, fraud = 2))
        val model = opened(repo)

        val counts = model.view().counts
        assertEquals(9, counts.unmatched)
        assertEquals(2, counts.fraud)
        assertEquals(2, counts.all) // not sent, so tallied
        assertTrue(model.view().hasIssues)
    }

    /** Unmatched ledger entries used to add to Unmatched, so unmatched invoices raised the sign-off warning. */
    @Test
    fun `unmatched ledger entries no longer add to the unmatched count`() = runTest(dispatcher) {
        val repo = repoWith(matchedA)
        repo.workspaceData = repo.workspaceData.copy(
            ledger = listOf(invoice.copy(transactionIds = listOf("t3")), Fixtures.suggestedInvoice),
            counts = ServerCounts(unmatched = 0, suggested = 0, fraud = 0),
        )
        val model = opened(repo)

        assertEquals(0, model.view().counts.unmatched)
    }

    @Test
    fun `an FX line shows under the pill of its own status`() = runTest(dispatcher) {
        val fx = unmatched.copy(id = "t6", currency = "EUR", fx = FxDetail(foreignAmount = 900.0, currency = "EUR"))
        val model = opened(repoWith(unmatched, fx))

        model.onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Unmatched))
        assertEquals(listOf("t1", "t6"), model.view().visibleBank.map { it.id })

        model.onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Fx))
        assertEquals(listOf("t6"), model.view().visibleBank.map { it.id })
    }

    // -- Quick Add --------------------------------------------------------

    private fun TestScope.quickAdd(repo: FakeBankRecRepository, line: BankTransaction) {
        val model = opened(repo)
        model.onEvent(BankRecEvent.OpenQuickAdd(line.id))
        model.onEvent(BankRecEvent.AddAndMatch)
        runCurrent()
    }

    /** A Suggested line has no exception: it is posted by its own transaction id on the same route. */
    @Test
    fun `a suggested line is quick-added by its transaction id`() = runTest(dispatcher) {
        val repo = repoWith(unmatched, suggested)
        quickAdd(repo, suggested)

        val (id, form, fromWorkspace) = repo.quickAdds.single()
        assertEquals("t2", id)
        assertEquals("400.00", form.amount)
        assertTrue(fromWorkspace)
    }

    @Test
    fun `a line with an exception is still posted through it`() = runTest(dispatcher) {
        val withException = unmatched.copy(exceptionId = "e1", exceptionType = "bank_charge")
        val repo = repoWith(withException)
        quickAdd(repo, withException)

        assertEquals("e1", repo.quickAdds.single().first)
    }

    /** The backend raises an exception beside each fraud alert; posting it accepts the alert. */
    @Test
    fun `a fraud line is quick-added through its fraud exception`() = runTest(dispatcher) {
        val flagged = unmatched.copy(
            id = "t7",
            fraudType = FraudType.UnregisteredPayee,
            fraudStatus = FraudStatus.Active,
            exceptionId = "e-fraud",
            exceptionType = "fraud",
        )
        val repo = repoWith(flagged)
        quickAdd(repo, flagged)

        assertEquals("e-fraud", repo.quickAdds.single().first)
    }

    /** It needs only a picked line and an amount. */
    @Test
    fun `quick add with no amount posts nothing`() = runTest(dispatcher) {
        val repo = repoWith(suggested.copy(debit = 0.0))
        quickAdd(repo, suggested)

        assertTrue(repo.quickAdds.isEmpty())
    }

    // -- Manual Match search ----------------------------------------------

    /** Set on open only, so Back from the confirm step keeps what was typed. */
    @Test
    fun `the manual match query survives Back and resets on the next open`() = runTest(dispatcher) {
        val model = opened(repoWith(unmatched))

        model.onEvent(BankRecEvent.OpenManualMatch("t1"))
        model.onEvent(BankRecEvent.SearchManualMatch("pana"))
        model.onEvent(BankRecEvent.PickManualMatch(invoice.id))
        model.onEvent(BankRecEvent.PickManualMatch(null))
        assertEquals("pana", model.state.value.workspace.manualMatchQuery)

        model.onEvent(BankRecEvent.CloseManualMatch)
        model.onEvent(BankRecEvent.OpenManualMatch("t1"))
        assertEquals("", model.state.value.workspace.manualMatchQuery)
    }
}
