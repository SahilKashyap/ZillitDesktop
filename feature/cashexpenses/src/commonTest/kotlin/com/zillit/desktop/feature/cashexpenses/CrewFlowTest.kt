package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashAttachment
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.CrewDates
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import com.zillit.desktop.feature.cashexpenses.ui.HistoryFilter
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Submit Receipts, Receipts History and Float Request, as the web's crew pages behave. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrewFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = CashViewer(
        userId = "u1",
        departmentIdentifier = "department_camera",
        designationIdentifier = null,
        departmentId = "d-camera",
    )

    /** FakeCash, recording the crew's reads and writes. */
    private class Recording(val inner: FakeCash) : CashRepository by inner {
        val batchReads = mutableListOf<Pair<String?, String?>>()
        var batchRows: List<ClaimBatch> = emptyList()
        var submitted: NewClaimBatch? = null
        var floatRequest: NewFloatRequest? = null

        override suspend fun myBatches(floatRequestId: String?, expenseType: String?): ZillitResult<List<ClaimBatch>> {
            batchReads += floatRequestId to expenseType
            return ZillitResult.Success(batchRows)
        }

        override suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit> {
            submitted = request
            return ZillitResult.Success(Unit)
        }

        override suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit> {
            floatRequest = request
            return ZillitResult.Success(Unit)
        }
    }

    private fun TestScope.viewModel(
        repository: CashRepository,
        template: FormTemplate = FormTemplate(),
    ) = CashExpensesViewModel(
        repository = repository,
        viewer = { crew },
        formTemplate = { ZillitResult.Success(template) },
    ).also {
        it.start()
        advanceUntilIdle()
    }

    // -- submit receipts ---------------------------------------------------------------

    @Test
    fun `the settlement counts only this float's batches still in the pipeline`() = runTest(dispatcher) {
        val repository = Recording(FakeCash(writesSucceed = true).apply { myFloatRows = listOf(float("f1")) }).apply {
            batchRows = listOf(
                batch("pending", "f1", BatchStatus.InAudit, 30.0),
                batch("rejected", "f1", BatchStatus.Rejected, 500.0),
                batch("posted", "f1", BatchStatus.Posted, 500.0),
                batch("elsewhere", "f2", BatchStatus.Coding, 500.0),
            )
        }
        val vm = viewModel(repository)

        vm.onEvent(CashEvent.Open(CashDestination.SubmitReceipts))
        advanceUntilIdle()

        assertTrue(repository.batchReads.contains("f1" to null), "asked for this float's batches")
        assertEquals(30.0, vm.state.value.pendingBatchesTotal)
    }

    @Test
    fun `a receipt with no file is refused inline and nothing is sent`() = runTest(dispatcher) {
        val repository = Recording(FakeCash(writesSucceed = true).apply { myFloatRows = listOf(float("f1")) })
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.SubmitReceipts))
        advanceUntilIdle()

        vm.onEvent(CashEvent.EditReceipt(0, DraftReceipt(description = "B&Q", amount = "10", date = DAY)))
        vm.onEvent(CashEvent.SubmitReceipts)
        advanceUntilIdle()

        assertNull(repository.submitted)
        assertEquals(
            "Each receipt must have an attachment — upload the receipt image or PDF.",
            vm.state.value.crew.submitError,
        )
    }

    @Test
    fun `an overdrawn batch is a BACS reimbursement in the float's currency, then history opens`() =
        runTest(dispatcher) {
            val repository = Recording(FakeCash(writesSucceed = true).apply { myFloatRows = listOf(float("f1")) })
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.SubmitReceipts))
            advanceUntilIdle()

            vm.onEvent(CashEvent.EditReceipt(0, receipt(amount = "60")))
            vm.onEvent(CrewEvent.ToggleFollowUp(FollowUp.CLOSE))
            vm.onEvent(CashEvent.SubmitReceipts)
            advanceUntilIdle()

            val sent = assertNotNull(repository.submitted)
            assertEquals("REIMBURSE", sent.settlementType, "60 against a balance of 40")
            assertEquals("EUR", sent.currency)
            assertEquals("d-camera", sent.departmentId)
            assertEquals(FollowUp.CLOSE, sent.settlementDetails?.followUp)
            assertEquals(ReimbursementMethod.Bacs, sent.settlementDetails?.paymentMethod, "payroll is off by default")
            assertEquals(CashDestination.ReceiptsHistory, vm.state.value.destination)
            assertNull(vm.state.value.crew.followUp, "the choices clear with the draft")
        }

    @Test
    fun `payroll is offered only when the settings say so`() = runTest(dispatcher) {
        val fake = FakeCash(writesSucceed = true).apply {
            myFloatRows = listOf(float("f1"))
            settingsDoc = CashSettings(reimburseToPayroll = true)
        }
        val repository = Recording(fake)
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.SubmitReceipts))
        advanceUntilIdle()
        assertTrue(vm.state.value.crew.payrollAllowed)

        vm.onEvent(CrewEvent.PickReimbursement(ReimbursementMethod.Payroll))
        vm.onEvent(CashEvent.EditReceipt(0, receipt(amount = "60")))
        vm.onEvent(CashEvent.SubmitReceipts)
        advanceUntilIdle()
        assertEquals(ReimbursementMethod.Payroll, repository.submitted?.settlementDetails?.paymentMethod)
        assertNull(repository.submitted?.settlementDetails?.bankDetails)
    }

    @Test
    fun `picking another float re-reads its batches`() = runTest(dispatcher) {
        val repository = Recording(
            FakeCash(writesSucceed = true).apply { myFloatRows = listOf(float("f1", 10), float("f2", 20)) },
        )
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.SubmitReceipts))
        advanceUntilIdle()
        assertEquals("f1", vm.state.value.submittableFloat?.id, "the oldest by default")

        vm.onEvent(CrewEvent.PickSubmitFloat("f2"))
        advanceUntilIdle()
        assertEquals("f2", vm.state.value.submittableFloat?.id)
        assertEquals("f2" to null, repository.batchReads.last())
    }

    // -- receipts history -------------------------------------------------------------

    @Test
    fun `history asks for this pipeline's batches and filters ready-to-post with overrides`() = runTest(dispatcher) {
        val repository = Recording(FakeCash(writesSucceed = true)).apply {
            batchRows = listOf(
                batch("a", "f1", BatchStatus.AcctOverride, 1.0),
                batch("b", "f1", BatchStatus.Coding, 1.0),
            )
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.ReceiptsHistory))
        advanceUntilIdle()
        assertEquals(null to "pc", repository.batchReads.last())

        val ready = vm.state.value.myBatches.filter { HistoryFilter.matches(BatchStatus.ReadyToPost.wire, it) }
        assertEquals(listOf("a"), ready.map { it.id })
    }

    // -- float request -----------------------------------------------------------------

    @Test
    fun `every template field is required but the department, with the web's messages`() = runTest(dispatcher) {
        val repository = Recording(FakeCash(writesSucceed = true))
        val vm = viewModel(repository, template = template())
        vm.onEvent(CashEvent.Open(CashDestination.FloatRequest))
        advanceUntilIdle()
        assertFalse(vm.state.value.crew.floatFormOpen, "crew land on their floats")

        vm.onEvent(CrewEvent.ShowFloatForm(true))
        vm.onEvent(CashEvent.EditFloatRequest(FloatRequestDraft(amount = "0")))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()
        assertEquals("Requested Amount must be greater than 0.", vm.state.value.crew.floatSubmitError)

        vm.onEvent(CashEvent.EditFloatRequest(FloatRequestDraft(amount = "250", durationType = "days", duration = "0")))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()
        assertEquals("Days must be a positive number.", vm.state.value.crew.floatSubmitError)

        vm.onEvent(CashEvent.EditFloatRequest(FloatRequestDraft(amount = "250", durationType = "run_of_show")))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()
        assertEquals("Please fix the highlighted fields.", vm.state.value.crew.floatSubmitError)
        val errors = vm.state.value.crew.floatErrors
        assertEquals(setOf(CashFormFields.COLLECT_DATE, CashFormFields.PURPOSE), errors.keys)
        assertNull(repository.floatRequest)

        vm.onEvent(
            CashEvent.EditFloatRequest(
                FloatRequestDraft(
                    amount = "250",
                    durationType = "run_of_show",
                    collectDate = "2099-01-01",
                    purpose = "Props",
                ),
            ),
        )
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        val sent = assertNotNull(repository.floatRequest)
        assertEquals("GBP", sent.currency, "the project default when none is picked")
        assertEquals("d-camera", sent.departmentId)
        assertEquals("production_office", sent.collectionMethod, "a select starts on its first option")
        assertEquals(CrewDates.utcMillis("2099-01-01"), sent.collectDate)
        assertNull(sent.targetUserId, "crew raise their own")
        assertFalse(vm.state.value.crew.floatFormOpen, "back to the float list")
    }

    @Test
    fun `the project cap refuses a bigger request`() = runTest(dispatcher) {
        val fake = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(requestCap = RequestCap(enabled = true, maxAmount = 200.0))
        }
        val repository = Recording(fake)
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.FloatRequest))
        advanceUntilIdle()

        vm.onEvent(CashEvent.EditFloatRequest(FloatRequestDraft(amount = "250", purpose = "Props")))
        vm.onEvent(CashEvent.SubmitFloatRequest)
        advanceUntilIdle()

        assertEquals("Requested Amount exceeds the project cap of £200.00.", vm.state.value.crew.floatSubmitError)
        assertNull(repository.floatRequest)
    }

    // -- fixtures -------------------------------------------------------------------------

    private fun template() = FormTemplate(
        listOf(
            FormSection(
                key = CashFormFields.FLOAT_REQUEST,
                label = "Float Request",
                fields = listOf(
                    FormField(label = "user_id", name = "Requested By", order = 1, systemDefault = true),
                    FormField(label = "department_id", name = "Department", order = 2, systemDefault = true),
                    FormField(label = "requested_amount", name = "Requested Amount", order = 3, systemDefault = true),
                    FormField(label = "duration_type", name = "How long", order = 4, systemDefault = true),
                    FormField(label = "duration", name = "Days", order = 5, systemDefault = true),
                    FormField(label = "start_date", name = "Start Date", order = 6, systemDefault = true),
                    FormField(label = "collection_method", name = "Collection", order = 7, systemDefault = true),
                    FormField(label = "collect_date", name = "Collect Date", order = 8, systemDefault = true),
                    FormField(label = "purpose", name = "Purpose", order = 9, systemDefault = true, type = "textarea"),
                ),
            ),
        ),
    )

    private fun receipt(amount: String) = DraftReceipt(
        description = "B&Q",
        amount = amount,
        date = DAY,
        attachment = CashAttachment(media = "k/r.jpg", name = "r.jpg"),
        attachmentKey = "k/r.jpg",
    )

    private fun float(id: String, createdAt: Long = 10) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u1", holderName = "", departmentId = null,
        status = FloatStatus.Spending, currency = "EUR", requestedAmount = 100.0, issuedAmount = 100.0,
        balance = 40.0, receiptsAmount = 0.0, receiptsCommits = null, returnAmount = 0.0, bsCode = null,
        companyId = null, duration = null, durationType = null, purpose = null, createdAt = createdAt,
    )

    private fun batch(id: String, floatId: String, status: BatchStatus, total: Double) =
        FakeCash(writesSucceed = true).queuedBatch(id = id, status = status)
            .copy(floatRequestId = floatId, totalGross = total)

    private companion object {
        val DAY = CrewDates.utcMillis("2026-09-01")
    }
}
