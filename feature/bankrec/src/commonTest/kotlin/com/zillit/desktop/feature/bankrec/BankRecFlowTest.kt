package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.StatementUploader
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.WorkspaceFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What this module refuses to do without being asked twice.
 *
 * Matching, signing off, deleting a period, escalating an alert and posting a
 * whole period's variances are all invisible or irreversible afterwards, so
 * each is proposed and then confirmed. These tests are mostly about the gap
 * between the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankRecFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val openPeriod = BankPeriod(
        id = "p1",
        periodMillis = 1_743_465_600_000,
        bankAccountId = "b1",
        status = PeriodStatus.InProgress,
        totalTxns = 3,
        matchedCount = 1,
    )

    private val closedPeriod = openPeriod.copy(
        id = "p0",
        periodMillis = 1_740_787_200_000,
        status = PeriodStatus.Complete,
    )

    private val unmatched = BankTransaction(
        id = "t1",
        periodId = "p1",
        vendorName = "Panavision",
        debit = 1200.0,
        status = TxnStatus.Unmatched,
    )

    private val suggested = BankTransaction(
        id = "t2",
        periodId = "p1",
        vendorName = "Kodak",
        debit = 400.0,
        status = TxnStatus.Suggested,
        matchConfidence = 92,
        matchedInvoiceIds = listOf("inv-2"),
    )

    private val invoice = LedgerEntry(
        id = "led-1",
        entityId = "inv-1",
        kind = LedgerEntryKind.Invoice,
        title = "Panavision",
        amount = -1200.0,
    )

    private val suggestedInvoice = invoice.copy(id = "led-2", entityId = "inv-2", title = "Kodak", amount = -400.0)

    private fun repo() = FakeBankRecRepository().apply {
        periodRows = listOf(openPeriod, closedPeriod)
        workspaceData = WorkspaceData(
            transactions = listOf(unmatched, suggested),
            ledger = listOf(invoice, suggestedInvoice),
        )
    }

    private fun viewModel(
        repo: FakeBankRecRepository,
        uploader: StatementUploader? = StatementUploader {
            ZillitResult.Success(StatementUpload(media = "k", fileName = "march.csv"))
        },
    ) = BankRecViewModel(repository = repo, uploader = uploader)

    private fun started(repo: FakeBankRecRepository): BankRecViewModel {
        val model = viewModel(repo)
        model.start()
        return model
    }

    /** The KPI row describes the period in progress, not the newest month. */
    @Test
    fun `the current period is the one still open`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        runCurrent()

        assertEquals("p1", model.state.value.currentPeriod?.id)
        assertEquals(listOf("p1"), model.state.value.openPeriods.map { it.id })
    }

    /** A match is proposed, then confirmed, and only then sent. */
    @Test
    fun `matching by hand takes two steps`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.SelectTransaction(unmatched))
        model.onEvent(BankRecEvent.ProposeMatch(unmatched, invoice))
        runCurrent()
        assertTrue(repo.matches.isEmpty())

        model.onEvent(BankRecEvent.ConfirmMatch)
        runCurrent()

        assertEquals(1, repo.matches.size)
        // The record's id, not the ledger row's: they are different tables.
        assertEquals(Triple("t1", "inv-1", LedgerEntryKind.Invoice), repo.matches.first())
    }

    /** Dismissing a proposal sends nothing. */
    @Test
    fun `a proposal that is dismissed reconciles nothing`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.ProposeMatch(unmatched, invoice))
        model.onEvent(BankRecEvent.DismissMatch)
        runCurrent()

        assertTrue(repo.matches.isEmpty())
        assertNull(model.state.value.workspace.pending)
    }

    /**
     * Accepting the engine's suggestion still confirms.
     *
     * A suggestion is a guess with a confidence on it, and taking one
     * reconciles two records that nothing afterwards will question.
     */
    @Test
    fun `accepting a suggestion proposes it rather than filing it`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.AcceptSuggestion(suggested))
        runCurrent()

        val pending = model.state.value.workspace.pending
        assertEquals("inv-2", pending?.entry?.entityId)
        assertTrue(pending?.wasSuggested == true)
        assertTrue(repo.matches.isEmpty())
    }

    /** A suggestion pointing at an entry that is already matched is refused. */
    @Test
    fun `a suggestion with nothing left to match against is refused`() = runTest(dispatcher) {
        val repo = repo()
        repo.workspaceData = WorkspaceData(
            transactions = listOf(suggested),
            ledger = listOf(suggestedInvoice.copy(transactionIds = listOf("t9"))),
        )
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.AcceptSuggestion(suggested))
        runCurrent()

        assertNull(model.state.value.workspace.pending)
    }

    /**
     * Signing off over unreconciled lines needs a reason.
     *
     * It is a judgement somebody will be asked about later, and the note is
     * what they will be asked to point at.
     */
    @Test
    fun `signing off with items outstanding requires a note`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()
        assertTrue(repo.signOffs.isEmpty())

        model.onEvent(BankRecEvent.EditSignOffNote("Two charges chased with the bank."))
        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()

        assertEquals(1, repo.signOffs.size)
        assertEquals("Two charges chased with the bank.", repo.signOffs.first().second)
    }

    /** With nothing outstanding, no note is demanded. */
    @Test
    fun `a fully reconciled period signs off without a note`() = runTest(dispatcher) {
        val repo = repo()
        repo.workspaceData = WorkspaceData(
            transactions = listOf(unmatched.copy(status = TxnStatus.Matched)),
            ledger = emptyList(),
        )
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()

        assertEquals(1, repo.signOffs.size)
    }

    /**
     * A signed-off period cannot be deleted.
     *
     * The server refuses it; saying so here spares the accountant a refusal
     * they cannot act on.
     */
    @Test
    fun `a signed-off period is not offered for deletion`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        runCurrent()

        model.onEvent(BankRecEvent.AskDeletePeriods(listOf(closedPeriod)))
        runCurrent()
        assertTrue(model.state.value.deleting.isEmpty())

        model.onEvent(BankRecEvent.ConfirmDeletePeriods)
        runCurrent()
        assertTrue(repo.deletes.isEmpty())
    }

    /** Deleting is confirmed, and takes the whole period with it. */
    @Test
    fun `deleting a period is confirmed first`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        runCurrent()

        model.onEvent(BankRecEvent.AskDeletePeriods(listOf(openPeriod, closedPeriod)))
        runCurrent()
        // The signed-off one is dropped from the ask, not carried into it.
        assertEquals(listOf("p1"), model.state.value.deleting.map { it.id })
        assertTrue(repo.deletes.isEmpty())

        model.onEvent(BankRecEvent.ConfirmDeletePeriods)
        runCurrent()

        assertEquals(listOf(listOf("p1")), repo.deletes)
    }

    /** Escalating an alert asks first; dismissing one does not. */
    @Test
    fun `escalating asks and dismissing does not`() = runTest(dispatcher) {
        val repo = repo()
        val alert = FraudAlert(
            id = "a1",
            periodId = "p1",
            alertType = FraudType.MandateFraud,
            status = FraudStatus.Active,
            riskScore = 88,
        )
        repo.alertRows = listOf(alert)
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts))
        runCurrent()

        model.onEvent(BankRecEvent.DismissAlert(alert))
        runCurrent()
        assertEquals(listOf("a1"), repo.dismissals)

        model.onEvent(BankRecEvent.AskEscalate(alert))
        runCurrent()
        assertTrue(repo.escalations.isEmpty())

        model.onEvent(BankRecEvent.ConfirmEscalate)
        runCurrent()
        assertEquals(listOf("a1"), repo.escalations)
    }

    /** Posting an exception without an account code is refused before it is sent. */
    @Test
    fun `a quick add without an account code posts nothing`() = runTest(dispatcher) {
        val repo = repo()
        val exception = BankException(id = "e1", periodId = "p1", title = "Bank charge")
        repo.exceptionRows = listOf(exception)
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Exceptions))
        runCurrent()

        model.onEvent(BankRecEvent.ComposeQuickAdd(exception))
        model.onEvent(BankRecEvent.SaveQuickAdd)
        runCurrent()
        assertTrue(repo.quickAdds.isEmpty())

        model.onEvent(
            BankRecEvent.EditQuickAdd(QuickAddForm(nominalCode = "7900", description = "Bank charge")),
        )
        model.onEvent(BankRecEvent.SaveQuickAdd)
        runCurrent()

        assertEquals(1, repo.quickAdds.size)
        assertEquals("7900", repo.quickAdds.first().second.nominalCode)
    }

    /** An exception's note travels with the status it is being set to. */
    @Test
    fun `a status change carries its note`() = runTest(dispatcher) {
        val repo = repo()
        val exception = BankException(id = "e1", periodId = "p1")
        repo.exceptionRows = listOf(exception)
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Exceptions))
        runCurrent()

        model.onEvent(BankRecEvent.ComposeExceptionNote(exception, ExceptionStatus.Ignored))
        model.onEvent(BankRecEvent.EditExceptionNote("Charged in error, credited next month."))
        model.onEvent(BankRecEvent.SaveExceptionNote)
        runCurrent()

        assertEquals(
            Triple("e1", ExceptionStatus.Ignored, "Charged in error, credited next month."),
            repo.statusChanges.single(),
        )
    }

    /** Posting a whole period's variances asks first. */
    @Test
    fun `posting every variance is confirmed`() = runTest(dispatcher) {
        val repo = repo()
        repo.fxRows = listOf(FxVariance(id = "v1", periodId = "p1", variance = -40.0))
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.FxVariances))
        runCurrent()

        model.onEvent(BankRecEvent.AskPostAllFx)
        runCurrent()
        assertTrue(repo.fxPostAlls.isEmpty())

        model.onEvent(BankRecEvent.ConfirmPostAllFx)
        runCurrent()
        assertEquals(listOf("p1"), repo.fxPostAlls)
    }

    /** The two rule halves save separately, each carrying only its own. */
    @Test
    fun `saving one rule section does not carry the other's edits`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Settings))
        runCurrent()

        model.onEvent(BankRecEvent.ToggleMatchRule("amt_fuzzy_vendor_match", false))
        model.onEvent(BankRecEvent.ToggleFraudRule("duplicate_detection", false))
        model.onEvent(BankRecEvent.SaveMatchRules)
        runCurrent()

        assertEquals(1, repo.savedMatchRules.size)
        assertEquals(false, repo.savedMatchRules.first()["amt_fuzzy_vendor_match"])
        assertTrue(repo.savedFraudRules.isEmpty())
        // The fraud edit is still unsaved and still on screen.
        assertTrue(model.state.value.rules.fraudDirty)
        assertFalse(model.state.value.rules.matchDirty)
    }

    /** A link with nothing on it shows the recipient nothing, and is refused. */
    @Test
    fun `a shared link needs a recipient, a period and something to show`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.GuarantorPortal))
        runCurrent()

        model.onEvent(BankRecEvent.ComposePortalLink)
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertTrue(repo.createdLinks.isEmpty())

        val draft = PortalLinkDraft(
            recipientName = "James Whitford",
            recipientEmail = "j@example.com",
            periodId = "p1",
            permissions = emptySet(),
        )
        model.onEvent(BankRecEvent.EditPortalDraft(draft))
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertTrue(repo.createdLinks.isEmpty())

        model.onEvent(
            BankRecEvent.EditPortalDraft(draft.copy(permissions = setOf(PortalPermission.Balances))),
        )
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertEquals(1, repo.createdLinks.size)
    }

    /** The two options that name people are off unless somebody turns them on. */
    @Test
    fun `a new link does not show individual payments by default`() {
        val defaults = PortalPermission.defaults

        assertFalse(PortalPermission.TransactionDetail in defaults)
        assertFalse(PortalPermission.FraudAlerts in defaults)
        assertTrue(PortalPermission.Balances in defaults)
    }

    /** Without an uploader nothing is imported, and the button says so. */
    @Test
    fun `an installation with no file storage cannot import`() = runTest(dispatcher) {
        val repo = repo()
        val model = viewModel(repo, uploader = null)
        model.start()
        runCurrent()

        assertFalse(model.state.value.canImport)
        model.onEvent(BankRecEvent.ComposeImport)
        model.onEvent(BankRecEvent.ChooseStatement)
        runCurrent()

        assertTrue(repo.imports.isEmpty())
    }

    /** A cancelled picker is not a failure, and imports nothing. */
    @Test
    fun `a cancelled file picker imports nothing and reports no error`() = runTest(dispatcher) {
        val repo = repo()
        val model = viewModel(repo, uploader = { ZillitResult.Success(null) })
        model.start()
        model.onEvent(BankRecEvent.ComposeImport)
        model.onEvent(BankRecEvent.ChooseStatement)
        runCurrent()

        assertTrue(repo.imports.isEmpty())
        assertFalse(model.state.value.import.uploading)
    }

    /** The workspace filter reads the effective status, not the stored one. */
    @Test
    fun `filtering fraud finds a line whose flag is still open`() = runTest(dispatcher) {
        val repo = repo()
        val flagged = unmatched.copy(
            id = "t3",
            fraudType = FraudType.DuplicatePayment,
            fraudStatus = FraudStatus.Active,
        )
        repo.workspaceData = WorkspaceData(transactions = listOf(unmatched, flagged))
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Fraud))

        assertEquals(listOf("t3"), model.state.value.workspace.visible.map { it.id })
    }

    /**
     * A period that disappears takes the open workspace with it.
     *
     * Somebody else signing off or deleting it leaves this screen showing
     * lines that no longer belong to anything.
     */
    @Test
    fun `the workspace lets go of a period that is no longer there`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()
        assertEquals("p1", model.state.value.workspace.periodId)

        repo.periodRows = listOf(closedPeriod)
        model.onEvent(BankRecEvent.Refresh)
        runCurrent()

        assertTrue(model.state.value.workspace.periodId.isBlank())
    }

    /** Switching tabs does not refetch what a tab already holds. */
    @Test
    fun `a tab already loaded is not fetched again on every switch`() = runTest(dispatcher) {
        val repo = repo()
        val model = started(repo)
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()
        val first = repo.workspaceLoads

        model.onEvent(BankRecEvent.OpenTab(BankTab.Exceptions))
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        assertEquals(first, repo.workspaceLoads)
    }
}
