package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.invoices.data.INVOICE_ROW_SYNC_EVENTS
import com.zillit.desktop.feature.invoices.data.enteredInvoiceBody
import com.zillit.desktop.feature.invoices.data.parseAnalytics
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.Company
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceProjectSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.OpenItemRow
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The P2 parity items: Pre-approval's PO rule, Open Items groups, Wires, Enter Invoice, Analytics, sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceP2ParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `pre-approval counts a linked order or a po id as matched, never a bare typed number`() {
        assertTrue(Invoice(id = "a", linkedPos = listOf(LinkedPo("p", "PO-1", "", null))).hasMatchedPo)
        assertTrue(Invoice(id = "b", poId = "p").hasMatchedPo)
        val typed = Invoice(id = "c", poNumber = "PO-9")
        assertFalse(typed.hasMatchedPo)
        assertTrue(typed.hasPo, "the register's looser rule is unchanged")
    }

    @Test
    fun `a paid invoice goes as paid, never beside the inbox status`() {
        val paid = enteredInvoiceBody(ENTERED.copy(paid = true, companyId = "co1"))
        assertEquals("true", paid["paid"]!!.jsonPrimitive.content)
        assertFalse("status" in paid)
        assertEquals("co1", paid["company_id"]!!.jsonPrimitive.content)
        val unpaid = enteredInvoiceBody(ENTERED)
        assertEquals("inbox", unpaid["status"]!!.jsonPrimitive.content)
        assertFalse("paid" in unpaid)
    }

    @Test
    fun `analytics reads spend by department apart from the cost report table`() {
        val analytics = parseAnalytics(
            Json.parseToJsonElement(
                """{"depts":[{"code":"CAM","name":"d1","posted":"£1"}],
                   "departments":[{"name":"d1","amount":"£1,200.00","pct":60}],
                   "suppliers":[{"name":"Lamps","amount":"£800.00","pct":40}]}""",
            ),
        )
        assertEquals(1, analytics.departments.size)
        assertEquals("£1,200.00", analytics.departmentSpend.single().amount)
        assertEquals(60.0, analytics.departmentSpend.single().percent)
    }

    @Test
    fun `credit notes, sales invoices, runs and accruals all refresh the open page live`() {
        listOf("creditNote:created", "salesInvoice:sent", "activeRun:created", "accruals:generated").forEach {
            assertTrue(SocketEventName(it) in INVOICE_ROW_SYNC_EVENTS, it)
        }
    }

    @Test
    fun `open items are grouped by vendor and currency, and a group shuts and ticks as one`() = runTest(dispatcher) {
        val rows = listOf(
            WIRE.copy(id = "a", vendorId = "v1", payMethod = PayMethod.Bacs),
            WIRE.copy(id = "b", vendorId = "v1", payMethod = PayMethod.Bacs),
            WIRE.copy(id = "c", vendorId = "v1", currency = "USD", payMethod = PayMethod.Bacs),
        )
        val repo = Repo(rows)
        val vm = payments(repo)
        val headers = vm.state.value.openItemRows.filterIsInstance<OpenItemRow.Header>()
        assertEquals(listOf("v1|GBP", "v1|USD"), headers.map { it.group.key })
        // Payment Runs opens with everything ticked, as the web does.
        assertEquals(setOf("a", "b", "c"), vm.state.value.selected)
        vm.onEvent(InvoicesEvent.SelectGroup(listOf("a", "b")))
        assertEquals(setOf("c"), vm.state.value.selected)
        vm.onEvent(InvoicesEvent.SelectGroup(listOf("a", "b")))
        assertEquals(setOf("a", "b", "c"), vm.state.value.selected)
        vm.onEvent(InvoicesEvent.ToggleGroupOpen("v1|GBP"))
        assertEquals(3, vm.state.value.openItemRows.size, "a shut group keeps only its header")
    }

    @Test
    fun `a wire is marked paid from its own row, and nothing else is`() = runTest(dispatcher) {
        val repo = Repo(listOf(WIRE, WIRE.copy(id = "bacs", payMethod = PayMethod.Bacs)))
        val vm = payments(repo)
        vm.onEvent(InvoicesEvent.MarkPaidOne(WIRE.copy(id = "bacs", payMethod = PayMethod.Bacs)))
        advanceUntilIdle()
        assertTrue(repo.paid.isEmpty())
        vm.onEvent(InvoicesEvent.MarkPaidOne(WIRE))
        advanceUntilIdle()
        assertEquals(listOf(listOf("w1")), repo.paid)
    }

    @Test
    fun `enter invoice picks the only bank and its account holder as the company`() = runTest(dispatcher) {
        val repo = Repo(emptyList())
        val vm = payments(repo)
        vm.onEvent(InvoicesEvent.OpenEnter)
        val form = assertNotNull(vm.state.value.enter)
        assertEquals("b1", form.bankId)
        assertEquals("co2", form.companyId)
        assertFalse(form.paid)
    }

    // -- harness ---------------------------------------------------------------------------

    private fun payments(repo: Repo) = InvoicesViewModel(
        repository = repo,
        files = NoFiles,
        resolveViewer = { SENIOR },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { NOW },
    ).also {
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
        it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Payments))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class Repo(private val rows: List<Invoice>) : InvoicesRepository {
        val paid = mutableListOf<List<String>>()

        override suspend fun markPaid(ids: List<String>): ZillitResult<Unit> {
            paid += ids
            return ZillitResult.Success(Unit)
        }
        override suspend fun projectSettings() = ZillitResult.Success(
            InvoiceProjectSettings(companies = listOf(Company("co1", "One"), Company("co2", "Two"))),
        )
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(rows)
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> = ZillitResult.Success(Invoice(id = id))
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(InvoiceSettings())
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> = ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> =
            ZillitResult.Success(listOf(Vendor("v1", "Lamps Ltd")))
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> =
            ZillitResult.Success(listOf(BankAccount("b1", "Main", entityId = "co2")))
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
        const val NOW = 1_790_164_800_000L

        val SENIOR = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )

        val WIRE = Invoice(
            id = "w1",
            vendorId = "v1",
            currency = "GBP",
            grossAmount = 100.0,
            payMethod = PayMethod.Wire,
            status = InvoiceStatus.ReadyToPay,
        )

        val ENTERED = EnteredInvoice(
            attachment = InvoiceAttachment("k", "b", "r", "a.pdf", "file", "pdf"),
            invoiceNumber = "INV-1",
            vendorId = "v1",
            description = "",
            grossAmount = 10.0,
            invoiceDateMs = NOW,
            dueDateMs = NOW,
            effectiveDateMs = null,
            payMethod = PayMethod.Bacs,
            currency = "GBP",
        )
    }
}
