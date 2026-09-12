package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.Fixtures.closedPeriod
import com.zillit.desktop.feature.bankrec.Fixtures.invoice
import com.zillit.desktop.feature.bankrec.Fixtures.suggested
import com.zillit.desktop.feature.bankrec.Fixtures.suggestedInvoice
import com.zillit.desktop.feature.bankrec.Fixtures.unmatched
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.ImportStep
import com.zillit.desktop.feature.bankrec.ui.workspaceView
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace, the period tables and the import — what the module refuses to
 * do without being asked twice, and what it must not do at all.
 *
 * Matching, signing off and deleting a period are all invisible or
 * irreversible afterwards, so each is proposed and then confirmed. These tests
 * are mostly about the gap between the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankRecFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** The KPI row describes the period in progress, not the newest month. */
    @Test
    fun `the current period is the one still open`() = runTest(dispatcher) {
        val model = bankRecViewModel(Fixtures.repo()).also { it.start() }
        runCurrent()

        assertEquals("p1", model.state.value.currentPeriod?.id)
        assertEquals(listOf("p1"), model.state.value.openPeriods.map { it.id })
    }

    /**
     * Accepting the engine's suggestion is confirmed before it is sent, and
     * sends the record's id and table — not the ledger row's.
     */
    @Test
    fun `accepting a suggestion is confirmed, then sends the record`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.ProposeMatch(suggested.id, "inv-2"))
        runCurrent()
        assertTrue(repo.matches.isEmpty())
        assertNotNull(model.state.value.workspace.proposal)

        model.onEvent(BankRecEvent.ConfirmMatch)
        runCurrent()

        assertEquals(listOf(Triple("t2", "inv-2", LedgerEntryKind.Invoice)), repo.matches)
        assertNull(model.state.value.workspace.proposal)
    }

    @Test
    fun `a dismissed proposal reconciles nothing`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.ProposeMatch(suggested.id, "inv-2"))
        model.onEvent(BankRecEvent.DismissMatch)
        runCurrent()

        assertTrue(repo.matches.isEmpty())
        assertNull(model.state.value.workspace.proposal)
    }

    /** A manual match picks the entry, confirms, and can go back to the list in between. */
    @Test
    fun `a manual match is chosen, confirmed, and can be backed out of`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.OpenManualMatch(unmatched.id))
        model.onEvent(BankRecEvent.PickManualMatch(invoice.id))
        model.onEvent(BankRecEvent.PickManualMatch(null))
        assertEquals("t1", model.state.value.workspace.manualMatchId)
        assertNull(model.state.value.workspace.manualMatchEntryId)

        model.onEvent(BankRecEvent.PickManualMatch(invoice.id))
        model.onEvent(BankRecEvent.ConfirmManualMatch)
        runCurrent()

        assertEquals(listOf(Triple("t1", "inv-1", LedgerEntryKind.Invoice)), repo.matches)
        assertNull(model.state.value.workspace.manualMatchId)
    }

    /**
     * Signing off over anything outstanding needs a reason — it is a judgement
     * somebody will be asked about later.
     */
    @Test
    fun `signing off with items outstanding requires a note`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.OpenSignOff)
        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()
        assertTrue(repo.signOffs.isEmpty())

        model.onEvent(BankRecEvent.EditSignOffNote("Two charges chased with the bank."))
        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()

        assertEquals(listOf("p1" to "Two charges chased with the bank."), repo.signOffs)
        assertNull(model.state.value.workspace.signOff)
    }

    /** Everything matched on both sides, nothing different: no note is demanded. */
    @Test
    fun `a fully reconciled period signs off without a note`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        repo.workspaceData = WorkspaceData(
            transactions = listOf(unmatched.copy(status = TxnStatus.Matched, matchedInvoiceIds = listOf("inv-1"))),
            ledger = listOf(invoice.copy(transactionIds = listOf("t1"))),
        )
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()
        assertFalse(model.state.value.workspaceView().hasIssues)

        model.onEvent(BankRecEvent.OpenSignOff)
        model.onEvent(BankRecEvent.ConfirmSignOff)
        runCurrent()

        assertEquals(1, repo.signOffs.size)
    }

    /** The server refuses to delete a signed-off period; so does the ask. */
    @Test
    fun `a signed-off period is not offered for deletion`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.AskDeletePeriods(listOf(closedPeriod.id)))
        runCurrent()
        assertNull(model.state.value.deleting)

        model.onEvent(BankRecEvent.ConfirmDeletePeriods)
        runCurrent()
        assertTrue(repo.deletes.isEmpty())
    }

    /** Deleting is confirmed first, and a signed-off period in the selection is dropped from the ask. */
    @Test
    fun `deleting a period is confirmed first`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.AskDeletePeriods(listOf("p1", "p0")))
        runCurrent()
        assertEquals(listOf("p1"), model.state.value.deleting?.ids)
        assertTrue(repo.deletes.isEmpty())

        model.onEvent(BankRecEvent.ConfirmDeletePeriods)
        runCurrent()

        assertEquals(listOf(listOf("p1")), repo.deletes)
        assertNull(model.state.value.deleting)
    }

    /** Only signed-off periods go into the PDF, through the host's file seam. */
    @Test
    fun `the period export saves the chosen signed-off periods`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val files = FakeFiles()
        val model = bankRecViewModel(repo, files = files).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenExportPdf)
        model.onEvent(BankRecEvent.ToggleAllExportPeriods)
        assertEquals(setOf("p0"), model.state.value.exportPdf?.selected)

        model.onEvent(BankRecEvent.ConfirmExportPdf)
        runCurrent()

        assertEquals(listOf(listOf("p0")), repo.periodExports)
        assertEquals(1, files.saved.size)
        assertTrue(files.saved.single().startsWith("reconciliation-export-"))
        assertNull(model.state.value.exportPdf)
    }

    /** Without file storage nothing is imported, and the dialog does not open. */
    @Test
    fun `an installation with no file storage cannot import`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo, statements = null).also { it.start() }
        runCurrent()

        assertFalse(model.state.value.canImport)
        model.onEvent(BankRecEvent.OpenImport)
        runCurrent()

        assertFalse(model.state.value.import.open)
        assertTrue(repo.imports.isEmpty())
    }

    /** A cancelled picker is not a failure. */
    @Test
    fun `a cancelled file picker chooses nothing and reports nothing`() = runTest(dispatcher) {
        val model = bankRecViewModel(Fixtures.repo(), statements = FakeStatementFiles(picked = null))
            .also { it.start() }
        model.onEvent(BankRecEvent.OpenImport)
        model.onEvent(BankRecEvent.BrowseStatement)
        runCurrent()

        assertNull(model.state.value.import.file)
        assertNull(model.state.value.import.error)
        assertFalse(model.state.value.import.picking)
    }

    /**
     * A dropped file that is not a statement is refused by extension; a real
     * one is shown, and nothing is uploaded until the import starts.
     */
    @Test
    fun `a statement is uploaded only when the import starts`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply { importResult = ImportResult(imported = 12, matched = 9, fraud = 1) }
        val statements = FakeStatementFiles()
        val model = bankRecViewModel(repo, statements = statements).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenImport)
        model.onEvent(BankRecEvent.SelectImportAccount("b1"))
        model.onEvent(BankRecEvent.DropStatement(PickedStatement("notes.txt", byteArrayOf(1))))
        assertNull(model.state.value.import.file)
        assertNotNull(model.state.value.import.error)

        model.onEvent(BankRecEvent.DropStatement(PickedStatement("march.CSV", byteArrayOf(1, 2))))
        assertEquals("march.CSV", model.state.value.import.file?.name)
        assertNull(model.state.value.import.error)
        runCurrent()
        assertTrue(statements.uploads.isEmpty())

        model.onEvent(BankRecEvent.StartImport)
        runCurrent()

        assertEquals(listOf("march.CSV"), statements.uploads.map { it.name })
        assertEquals(listOf("b1"), repo.imports.map { it.second })
        assertEquals(12, model.state.value.import.result?.imported)
        assertEquals(ImportStep.Done.ordinal, model.state.value.import.step)

        model.onEvent(BankRecEvent.CloseImport)
        assertFalse(model.state.value.import.open)
    }

    /** The workspace filter reads the effective status: an open flag is fraud whatever is stored. */
    @Test
    fun `filtering fraud finds a line whose flag is still open`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val flagged = unmatched.copy(
            id = "t3",
            fraudType = FraudType.DuplicatePayment,
            fraudStatus = FraudStatus.Active,
        )
        repo.workspaceData = WorkspaceData(transactions = listOf(unmatched, flagged))
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Fraud))

        assertEquals(listOf("t3"), model.state.value.workspaceView().visibleBank.map { it.id })
    }

    /** Somebody else signing the period off takes the open workspace with it. */
    @Test
    fun `the workspace lets go of a period that is no longer open`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()
        assertEquals("p1", model.state.value.workspace.periodId)

        repo.periodRows = listOf(closedPeriod)
        model.onEvent(BankRecEvent.Refresh)
        runCurrent()

        assertTrue(model.state.value.workspace.periodId.isBlank())
    }

    /** Coming back to the workspace keeps its period and its rows while it re-reads. */
    @Test
    fun `returning to the workspace keeps the period on screen`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))
        runCurrent()

        model.onEvent(BankRecEvent.OpenTab(BankTab.Exceptions))
        model.onEvent(BankRecEvent.OpenTab(BankTab.Workspace))

        assertEquals("p1", model.state.value.workspace.periodId)
        assertFalse(model.state.value.workspace.loading)
        assertEquals(2, model.state.value.workspace.transactions.size)
    }

    /** Overview's Open lands in the full view on the period it named, not the first one open. */
    @Test
    fun `opening a period from a table goes to it in the full view`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenPeriod("p1"))
        runCurrent()

        assertEquals(BankTab.Workspace, model.state.value.tab)
        assertTrue(model.state.value.workspace.expanded)
        assertEquals("p1", model.state.value.workspace.periodId)
        assertEquals(listOf("inv-1", "inv-2"), model.state.value.workspace.ledger.map { it.entityId })
        assertEquals(suggestedInvoice.id, model.state.value.workspaceView().invoice("inv-2")?.id)
    }
}
