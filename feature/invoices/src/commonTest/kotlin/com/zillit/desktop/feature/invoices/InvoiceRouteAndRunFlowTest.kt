package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.RunSignOff
import com.zillit.desktop.feature.invoices.domain.TeamMember
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

/**
 * The host's route, the senior-only page and the payment run's sign-off —
 * driven through the view model, so the handlers are what is tested, not the
 * screen that hides a button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceRouteAndRunFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- routes ---------------------------------------------------------------

    @Test
    fun `a route asked for before start lands once the viewer is known`() = runTest(dispatcher) {
        val vm = viewModel(Repo(), junior(), start = false)
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/register"))
        vm.start()
        advanceUntilIdle()
        assertEquals(AccountantPage.Register, vm.state.value.page)
    }

    @Test
    fun `every route change is honoured, and the bare path is overview`() = runTest(dispatcher) {
        val vm = viewModel(Repo(), junior())
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/entry"))
        advanceUntilIdle()
        assertEquals(AccountantPage.Entry, vm.state.value.page)

        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Credits))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices"))
        advanceUntilIdle()
        assertEquals(AccountantPage.Overview, vm.state.value.page)
    }

    @Test
    fun `settings is refused to a non-senior by route and by sidebar event`() = runTest(dispatcher) {
        val vm = viewModel(Repo(), junior())
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Settings))
        advanceUntilIdle()
        assertEquals(AccountantPage.Overview, vm.state.value.page)

        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/settings"))
        advanceUntilIdle()
        assertEquals(AccountantPage.Overview, vm.state.value.page)
    }

    /**
     * Seniority by the settings flag arrives after the route; the route waits
     * for it rather than refusing, then opens Settings once it is confirmed.
     */
    @Test
    fun `a settings route waits for the settings document to confirm seniority`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = Repo(settings = InvoiceSettings(isSenior = true), settingsGate = gate)
        val vm = viewModel(repo, junior(), start = false)
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/settings"))
        vm.start()
        advanceUntilIdle()
        assertEquals(AccountantPage.Overview, vm.state.value.page)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountantPage.Settings, vm.state.value.page)
    }

    @Test
    fun `a senior by designation reaches settings straight away`() = runTest(dispatcher) {
        val vm = viewModel(Repo(), junior().copy(designationIdentifier = "designation_financial_controller_accounts"))
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/settings"))
        advanceUntilIdle()
        assertEquals(AccountantPage.Settings, vm.state.value.page)
    }

    @Test
    fun `the department view has no pages and ignores the route`() = runTest(dispatcher) {
        val crew = InvoiceViewer(userId = "me", departmentIdentifier = "department_camera", ready = true)
        val vm = viewModel(Repo(), crew)
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/register"))
        advanceUntilIdle()
        assertEquals(AccountantPage.Overview, vm.state.value.page)
    }

    // -- payment runs -----------------------------------------------------------

    @Test
    fun `signing a run sends the next tier and re-reads the run`() = runTest(dispatcher) {
        val repo = Repo(
            settings = InvoiceSettings(runAuthorisation = CHAIN),
            detail = PaymentRunDetail(RUN.copy(approvals = listOf(RunSignOff(1, "u2")))),
        )
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.OpenRun(RUN))
        advanceUntilIdle()

        vm.onEvent(InvoicesEvent.ApproveRun(RUN))
        advanceUntilIdle()
        assertEquals(listOf(Triple("r1", 2, 2)), repo.approved)
        assertTrue(repo.detailReads >= 2, "the run is read again after signing")
    }

    @Test
    fun `a reader not on the next tier cannot sign, whatever the screen shows`() = runTest(dispatcher) {
        val repo = Repo(settings = InvoiceSettings(runAuthorisation = CHAIN), detail = PaymentRunDetail(RUN))
        // "me" is on tier two only; tier one is next.
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.ApproveRun(RUN))
        vm.onEvent(InvoicesEvent.StartRejectRun(RUN))
        advanceUntilIdle()
        assertTrue(repo.approved.isEmpty())
        assertNull(vm.state.value.rejectRun)
    }

    @Test
    fun `cancelling a run asks first, and needs run access`() = runTest(dispatcher) {
        val withAccess = InvoiceSettings(teamMembers = listOf(TeamMember(userId = "me", runAccess = true)))
        val repo = Repo(settings = withAccess, detail = PaymentRunDetail(RUN))
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.OpenRun(RUN))
        advanceUntilIdle()

        vm.onEvent(InvoicesEvent.RequestCancelRun)
        assertTrue(assertNotNull(vm.state.value.runDetail).confirmCancel)
        assertTrue(repo.deleted.isEmpty(), "nothing is deleted until confirmed")

        vm.onEvent(InvoicesEvent.ConfirmCancelRun)
        advanceUntilIdle()
        assertEquals(listOf("r1"), repo.deleted)
        assertNull(vm.state.value.runDetail)
    }

    @Test
    fun `without run access the run cannot be cancelled`() = runTest(dispatcher) {
        val repo = Repo(detail = PaymentRunDetail(RUN))
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.OpenRun(RUN))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.RequestCancelRun)
        vm.onEvent(InvoicesEvent.ConfirmCancelRun)
        advanceUntilIdle()
        assertTrue(repo.deleted.isEmpty())
    }

    // -- credit notes -----------------------------------------------------------

    @Test
    fun `resolving a disputed note applies it rather than disputing it again`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.ActOnCreditNote(CreditNote(id = "cn1", status = CreditNoteStatus.Disputed)))
        advanceUntilIdle()
        assertEquals(listOf("apply:cn1"), repo.creditActions)
    }

    // -- harness -------------------------------------------------------------------

    private fun junior() = InvoiceViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_assistant_production_accountant_accounts",
        ready = true,
    )

    private fun viewModel(repo: Repo, viewer: InvoiceViewer, start: Boolean = true) = InvoicesViewModel(
        repository = repo,
        files = NoFiles,
        resolveViewer = { viewer },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { 0L },
    ).also {
        if (start) {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    private class Repo(
        private val settings: InvoiceSettings = InvoiceSettings(),
        private val settingsGate: CompletableDeferred<Unit>? = null,
        private val detail: PaymentRunDetail = PaymentRunDetail(RUN),
    ) : InvoicesRepository {
        val approved = mutableListOf<Triple<String, Int, Int>>()
        val deleted = mutableListOf<String>()
        val creditActions = mutableListOf<String>()
        var detailReads = 0

        override suspend fun settings(): ZillitResult<InvoiceSettings> {
            settingsGate?.await()
            return ZillitResult.Success(settings)
        }
        override suspend fun paymentRun(id: String): ZillitResult<PaymentRunDetail> {
            detailReads++
            return ZillitResult.Success(detail)
        }
        override suspend fun paymentRuns(): ZillitResult<List<PaymentRun>> = ZillitResult.Success(listOf(RUN))
        override suspend fun approvePaymentRun(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> {
            approved += Triple(id, tierNumber, totalTiers)
            return ZillitResult.Success(Unit)
        }
        override suspend fun deletePaymentRun(id: String): ZillitResult<Unit> {
            deleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun applyCreditNote(id: String): ZillitResult<Unit> {
            creditActions += "apply:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun disputeCreditNote(id: String): ZillitResult<Unit> {
            creditActions += "dispute:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> = ZillitResult.Success(Invoice(id))
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
            ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
    }

    private object NoFiles : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = emptyList()
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Success(InvoiceAttachment("", "", "", "", "", ""))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private companion object {
        val RUN = PaymentRun(id = "r1", number = "PR-001", status = PaymentRunStatus.Pending)

        /** Tier one: u2. Tier two: me. */
        val CHAIN = listOf(RunAuthLevel(1, listOf("u2")), RunAuthLevel(2, listOf("me")))
    }
}
