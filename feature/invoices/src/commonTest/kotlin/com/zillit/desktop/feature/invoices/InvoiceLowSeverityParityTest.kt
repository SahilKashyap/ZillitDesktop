package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.parseAnalytics
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BadgeTone
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEffect
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.pages.historyActor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web-parity pass of 2026-10-12 over the invoices module: the Query chip and its read, the
 * search words of Entry and Posted, the history actor, the OCR pill, the Analytics row flags, the
 * Approval pill's exact pay-method test and the hold's success toast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceLowSeverityParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the Query chip and its read -----------------------------------------------------------

    @Test
    fun `the query scope is the register for an accountant and My Invoices for a department`() {
        val accountant = InvoicesUiState(viewer = ACCOUNTANT, page = AccountantPage.Register)
        assertEquals("invoice_register", accountant.detailQueryKey)
        assertNull(accountant.copy(page = AccountantPage.Payments).detailQueryKey)
        assertNull(accountant.copy(page = AccountantPage.ApprovalQueue).detailQueryKey)

        val crew = InvoicesUiState(viewer = CREW, departmentTab = DepartmentTab.MyInvoices)
        assertEquals("my_invoices", crew.detailQueryKey)
        assertNull(crew.copy(departmentTab = DepartmentTab.MyDepartment).detailQueryKey)
        assertNull(crew.copy(departmentTab = DepartmentTab.ApprovalQueue).detailQueryKey)

        assertEquals("invoice_register", accountant.copy(page = AccountantPage.Matching).overlayQueryKey)
        assertNull(crew.overlayQueryKey)
    }

    @Test
    fun `the chip shows the row's unread under that scope and nowhere else`() {
        val state = InvoicesUiState(
            viewer = ACCOUNTANT,
            page = AccountantPage.Register,
            unreadRows = mapOf("invoice_register" to mapOf("i1" to 3), "payment_runs" to mapOf("i1" to 9)),
        )
        assertEquals(3, state.queryUnread(state.detailQueryKey, "i1"))
        assertEquals(0, state.queryUnread(state.detailQueryKey, "i2"))
        assertEquals(0, state.copy(page = AccountantPage.Payments).let { it.queryUnread(it.detailQueryKey, "i1") })
    }

    @Test
    fun `opening a query reads the thread's unread under the scope the dialog passed`() = runTest(dispatcher) {
        val reads = Reads()
        val vm = viewModel(Repo(), ACCOUNTANT, reads)
        vm.onEvent(QueryEvent.Open(INVOICE, readKey = "invoice_register"))
        advanceUntilIdle()
        assertEquals(listOf("invoice_register:i1:query_chat"), reads.reads)

        vm.onEvent(QueryEvent.Open(INVOICE))
        advanceUntilIdle()
        assertEquals(1, reads.reads.size, "a surface the web gives no query scope reads nothing")
    }

    // -- the hold's success toast ---------------------------------------------------------------

    @Test
    fun `a hold toasts the server's own word, and says so itself when it sent none`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = viewModel(repo, ACCOUNTANT)
        val notices = mutableListOf<String>()
        val watching = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.effects.collect { if (it is InvoicesEffect.Notice) notices += it.text }
        }
        hold(vm, INVOICE)
        assertEquals(listOf("i1"), repo.held)
        assertEquals(listOf("Held for query"), notices)

        repo.message = null
        hold(vm, INVOICE)
        assertEquals(2, notices.size)
        assertTrue(notices.last().isNotBlank(), "the fallback, not silence")
        watching.cancel()
    }

    private fun hold(vm: InvoicesViewModel, invoice: Invoice) {
        vm.onEvent(InvoicesEvent.StartHold(invoice))
        vm.onEvent(InvoicesEvent.HoldReasonChanged(HoldReason.entries.first()))
        vm.onEvent(InvoicesEvent.ConfirmHold)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // -- Entry and Posted search ------------------------------------------------------------------

    @Test
    fun `entry search reads the formatted gross and no purchase order`() {
        val state = InvoicesUiState(
            viewer = ACCOUNTANT,
            page = AccountantPage.Entry,
            projectCurrency = "GBP",
            vendors = mapOf("v1" to Vendor("v1", "Lamps Ltd")),
            invoices = listOf(INVOICE.copy(poNumber = "PO-777", linkedPos = listOf(LinkedPo("po1", "PO-777")))),
        )
        assertEquals(1, state.copy(search = "1,200.00").entryRows.size)
        assertEquals(1, state.copy(search = "£1,200").entryRows.size)
        assertEquals(0, state.copy(search = "1200.0").entryRows.size, "the raw double is not on the screen")
        assertEquals(0, state.copy(search = "po-777").entryRows.size, "Entry's search has no PO field")
        assertEquals(1, state.copy(search = "lamps").entryRows.size)
    }

    @Test
    fun `posted search reads the formatted gross, the po cell and the description's supplier`() {
        val posted = INVOICE.copy(
            vendorId = "",
            description = "Acme Props – hire of lamps",
            linkedPos = listOf(LinkedPo("po1", "PO-777")),
        )
        val state = InvoicesUiState(
            viewer = ACCOUNTANT,
            page = AccountantPage.Posted,
            projectCurrency = "GBP",
            invoices = listOf(posted),
        )
        assertEquals(1, state.copy(search = "1,200.00").shownInvoices.size)
        assertEquals(1, state.copy(search = "po-777").shownInvoices.size)
        assertEquals(0, state.copy(search = "1200.0").shownInvoices.size)
        assertEquals("Acme Props", state.pageVendorName(posted))
    }

    // -- the history actor ------------------------------------------------------------------------------

    @Test
    fun `a history row names its actor with their designation, and the system sentinel reads System`() {
        val state = InvoicesUiState(
            userNames = mapOf("u1" to "Sam Lee"),
            assignees = listOf(InvoiceAssignee("u1", "Sam Lee", role = "production_accountant")),
        )
        assertEquals("Sam Lee (Production Accountant)", historyActor(state, "u1"))
        assertEquals(historyActor(state, ""), historyActor(state, "system"))
        assertEquals("System", historyActor(state, "system"))
        assertEquals("u9", historyActor(state, "u9"), "a real id nobody resolves is kept, as the web keeps it")
    }

    // -- small rules ---------------------------------------------------------------------------------------

    @Test
    fun `an ocr confidence of zero is not an extraction`() {
        assertFalse(INVOICE.copy(ocrConfidence = null).isOcrExtracted)
        assertFalse(INVOICE.copy(ocrConfidence = 0.0).isOcrExtracted)
        assertTrue(INVOICE.copy(ocrConfidence = 0.93).isOcrExtracted)
    }

    @Test
    fun `the department Approval pill tests the stored pay method exactly`() {
        val wire = INVOICE.copy(payMethod = PayMethod.Wire, payMethodRaw = "wire")
        assertEquals(BadgeTone.Rejected, InvoiceRules.approvalBadge(wire, emptyList()).tone)
        val shouted = INVOICE.copy(payMethod = PayMethod.Wire, payMethodRaw = "WIRE")
        assertEquals(BadgeTone.Neutral, InvoiceRules.approvalBadge(shouted, emptyList()).tone)
    }

    @Test
    fun `analytics rows carry the web's highlight and bold-projected flags`() {
        val parsed = parseAnalytics(
            Json.parseToJsonElement(
                """{"depts":[{"code":"a","name":"Art","over":true,"highlight":true,"projBold":true},
                |{"code":"b","name":"Camera"}]}
                """.trimMargin(),
            ),
        )
        val (art, camera) = parsed.departments
        assertTrue(art.highlight)
        assertTrue(art.projectedBold)
        assertTrue(art.isOver)
        assertFalse(camera.highlight)
        assertFalse(camera.projectedBold)
    }

    // -- harness ---------------------------------------------------------------------------------------------

    private fun viewModel(repo: Repo, viewer: InvoiceViewer, badges: TabBadgeSource = TabBadgeSource.None) =
        InvoicesViewModel(
            repository = repo,
            files = NoFiles,
            resolveViewer = { viewer },
            projectMoney = { CurrencyRates("GBP") },
            resolveUser = { null },
            departmentName = { null },
            nowMillis = { NOW },
            badges = badges,
        ).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private class Reads : TabBadgeSource {
        val reads = mutableListOf<String>()
        override fun readEntity(key: String, entityId: String, kind: String?) {
            reads += "$key:$entityId:$kind"
        }
    }

    private class Repo : InvoicesRepository {
        val held = mutableListOf<String>()
        var message: String? = "Held for query"

        override suspend fun holdWithMessage(id: String, reason: HoldReason, notes: String): ZillitResult<String?> {
            held += id
            return ZillitResult.Success(message)
        }
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> = ZillitResult.Success(INVOICE)
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(InvoiceSettings())
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
            ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
    }

    private object NoFiles : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = emptyList()
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Failure(ZillitError.Unknown("none"))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L

        val ACCOUNTANT = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )
        val CREW = InvoiceViewer(userId = "me", departmentIdentifier = "department_camera", ready = true)

        val INVOICE = Invoice(
            id = "i1",
            invoiceNumber = "V-1",
            vendorId = "v1",
            departmentId = "d1",
            currency = "GBP",
            grossAmount = 1200.0,
            payMethod = PayMethod.Bacs,
            status = InvoiceStatus.Approval,
        )
    }
}
