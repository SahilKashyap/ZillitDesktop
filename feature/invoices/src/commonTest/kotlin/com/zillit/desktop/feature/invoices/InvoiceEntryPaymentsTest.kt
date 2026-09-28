package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.parseRuns
import com.zillit.desktop.feature.invoices.data.parseSalesInvoices
import com.zillit.desktop.feature.invoices.data.runBody
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.AssignmentReason
import com.zillit.desktop.feature.invoices.domain.EntryFilter
import com.zillit.desktop.feature.invoices.domain.EntrySort
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceDirectory
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.domain.VendorSpendReport
import com.zillit.desktop.feature.invoices.domain.canAccessEntryRow
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent
import com.zillit.desktop.feature.invoices.ui.SalesInvoiceDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four screens the entry-and-pay half of the module is built on: Invoice
 * Entry, Payment Runs, Vendors and Sales Invoices.
 *
 * The rules under test are the ones a screenshot cannot show — who may touch a
 * row, what a mixed selection does, how a run is named and numbered, and which
 * total the server's own sum wins against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceEntryPaymentsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- Invoice Entry ------------------------------------------------------

    @Test
    fun `the entry queue asks for what approval cleared or bypassed`() = runTest(dispatcher) {
        val repo = EntryRepo()
        val vm = open(repo, AccountantPage.Entry, senior())
        advanceUntilIdle()
        assertEquals(
            listOf(InvoiceStatus.Approved, InvoiceStatus.UnderReview, InvoiceStatus.Override),
            repo.queries.last().statuses,
        )
        assertEquals(4, vm.state.value.entryRows.size)
    }

    @Test
    fun `the filter row narrows by who holds it and how ready it is`() = runTest(dispatcher) {
        val vm = open(EntryRepo(), AccountantPage.Entry, senior())
        advanceUntilIdle()

        vm.onEvent(InvoicesEvent.SelectEntryFilter(EntryFilter.AssignedToMe))
        assertEquals(listOf("mine"), vm.state.value.entryRows.map { it.id })

        vm.onEvent(InvoicesEvent.SelectEntryFilter(EntryFilter.Unassigned))
        assertEquals(listOf("free"), vm.state.value.entryRows.map { it.id })

        vm.onEvent(InvoicesEvent.SelectEntryFilter(EntryFilter.NeedsReview))
        assertEquals(listOf("review"), vm.state.value.entryRows.map { it.id })

        vm.onEvent(InvoicesEvent.SelectEntryFilter(EntryFilter.Ready))
        assertEquals(setOf("mine", "free", "theirs"), vm.state.value.entryRows.map { it.id }.toSet())
    }

    @Test
    fun `sorting orders by amount and by vendor, and the pay filter by code`() = runTest(dispatcher) {
        val vm = open(EntryRepo(), AccountantPage.Entry, senior())
        advanceUntilIdle()

        vm.onEvent(InvoicesEvent.SelectEntrySort(EntrySort.AmountHighLow))
        assertEquals("theirs", vm.state.value.entryRows.first().id)
        vm.onEvent(InvoicesEvent.SelectEntrySort(EntrySort.AmountLowHigh))
        assertEquals("review", vm.state.value.entryRows.first().id)

        vm.onEvent(InvoicesEvent.SelectEntrySort(EntrySort.Default))
        vm.onEvent(InvoicesEvent.SelectPayFilter(PayMethod.Faster))
        // The row was stored under the legacy `faster_payment` spelling; the
        // filter compares the canonical code, so it is still found.
        assertEquals(listOf("theirs"), vm.state.value.entryRows.map { it.id })
    }

    @Test
    fun `a non-senior can only act on what is assigned to them`() {
        val mine = row("mine", assignedTo = "acc")
        val theirs = row("theirs", assignedTo = "someone")
        val free = row("free")

        assertTrue(canAccessEntryRow(theirs, isSenior = true, viewerId = "acc"))
        assertTrue(canAccessEntryRow(mine, isSenior = false, viewerId = "acc"))
        assertFalse(canAccessEntryRow(theirs, isSenior = false, viewerId = "acc"))
        // Unassigned belongs to nobody: a non-senior cannot pick it up.
        assertFalse(canAccessEntryRow(free, isSenior = false, viewerId = "acc"))
    }

    @Test
    fun `select-all covers only the rows this viewer may open`() = runTest(dispatcher) {
        val vm = open(EntryRepo(), AccountantPage.Entry, junior())
        advanceUntilIdle()
        assertEquals(listOf("mine"), vm.state.value.entrySelectableIds)
    }

    /**
     * There is no bulk post — posting is gated on the coding screen — and the
     * review hand-off is a junior's: a senior is the reviewer (ZL-20450).
     */
    @Test
    fun `a junior hands the selection off for review, a senior cannot, and nothing is posted`() = runTest(dispatcher) {
        val repo = EntryRepo()
        val junior = open(repo, AccountantPage.Entry, junior())
        advanceUntilIdle()
        junior.onEvent(InvoicesEvent.ToggleSelect("mine"))
        junior.onEvent(InvoicesEvent.ReviewSelected)
        advanceUntilIdle()
        assertEquals(listOf("mine"), repo.reviewed)
        assertTrue(junior.state.value.selected.isEmpty())

        val senior = open(repo, AccountantPage.Entry, senior())
        advanceUntilIdle()
        senior.onEvent(InvoicesEvent.ToggleSelect("free"))
        senior.onEvent(InvoicesEvent.ReviewSelected)
        advanceUntilIdle()
        assertEquals(listOf("mine"), repo.reviewed)
        assertTrue(repo.posted.isEmpty())
    }

    @Test
    fun `assigning needs a person and a reason, and Other needs words`() = runTest(dispatcher) {
        val repo = EntryRepo()
        val vm = open(repo, AccountantPage.Entry, senior())
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.ToggleSelect("free"))
        vm.onEvent(InvoicesEvent.StartAssign)
        val opened = assertNotNull(vm.state.value.assignFor)
        assertFalse(opened.isReady)

        vm.onEvent(InvoicesEvent.EditAssign(opened.copy(userId = "u2", reason = AssignmentReason.Other)))
        assertFalse(assertNotNull(vm.state.value.assignFor).isReady)

        val withNotes = assertNotNull(vm.state.value.assignFor).copy(notes = "Sarah is away")
        vm.onEvent(InvoicesEvent.EditAssign(withNotes))
        assertTrue(assertNotNull(vm.state.value.assignFor).isReady)

        vm.onEvent(InvoicesEvent.ConfirmAssign)
        advanceUntilIdle()
        assertEquals(listOf(Triple("free", "u2", "Sarah is away")), repo.assigned)
        assertNull(vm.state.value.assignFor)
    }

    // -- Payment Runs -------------------------------------------------------

    @Test
    fun `open items group by vendor and currency, one run each`() {
        val rows = listOf(
            row("a", vendorId = "v1", currency = "GBP"),
            row("b", vendorId = "v1", currency = "GBP"),
            row("c", vendorId = "v1", currency = "USD"),
            row("d", vendorId = "v2", currency = ""),
        )
        val groups = PaymentRuns.groupByVendorCurrency(
            invoices = rows,
            vendorName = { if (it.vendorId == "v1") "Acme Lighting" else "Zed Trucks" },
            defaultCurrency = "GBP",
        )
        assertEquals(3, groups.size)
        assertEquals(listOf("Acme Lighting", "Acme Lighting", "Zed Trucks"), groups.map { it.vendorName })
        assertEquals(listOf("GBP", "USD", "GBP"), groups.map { it.currency })
        assertEquals(listOf("a", "b"), groups.first().ids)
        assertEquals("BACs Run — Acme Lighting (GBP)", groups.first().runName)
    }

    @Test
    fun `run numbers continue the sequence already on the server`() {
        val runs = listOf(paymentRun("r1", number = "PR-007"), paymentRun("r2", number = "Run 12"))
        assertEquals("PR-013", PaymentRuns.nextNumber(runs))
        assertEquals("PR-014", PaymentRuns.nextNumber(runs, offset = 1))
        assertEquals("PR-001", PaymentRuns.nextNumber(emptyList()))
    }

    @Test
    fun `the payment queue opens with everything ticked`() = runTest(dispatcher) {
        val vm = open(PayRepo(), AccountantPage.Payments, senior())
        advanceUntilIdle()
        assertEquals(setOf("bacs-1", "bacs-2", "wire-1"), vm.state.value.pay.openItemsSelected)
    }

    @Test
    fun `a one-method selection builds its runs without asking`() = runTest(dispatcher) {
        val repo = PayRepo()
        val vm = open(repo, AccountantPage.Payments, senior())
        advanceUntilIdle()
        vm.onEvent(PaymentsEvent.ToggleOpenItem("wire-1"))
        assertEquals(PayMethod.Bacs, vm.state.value.selectedPayMethod)

        vm.onEvent(InvoicesEvent.ProcessSelected(null))
        advanceUntilIdle()
        // Two vendors, so two runs, numbered on from the one already there.
        assertEquals(
            listOf("PR-004" to "BACs Run — Acme Lighting (GBP)", "PR-005" to "BACs Run — Zed Trucks (GBP)"),
            repo.created.map { it.number to it.name },
        )
        assertNull(vm.state.value.runDraft)
    }

    @Test
    fun `a mixed selection opens the sheet instead of guessing`() = runTest(dispatcher) {
        val repo = PayRepo()
        val vm = open(repo, AccountantPage.Payments, senior())
        advanceUntilIdle()
        assertNull(vm.state.value.selectedPayMethod)

        vm.onEvent(InvoicesEvent.ProcessSelected(null))
        advanceUntilIdle()
        assertTrue(repo.created.isEmpty())
        val sheet = assertNotNull(vm.state.value.runDraft)
        assertEquals(listOf(PayMethod.Bacs, PayMethod.Wire), sheet.methods)
        assertEquals(2, sheet.countFor(PayMethod.Bacs))

        vm.onEvent(InvoicesEvent.ProcessSelected(PayMethod.Wire))
        advanceUntilIdle()
        assertEquals(listOf(listOf("wire-1")), repo.paid)
    }

    @Test
    fun `a run cannot be rejected without a reason`() = runTest(dispatcher) {
        val repo = PayRepo()
        val vm = open(repo, AccountantPage.Payments, senior())
        advanceUntilIdle()
        val run = vm.state.value.paymentRuns.single()

        vm.onEvent(InvoicesEvent.StartRejectRun(run))
        vm.onEvent(InvoicesEvent.ConfirmRejectRun)
        advanceUntilIdle()
        assertTrue(repo.rejected.isEmpty())
        assertNotNull(vm.state.value.rejectRun)

        vm.onEvent(InvoicesEvent.RejectRunReasonChanged("Wrong bank details"))
        vm.onEvent(InvoicesEvent.ConfirmRejectRun)
        advanceUntilIdle()
        assertEquals(listOf("r1" to "Wrong bank details"), repo.rejected)
        assertNull(vm.state.value.rejectRun)
    }

    @Test
    fun `the wires tab holds faster payments too`() = runTest(dispatcher) {
        val vm = open(PayRepo(withFaster = true), AccountantPage.Payments, senior())
        advanceUntilIdle()
        assertEquals(setOf("wire-1", "faster-1"), vm.state.value.wireInvoices.map { it.id }.toSet())
    }

    @Test
    fun `a mixed-currency total is converted, and says so`() {
        val rates = CurrencyRates(defaultCode = "GBP", rates = mapOf("USD" to 1.25, "EUR" to 1.20))

        // One currency is left alone — converting GBP to GBP only adds error.
        val single = rates.total(listOf(100.0 to "GBP", 50.0 to "GBP"))
        assertEquals(150.0, single.amount)
        assertEquals("GBP", single.currency)
        assertFalse(single.mixed)
        assertNull(single.caveat)

        // Mixed converts through each rate and lands in the default.
        val mixed = rates.total(listOf(100.0 to "GBP", 125.0 to "USD"))
        assertEquals(200.0, mixed.amount)
        assertEquals("GBP", mixed.currency)
        assertEquals("converted to GBP", mixed.caveat)

        // A currency with no rate is added at face value and the caveat says
        // so, rather than the row being dropped or the total being silent.
        val unrated = rates.total(listOf(100.0 to "GBP", 900.0 to "JPY"))
        assertEquals(1000.0, unrated.amount)
        assertEquals("converted; some amounts had no rate", unrated.caveat)
    }

    // -- Vendors ------------------------------------------------------------

    @Test
    fun `vendor spend adds up their invoices, biggest first`() {
        val vendors = listOf(
            Vendor(id = "v1", name = "Acme Lighting", taxNumber = "GB1", bankName = "Barclays", bankId = "b1"),
            Vendor(id = "v2", name = "Zed Trucks"),
        )
        val invoices = listOf(
            row("a", vendorId = "v1", gross = 100.0, currency = "GBP"),
            row("b", vendorId = "v1", gross = 250.0, currency = "GBP"),
            row("c", vendorId = "v2", gross = 900.0, currency = "GBP"),
        )
        val rows = VendorSpendReport.rows(vendors, invoices)
        assertEquals(listOf("Zed Trucks", "Acme Lighting"), rows.map { it.vendor.name })
        assertEquals(350.0, rows.last().totalSpend)
        assertEquals(2, rows.last().invoiceCount)
        // The web's fixed labels: every master vendor is "Pending"; the bank is the linked bank_id.
        assertEquals("Pending", rows.first().complianceLabel)
        assertTrue(rows.last().hasBank)
        assertFalse(rows.first().hasBank)
        assertEquals("UK", rows.first().country, "no stored country reads as the web's default")
    }

    /**
     * `fetchSuppliers`: a vendor with no invoice by id is matched by the
     * supplier name, mixed currencies convert to the default, a net-only
     * invoice counts its net, and a supplier only named on invoices gets a row
     * of its own, compliance "Unknown".
     */
    @Test
    fun `vendor spend falls back to the name, converts currencies and keeps invoice-only suppliers`() {
        val vendors = listOf(Vendor(id = "v1", name = "Acme Lighting"), Vendor(id = "v2", name = "Zed Trucks"))
        val invoices = listOf(
            row("a", vendorId = "v1", gross = 100.0, currency = "GBP"),
            row("b", vendorId = "v1", gross = 200.0, currency = "USD"),
            row("c", vendorId = "").copy(supplierName = " zed trucks ", grossAmount = 0.0, netAmount = 40.0),
            row("d", vendorId = "").copy(supplierName = "Grip House", grossAmount = 75.0),
            row("e", vendorId = "").copy(supplierName = "grip house", grossAmount = 25.0),
        )
        val rates = CurrencyRates("GBP", mapOf("USD" to 2.0))
        val rows = VendorSpendReport.rows(vendors, invoices, rates).associateBy { it.vendor.name }

        val acme = rows.getValue("Acme Lighting")
        assertEquals(200.0, acme.totalSpend, "£100 + $200 at 2 to the pound")
        assertEquals("GBP", acme.currency)
        assertTrue(acme.mixedCurrency)

        assertEquals(40.0, rows.getValue("Zed Trucks").totalSpend, "matched by name, the net standing in for gross")

        val grip = rows.getValue("Grip House")
        assertEquals(100.0, grip.totalSpend)
        assertFalse(grip.fromMaster)
        assertEquals("Unknown", grip.complianceLabel)
        assertEquals(3, rows.size, "one row per supplier, however it is spelled")
    }

    // -- Sales Invoices -----------------------------------------------------

    @Test
    fun `a sales invoice needs a client and an invoice date`() {
        assertFalse(SalesInvoiceDraft().isReady)
        assertFalse(SalesInvoiceDraft(clientName = "Channel 4").isReady)
        assertTrue(SalesInvoiceDraft(clientName = "Channel 4", invoiceDate = "2026-09-23").isReady)

        val wrongDate = SalesInvoiceDraft(
            clientName = "Channel 4",
            invoiceDate = "2026-09-23",
            dueDate = "next Tuesday",
        )
        assertTrue(wrongDate.dateIsWrong)
        assertFalse(wrongDate.isReady)
        assertNotNull(SalesInvoiceDraft(dueDate = "2026-10-01").dueDateMs)
    }

    @Test
    fun `sending is for a draft and marking paid is for one already out`() {
        assertTrue(SalesInvoiceStatus.Draft.canSend)
        assertFalse(SalesInvoiceStatus.Sent.canSend)
        assertTrue(SalesInvoiceStatus.Sent.canMarkPaid)
        assertTrue(SalesInvoiceStatus.Overdue.canMarkPaid)
        assertFalse(SalesInvoiceStatus.Paid.canMarkPaid)
    }

    // -- the wire -----------------------------------------------------------

    /** `run.total_amount || run.computed_total || 0` — the stored figure first (`PaymentsPage.jsx:2100`). */
    @Test
    fun `a run's stored total wins over the computed one`() {
        val json = Json.parseToJsonElement(
            """
            {"data":[{"_id":"r1","number":"PR-001","name":"BACs Run",
              "pay_method":"bacs","total_amount":900,"computed_total":250.5,
              "currency":"GBP","invoice_count":2,"status":"pending"}]}
            """.trimIndent(),
        )
        val run = parseRuns(json).single()
        assertEquals(900.0, run.total)
        assertEquals(PaymentRunStatus.Pending, run.status)
        assertEquals(PayMethod.Bacs, run.payMethod)
        assertEquals(2, run.invoiceCount)
    }

    @Test
    fun `a sales invoice reads back with its client and status`() {
        val json = Json.parseToJsonElement(
            """
            {"data":[{"id":"s1","invoice_number":"SI-004","client_name":"Channel 4",
              "gross_amount":"1250.00","currency":"GBP","status":"sent"}]}
            """.trimIndent(),
        )
        val invoice = parseSalesInvoices(json).single()
        assertEquals("SI-004", invoice.reference)
        assertEquals("Channel 4", invoice.clientName)
        assertEquals(1250.0, invoice.grossAmount)
        assertEquals(SalesInvoiceStatus.Sent, invoice.status)
    }

    @Test
    fun `a run is created with its name, number, method and rows`() {
        val body = runBody("BACs Run — Acme (GBP)", "PR-002", PayMethod.Bacs, listOf("a", "b"))
        assertEquals("BACs Run — Acme (GBP)", body["name"]?.jsonPrimitive?.content)
        assertEquals("PR-002", body["number"]?.jsonPrimitive?.content)
        assertEquals("bacs", body["pay_method"]?.jsonPrimitive?.content)
        assertEquals(listOf("a", "b"), body["invoice_ids"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    // -- harness ------------------------------------------------------------

    private fun senior() = InvoiceViewer(
        userId = "acc",
        departmentIdentifier = "accounts",
        designationIdentifier = "production_accountant",
        ready = true,
    )

    private fun junior() = InvoiceViewer(userId = "acc", departmentIdentifier = "accounts", ready = true)

    private fun open(repo: InvoicesRepository, page: AccountantPage, viewer: InvoiceViewer): InvoicesViewModel {
        val vm = InvoicesViewModel(
            repository = repo,
            files = NoFiles(),
            resolveViewer = { viewer },
            projectMoney = { CurrencyRates("GBP") },
            resolveUser = { id -> USERS[id] },
            departmentName = { null },
            nowMillis = { NOW },
            directory = InvoiceDirectory(accountsTeam = { TEAM }),
        )
        vm.start()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onEvent(InvoicesEvent.SelectPage(page))
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private class EntryRepo : StubRepo() {
        val queries = mutableListOf<InvoiceQuery>()
        val posted = mutableListOf<String>()
        val assigned = mutableListOf<Triple<String, String, String>>()

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> {
            queries += query
            return ZillitResult.Success(
                listOf(
                    row("mine", assignedTo = "acc", gross = 200.0, vendorId = "v1"),
                    row("free", gross = 300.0, vendorId = "v2"),
                    row("theirs", assignedTo = "other", gross = 900.0, vendorId = "v1", payMethod = "faster_payment"),
                    row(
                        "review",
                        assignedTo = "other",
                        gross = 50.0,
                        vendorId = "v2",
                        status = InvoiceStatus.UnderReview,
                    ),
                ),
            )
        }

        override suspend fun postInvoice(id: String): ZillitResult<Unit> {
            posted += id
            return ZillitResult.Success(Unit)
        }

        val reviewed = mutableListOf<String>()

        override suspend fun markUnderReview(id: String): ZillitResult<Unit> {
            reviewed += id
            return ZillitResult.Success(Unit)
        }

        override suspend fun assign(id: String, userId: String, reason: String): ZillitResult<Unit> {
            assigned += Triple(id, userId, reason)
            return ZillitResult.Success(Unit)
        }
    }

    private class PayRepo(private val withFaster: Boolean = false) : StubRepo() {
        val created = mutableListOf<PaymentRun>()
        val paid = mutableListOf<List<String>>()
        val rejected = mutableListOf<Pair<String, String>>()

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(
            buildList {
                add(row("bacs-1", vendorId = "v1", gross = 100.0, currency = "GBP"))
                add(row("bacs-2", vendorId = "v2", gross = 200.0, currency = "GBP"))
                add(row("wire-1", vendorId = "v1", gross = 300.0, payMethod = "wire"))
                if (withFaster) add(row("faster-1", vendorId = "v2", payMethod = "faster"))
            },
        )

        override suspend fun paymentRuns(): ZillitResult<List<PaymentRun>> =
            ZillitResult.Success(listOf(paymentRun("r1", number = "PR-003")))

        override suspend fun createPaymentRun(
            name: String,
            number: String,
            payMethod: PayMethod,
            invoiceIds: List<String>,
        ): ZillitResult<Unit> {
            created += PaymentRun(id = name, number = number, name = name, payMethod = payMethod)
            return ZillitResult.Success(Unit)
        }

        override suspend fun markPaid(ids: List<String>): ZillitResult<Unit> {
            paid += ids
            return ZillitResult.Success(Unit)
        }

        override suspend fun rejectPaymentRun(id: String, reason: String): ZillitResult<Unit> {
            rejected += id to reason
            return ZillitResult.Success(Unit)
        }

        // Rejecting answers to the run's own chain, so the reader signs tier one.
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(
            InvoiceSettings(runAuthorisation = listOf(RunAuthLevel(tier = 1, userIds = listOf("acc")))),
        )

        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(
            listOf(Vendor(id = "v1", name = "Acme Lighting"), Vendor(id = "v2", name = "Zed Trucks")),
        )
    }

    private open class StubRepo : InvoicesRepository {
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> =
            ZillitResult.Success(emptyList())
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> = ZillitResult.Success(row(id))
        override suspend fun createEntered(
            entered: com.zillit.desktop.feature.invoices.domain.EnteredInvoice,
        ): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(InvoiceSettings())
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
            ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
    }

    private class NoFiles : com.zillit.desktop.feature.invoices.domain.InvoiceFiles {
        override suspend fun pick() = emptyList<com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile>()
        override suspend fun upload(
            file: com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile,
        ): ZillitResult<InvoiceAttachment> = ZillitResult.Success(EMPTY_ATTACHMENT)
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private companion object {
        const val NOW = 1_757_000_000_000L

        val EMPTY_ATTACHMENT = InvoiceAttachment(
            media = "",
            bucket = "",
            region = "",
            name = "",
            contentType = "",
            contentSubtype = "",
        )

        val USERS = mapOf("acc" to "Me Myself", "other" to "Sarah A", "u2" to "Priya N")
        val TEAM = listOf(
            InvoiceAssignee(id = "u2", name = "Priya N", role = "1st Assistant Accountant"),
            InvoiceAssignee(id = "other", name = "Sarah A", role = "Production Accountant"),
        )

        fun row(
            id: String,
            vendorId: String = "v1",
            assignedTo: String = "",
            gross: Double = 100.0,
            currency: String = "GBP",
            payMethod: String = "bacs",
            status: InvoiceStatus = InvoiceStatus.Approved,
        ) = Invoice(
            id = id,
            invoiceNumber = id.uppercase(),
            vendorId = vendorId,
            assignedTo = assignedTo,
            grossAmount = gross,
            currency = currency,
            payMethod = PayMethod.from(payMethod),
            status = status,
        )

        fun paymentRun(id: String, number: String) = PaymentRun(
            id = id,
            number = number,
            name = "Run",
            status = PaymentRunStatus.Pending,
        )
    }
}
