package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.EntryWrite
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.QueryMessage
import com.zillit.desktop.feature.invoices.domain.QueryThread
import com.zillit.desktop.feature.invoices.domain.QuickEntry
import com.zillit.desktop.feature.invoices.domain.TeamMember
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.QuickEntryDraft
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
 * Invoice Entry's coding screen through the view model: what opens it, what
 * refuses a post, and the order its writes go in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryLedgerFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening seeds the screen from the saved record`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = open(repo, senior())
        val ledger = assertNotNull(vm.state.value.ledger)
        assertEquals(listOf("l1"), ledger.lines.map { it.id })
        assertEquals("INV-1", ledger.header.invoiceNumber)
        // The only bank there is is picked, and its company comes with it.
        assertEquals("b1", ledger.header.bankId)
        assertEquals("co1", ledger.header.companyId)
    }

    @Test
    fun `a row the reader may not open does not open`() = runTest(dispatcher) {
        val repo = Repo(row = ROW.copy(assignedTo = "somebody-else"))
        val vm = open(repo, junior())
        assertNull(vm.state.value.ledger)
    }

    @Test
    fun `post saves the whole entry first, then posts, then closes`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = open(repo, senior())
        vm.onEvent(EntryEvent.Post)
        advanceUntilIdle()
        assertEquals(listOf("save:i1", "post:i1"), repo.writes)
        assertEquals("2400", repo.saved.single().lines?.single()?.account)
        assertNull(vm.state.value.ledger)
    }

    @Test
    fun `post is refused with the web's reason, and nothing is written`() = runTest(dispatcher) {
        val repo = Repo(row = ROW.copy(lineItems = listOf(LINE.copy(account = ""))))
        val vm = open(repo, senior())
        vm.onEvent(EntryEvent.Post)
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertEquals("Line 1 needs a nominal code before you can post", vm.state.value.ledger?.problem)
    }

    @Test
    fun `save is refused while the lines do not reconcile`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = open(repo, senior())
        vm.onEvent(EntryEvent.EditLine(LINE.withAmount(90.0)))
        vm.onEvent(EntryEvent.Save)
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertNotNull(vm.state.value.ledger?.problem)
    }

    @Test
    fun `no posting right, no post - and a senior is not offered the review hand-off`() = runTest(dispatcher) {
        val repo = Repo(row = ROW.copy(assignedTo = "me"))
        val junior = open(repo, junior())
        junior.onEvent(EntryEvent.Post)
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty(), "a junior with no posting limit cannot post")

        val senior = open(Repo(), senior())
        senior.onEvent(EntryEvent.SubmitForReview)
        advanceUntilIdle()
        assertNotNull(senior.state.value.ledger, "a senior's Submit for Review does nothing")
    }

    @Test
    fun `a junior with a posting limit may post, and may hand off for review`() = runTest(dispatcher) {
        val settings = InvoiceSettings(teamMembers = listOf(TeamMember(userId = "me", postingRight = true)))
        val repo = Repo(row = ROW.copy(assignedTo = "me"), settings = settings)
        val vm = open(repo, junior())
        vm.onEvent(EntryEvent.SubmitForReview)
        advanceUntilIdle()
        assertEquals("under_review", repo.saved.single().status)
        assertNull(vm.state.value.ledger)
    }

    @Test
    fun `an invoice dated in a closed period is frozen`() = runTest(dispatcher) {
        val repo = Repo(lock = PeriodLock(lockedThrough = "2026-12-31"))
        val vm = open(repo, senior())
        vm.onEvent(EntryEvent.EditLine(LINE.copy(description = "changed")))
        vm.onEvent(EntryEvent.Save)
        vm.onEvent(EntryEvent.Post)
        vm.onEvent(EntryEvent.ReturnToApproval)
        advanceUntilIdle()
        assertEquals("Lamps", vm.state.value.ledger?.lines?.single()?.description)
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `quick entry posts straight to ready to pay and shows payment runs`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = open(repo, senior())
        vm.onEvent(EntryEvent.Close)
        vm.onEvent(EntryEvent.StartQuick)
        vm.onEvent(EntryEvent.EditQuick(QuickEntryDraft(reference = "Q-1", net = "100")))
        vm.onEvent(EntryEvent.PostQuick)
        advanceUntilIdle()
        assertEquals("Q-1", repo.quick.single().reference)
        assertEquals(100.0, repo.quick.single().net)
        assertNull(vm.state.value.quickEntry)
        assertEquals(AccountantPage.Payments, vm.state.value.page)
    }

    @Test
    fun `a query's first message opens the thread, the next is added to it`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = open(repo, senior())
        vm.onEvent(QueryEvent.Open(ROW))
        advanceUntilIdle()
        vm.onEvent(QueryEvent.Draft("Which PO?"))
        vm.onEvent(QueryEvent.Send)
        advanceUntilIdle()
        vm.onEvent(QueryEvent.Draft("Thanks"))
        vm.onEvent(QueryEvent.Send)
        advanceUntilIdle()
        assertEquals(listOf("open:i1:Which PO?", "add:q1:Thanks"), repo.queries)
        assertEquals(2, vm.state.value.query?.thread?.messages?.size)
        assertEquals("", vm.state.value.query?.draft)
    }

    @Test
    fun `select-all on entry leaves out what the reader may not open and anything locked`() = runTest(dispatcher) {
        val rows = listOf(
            // Dated after the lock, so only the assignment decides it.
            ROW.copy(assignedTo = "me", effectiveDateMs = NOW),
            ROW.copy(id = "i2", assignedTo = "somebody-else"),
            ROW.copy(id = "i3", assignedTo = "me", effectiveDateMs = LOCKED_DAY),
        )
        val repo = Repo(list = rows, lock = PeriodLock(lockedThrough = "2026-09-13"))
        val vm = viewModel(repo, junior())
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Entry))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.ToggleSelectAll)
        assertEquals(setOf("i1"), vm.state.value.selected)
        vm.onEvent(InvoicesEvent.ToggleSelect("i3"))
        assertEquals(setOf("i1"), vm.state.value.selected, "a locked row cannot be ticked")
    }

    // -- harness -------------------------------------------------------------------

    private fun senior() = InvoiceViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        ready = true,
    )

    private fun junior() = senior().copy(designationIdentifier = "designation_assistant_accountant_accounts")

    private fun viewModel(repo: Repo, viewer: InvoiceViewer) = InvoicesViewModel(
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
    }

    private fun open(repo: Repo, viewer: InvoiceViewer): InvoicesViewModel = viewModel(repo, viewer).also {
        it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Entry))
        dispatcher.scheduler.advanceUntilIdle()
        it.onEvent(EntryEvent.Open(repo.row))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class Repo(
        val row: Invoice = ROW,
        private val list: List<Invoice> = listOf(row),
        private val settings: InvoiceSettings = InvoiceSettings(),
        private val lock: PeriodLock = PeriodLock(),
    ) : InvoicesRepository {
        val writes = mutableListOf<String>()
        val saved = mutableListOf<EntryWrite>()
        val quick = mutableListOf<QuickEntry>()
        val queries = mutableListOf<String>()
        private var thread = QueryThread()

        override suspend fun saveEntry(id: String, write: EntryWrite): ZillitResult<Unit> {
            writes += "save:$id"
            saved += write
            return ZillitResult.Success(Unit)
        }
        override suspend fun postInvoice(id: String): ZillitResult<Unit> {
            writes += "post:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun returnToApproval(id: String): ZillitResult<Unit> {
            writes += "return:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun quickEntry(entry: QuickEntry): ZillitResult<Unit> {
            quick += entry
            return ZillitResult.Success(Unit)
        }
        override suspend fun periodLock(): ZillitResult<PeriodLock> = ZillitResult.Success(lock)
        override suspend fun queryThread(invoiceId: String): ZillitResult<QueryThread> = ZillitResult.Success(thread)
        override suspend fun openQuery(invoiceId: String, text: String): ZillitResult<QueryThread> {
            queries += "open:$invoiceId:$text"
            thread = QueryThread("q1", listOf(QueryMessage(text, "me")))
            return ZillitResult.Success(thread)
        }
        override suspend fun addQuery(threadId: String, text: String): ZillitResult<QueryThread> {
            queries += "add:$threadId:$text"
            thread = thread.copy(messages = thread.messages + QueryMessage(text, "me"))
            return ZillitResult.Success(thread)
        }
        override suspend fun projectSettings() = ZillitResult.Success(
            com.zillit.desktop.feature.invoices.domain.InvoiceProjectSettings(
                companies = listOf(com.zillit.desktop.feature.invoices.domain.Company("co1", "Prod Co")),
            ),
        )
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(list)
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> =
            ZillitResult.Success(list.firstOrNull { it.id == id } ?: row)
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            ZillitResult.Success(null)
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(settings)
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> =
            ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> =
            ZillitResult.Success(listOf(BankAccount("b1", "Main", entityId = "co1")))
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
        const val NOW = 1_790_000_000_000L

        /** 2026-09-13, inside a lock through that day. */
        const val LOCKED_DAY = 1_789_300_000_000L

        val LINE = CodedLine("l1", description = "Lamps", account = "2400", amount = 100.0)

        val ROW = Invoice(
            id = "i1",
            invoiceNumber = "INV-1",
            grossAmount = 100.0,
            status = InvoiceStatus.Approved,
            // Before the lock in the one test that sets it to the year's end.
            effectiveDateMs = 1_788_220_800_000L,
            lineItems = listOf(LINE),
        )
    }
}
