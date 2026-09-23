package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.bulkUploadBody
import com.zillit.desktop.feature.invoices.data.parseBulkBatches
import com.zillit.desktop.feature.invoices.data.processBody
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.BulkBatch
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkFileProblem
import com.zillit.desktop.feature.invoices.domain.BulkFileStatus
import com.zillit.desktop.feature.invoices.domain.BulkPhase
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.InboxAccept
import com.zillit.desktop.feature.invoices.domain.InboxField
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InboxValues
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.PoPick
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InboxTab
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * The Inbox's triage — the review's rules, `/process`, and the bulk upload
 * that replaced the old one-file `/upload` flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxTriageFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the review's rules ----------------------------------------------------------

    @Test
    fun `every required field is named when nothing is filled`() {
        assertEquals(InboxField.entries, InboxTriage.missing(InboxValues()))
        val full = InboxValues("v", "d", "GBP", "2026-09-01", "bacs", 10.0)
        assertTrue(InboxTriage.missing(full).isEmpty())
        assertEquals(listOf(InboxField.GrossAmount), InboxTriage.missing(full.copy(gross = 0.0)))
    }

    @Test
    fun `gross follows net and tax until it is typed, then anchors them`() {
        val free = AmountSplit("", "", "", grossAnchored = false)
        val net = InboxTriage.applyAmountEdit(free, AmountField.Net, "100")
        assertEquals("100", net.gross)
        val taxed = InboxTriage.applyAmountEdit(net, AmountField.Tax, "20")
        assertEquals("120", taxed.gross)

        val anchored = AmountSplit("", "", "120", grossAnchored = true)
        val worked = InboxTriage.applyAmountEdit(anchored, AmountField.Net, "100")
        assertEquals("20", worked.tax, "an anchored gross works the other figure out")
        assertEquals("120", InboxTriage.applyAmountEdit(anchored, AmountField.Net, "").gross)
    }

    @Test
    fun `a split that does not add up is a mismatch, a blank one never is`() {
        assertTrue(InboxTriage.splitMismatch(AmountSplit("100", "10", "120", grossAnchored = true)))
        assertFalse(InboxTriage.splitMismatch(AmountSplit("100", "20", "120", grossAnchored = true)))
        assertFalse(InboxTriage.splitMismatch(AmountSplit("", "", "120", grossAnchored = true)))
        assertFalse(InboxTriage.splitMismatch(AmountSplit("100", "10", "0", grossAnchored = true)))
    }

    @Test
    fun `the PO balance needs every order's amount`() {
        assertNull(InboxTriage.poBalance(100.0, emptyList()))
        assertNull(InboxTriage.poBalance(100.0, listOf(PoPick("p1", "PO-1", null))))
        assertEquals(10.0, InboxTriage.poBalance(100.0, listOf(PoPick("p1", "PO-1", 60.0), PoPick("p2", "PO-2", 30.0))))
    }

    // -- the wire ------------------------------------------------------------------------

    @Test
    fun `bulk process sends only the ids, a review sends its edits`() {
        assertEquals("""{"ids":["a","b"]}""", processBody(listOf("a", "b")).toString())
        val body = processBody(listOf("a"), ACCEPT)
        val updates = body["updates"]!!.jsonObject
        assertEquals("V-1", updates["invoice_number"]!!.jsonPrimitive.content)
        assertEquals(listOf("po1"), updates["po_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, updates["company_id"])
        assertFalse("net_amount" in updates, "net goes only when typed")
        assertEquals(120.0, updates["gross_amount"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `a bulk file is one attachment, sized, and paid only when set`() {
        val paid = bulkUploadBody("inv-bulk-1", ATTACHMENT, 42L, paid = true)
        val entry = paid["attachments"]!!.jsonArray.single().jsonObject
        assertEquals("inv-bulk-1", paid["batch_id"]!!.jsonPrimitive.content)
        assertEquals("42", entry["file_size"]!!.jsonPrimitive.content)
        assertEquals("true", entry["paid"]!!.jsonPrimitive.content)
        val unpaid = bulkUploadBody("inv-bulk-1", ATTACHMENT, 42L, paid = false)
        assertFalse("paid" in unpaid["attachments"]!!.jsonArray.single().jsonObject)
    }

    @Test
    fun `server batches read their counts and ids`() {
        val data = Json.parseToJsonElement(
            """[{"batch_id":"b1","total":3,"completed":2,"failed":1,"pending":0,
               "invoice_ids":["i1","i2"],"is_complete":true}]""",
        )
        val batch = parseBulkBatches(data).single()
        assertEquals(ServerBatch("b1", 3, 2, 1, 0, listOf("i1", "i2"), isComplete = true), batch)
    }

    // -- bulk upload rules ---------------------------------------------------------------

    @Test
    fun `a file is refused for its type, its size, and its pages`() {
        fun file(name: String, size: Int = 10) = PickedInvoiceFile(name, "", ByteArray(size))
        assertEquals(BulkFileProblem.WrongType, BulkUploads.problemWith(file("a.docx"), null))
        val big = file("a.jpg", (BulkUploads.MAX_FILE_BYTES + 1).toInt())
        assertEquals(BulkFileProblem.TooBig, BulkUploads.problemWith(big, null))
        assertEquals(BulkFileProblem.Unreadable, BulkUploads.problemWith(file("a.pdf"), null))
        assertEquals(BulkFileProblem.TooManyPages, BulkUploads.problemWith(file("a.pdf"), 6))
        assertNull(BulkUploads.problemWith(file("a.pdf"), 5))
    }

    @Test
    fun `a batch reads as uploading, then processing, then done once the server drops it`() {
        val sent = BulkFile(1, "a.jpg", 1, status = BulkFileStatus.Sent)
        val posting = BulkBatch("b1", listOf(sent), createdAtMs = 1)
        assertEquals(BulkPhase.Uploading, BulkUploads.rows(listOf(posting), emptyList(), emptySet()).single().phase)
        val posted = posting.copy(postingDone = true)
        val frame = ServerBatch("b1", total = 1)
        assertEquals(BulkPhase.Processing, BulkUploads.rows(listOf(posted), listOf(frame), setOf("b1")).single().phase)
        assertEquals(BulkPhase.Done, BulkUploads.rows(listOf(posted), emptyList(), setOf("b1")).single().phase)
        val failed = posted.copy(files = listOf(sent.copy(status = BulkFileStatus.SendFailed)))
        assertEquals(BulkPhase.Error, BulkUploads.rows(listOf(failed), emptyList(), emptySet()).single().phase)
    }

    // -- through the view model -------------------------------------------------------

    @Test
    fun `bulk process is refused, whole, when any row is missing a required field`() = runTest(dispatcher) {
        val rows = listOf(ROW, ROW.copy(id = "i2", departmentId = ""))
        val repo = Repo(rows)
        val vm = inbox(repo)
        vm.onEvent(InvoicesEvent.ToggleSelect("i1"))
        vm.onEvent(InvoicesEvent.ToggleSelect("i2"))
        vm.onEvent(InboxEvent.ProcessSelected)
        advanceUntilIdle()
        assertTrue(repo.processed.isEmpty())
        assertEquals(listOf("i2"), vm.state.value.blockedProcess.map { it.invoice.id })
    }

    @Test
    fun `bulk process sends every ticked row and moves to the register`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW, ROW.copy(id = "i2")))
        val vm = inbox(repo)
        vm.onEvent(InvoicesEvent.ToggleSelect("i1"))
        vm.onEvent(InvoicesEvent.ToggleSelect("i2"))
        vm.onEvent(InboxEvent.ProcessSelected)
        advanceUntilIdle()
        assertEquals(listOf<Pair<List<String>, InboxAccept?>>(listOf("i1", "i2") to null), repo.processed)
        assertEquals(AccountantPage.Register, vm.state.value.page)
    }

    @Test
    fun `accept names what is missing and sends nothing`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW.copy(vendorId = "", departmentId = "")))
        val vm = inbox(repo)
        vm.onEvent(InboxEvent.Open(repo.rows.single()))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.Accept)
        advanceUntilIdle()
        assertEquals(setOf(InboxField.Vendor, InboxField.Department), vm.state.value.inboxReview?.errors)
        assertTrue(repo.processed.isEmpty())
    }

    @Test
    fun `accept with an order sends the edits, then the note on the order`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW))
        val vm = inbox(repo)
        vm.onEvent(InboxEvent.Open(ROW))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.AddPo(PoPick("po1", "PO-1", 120.0)))
        val form = assertNotNull(vm.state.value.inboxReview).form
        vm.onEvent(InboxEvent.Edit(form.copy(matchNotes = "Matched by hand")))
        vm.onEvent(InboxEvent.Accept)
        advanceUntilIdle()
        val (ids, accept) = repo.processed.single()
        assertEquals(listOf("i1"), ids)
        assertEquals(listOf("po1"), accept?.poIds)
        assertEquals(listOf("i1:po1:Matched by hand"), repo.notes)
        assertNull(vm.state.value.inboxReview)
    }

    @Test
    fun `accept with no order asks first`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW))
        val vm = inbox(repo)
        vm.onEvent(InboxEvent.Open(ROW))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.Accept)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.inboxReview?.confirmNoPo)
        assertTrue(repo.processed.isEmpty())
        vm.onEvent(InboxEvent.ConfirmNoPo)
        advanceUntilIdle()
        assertEquals(emptyList(), repo.processed.single().second?.poIds)
    }

    @Test
    fun `the accountant's bulk upload sends each file under one batch, paid where ticked`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW))
        val files = Files(
            listOf(
                PickedInvoiceFile("a.jpg", "image/jpeg", ByteArray(8)),
                PickedInvoiceFile("b.png", "image/png", ByteArray(9)),
            ),
        )
        val vm = inbox(repo, files)
        vm.onEvent(InboxEvent.StartBulk(allowPaid = true))
        advanceUntilIdle()
        val pick = assertNotNull(vm.state.value.bulkPick)
        assertEquals(2, pick.sendable)
        vm.onEvent(InboxEvent.ToggleBulkPaid(pick.files.first().ref))
        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertEquals(2, repo.bulk.size)
        assertEquals(1, repo.bulk.map { it.first }.distinct().size, "one batch id for the whole drop")
        assertEquals(listOf(true, false), repo.bulk.map { it.second })
        assertNull(vm.state.value.bulkPick)
        assertEquals(InboxTab.Uploads, vm.state.value.inboxTab)
        assertTrue(vm.state.value.bulkBatches.single().postingDone)
    }

    @Test
    fun `a register row opens a detail with no decisions, and the handler holds the line`() = runTest(dispatcher) {
        val row = ROW.copy(status = InvoiceStatus.Approval)
        val repo = Repo(listOf(row))
        val vm = viewModel(repo, Files(emptyList()))
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Register))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.Open(row))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.detail?.decisions)
        vm.onEvent(InvoicesEvent.Approve(row))
        vm.onEvent(InvoicesEvent.Override(row))
        advanceUntilIdle()
        assertTrue(repo.decisions.isEmpty())
    }

    // -- harness ---------------------------------------------------------------------------

    private fun viewModel(repo: Repo, files: Files) = InvoicesViewModel(
        repository = repo,
        files = files,
        resolveViewer = { SENIOR },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { NOW },
    ).also {
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun inbox(repo: Repo, files: Files = Files(emptyList())) = viewModel(repo, files).also {
        it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Inbox))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class Repo(val rows: List<Invoice>) : InvoicesRepository {
        val processed = mutableListOf<Pair<List<String>, InboxAccept?>>()
        val notes = mutableListOf<String>()
        val bulk = mutableListOf<Pair<String, Boolean>>()
        val decisions = mutableListOf<String>()

        override suspend fun process(ids: List<String>, accept: InboxAccept?): ZillitResult<Unit> {
            processed += ids to accept
            return ZillitResult.Success(Unit)
        }
        override suspend fun matchNote(id: String, poId: String, note: String): ZillitResult<Unit> {
            notes += "$id:$poId:$note"
            return ZillitResult.Success(Unit)
        }
        override suspend fun bulkUpload(
            batchId: String,
            attachment: InvoiceAttachment,
            size: Long,
            paid: Boolean,
        ): ZillitResult<Unit> {
            bulk += batchId to paid
            return ZillitResult.Success(Unit)
        }
        override suspend fun override(id: String): ZillitResult<Unit> {
            decisions += "override:$id"
            return ZillitResult.Success(Unit)
        }
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(rows)
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> =
            ZillitResult.Success(rows.firstOrNull { it.id == id } ?: rows.first())
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> {
            decisions += "approve:$id"
            return ZillitResult.Success(null)
        }
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> =
            ZillitResult.Success(InvoiceSettings(canOverride = true))
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> = ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> =
            ZillitResult.Success(listOf(Vendor("v1", "Lamps Ltd")))
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
    }

    private class Files(private val picked: List<PickedInvoiceFile>) : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = picked
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Success(ATTACHMENT.copy(name = file.name))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L

        val SENIOR = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )

        val ATTACHMENT = InvoiceAttachment("k/a.jpg", "bucket", "eu-west-2", "a.jpg", "image", "jpg")

        val ROW = Invoice(
            id = "i1",
            invoiceNumber = "V-1",
            vendorId = "v1",
            departmentId = "d1",
            currency = "GBP",
            grossAmount = 120.0,
            effectiveDateMs = 1_788_220_800_000L,
            payMethod = PayMethod.Bacs,
            status = InvoiceStatus.Inbox,
        )

        val ACCEPT = InboxAccept(
            invoiceNumber = "V-1",
            vendorId = "v1",
            description = "",
            invoiceDate = "",
            dueDate = "",
            effectiveDate = "2026-09-01",
            net = "",
            tax = "",
            gross = "120",
            poIds = listOf("po1"),
            payMethod = PayMethod.Bacs,
            departmentId = "d1",
            currency = "GBP",
            companyId = "",
            bankId = "",
            episode = "",
        )
    }
}
