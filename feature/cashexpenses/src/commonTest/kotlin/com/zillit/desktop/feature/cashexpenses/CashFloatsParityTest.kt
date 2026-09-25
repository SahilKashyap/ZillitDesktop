package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTierConfig
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.TierRule
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.pages.batchActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * Approval Queue, Sign-off and Active Floats against the web's rules: no chain
 * means nobody approves, one-click actions skip the dialog, Override is only
 * for someone who is not the next approver, a sign-off date is picked rather
 * than assumed, and a float card opens onto its batches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFloatsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chainForMe = listOf(
        ApprovalTierConfig(
            scope = "all",
            departmentId = null,
            tiers = listOf(ApprovalTier(listOf(TierRule("default", null, listOf("me"))))),
        ),
    )

    private fun accountant(metadata: CashMetadata = CashMetadata()) = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = metadata,
    )

    private fun TestScope.viewModel(repository: FakeCash, viewer: CashViewer) = CashExpensesViewModel(
        repository = repository,
        viewer = { viewer },
    ).also {
        it.start()
        advanceUntilIdle()
    }

    // -- approval chains ----------------------------------------------------------------

    @Test
    fun `with no chain nobody approves, and an accountant is sent to set one`() {
        val approver = accountant(CashMetadata(isApprover = true))
        assertFalse(CashRules.mayApprove(approver, "dept", 100.0, emptyList()))
        assertTrue(ApprovalTiers.needsApprovalLevel(approver, "dept", 100.0))

        val covered = accountant(CashMetadata(isApprover = true, approvalTierConfigs = chainForMe))
        assertTrue(CashRules.mayApprove(covered, "dept", 100.0, emptyList()))
        assertFalse(ApprovalTiers.needsApprovalLevel(covered, "dept", 100.0))
        assertEquals(1, ApprovalTiers.totalFor(covered, "dept", 100.0))
    }

    @Test
    fun `approve on the row fires at once, with no dialog`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isApprover = true, approvalTierConfigs = chainForMe)
            activeFloatRows = listOf(float("f1", FloatStatus.AwaitingApproval))
        }
        val vm = viewModel(repository, accountant())
        vm.onEvent(CashEvent.Open(CashDestination.ApprovalQueue))
        advanceUntilIdle()

        vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "", "")))
        advanceUntilIdle()

        assertNull(vm.state.value.prompt, "no confirmation was asked for")
        assertTrue("approveFloat:f1" in repository.calls)
    }

    @Test
    fun `with no chain the approval is never sent`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isApprover = true)
            activeFloatRows = listOf(float("f1", FloatStatus.AwaitingApproval))
        }
        val vm = viewModel(repository, accountant())
        vm.onEvent(CashEvent.Open(CashDestination.ApprovalQueue))
        advanceUntilIdle()

        vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "", "")))
        advanceUntilIdle()

        assertTrue(repository.calls.none { it.startsWith("approveFloat") })
    }

    @Test
    fun `override is offered only to someone who is not the next approver`() {
        val batch = FakeCash(true).queuedBatch(status = BatchStatus.AwaitingApproval)
        val overrideRights = CashMetadata(canOverride = true, overrideReceiptBatch = true)
        fun labels(metadata: CashMetadata) = batchActions(
            CashUiState(
                viewer = accountant(metadata),
                destination = CashDestination.ApprovalQueue,
                queueBatches = listOf(batch),
            ),
            batch,
        ).map { it.label }

        val approverToo = labels(overrideRights.copy(approvalTierConfigs = chainForMe))
        assertTrue("Approve" in approverToo)
        assertFalse("Override" in approverToo)

        val notTheApprover = labels(overrideRights)
        assertTrue("Override" in notTheApprover)
        assertFalse("Approve" in notTheApprover)
    }

    // -- sign-off -------------------------------------------------------------------------

    @Test
    fun `a sign-off batch with no ledger date opens with none, and will not post until one is picked`() =
        runTest(dispatcher) {
            val repository = FakeCash(writesSucceed = true).apply {
                metadata = CashMetadata(isSenior = true, requireSeniorSignOff = true)
                queueRows = listOf(queuedBatch(status = BatchStatus.Escalated))
            }
            val vm = viewModel(repository, accountant(CashMetadata(isSenior = true, requireSeniorSignOff = true)))
            vm.onEvent(CashEvent.Open(CashDestination.PettyCashSignOff))
            advanceUntilIdle()
            vm.onEvent(CashEvent.SelectBatch("b1"))
            advanceUntilIdle()

            assertEquals("", vm.state.value.panel?.effectiveDate)
            vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.PostBatch, "b1", "", "")))
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("post") }, "no date, no post")
        }

    @Test
    fun `return to accounts fires at once`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isSenior = true, requireSeniorSignOff = true)
            queueRows = listOf(queuedBatch(status = BatchStatus.Escalated))
        }
        val vm = viewModel(repository, accountant(CashMetadata(isSenior = true, requireSeniorSignOff = true)))
        vm.onEvent(CashEvent.Open(CashDestination.PettyCashSignOff))
        advanceUntilIdle()

        vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.ReturnToAccounts, "b1", "", "")))
        advanceUntilIdle()

        assertTrue("deescalate:b1" in repository.calls)
        assertNull(vm.state.value.prompt)
    }

    // -- active floats ----------------------------------------------------------------------

    @Test
    fun `a float card opens onto its batches, and a batch onto its receipts`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(float("f1", FloatStatus.Spending))
            queueRows = listOf(queuedBatch(id = "b1", status = BatchStatus.InAudit))
            floatBatchRows = queueRows
        }
        val vm = viewModel(repository, accountant())
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()

        vm.onEvent(CashEvent.ToggleFloatBatches("f1"))
        advanceUntilIdle()
        assertEquals(listOf("b1"), vm.state.value.floatExpansions["f1"]?.batches?.map { it.id })

        vm.onEvent(CashEvent.SelectFloatBatch("f1", "b1"))
        advanceUntilIdle()
        val open = vm.state.value.floatExpansions.getValue("f1")
        assertEquals("b1", open.selectedBatchId)
        assertEquals(listOf("c1"), open.claims["b1"]?.map { it.id })

        vm.onEvent(CashEvent.ToggleFloatBatches("f1"))
        assertNull(vm.state.value.floatExpansions["f1"])
    }

    @Test
    fun `a float's history opens in its drawer`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            floatHistoryRows = listOf(CashHistoryEntry(action = "FLOAT_COLLECTED", userId = "me", note = null, at = 1))
        }
        val vm = viewModel(repository, accountant())

        vm.onEvent(CashEvent.ShowFloatHistory("f1", "PC-1"))
        advanceUntilIdle()
        assertEquals("FLOAT_COLLECTED", vm.state.value.floatHistory?.entries?.single()?.action)

        vm.onEvent(CashEvent.ShowFloatHistory(null))
        assertNull(vm.state.value.floatHistory)
    }

    @Test
    fun `mark collected and close fire at once`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(float("ready", FloatStatus.ReadyToCollect), float("spent", FloatStatus.Spent))
        }
        val vm = viewModel(repository, accountant())
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()

        vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.CollectFloat, "ready", "", "")))
        advanceUntilIdle()
        vm.onEvent(CashEvent.ActNow(CashPrompt.Confirm(ConfirmAction.CloseFloat, "spent", "", "")))
        advanceUntilIdle()

        assertTrue("collectFloat:ready" in repository.calls)
        assertTrue("closeFloat:spent" in repository.calls)
    }

    private fun float(id: String, status: FloatStatus) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u1", holderName = "", departmentId = "dept", status = status,
        currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0, balance = 40.0, receiptsAmount = 0.0,
        receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null, duration = null,
        durationType = null, purpose = null, createdAt = 1,
    )
}
