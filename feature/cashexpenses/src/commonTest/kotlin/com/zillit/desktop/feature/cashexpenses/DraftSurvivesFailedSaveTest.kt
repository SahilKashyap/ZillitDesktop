package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A save that fails — offline, rejected, timed out — must leave the typing
 * where the user can retry it. The float request and the coding editor both
 * used to clear themselves before the server answered; these pin the fix and
 * the success path that does clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftSurvivesFailedSaveTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = CashViewer(userId = "u1", departmentIdentifier = "camera", designationIdentifier = null)

    private fun viewModel(repository: CashRepository) =
        CashExpensesViewModel(repository = repository, viewer = { crew }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `a float request that fails to send keeps its amount and purpose`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = false)
        val vm = viewModel(repository)
        val typed = FloatRequestDraft(amount = "250", purpose = "Location petty cash")

        vm.onEvent(CashEvent.EditFloatRequest(typed))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        assertEquals(1, repository.floatRequests, "the request was attempted")
        assertEquals(typed, vm.state.value.floatDraft, "a failed send must not wipe the form")

        repository.writesSucceed = true
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        assertEquals(FloatRequestDraft(), vm.state.value.floatDraft, "a sent request clears the form")
        assertEquals(2, repository.floatRequests)
    }

    @Test
    fun `coding that fails to save stays open for a retry`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = false)
        val vm = viewModel(repository)

        vm.onEvent(CashEvent.Open(CashDestination.CodingQueue))
        advanceUntilIdle()
        vm.onEvent(CashEvent.OpenCoding(batchId = "b1", claimId = "c1"))
        val open = assertNotNull(vm.state.value.coding, "the editor opened on the queued receipt")

        vm.onEvent(CashEvent.SaveCoding)
        advanceUntilIdle()

        assertEquals(1, repository.codingSaves, "the save was attempted")
        assertEquals(open, vm.state.value.coding, "a failed save must not close the editor")

        repository.writesSucceed = true
        vm.onEvent(CashEvent.SaveCoding)
        advanceUntilIdle()

        assertNull(vm.state.value.coding, "a saved coding closes the editor")
        assertEquals(2, repository.codingSaves)
    }

    /**
     * Reads answer with what the two flows need; every write answers
     * [writesSucceed]; everything else fails, which the view model absorbs as
     * an error banner.
     */
    @Suppress("TooManyFunctions") // One override per server operation.
    private class FakeCash(var writesSucceed: Boolean) : CashRepository {
        var floatRequests = 0
        var codingSaves = 0
        var floatApprovals = 0
        var floatCloses = 0

        private fun <T> fail(): ZillitResult<T> =
            ZillitResult.Failure(ZillitError.NoConnection(technical = "test: offline"))

        private fun write(): ZillitResult<Unit> =
            if (writesSucceed) ZillitResult.Success(Unit) else fail()

        override suspend fun metadata(): ZillitResult<CashMetadata> = ZillitResult.Success(CashMetadata())
        override suspend fun myFloats(): ZillitResult<List<CashFloat>> = ZillitResult.Success(emptyList())
        override suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit> {
            floatRequests++
            return write()
        }

        override suspend fun queue(queue: CashQueue, expenseType: ExpenseType?): ZillitResult<List<ClaimBatch>> =
            ZillitResult.Success(listOf(queuedBatch()))

        override suspend fun saveClaimLines(
            batchId: String,
            claimId: String,
            lines: List<ClaimLineItem>,
        ): ZillitResult<Unit> {
            codingSaves++
            return write()
        }

        override suspend fun activeFloats(): ZillitResult<List<CashFloat>> = fail()
        override suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>> = fail()
        override suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>> = fail()
        override suspend fun approveFloat(floatId: String, note: String?): ZillitResult<Unit> {
            floatApprovals++
            return if (writesSucceed) ZillitResult.Success(Unit) else fail()
        }
        override suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit> = fail()
        override suspend fun overrideFloat(floatId: String): ZillitResult<Unit> = fail()
        override suspend fun markFloatReadyToCollect(floatId: String, companyId: String?): ZillitResult<Unit> = fail()
        override suspend fun issueFloat(floatId: String): ZillitResult<Unit> = fail()
        override suspend fun collectFloat(floatId: String): ZillitResult<Unit> = fail()
        override suspend fun closeFloat(floatId: String): ZillitResult<Unit> {
            floatCloses++
            return if (writesSucceed) ZillitResult.Success(Unit) else fail()
        }
        override suspend fun recordCashReturn(floatId: String, amount: Double, note: String?): ZillitResult<Unit> =
            fail()

        override suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>> = fail()
        override suspend fun requestFloatTopUp(floatId: String, amount: Double, reason: String?): ZillitResult<Unit> =
            fail()

        override suspend fun topUps(): ZillitResult<List<CashTopUp>> = fail()
        override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> = fail()
        override suspend fun partialTopUp(topUpId: String, amount: Double): ZillitResult<Unit> = fail()
        override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> = fail()
        override suspend fun myBatches(): ZillitResult<List<ClaimBatch>> = fail()
        override suspend fun batch(batchId: String): ZillitResult<ClaimBatch> = fail()
        override suspend fun batchHistory(batchId: String): ZillitResult<List<CashHistoryEntry>> = fail()
        override suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit> = fail()
        override suspend fun resubmitBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
        override suspend fun codeClaim(
            batchId: String,
            claimId: String,
            costCode: String,
            description: String?,
        ): ZillitResult<Unit> = fail()

        override suspend fun saveAndSubmitCoded(batchId: String): ZillitResult<Unit> = fail()
        override suspend fun saveAndVerify(batchId: String): ZillitResult<Unit> = fail()
        override suspend fun approveBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
        override suspend fun rejectBatch(batchId: String, reason: String): ZillitResult<Unit> = fail()
        override suspend fun overrideBatch(batchId: String): ZillitResult<Unit> = fail()
        override suspend fun queryBatch(batchId: String, reason: String): ZillitResult<Unit> = fail()
        override suspend fun escalateBatch(batchId: String, reason: String?): ZillitResult<Unit> = fail()
        override suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit> = fail()
        override suspend fun assignBatch(batchId: String, userId: String, reason: String?): ZillitResult<Unit> = fail()
        override suspend fun postBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
        override suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview> = fail()
        override suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview> = fail()
        override suspend fun myOverview(): ZillitResult<MyCashOverview> = fail()
        override suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview> = fail()
        override suspend fun paymentRouting(): ZillitResult<PaymentRouting> = fail()
        override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> = fail()
        override suspend fun computeBookBalance(): ZillitResult<Double> = fail()
        override suspend fun createReconciliation(countedBalance: Double, note: String?): ZillitResult<Reconciliation> =
            fail()

        override suspend fun submitReconciliationForReview(id: String): ZillitResult<Unit> = fail()
        override suspend fun signOffReconciliation(id: String, note: String?): ZillitResult<Unit> = fail()
        override suspend fun settings(): ZillitResult<CashSettings> = fail()
        override suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings> = fail()

        /** One receipt awaiting coding, already carrying a cost code so its seeded line balances. */
        private fun queuedBatch() = ClaimBatch(
            id = "b1",
            reference = "PC-001",
            userId = "u2",
            holderName = "Sam Grip",
            departmentId = "grip",
            status = BatchStatus.Coding,
            expenseType = ExpenseType.PettyCash,
            claimCount = 1,
            totalGross = 100.0,
            reimbursementAmount = 0.0,
            currency = "GBP",
            settlementType = null,
            paymentMethod = null,
            notes = null,
            assignedTo = null,
            assignedBy = null,
            assignmentReason = null,
            createdAt = null,
            claims = listOf(
                Claim(
                    id = "c1",
                    batchId = "b1",
                    description = "Gaffer tape",
                    supplier = null,
                    category = null,
                    costCode = "5010",
                    codedDescription = null,
                    episode = null,
                    receiptDate = null,
                    grossAmount = 100.0,
                    netAmount = 100.0,
                    vatAmount = 0.0,
                    taxRate = null,
                    taxType = null,
                    settlementType = null,
                    status = BatchStatus.Coding,
                    receiptUrl = null,
                ),
            ),
        )
    }
    /**
     * The queue only offers Approve to an approver, and this is the money.
     *
     * `resolveConfirm` dispatched every confirmable action straight to the
     * repository with no rights check of its own, so a prompt reaching it
     * approved a float or a batch outright. The screens gate it
     * (`QueuePage` on `viewer.isApprover`); the handler did not.
     */
    @Test
    fun `a non-approver cannot approve a float`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true)
        val vm = viewModel(repository)

        vm.onEvent(
            CashEvent.Ask(
                CashPrompt.Confirm(ConfirmAction.ApproveFloat, "f1", "Approve this float", ""),
            ),
        )
        vm.onEvent(CashEvent.ConfirmPrompt)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repository.floatApprovals, "a non-approver approved a float")
    }

    /**
     * The float lifecycle is an accountant's, per `FloatPages` — the whole
     * action column renders "—" for anyone else. Closing a float is final.
     */
    @Test
    fun `a non-accountant cannot close a float`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true)
        val vm = viewModel(repository)

        vm.onEvent(
            CashEvent.Ask(
                CashPrompt.Confirm(ConfirmAction.CloseFloat, "f1", "Close this float", ""),
            ),
        )
        vm.onEvent(CashEvent.ConfirmPrompt)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repository.floatCloses, "a non-accountant closed a float")
    }

}
