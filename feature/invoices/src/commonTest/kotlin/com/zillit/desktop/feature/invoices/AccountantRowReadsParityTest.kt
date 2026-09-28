package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
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
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.CreditEvent
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.SalesEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accountant console's unread reads, page by page, against the web's
 * pages under `components/invoices/components`: no page reads its whole
 * `level_1` but Payment Runs, and each reads its own rows only at the moment
 * the web's page does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountantRowReadsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- whole-page reads ---------------------------------------------------------

    @Test
    fun `no accountant page reads its whole level_1 on opening, but Payment Runs`() = runTest(dispatcher) {
        val badges = Badges()
        badges.counts.value = ROW_PAGES.associate { it.badgeKey.orEmpty() to 3 } + ("payment_runs" to 2)
        val vm = viewModel(Repo(), badges)
        // Each page reached from the sidebar and from its address alike.
        ROW_PAGES.forEach { page ->
            vm.onEvent(InvoicesEvent.SelectPage(page))
            advanceUntilIdle()
            vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Overview))
            vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices/${page.segment}"))
            advanceUntilIdle()
            assertEquals(page, vm.state.value.page)
        }
        // A row landing while one of them is on screen is not read either.
        badges.counts.value = badges.counts.value + ("invoice_approval_queue" to 4)
        advanceUntilIdle()
        assertTrue(badges.tabReads.isEmpty(), "row pages read row by row: ${badges.tabReads}")
        assertTrue(badges.entityReads.isEmpty())

        // Payment Runs' tab-entry read (`PaymentsPage.jsx:959-977`), and again as a run lands.
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Payments))
        advanceUntilIdle()
        assertEquals(listOf("payment_runs"), badges.tabReads)
        badges.counts.value = badges.counts.value + ("payment_runs" to 3)
        advanceUntilIdle()
        assertEquals(listOf("payment_runs", "payment_runs"), badges.tabReads)
    }

    // -- Register (`RegisterPage.jsx:611-645`) --------------------------------------

    @Test
    fun `the register reads nothing, whichever detail a row opens`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Register, badges)
        vm.onEvent(InvoicesEvent.Open(PENDING))
        vm.onEvent(InvoicesEvent.CloseDetail)
        vm.onEvent(InvoicesEvent.OpenReview(PENDING.copy(status = InvoiceStatus.Matching)))
        vm.onEvent(InvoicesEvent.CloseReview)
        vm.onEvent(InboxEvent.Open(PENDING.copy(status = InvoiceStatus.Inbox)))
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "the register is a history surface: ${badges.entityReads}")
        assertTrue(badges.tabReads.isEmpty())
    }

    // -- Pre-approval (`MatchingPage.jsx:371-377, 682, 709, 724`) --------------------

    @Test
    fun `pre-approval reads an invoice as its review opens, and its override reads nothing`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Matching, badges)
        vm.onEvent(InvoicesEvent.OpenReview(PENDING.copy(status = InvoiceStatus.Matching)))
        advanceUntilIdle()
        assertEquals(listOf(read("invoice_matching", PENDING.id)), badges.entityReads)

        vm.onEvent(InvoicesEvent.CloseReview)
        vm.onEvent(InvoicesEvent.Override(PENDING.copy(status = InvoiceStatus.Matching)))
        advanceUntilIdle()
        assertEquals(1, badges.entityReads.size, "only the review's read")
    }

    // -- Approval Queue (`ApprovalPage.jsx:134, 282-286, 327-331, 360-364, 635-639`) --

    @Test
    fun `the approval queue reads an invoice only once a decision lands`() = runTest(dispatcher) {
        val badges = Badges()
        val repo = Repo()
        val vm = page(AccountantPage.ApprovalQueue, badges, repo)

        vm.onEvent(InvoicesEvent.Open(PENDING))
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "the queue's detail does not read on open")

        vm.onEvent(InvoicesEvent.Approve(PENDING))
        advanceUntilIdle()
        assertEquals(listOf(PENDING.id), repo.approvals)
        assertEquals(listOf(read("invoice_approval_queue", PENDING.id)), badges.entityReads)

        vm.onEvent(InvoicesEvent.OverrideAndPay(OTHER.copy(status = InvoiceStatus.Override)))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.RequestDelete(THIRD))
        vm.onEvent(InvoicesEvent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(
            listOf(
                read("invoice_approval_queue", PENDING.id),
                read("invoice_approval_queue", OTHER.id),
                read("invoice_approval_queue", THIRD.id),
            ),
            badges.entityReads,
        )
        assertTrue(badges.tabReads.isEmpty())
    }

    @Test
    fun `a refused decision leaves the approval queue's badge lit`() = runTest(dispatcher) {
        val badges = Badges()
        val repo = Repo(refuse = true)
        val vm = page(AccountantPage.ApprovalQueue, badges, repo)
        vm.onEvent(InvoicesEvent.Approve(PENDING))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.Override(PENDING))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.RequestDelete(PENDING))
        vm.onEvent(InvoicesEvent.ConfirmDelete)
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "a failed call must not eat the badge: ${badges.entityReads}")
    }

    // -- Invoice Entry (`EntryDetailModal.jsx:374-396`) ------------------------------

    @Test
    fun `the coding screen reads its invoice on opening, only with something unread`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Entry, badges)
        vm.onEvent(EntryEvent.Open(PENDING))
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "nothing unread, nothing read")
        vm.onEvent(EntryEvent.Close)

        badges.entityCounts.value = mapOf("invoice_entry" to mapOf(OTHER.id to 2))
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty(), "a chip in the queue is not read until its screen opens")
        vm.onEvent(EntryEvent.Open(OTHER))
        advanceUntilIdle()
        assertEquals(listOf(read("invoice_entry", OTHER.id)), badges.entityReads)
    }

    @Test
    fun `an open coding screen reads what lands on it, and a read-only one never reads`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Entry, badges)
        vm.onEvent(EntryEvent.Open(PENDING))
        advanceUntilIdle()
        badges.entityCounts.value = mapOf("invoice_entry" to mapOf(PENDING.id to 1, OTHER.id to 1))
        advanceUntilIdle()
        assertEquals(listOf(read("invoice_entry", PENDING.id)), badges.entityReads)

        vm.onEvent(EntryEvent.Close)
        vm.onEvent(EntryEvent.Open(OTHER, readOnly = true))
        advanceUntilIdle()
        badges.entityCounts.value = mapOf("invoice_entry" to mapOf(OTHER.id to 2))
        advanceUntilIdle()
        assertEquals(1, badges.entityReads.size, "Posted's read-only screen passes no readScope")
    }

    // -- Credit Notes (`CreditsPage.jsx:862-864, 884-887`) ---------------------------

    @Test
    fun `a credit note is read by a click on its row, not by its View button`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Credits, badges)
        val note = CreditNote(id = "cn1", reference = "CN-1")
        vm.onEvent(CreditEvent.Preview(note))
        vm.onEvent(CreditEvent.ClosePreview)
        advanceUntilIdle()
        assertTrue(badges.entityReads.isEmpty())
        vm.onEvent(CreditEvent.Preview(note, fromRow = true))
        advanceUntilIdle()
        assertEquals(listOf(read("credit_notes", "cn1")), badges.entityReads)
    }

    // -- Sales Invoices (`SalesPage.jsx:950-955`) ------------------------------------

    @Test
    fun `a sales invoice is read by a click on its row`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Sales, badges)
        vm.onEvent(SalesEvent.Preview(SalesInvoice(id = "s1", reference = "SI-1")))
        advanceUntilIdle()
        assertEquals(listOf(read("sales_invoices", "s1")), badges.entityReads)
    }

    // -- Payment Runs (`PaymentsPage.jsx:2389-2397`) ----------------------------------

    @Test
    fun `a payments detail reads its invoice under payment runs as it opens`() = runTest(dispatcher) {
        val badges = Badges()
        val vm = page(AccountantPage.Payments, badges)
        vm.onEvent(InvoicesEvent.Open(PENDING.copy(status = InvoiceStatus.ReadyToPay)))
        advanceUntilIdle()
        assertEquals(listOf(read("payment_runs", PENDING.id)), badges.entityReads)
    }

    // -- harness ------------------------------------------------------------------------

    private fun read(key: String, id: String) = Triple<String, String, String?>(key, id, "invoice_label")

    private fun page(page: AccountantPage, badges: Badges, repo: Repo = Repo()) = viewModel(repo, badges).also {
        it.onEvent(InvoicesEvent.SelectPage(page))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun viewModel(repo: Repo, badges: Badges) = InvoicesViewModel(
        repository = repo,
        files = Files,
        resolveViewer = { ACCOUNTANT },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { NOW },
        badges = badges,
    ).also {
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** Records every read, and lets a test land unread per page and per row. */
    private class Badges : TabBadgeSource {
        override val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val entityCounts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
        val tabReads = mutableListOf<String>()
        val entityReads = mutableListOf<Triple<String, String, String?>>()
        override fun read(key: String) {
            tabReads += key
        }
        override fun readEntity(key: String, entityId: String, kind: String?) {
            entityReads += Triple(key, entityId, kind)
        }
    }

    private object Files : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = emptyList()
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Success(InvoiceAttachment("k", "b", "r", file.name, "document", file.extension))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(1))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private class Repo(private val refuse: Boolean = false) : InvoicesRepository {
        val approvals = mutableListOf<String>()

        private fun <T> answer(value: T): ZillitResult<T> =
            if (refuse) ZillitResult.Failure(ZillitError.Unknown("refused")) else ZillitResult.Success(value)

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> =
            ZillitResult.Success(listOf(PENDING, OTHER, THIRD))
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(listOf(PENDING))
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> =
            ZillitResult.Success(listOf(PENDING, OTHER, THIRD).firstOrNull { it.id == id } ?: PENDING)
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = answer(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> {
            if (!refuse) approvals += id
            return answer(null)
        }
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = answer(null)
        override suspend fun override(id: String): ZillitResult<Unit> = answer(Unit)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> =
            ZillitResult.Success(InvoiceSettings(canOverride = true, isSenior = true))
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> = ZillitResult.Success(
            listOf(
                ApprovalTierConfig(
                    scope = TierScope.All,
                    tiers = listOf(TierLevel(1, listOf(TierRule("default", userIds = listOf("me"))))),
                ),
            ),
        )
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(listOf(Vendor("v1", "Acme")))
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> =
            ZillitResult.Success(listOf(BankAccount("b1", "Main")))
    }

    private companion object {
        const val NOW = 1_787_011_200_000L

        val ACCOUNTANT = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )

        val PENDING = Invoice(
            id = "i-pending",
            invoiceNumber = "INV-7",
            vendorId = "v1",
            grossAmount = 100.0,
            status = InvoiceStatus.Approval,
            userId = "someone",
        )
        val OTHER = PENDING.copy(id = "i-other", invoiceNumber = "INV-8")
        val THIRD = PENDING.copy(id = "i-third", invoiceNumber = "INV-9")

        /** The pages that read row by row — every page with a `level_1` but Payment Runs. */
        val ROW_PAGES = listOf(
            AccountantPage.Inbox,
            AccountantPage.Register,
            AccountantPage.Matching,
            AccountantPage.ApprovalQueue,
            AccountantPage.Entry,
            AccountantPage.Credits,
            AccountantPage.Sales,
        )
    }
}
