package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrency
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUps
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Top-Ups, Cash Extension, Fund Requests and Cash Recon against the web's
 * rules (`PCTopUpsPage.jsx`, `TopUpExtensionPanel.jsx`,
 * `RequestCashFundsModal.jsx`, `PCCashReconPage.jsx`) — what reaches the
 * server, with which keys, and what is stopped before it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFundsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val crew = CashViewer(userId = "u2", departmentIdentifier = "department_art", designationIdentifier = null)

    private val euros = CashCurrencies(listOf(CashCurrency("EUR", "€"), CashCurrency("GBP", "£")), defaultCode = "EUR")

    private fun TestScope.viewModel(repository: CashRepository, viewer: CashViewer = accountant) =
        CashExpensesViewModel(
            repository = repository,
            viewer = { viewer },
            reference = CashReferenceSources(currencies = { euros }),
        ).also {
            it.start()
            advanceUntilIdle()
        }

    private fun TestScope.act(vm: CashExpensesViewModel, action: FundsAction) {
        vm.onEvent(CashEvent.Funds(action))
        advanceUntilIdle()
    }

    // -- Cash Extension ----------------------------------------------------------------

    @Test
    fun `cash extension lists the first toppable float's top-ups, and another float's on select`() =
        runTest(dispatcher) {
            val repository = FundsFake().apply {
                base.myFloatRows = listOf(
                    float("closed", FloatStatus.Closed, created = 1),
                    float("a", FloatStatus.Active, created = 2),
                    float("b", FloatStatus.Spending, created = 3),
                )
                floatTopUpRows = mapOf("a" to listOf(topUp("ta")), "b" to listOf(topUp("tb1"), topUp("tb2")))
            }
            val vm = viewModel(repository, crew)

            vm.onEvent(CashEvent.Open(CashDestination.CashExtension))
            advanceUntilIdle()
            val first = vm.state.value.fundsUi.extension
            assertEquals("a", first.floatId, "a closed float cannot be topped up")
            assertEquals(listOf("ta"), first.rows.map { it.id })

            act(vm, FundsAction.SelectExtensionFloat("b"))
            assertEquals(listOf("tb1", "tb2"), vm.state.value.fundsUi.extension.rows.map { it.id })

            act(vm, FundsAction.SelectExtensionFloat("closed"))
            assertEquals("b", vm.state.value.fundsUi.extension.floatId, "a closed float is never selected")
        }

    @Test
    fun `a top-up request needs an amount and a reason, and sends both`() = runTest(dispatcher) {
        val repository = FundsFake().apply {
            base.myFloatRows = listOf(float("a", FloatStatus.Active))
            floatTopUpRows = mapOf("a" to emptyList())
        }
        val vm = viewModel(repository, crew)
        vm.onEvent(CashEvent.Open(CashDestination.CashExtension))
        advanceUntilIdle()

        act(vm, FundsAction.OpenRequest)
        act(vm, FundsAction.EditRequest("50", " "))
        act(vm, FundsAction.SubmitRequest)
        assertNull(repository.lastTopUpRequest, "a request with no reason (ZL-20808)")
        assertNotNull(vm.state.value.fundsUi.request, "the dialog stays open to put it right")

        act(vm, FundsAction.EditRequest("50", "Fuel run"))
        act(vm, FundsAction.SubmitRequest)
        assertEquals(Triple("a", 50.0, "Fuel run"), repository.lastTopUpRequest)
        assertNull(vm.state.value.fundsUi.request, "the dialog closes once sent")
    }

    // -- the accountant's top-ups ----------------------------------------------------------

    @Test
    fun `mark topped up goes at once, and past the float's limit shows the web's alert instead`() =
        runTest(dispatcher) {
            val repository = FundsFake().apply {
                base.topUpRows = listOf(
                    topUp("fits", amount = 50.0, balance = 20.0, limit = 100.0),
                    topUp("over", amount = 90.0, balance = 20.0, limit = 100.0),
                )
            }
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.TopUps))
            advanceUntilIdle()

            act(vm, FundsAction.CompleteTopUp("over"))
            assertTrue(repository.base.calls.none { it == "completeTopUp:over" })
            val alert = assertNotNull(vm.state.value.fundsUi.limitAlert)
            assertEquals("Top-up exceeds float limit", alert.title)
            assertTrue("€90.00" in alert.message && "€80.00" in alert.message, alert.message)

            act(vm, FundsAction.DismissLimitAlert)
            act(vm, FundsAction.CompleteTopUp("fits"))
            assertTrue("completeTopUp:fits" in repository.base.calls, "no confirmation step")
        }

    @Test
    fun `a partial top-up opens at the full amount, needs its note, and closes once recorded`() =
        runTest(dispatcher) {
            val repository = FundsFake().apply { base.topUpRows = listOf(topUp("t1", amount = 60.0)) }
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.TopUps))
            advanceUntilIdle()

            act(vm, FundsAction.OpenPartial("t1"))
            assertEquals("60", vm.state.value.fundsUi.partial?.amount)

            act(vm, FundsAction.SubmitPartial)
            assertTrue(repository.base.calls.none { it.startsWith("partialTopUp") }, "no note")

            act(vm, FundsAction.EditPartial("40", "Only 40 in the safe"))
            act(vm, FundsAction.SubmitPartial)
            assertTrue("partialTopUp:t1:40.0:Only 40 in the safe" in repository.base.calls)
            assertNull(vm.state.value.fundsUi.partial)
        }

    @Test
    fun `the inbox filters completed with partials, and sorts oldest, newest and largest`() {
        val rows = listOf(
            topUp("p", created = 2, amount = 10.0),
            topUp("c", status = CashTopUps.COMPLETED, created = 1, amount = 30.0),
            topUp("h", status = CashTopUps.PARTIAL, created = 3, amount = 20.0),
            topUp("s", status = CashTopUps.SKIPPED, created = 4, amount = 5.0),
        )
        val (pending, settled) = CashTopUps.sections(rows, CashTopUps.Filter.Completed, CashTopUps.Sort.Oldest)
        assertTrue(pending.isEmpty())
        assertEquals(listOf("c", "h"), settled.map { it.id })
        assertEquals(listOf("s", "h", "p", "c"), CashTopUps.sorted(rows, CashTopUps.Sort.Newest).map { it.id })
        assertEquals(listOf("c", "h", "p", "s"), CashTopUps.sorted(rows, CashTopUps.Sort.Largest).map { it.id })
    }

    // -- fund requests --------------------------------------------------------------

    @Test
    fun `a fund request sends the project default currency when none is picked, and receive goes at once`() =
        runTest(dispatcher) {
            val repository = FundsFake().apply {
                fundRows = listOf(fund("r1", FundRequest.REQUESTED), fund("r2", FundRequest.CANCELLED, amount = 75.0))
            }
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
            advanceUntilIdle()
            vm.onEvent(CashEvent.ShowFunds(true))
            advanceUntilIdle()
            val funds = assertNotNull(vm.state.value.funds)

            vm.onEvent(CashEvent.EditFunds(funds.copy(fundAccount = "1100", amount = "250")))
            vm.onEvent(CashEvent.SubmitFunds)
            advanceUntilIdle()
            assertEquals(Triple("1100", "EUR", 250.0), repository.lastFunds)

            act(vm, FundsAction.ReceiveFunds("r1"))
            assertTrue("receiveFunds:r1" in repository.base.calls, "no confirmation step")

            act(vm, FundsAction.DuplicateFunds("r2"))
            assertEquals("75", vm.state.value.funds?.amount)
            assertEquals("CUST", vm.state.value.funds?.fundAccount)
        }

    // -- cash reconciliation -----------------------------------------------------------

    @Test
    fun `the recon list asks for no book balance, and a new period takes the default currency`() =
        runTest(dispatcher) {
            val repository = FundsFake()
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.CashReconciliation))
            advanceUntilIdle()
            assertEquals(0, repository.bookAsks, "the web never asks compute-book bare")

            act(vm, FundsAction.CreateReconciliation("500", 2026, 9, ""))
            val draft = assertNotNull(vm.state.value.recon)
            assertEquals("EUR", draft.currency)
            assertEquals("r1", draft.id)
            assertEquals(1, repository.bookAsks, "the count asks once for its opening balance")
        }

    @Test
    fun `the book balance is asked half a second after the last change`() = runTest(dispatcher) {
        val repository = FundsFake()
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.CashReconciliation))
        advanceUntilIdle()
        act(vm, FundsAction.CreateReconciliation("500", 2026, 9, "GBP"))
        val asked = repository.bookAsks

        val draft = assertNotNull(vm.state.value.recon)
        vm.onEvent(CashEvent.EditReconciliation(draft.copy(openingBalance = "5000")))
        runCurrent()
        vm.onEvent(CashEvent.EditReconciliation(draft.copy(openingBalance = "50000")))
        runCurrent()
        assertTrue(vm.state.value.recon?.computingBook == true)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(asked, repository.bookAsks, "still typing")
        advanceUntilIdle()
        assertEquals(asked + 1, repository.bookAsks, "one ask for the last value")
        assertFalse(vm.state.value.recon?.computingBook == true)
    }

    @Test
    fun `submit for review goes at once, and again while under review`() = runTest(dispatcher) {
        val junior = accountant.copy(designationIdentifier = null)
        val repository = FundsFake()
        val vm = viewModel(repository, junior)
        vm.onEvent(CashEvent.Open(CashDestination.CashReconciliation))
        advanceUntilIdle()
        act(vm, FundsAction.CreateReconciliation("500", 2026, 9, "GBP"))

        act(vm, FundsAction.SubmitReconciliation)
        act(vm, FundsAction.SubmitReconciliation)
        assertEquals(2, repository.base.calls.count { it == "submitRecon:r1" })
    }

    // -- fixtures ---------------------------------------------------------------------

    /** [FakeCash], with the reads and writes these pages need recorded. */
    private class FundsFake(val base: FakeCash = FakeCash(writesSucceed = true)) : CashRepository by base {
        var floatTopUpRows: Map<String, List<CashTopUp>> = emptyMap()
        var fundRows: List<FundRequest> = emptyList()
        var lastTopUpRequest: Triple<String, Double, String?>? = null
        var lastFunds: Triple<String, String?, Double>? = null
        var bookAsks = 0

        override suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>> =
            ZillitResult.Success(floatTopUpRows[floatId].orEmpty())

        override suspend fun requestFloatTopUp(floatId: String, amount: Double, reason: String?): ZillitResult<Unit> {
            lastTopUpRequest = Triple(floatId, amount, reason)
            return base.requestFloatTopUp(floatId, amount, reason)
        }

        override suspend fun fundRequests(): ZillitResult<List<FundRequest>> = ZillitResult.Success(fundRows)

        override suspend fun createFundRequest(
            fundAccount: String,
            currency: String?,
            amount: Double,
        ): ZillitResult<Unit> {
            lastFunds = Triple(fundAccount, currency, amount)
            return base.createFundRequest(fundAccount, currency, amount)
        }

        override suspend fun computeBookBalance(draft: ReconDraft?): ZillitResult<Double> {
            bookAsks++
            return ZillitResult.Success(draft?.opening ?: 0.0)
        }

        override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> = ZillitResult.Success(emptyList())
    }

    private fun float(id: String, status: FloatStatus, created: Long = 0) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u2", holderName = "", departmentId = null, status = status,
        currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0, balance = 40.0, receiptsAmount = 0.0,
        receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null, duration = null,
        durationType = null, purpose = null, createdAt = created,
    )

    @Suppress("LongParameterList") // A fixture: every figure the inbox reads.
    private fun topUp(
        id: String,
        status: String = CashTopUps.PENDING,
        amount: Double = 100.0,
        balance: Double = 20.0,
        limit: Double = 100.0,
        created: Long? = null,
    ) = CashTopUp(
        id = id, userId = "u2", holderName = "", amount = amount, currency = null, status = status, note = null,
        floatRequestNumber = "PC-1", floatIssued = 100.0, floatBalance = balance, floatRequestedAmount = limit,
        createdAt = created,
    )

    private fun fund(id: String, status: String, amount: Double = 100.0) = FundRequest(
        id = id, fundAccount = "CUST", currency = null, amount = amount, receivedAmount = null, status = status,
        requestedBy = "me", requestedAt = null, receivedBy = null, receivedAt = null,
    )
}
