package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.BatchEdits
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ClaimField
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.ui.BatchEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web's batch view (`PostModal`) on Post & Ledger, Audit, History and
 * Coding: receipts edited in memory and saved whole, the assignee lock on the
 * audit queue, and the split editor's lines — tax line included — reaching
 * the save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchWorkFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A production accountant — senior by designation, so every row is theirs to open. */
    private val senior = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val junior = senior.copy(designationIdentifier = null)

    private fun TestScope.viewModel(repository: FakeCash, viewer: CashViewer = senior) =
        CashExpensesViewModel(
            repository = repository,
            viewer = { viewer },
            reference = CashReferenceSources(chartAccounts = { listOf(CashAccount("5010", "Materials")) }),
        ).also {
            it.start()
            advanceUntilIdle()
        }

    private fun TestScope.openBatch(vm: CashExpensesViewModel, destination: CashDestination) {
        vm.onEvent(CashEvent.Open(destination))
        advanceUntilIdle()
        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()
    }

    @Test
    fun `save sends every receipt with the ledger date and wraps a code the chart lacks`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
        }
        val vm = viewModel(repository)
        openBatch(vm, CashDestination.PostLedger)

        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.CostCode, "9999"))
        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.Vendor, "Screwfix"))
        assertTrue(vm.state.value.panel?.dirty == true)
        vm.onEvent(BatchEvent.Save("b1"))
        advanceUntilIdle()

        assertTrue("saveBatch:b1" in repository.calls)
        val sent = assertNotNull(repository.lastSavedClaims).single()
        assertEquals("[[9999]]", sent.costCode)
        assertEquals("Screwfix", sent.description)
        assertNotNull(repository.lastSaveDate, "a Post & Ledger save stamps the ledger date")
    }

    @Test
    fun `post and ledger refuses a save whose coding misses the batch total`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
        }
        val vm = viewModel(repository)
        openBatch(vm, CashDestination.PostLedger)

        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.Amount, "80"))
        vm.onEvent(BatchEvent.Save("b1"))
        advanceUntilIdle()
        assertTrue(repository.calls.none { it.startsWith("saveBatch") })
    }

    @Test
    fun `coding saves a draft without a ledger date and never the claimant's figures`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isCoordinator = true, codingRequired = true)
            queueRows = listOf(queuedBatch(status = BatchStatus.Coding))
        }
        val vm = viewModel(repository, junior)
        openBatch(vm, CashDestination.CodingQueue)

        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.Amount, "5"))
        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.Episode, "Ep.3"))
        vm.onEvent(BatchEvent.Save("b1"))
        advanceUntilIdle()

        val sent = assertNotNull(repository.lastSavedClaims).single()
        assertEquals(100.0, sent.grossAmount)
        assertEquals("Ep.3", sent.episode)
        assertNull(repository.lastSaveDate)
    }

    @Test
    fun `the audit queue keeps a row to its assignee and a senior`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.InAudit, assignedTo = "someone-else"))
        }
        val vm = viewModel(repository, junior)
        openBatch(vm, CashDestination.AuditQueue)
        assertNull(vm.state.value.selectedBatchId)

        val mine = viewModel(repository, senior)
        openBatch(mine, CashDestination.AuditQueue)
        assertEquals("b1", mine.state.value.selectedBatchId)
    }

    @Test
    fun `a split is held on the receipt and saved with its tax line`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
        }
        val vm = viewModel(repository)
        openBatch(vm, CashDestination.PostLedger)

        vm.onEvent(BatchEvent.OpenSplit("c1"))
        val seeded = assertNotNull(vm.state.value.coding).lines.single()
        assertEquals("5010", seeded.account)
        vm.onEvent(BatchEvent.AddTaxLine)
        val tax = vm.state.value.coding!!.lines.last()
        assertTrue(tax.extras.isTax)
        vm.onEvent(CashEvent.EditCodingLine(0, seeded.copy(unitPrice = 80.0)))
        vm.onEvent(CashEvent.EditCodingLine(1, tax.copy(unitPrice = 20.0, account = "2200")))
        vm.onEvent(BatchEvent.ApplySplit)
        assertNull(vm.state.value.coding)
        assertEquals(2, vm.state.value.panelClaims.single().lineItems.size)

        vm.onEvent(BatchEvent.Save("b1"))
        advanceUntilIdle()
        val lines = assertNotNull(repository.lastSavedClaims).single().rawLines
        val taxLine = lines.single { it["is_tax"] == JsonPrimitive(true) }
        assertEquals("", taxLine["description"]?.jsonPrimitive?.content)
        assertEquals(20.0, taxLine["total"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("[[2200]]", taxLine["account"]?.jsonPrimitive?.content)
        assertEquals("5010", lines.single { it["is_tax"] == null }["account"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a refresh keeps receipts edited and not yet saved`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
        }
        val vm = viewModel(repository)
        openBatch(vm, CashDestination.PostLedger)
        vm.onEvent(BatchEvent.EditClaim("c1", ClaimField.Name, "Tape for set"))
        vm.onEvent(CashEvent.Refresh)
        advanceUntilIdle()
        assertEquals("Tape for set", vm.state.value.panelClaims.single().codedDescription)
    }

    // -- the pure rules --------------------------------------------------------

    @Test
    fun `a tax line goes out as the web sends it`() {
        val line = ClaimLineItem(
            id = null, account = "2200", description = "VAT", total = 20.0, taxRate = 20.0,
            autoDeduction = false, quantity = 2.0, unitPrice = 5.0, taxType = "STANDARD", isTax = true,
        )
        val sent = BatchEdits.normaliseTaxLine(line)
        assertEquals("", sent.description)
        assertEquals(1.0, sent.quantity)
        assertEquals(20.0, sent.unitPrice)
        assertNull(sent.taxRate)
        assertEquals(0.0, sent.taxAmount)
    }

    @Test
    fun `the queue age reads in days, else hours`() {
        val day = 86_400_000L
        assertEquals("3d", BatchEdits.ageInQueue(1_000L, 1_000L + 3 * day + 5))
        assertEquals("1h", BatchEdits.ageInQueue(1_000L, 1_000L + 60))
        assertNull(BatchEdits.ageInQueue(null, 1_000L))
        assertNull(BatchEdits.ageInQueue(5_000L, 1_000L))
    }

    @Test
    fun `new line ids are version 4 uuids`() {
        val id = BatchEdits.newUuid(Random(7))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
    }
}
