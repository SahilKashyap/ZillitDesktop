package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.parseInvoice
import com.zillit.desktop.feature.invoices.data.parseRuns
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CreditorAgeing
import com.zillit.desktop.feature.invoices.domain.CreditorFilter
import com.zillit.desktop.feature.invoices.domain.CreditorSort
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.DueTone
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.PostedLedger
import com.zillit.desktop.feature.invoices.domain.RunCreated
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEffect
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payment Runs, Posted and Creditors against the web's `PaymentsPage`,
 * `PostedPage` and `CreditorsPage`: what Open Items leaves out, the three
 * tick sets, the detail's Mark Paid, the days labels, the run statuses, the
 * posted order and deep link, and the creditors' currencies, selects and ages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsCreditorsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- Open Items ---------------------------------------------------------------

    @Test
    fun `an invoice already in a run is left out of open items, and 500 are read`() = runTest(dispatcher) {
        val repo = Repo(listOf(bacs("free"), bacs("taken").copy(activeRunId = "r9")))
        val vm = payments(repo)
        assertEquals(listOf("free"), vm.state.value.invoices.map { it.id })
        val ready = repo.queries.first { InvoiceStatus.ReadyToPay in it.statuses }
        assertEquals(500, ready.perPage)
        assertTrue(repo.queries.any { it.statuses == listOf(InvoiceStatus.Paid) && it.perPage == 100 })
    }

    @Test
    fun `the three tick sets are the tabs' own and survive moving between them`() = runTest(dispatcher) {
        val vm = payments(Repo(listOf(bacs("b1"), wire("w1"), wire("w2", PayMethod.Faster), cheque("c1"))))
        val pay = vm.state.value.pay
        assertEquals(setOf("b1", "w1", "w2", "c1"), pay.openItemsSelected, "open items start all ticked")
        assertTrue(pay.wiresSelected.isEmpty(), "wires are never ticked for the reader")
        assertTrue(pay.chequesSelected.isEmpty())

        vm.onEvent(PaymentsEvent.ToggleOpenItem("b1"))
        vm.onEvent(InvoicesEvent.SelectPaymentTab(PaymentTab.Wires))
        vm.onEvent(PaymentsEvent.ToggleWire("w1"))
        vm.onEvent(InvoicesEvent.SelectPaymentTab(PaymentTab.OpenItems))
        assertFalse("b1" in vm.state.value.pay.openItemsSelected, "a tab switch does not wipe the ticks")
        assertEquals(setOf("w1"), vm.state.value.pay.wiresSelected)

        vm.onEvent(InvoicesEvent.Refresh)
        advanceUntilIdle()
        assertEquals(setOf("b1", "w1", "w2", "c1"), vm.state.value.pay.openItemsSelected, "a refresh re-ticks")
        assertEquals(setOf("w1"), vm.state.value.pay.wiresSelected, "…open items only")
    }

    @Test
    fun `a wires row ticks while anything is ticked, and opens the detail otherwise`() = runTest(dispatcher) {
        val vm = payments(Repo(listOf(wire("w1"), wire("w2"))))
        vm.onEvent(InvoicesEvent.SelectPaymentTab(PaymentTab.Wires))
        vm.onEvent(PaymentsEvent.ClickRow(wire("w1")))
        assertEquals("w1", vm.state.value.detail?.invoice?.id)
        vm.onEvent(InvoicesEvent.CloseDetail)

        vm.onEvent(PaymentsEvent.ToggleWire("w1"))
        vm.onEvent(PaymentsEvent.ClickRow(wire("w2")))
        assertNull(vm.state.value.detail)
        assertEquals(setOf("w1", "w2"), vm.state.value.pay.wiresSelected)
    }

    @Test
    fun `the wires bulk bar pays wire and faster together, and they move to recently paid`() = runTest(dispatcher) {
        val repo = Repo(listOf(wire("w1"), wire("f1", PayMethod.Faster), bacs("b1")))
        val vm = payments(repo)
        vm.onEvent(PaymentsEvent.ToggleWire("w1"))
        vm.onEvent(PaymentsEvent.ToggleWire("f1"))
        vm.onEvent(PaymentsEvent.MarkWiresPaid)
        advanceUntilIdle()
        assertEquals(listOf(listOf("w1", "f1")), repo.paid, "one call for the whole mix")
        val state = vm.state.value
        assertTrue(state.pay.wiresSelected.isEmpty())
        assertEquals(listOf("b1"), state.invoices.map { it.id })
        assertEquals(setOf("w1", "f1"), state.pay.recentlyPaid.map { it.id }.toSet())
        assertTrue(state.pay.recentlyPaid.all { it.status == InvoiceStatus.Paid && it.paidAtMs == NOW })
    }

    @Test
    fun `payments' detail decides nothing, and offers mark paid on a wire only`() = runTest(dispatcher) {
        val repo = Repo(listOf(wire("w1"), bacs("b1")))
        val vm = payments(repo)
        vm.onEvent(InvoicesEvent.Open(bacs("b1")))
        val bacsDetail = assertNotNull(vm.state.value.detail)
        assertFalse(bacsDetail.decisions)
        assertFalse(bacsDetail.markPaid)

        vm.onEvent(InvoicesEvent.Open(wire("w1")))
        assertTrue(assertNotNull(vm.state.value.detail).markPaid)
        vm.onEvent(PaymentsEvent.MarkPaidFromDetail(wire("w1")))
        advanceUntilIdle()
        assertEquals(listOf(listOf("w1")), repo.paid)
        assertNull(vm.state.value.detail, "the detail closes once it is paid")
    }

    @Test
    fun `cheque and unknown methods have no header action, and never join a bacs run`() = runTest(dispatcher) {
        val odd = bacs("x1").copy(payMethodRaw = "crypto")
        val repo = Repo(listOf(cheque("c1"), odd))
        val vm = payments(repo)
        vm.onEvent(PaymentsEvent.ToggleOpenItem("x1"))
        assertEquals(PayMethod.Cheque.wire, vm.state.value.selectedPayCode)
        vm.onEvent(InvoicesEvent.ProcessSelected(null))
        advanceUntilIdle()
        assertNull(vm.state.value.detail, "Print Cheque is not available yet")
        assertTrue(vm.state.value.bacsInvoices.isEmpty(), "an unknown code is not BACs")
        assertEquals("crypto", odd.payCode)
    }

    @Test
    fun `creating runs lands on active runs with the new run open`() = runTest(dispatcher) {
        val repo = Repo(listOf(bacs("b1")), createdId = "new-run")
        val vm = payments(repo)
        vm.onEvent(InvoicesEvent.ProcessSelected(null))
        advanceUntilIdle()
        assertEquals(listOf("BACs Run — Lamps Ltd (GBP)"), repo.created)
        assertEquals(PaymentTab.Runs, vm.state.value.paymentTab)
        assertEquals("new-run", vm.state.value.runDetail?.run?.id)
        assertTrue(vm.state.value.invoices.isEmpty(), "its invoices leave open items")
    }

    @Test
    fun `a failed settings read still raises the no-authoriser banner`() = runTest(dispatcher) {
        val vm = payments(Repo(emptyList(), settingsFail = true), viewer = JUNIOR)
        assertTrue(vm.state.value.showNoRunAuthoriser)
        val fine = payments(Repo(emptyList()), viewer = JUNIOR)
        assertTrue(fine.state.value.showNoRunAuthoriser, "an empty team has no authoriser either")
        assertFalse(payments(Repo(emptyList()), viewer = SENIOR).state.value.showNoRunAuthoriser)
    }

    // -- days, PO and statuses ----------------------------------------------------------

    @Test
    fun `open items' days count up to the due date and then overdue`() {
        val now = 10 * DAY
        assertEquals("3d", PaymentRuns.openItemDays(now + 2 * DAY + 1, now).text)
        assertEquals("1d", PaymentRuns.openItemDays(now + DAY / 2, now).text)
        val late = PaymentRuns.openItemDays(now - 2 * DAY, now)
        assertEquals("2d overdue", late.text)
        assertEquals(DueTone.Overdue, late.tone)
        assertEquals("0d overdue", PaymentRuns.openItemDays(now - DAY / 2, now).text)
        assertEquals("—", PaymentRuns.openItemDays(null, now).text)
    }

    @Test
    fun `a wire's due line says overdue, due today or days left`() {
        val now = 10 * DAY
        assertEquals("2d overdue", PaymentRuns.wireDue(now - 2 * DAY, now).text)
        val today = PaymentRuns.wireDue(now - DAY / 2, now)
        assertEquals("Due today", today.text)
        assertEquals(DueTone.Today, today.tone)
        assertEquals("4d left", PaymentRuns.wireDue(now + 4 * DAY, now).text)
    }

    @Test
    fun `open items' po chip is the linked number, or no po — never a truncated id`() {
        assertNull(PaymentRuns.openItemPo(Invoice(id = "a", poId = "abcdef123")))
        assertNull(PaymentRuns.openItemPo(Invoice(id = "b", poNumber = "PO-TYPED")), "a typed number alone is no PO")
        assertEquals("PO-7", PaymentRuns.openItemPo(Invoice(id = "c", linkedPos = listOf(LinkedPo("p", "PO-7")))))
        assertEquals("PO-9", PaymentRuns.openItemPo(Invoice(id = "d", poId = "p", poNumber = "PO-9")))
    }

    @Test
    fun `a run status is the web's own word, and one never seen reads as itself`() {
        // No status is not pending for signing (`status === "pending"`), but it reads "Pending".
        assertEquals(PaymentRunStatus.Other, PaymentRunStatus.from(null))
        assertEquals("Pending", PaymentRun(id = "r", status = PaymentRunStatus.from("")).statusLabel)
        assertEquals(PaymentRunStatus.Waiting, PaymentRunStatus.from("waiting"))
        assertEquals(PaymentRunStatus.Sent, PaymentRunStatus.from("sent"))
        assertEquals("Pending", PaymentRunStatus.Pending.label)
        val odd = PaymentRun(id = "r", status = PaymentRunStatus.from("queued"), statusRaw = "queued")
        assertEquals(PaymentRunStatus.Other, odd.status)
        assertEquals("Queued", odd.statusLabel)
    }

    @Test
    fun `a run's computed total stands in only when the stored one is missing or zero`() {
        val runs = parseRuns(
            Json.parseToJsonElement(
                """{"data":[{"id":"a","total_amount":0,"computed_total":42},{"id":"b","computed_total":7}]}""",
            ),
        )
        assertEquals(listOf(42.0, 7.0), runs.map { it.total })
    }

    @Test
    fun `the rejection stamp is day, month, year and a twelve-hour time`() {
        // 2026-08-05T17:07:00Z
        assertEquals("05 Aug 2026 | 5:07 PM", PaymentRuns.stamp(1_785_949_620_000L, TimeZone.UTC))
    }

    // -- the wire -------------------------------------------------------------------

    @Test
    fun `an invoice reads its run, its paid time, its confirmations and its cis flag`() {
        val invoice = assertNotNull(
            parseInvoice(
                Json.parseToJsonElement(
                    """{"id":"i1","pay_method":"Faster_Payment","active_run_id":"r1","paid_at":1700000000000,
                       "cisApplies":true,"paymentTerms":"45 days",
                       "wire_attachments":"$ENCODED_CONFIRMATIONS"}""",
                ) as JsonObject,
            ),
        )
        assertEquals("r1", invoice.activeRunId)
        assertEquals(1_700_000_000_000L, invoice.paidAtMs)
        assertTrue(invoice.cis)
        assertEquals("45 days", invoice.paymentTerms)
        assertEquals("faster", invoice.payCode)
        val confirmation = invoice.wireAttachments.single()
        assertEquals("k/conf.pdf", confirmation.key)
        assertEquals("conf.pdf", confirmation.name)
        assertEquals("application/pdf · 2.0 KB", confirmation.detail)
        val none = parseInvoice(Json.parseToJsonElement("""{"id":"i2","wire_attachments":{}}""") as JsonObject)
        assertTrue(assertNotNull(none).wireAttachments.isEmpty(), "an empty list sent as {} is no list")
    }

    // -- Posted ---------------------------------------------------------------------

    @Test
    fun `posted is newest first — paid, then effective, invoice and updated dates`() {
        val rows = listOf(
            Invoice(id = "old", invoiceDateMs = 1L),
            Invoice(id = "paid", paidAtMs = 50L, invoiceDateMs = 2L),
            Invoice(id = "effective", effectiveDateMs = 30L),
            Invoice(id = "updated", updatedAtMs = 40L),
        )
        assertEquals(listOf("paid", "updated", "effective", "old"), PostedLedger.sorted(rows).map { it.id })
        assertTrue(PostedLedger(rows, total = 9).truncated)
        assertFalse(PostedLedger(rows).truncated)
    }

    @Test
    fun `a posted route names the invoice it opens`() {
        assertEquals("i42", AccountantPage.postedDetailId("/film-tools/invoices/posted/i42"))
        assertEquals("i42", AccountantPage.postedDetailId("/film-tools/account-hub/invoices/posted/i42?x=1"))
        assertNull(AccountantPage.postedDetailId("/film-tools/invoices/posted"))
        assertNull(AccountantPage.postedDetailId("/film-tools/invoices/payments/r1"))
        assertEquals(AccountantPage.Posted, AccountantPage.forRoute("/film-tools/invoices/posted/i42"))
    }

    /** `/cash-close` moved to the Account Hub (`InvoicesModule.jsx:534`): the route is sent on, not dropped. */
    @Test
    fun `the old cash and close address moves on to the hub's period close`() = runTest(dispatcher) {
        val vm = payments(Repo(emptyList()))
        val seen = mutableListOf<InvoicesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { seen += it } }
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/cash-close"))
        advanceUntilIdle()
        assertEquals(
            listOf(InvoicesEffect.Navigate("/film-tools/account-hub/period-close?tab=cash-close")),
            seen.filterIsInstance<InvoicesEffect.Navigate>(),
        )
        assertEquals(AccountantPage.Payments, vm.state.value.page, "the page on screen stays put")
    }

    // -- Creditors ------------------------------------------------------------------

    @Test
    fun `a creditor's buckets keep their currencies, so a mixed row is converted`() {
        val now = 100 * DAY
        val rows = Creditors.rows(
            invoices = listOf(
                owed("a", "Lamps", 100.0, "GBP", now - DAY),
                owed("b", "Lamps", 125.0, "USD", now - 2 * DAY),
            ),
            nowMs = now,
            nameOf = { it.supplierName },
        )
        val lamps = rows.single()
        val rates = CurrencyRates("GBP", mapOf("USD" to 1.25))
        val current = rates.total(lamps.currentItems)
        assertEquals(200.0, current.amount)
        assertEquals("GBP", current.currency)
        assertTrue(current.mixed)
    }

    @Test
    fun `an undated invoice ages from when it was created`() {
        val now = 100 * DAY
        val rows = Creditors.rows(
            invoices = listOf(
                Invoice(id = "a", supplierName = "Lamps", grossAmount = 10.0, createdAtMs = now - 61 * DAY),
            ),
            nowMs = now,
            nameOf = { it.supplierName },
        )
        assertEquals(10.0, rows.single().days60)
    }

    @Test
    fun `the creditor selects filter, age and sort the balance rows as the web does`() {
        val now = 100 * DAY
        val rows = Creditors.rows(
            invoices = listOf(
                owed("a", "Zed", 50.0, "GBP", now - DAY),
                owed("b", "Acme", 500.0, "GBP", now - 70 * DAY),
                owed("c", "Mid", 80.0, "GBP", now - 40 * DAY).copy(cis = true),
            ),
            nowMs = now,
            nameOf = { it.supplierName },
        )
        fun shown(
            search: String = "",
            filter: CreditorFilter = CreditorFilter.All,
            ageing: CreditorAgeing = CreditorAgeing.All,
            sort: CreditorSort = CreditorSort.BalanceDesc,
        ) = Creditors.shown(rows, search, filter, ageing, sort).map { it.vendor }

        assertEquals(listOf("Acme", "Mid", "Zed"), shown())
        assertEquals(listOf("Zed", "Mid", "Acme"), shown(sort = CreditorSort.BalanceAsc))
        assertEquals(listOf("Acme", "Mid", "Zed"), shown(sort = CreditorSort.VendorAz))
        assertEquals(listOf("Acme"), shown(filter = CreditorFilter.Overdue))
        assertEquals(listOf("Mid"), shown(filter = CreditorFilter.Cis))
        assertEquals(listOf("Zed"), shown(ageing = CreditorAgeing.CurrentOnly))
        assertEquals(listOf("Acme", "Mid"), shown(ageing = CreditorAgeing.Over30))
        assertEquals(listOf("Acme"), shown(ageing = CreditorAgeing.Over60))
        assertEquals(listOf("Zed"), shown(search = "ze"))
        assertTrue(shown(search = "500").isEmpty(), "the search is on the vendor's name alone")
    }

    @Test
    fun `the creditor tiles cover every open invoice, with each bucket's share`() {
        val now = 100 * DAY
        val owed = listOf(
            owed("a", "Zed", 25.0, "GBP", now - DAY),
            owed("b", "Acme", 75.0, "GBP", now - 70 * DAY),
        )
        val stats = Creditors.stats(owed, vendorCount = 2, nowMs = now)
        assertEquals(25, stats.currentPercent)
        assertEquals(0, stats.days30Percent)
        assertEquals(75, stats.days60Percent)
        assertEquals(0, Creditors.stats(emptyList(), 0, now).currentPercent, "nothing owed is 0%")
    }

    @Test
    fun `creditors' terms are the invoice's, then 30 days, and the week is counted from new year`() {
        val now = 100 * DAY
        val rows = Creditors.rows(
            invoices = listOf(owed("a", "Lamps", 1.0, "GBP", now).copy(paymentTerms = "14 days")),
            nowMs = now,
            nameOf = { it.supplierName },
        )
        assertEquals("14 days", rows.single().terms)
        // 1970-04-11: day 100 of the year, so the last bar is week ceil(100 / 7) = 15.
        val weeks = Creditors.trend(emptyList(), now, TimeZone.UTC)
        assertEquals("W15", weeks.last().label)
        assertEquals(6, weeks.size)
    }

    // -- harness ------------------------------------------------------------------

    private fun payments(repo: Repo, viewer: InvoiceViewer = SENIOR) = InvoicesViewModel(
        repository = repo,
        files = NoFiles,
        resolveViewer = { viewer },
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

    private class Repo(
        private val rows: List<Invoice>,
        private val createdId: String = "",
        private val settingsFail: Boolean = false,
    ) : InvoicesRepository {
        val queries = mutableListOf<InvoiceQuery>()
        val paid = mutableListOf<List<String>>()
        val created = mutableListOf<String>()

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> {
            queries += query
            return ZillitResult.Success(if (query.statuses == listOf(InvoiceStatus.Paid)) emptyList() else rows)
        }
        override suspend fun markPaid(ids: List<String>): ZillitResult<Unit> {
            paid += ids
            return ZillitResult.Success(Unit)
        }
        override suspend fun createPaymentRunWithMessage(
            name: String,
            number: String,
            payMethod: PayMethod,
            invoiceIds: List<String>,
        ): ZillitResult<RunCreated> {
            created += name
            return ZillitResult.Success(RunCreated(id = createdId))
        }
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> =
            ZillitResult.Success(rows.firstOrNull { it.id == id } ?: Invoice(id = id))
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = if (settingsFail) {
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("down"))
        } else {
            ZillitResult.Success(InvoiceSettings())
        }
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> = ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> =
            ZillitResult.Success(listOf(Vendor("v1", "Lamps Ltd")))
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
        const val DAY = 86_400_000L

        /** `wire_attachments` as the service sometimes stores it: the array, JSON-encoded into a string. */
        const val ENCODED_CONFIRMATIONS =
            """[{\"stored_filename\":\"k/conf.pdf\",\"filename\":\"conf.pdf\",\"mime_type\":\"application/pdf\",\"size\":2048}]"""
        const val NOW = 1_790_000_000_000L

        val SENIOR = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )
        val JUNIOR = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_assistant_production_accountant_accounts",
            ready = true,
        )

        fun bacs(id: String) = Invoice(
            id = id,
            vendorId = "v1",
            currency = "GBP",
            grossAmount = 100.0,
            payMethod = PayMethod.Bacs,
            status = InvoiceStatus.ReadyToPay,
        )

        fun wire(id: String, method: PayMethod = PayMethod.Wire) = bacs(id).copy(payMethod = method)

        fun cheque(id: String) = bacs(id).copy(payMethod = PayMethod.Cheque)

        fun owed(id: String, supplier: String, gross: Double, currency: String, dateMs: Long) = Invoice(
            id = id,
            supplierName = supplier,
            grossAmount = gross,
            currency = currency,
            invoiceDateMs = dateMs,
            status = InvoiceStatus.Approved,
        )
    }
}
