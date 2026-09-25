package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.bulkUploadBody
import com.zillit.desktop.feature.invoices.data.linkNotes
import com.zillit.desktop.feature.invoices.data.parseBulkBatches
import com.zillit.desktop.feature.invoices.data.parseBulkProgress
import com.zillit.desktop.feature.invoices.data.processBody
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.BulkBatch
import com.zillit.desktop.feature.invoices.domain.BulkCounterKind
import com.zillit.desktop.feature.invoices.domain.BulkCounts
import com.zillit.desktop.feature.invoices.domain.BulkFile
import com.zillit.desktop.feature.invoices.domain.BulkFileProblem
import com.zillit.desktop.feature.invoices.domain.BulkFileStatus
import com.zillit.desktop.feature.invoices.domain.BulkPhase
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.CatalogueCurrency
import com.zillit.desktop.feature.invoices.domain.Company
import com.zillit.desktop.feature.invoices.domain.CountryCurrency
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
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.PoPick
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.domain.VendorSeed
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.BulkPick
import com.zillit.desktop.feature.invoices.ui.InboxForm
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
    fun `an exact supplier match is the vendor, a new supplier is one to create`() {
        val vendors = listOf(Vendor("v1", "Lamps Ltd"))
        assertEquals(VendorSeed(vendorId = "v1"), InboxTriage.seedVendor("  lamps ltd ", vendors))
        assertEquals(VendorSeed(pendingName = "Lamps Limited"), InboxTriage.seedVendor("Lamps Limited", vendors))
        assertNull(InboxTriage.seedVendor("", vendors))
        assertNull(InboxTriage.seedVendor("Lamps Ltd", emptyList()), "an empty list may be a failed read — never mint a vendor from it")
    }

    @Test
    fun `a lone bank is picked and its holder becomes the company, never over a value`() {
        val banks = listOf(BankAccount("b1", "Main", entityId = "co2"))
        val companies = listOf(Company("co1", "One"), Company("co2", "Two"))
        assertEquals("b1" to null, InboxTriage.autoFill("", "", banks, companies), "one pass fills the bank")
        assertEquals(null to "co2", InboxTriage.autoFill("b1", "", banks, companies), "the next finds its holder")
        assertEquals(null to null, InboxTriage.autoFill("b1", "co1", banks, companies))
        assertEquals(null to "co1", InboxTriage.autoFill("", "", emptyList(), listOf(Company("co1", "One"))))
    }

    @Test
    fun `a company's country gives its currency, overrides first`() {
        val catalogue = listOf(
            CatalogueCurrency("GBP", country = "United Kingdom"),
            CatalogueCurrency("EUR", country = "Eurozone (Austria, Belgium)"),
        )
        assertEquals("GBP", CountryCurrency.forCountry("United Kingdom", catalogue))
        assertEquals("EUR", CountryCurrency.forCountry("France", catalogue))
        assertEquals("USD", CountryCurrency.forCountry("Ecuador", emptyList()))
        assertNull(CountryCurrency.forCountry("Atlantis", catalogue))
        assertNull(CountryCurrency.forCountry("", catalogue))
    }

    @Test
    fun `derived amounts use natural decimals and never go below zero`() {
        val anchored = AmountSplit("", "", "1200", grossAnchored = true)
        assertEquals("0", InboxTriage.applyAmountEdit(anchored, AmountField.Net, "1500").tax)
        assertEquals("200.5", InboxTriage.applyAmountEdit(anchored, AmountField.Net, "999.5").tax)
        val free = InboxTriage.applyAmountEdit(AmountSplit("", "", "", grossAnchored = false), AmountField.Net, "1000")
        assertEquals("1000", free.gross, "never 1000.00")
        val cleared = InboxTriage.applyAmountEdit(AmountSplit("100", "20", "120", grossAnchored = true), AmountField.Net, "")
        assertEquals("20", cleared.tax, "clearing a field leaves its partner alone")
        assertTrue(InboxTriage.splitMismatch(AmountSplit("100", "", "120", grossAnchored = true)), "a blank tax counts as 0")
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

    /** The server's own shape: `data` is an object holding `batches` (`bulkUploadStore.js:371-372`). */
    @Test
    fun `server batches are read from the batches envelope`() {
        val data = Json.parseToJsonElement(
            """{"batches":[{"batch_id":"b1","total":2,"completed":1,"failed":0,"pending":1},
               {"batch_id":"b2","total":1,"completed":1,"is_complete":true}]}""",
        )
        val batches = parseBulkBatches(data)
        assertEquals(listOf("b1", "b2"), batches.map { it.batchId })
        assertEquals(1, batches.first().pending)
        assertTrue(batches.last().isComplete)
        assertTrue(parseBulkBatches(Json.parseToJsonElement("""{"batches":[]}""")).isEmpty())
    }

    @Test
    fun `a progress frame is read from the envelope's data, and needs a batch id`() {
        val frame = parseBulkProgress(
            Json.parseToJsonElement(
                """{"project_id":"p1","data":{"batch_id":"b1","total":2,"completed":1,"failed":1,"is_complete":true}}""",
            ),
        )
        assertEquals(ServerBatch("b1", total = 2, completed = 1, failed = 1, isComplete = true), frame)
        assertNull(parseBulkProgress(Json.parseToJsonElement("""{"data":{"total":2}}""")))
    }

    @Test
    fun `a link's notes read as an array, a JSON string or plain text`() {
        assertEquals(listOf("a", "b"), linkNotes(Json.parseToJsonElement("""[" a ","","b"]""")))
        assertEquals(listOf("x"), linkNotes(Json.parseToJsonElement("\"[\\\"x\\\"]\"")))
        assertEquals(listOf("Called vendor"), linkNotes(Json.parseToJsonElement("\"Called vendor\"")))
        assertTrue(linkNotes(null).isEmpty())
        val invoice = ROW.copy(
            linkedPos = listOf(
                LinkedPo("po1", notes = listOf("Agreed", "Agreed ")),
                LinkedPo("po2", notes = listOf("Second")),
            ),
        )
        assertEquals("Agreed\n\nSecond", InboxForm.of(invoice, "v1").matchNotes, "each note once, a blank line between")
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
    fun `counters leave out zeros but keep completed once posting is over, and split the failures`() {
        val files = listOf(
            BulkFile(1, "a.jpg", 1, status = BulkFileStatus.Sent),
            BulkFile(2, "b.jpg", 1, status = BulkFileStatus.Failed),
            BulkFile(3, "c.jpg", 1, status = BulkFileStatus.SendFailed),
            BulkFile(4, "d.txt", 1, status = BulkFileStatus.Invalid),
        )
        val posting = BulkBatch("b1", files.map { it.copy(status = BulkFileStatus.Uploading) }, createdAtMs = 1)
        val uploading = BulkUploads.counters(BulkUploads.rows(listOf(posting), emptyList()).single())
        assertEquals(listOf(BulkCounterKind.Uploading, BulkCounterKind.Pending), uploading.map { it.kind })

        val done = BulkBatch("b1", files, createdAtMs = 1, postingDone = true)
        val row = BulkUploads.rows(listOf(done), listOf(ServerBatch("b1", total = 1, pending = 1))).single()
        assertEquals(
            listOf(
                BulkCounterKind.Sent,
                BulkCounterKind.Completed,
                BulkCounterKind.Pending,
                BulkCounterKind.FailedUpload,
                BulkCounterKind.SendFailed,
                BulkCounterKind.Rejected,
            ),
            BulkUploads.counters(row).map { it.kind },
        )
        assertEquals(0, BulkUploads.counters(row).first { it.kind == BulkCounterKind.Completed }.value)
        assertTrue(BulkUploads.counters(row).filter { it.kind != BulkCounterKind.Sent }.none { it.kind.bad && it.value == 0 })
    }

    @Test
    fun `a vanished batch quotes its terminal frame, and infers from a stale one`() {
        val sent = (1..3).map { BulkFile(it, "f$it.jpg", 1, status = BulkFileStatus.Sent) }
        val terminal = ServerBatch("b1", total = 3, completed = 1, failed = 2, isComplete = true)
        val kept = BulkBatch("b1", sent, createdAtMs = 1, postingDone = true, sawServerRow = true, lastServerRow = terminal)
        val row = BulkUploads.rows(listOf(kept), emptyList()).single()
        assertEquals(BulkPhase.Done, row.phase)
        assertEquals(BulkCounts(total = 3, completed = 1, failed = 2), row.counts, "the last word, quoted")

        val stale = kept.copy(lastServerRow = ServerBatch("b1", total = 2, completed = 1, pending = 1))
        assertEquals(
            BulkCounts(total = 3, completed = 3, failed = 0),
            BulkUploads.rows(listOf(stale), emptyList()).single().counts,
            "a finished batch has nothing pending; the total never shrinks below what was sent",
        )
    }

    @Test
    fun `a terminal snapshot is never downgraded by a later poll, and a clean finish expires`() {
        val sent = listOf(BulkFile(1, "a.jpg", 1, status = BulkFileStatus.Sent))
        val batch = BulkBatch("b1", sent, createdAtMs = 1, postingDone = true)
        val framed = BulkUploads.recordFrame(listOf(batch), ServerBatch("b1", total = 1, completed = 1, isComplete = true))
        assertTrue(framed.single().sawServerRow)
        val polled = BulkUploads.applyListed(framed, listOf(ServerBatch("b1", total = 1, pending = 1)))
        assertEquals(true, polled.single().lastServerRow?.isComplete, "the terminal frame stands")
        assertEquals(listOf("b1"), BulkUploads.expired(polled, emptyList()))
        assertTrue(BulkUploads.expired(polled, listOf(ServerBatch("b1"))).isEmpty(), "still listed, still running")

        val failed = polled.single().copy(files = sent + BulkFile(2, "b.jpg", 1, status = BulkFileStatus.Failed))
        assertTrue(BulkUploads.needsAttention(failed))
        assertTrue(BulkUploads.expired(listOf(failed), emptyList()).isEmpty(), "a retry on offer holds the row")
        val retried = failed.copy(files = failed.files.map { if (it.ref == 2) it.copy(retried = true) else it })
        assertTrue(BulkUploads.retryable(retried).isEmpty())
        assertFalse(BulkUploads.needsAttention(retried))
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
    fun `a refused file blocks the whole batch until it is removed, and a double drop is added once`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW))
        val files = Files(
            listOf(
                PickedInvoiceFile("a.jpg", "image/jpeg", ByteArray(8)),
                PickedInvoiceFile("a.jpg", "image/jpeg", ByteArray(8)),
                PickedInvoiceFile("notes.txt", "text/plain", ByteArray(3)),
            ),
        )
        val vm = inbox(repo, files)
        vm.onEvent(InboxEvent.StartBulk(allowPaid = true))
        advanceUntilIdle()
        val pick = assertNotNull(vm.state.value.bulkPick)
        assertEquals(listOf("a.jpg", "notes.txt"), pick.files.map { it.name })
        assertEquals(1, pick.rejected)
        assertFalse(pick.canSubmit)
        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertTrue(repo.bulk.isEmpty(), "nothing is uploaded while a file is refused")

        vm.onEvent(InboxEvent.RemoveBulkFile(pick.files.last().ref))
        vm.onEvent(InboxEvent.SetAllBulkPaid(true))
        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertEquals(listOf(true), repo.bulk.map { it.second })
    }

    @Test
    fun `eleven files are all kept, and the batch refused until one goes`() {
        val files = (1..11).map { BulkFile(it, "f$it.jpg", it.toLong()) }
        val pick = BulkPick(files = files, allowPaid = false)
        assertTrue(pick.tooMany)
        assertFalse(pick.canSubmit)
        assertTrue(pick.copy(files = files.drop(1)).canSubmit)
    }

    @Test
    fun `a storage failure is tried again, then offered as a retry batch that keeps the paid flag`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW))
        val files = Files(listOf(PickedInvoiceFile("a.jpg", "image/jpeg", ByteArray(8))), failUploads = 3)
        val vm = inbox(repo, files)
        vm.onEvent(InboxEvent.StartBulk(allowPaid = true))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.SetAllBulkPaid(true))
        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertEquals(3, files.attempts, "three tries at storage")
        val failed = vm.state.value.bulkBatches.single()
        assertEquals(BulkFileStatus.Failed, failed.files.single().status)
        assertTrue(repo.bulk.isEmpty())
        assertEquals(BulkPhase.Error, vm.state.value.uploadRows.single().phase)

        vm.onEvent(InboxEvent.RetryBatch(failed.id))
        advanceUntilIdle()
        assertEquals(listOf(true), repo.bulk.map { it.second }, "the retry sends it, still paid")
        val old = vm.state.value.bulkBatches.first { it.id == failed.id }
        assertTrue(old.files.single().retried)
        assertTrue(BulkUploads.retryable(old).isEmpty(), "a second click finds nothing to send twice")
    }

    @Test
    fun `a new supplier becomes a vendor on accept, created before the invoice is sent`() = runTest(dispatcher) {
        val row = ROW.copy(vendorId = "", supplierName = "Brand New Ltd")
        val repo = Repo(listOf(row))
        val vm = inbox(repo)
        vm.onEvent(InboxEvent.Open(row))
        advanceUntilIdle()
        val form = assertNotNull(vm.state.value.inboxReview).form
        assertEquals("", form.vendorId)
        assertEquals("Brand New Ltd", form.pendingVendorName)
        assertEquals(listOf<Pair<String, String?>>("i1" to null), repo.suggestionVendors, "a pending vendor asks for no vendor's orders")

        vm.onEvent(InboxEvent.Accept)
        advanceUntilIdle()
        vm.onEvent(InboxEvent.ConfirmNoPo)
        advanceUntilIdle()
        assertEquals(listOf("Brand New Ltd"), repo.createdVendors)
        assertEquals("v-new", repo.processed.single().second?.vendorId)
        assertEquals("Brand New Ltd", vm.state.value.vendors["v-new"]?.name)
    }

    @Test
    fun `a refused vendor stops the accept with the pick intact`() = runTest(dispatcher) {
        val row = ROW.copy(vendorId = "", supplierName = "Brand New Ltd")
        val repo = Repo(listOf(row), refuseVendor = true)
        val vm = inbox(repo)
        vm.onEvent(InboxEvent.Open(row))
        advanceUntilIdle()
        vm.onEvent(InboxEvent.AddPo(PoPick("po1", "PO-1", 120.0)))
        vm.onEvent(InboxEvent.Accept)
        advanceUntilIdle()
        assertTrue(repo.processed.isEmpty())
        val review = assertNotNull(vm.state.value.inboxReview)
        assertFalse(review.busy)
        assertEquals("Brand New Ltd", review.form.pendingVendorName)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `opening a review reads that invoice's unread and fills the lone bank`() = runTest(dispatcher) {
        val repo = Repo(listOf(ROW), banks = listOf(BankAccount("b1", "Main", entityId = "co1")))
        val badges = Reads()
        val vm = viewModel(repo, Files(emptyList()), badges).also {
            it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Inbox))
            dispatcher.scheduler.advanceUntilIdle()
        }
        vm.onEvent(InboxEvent.Open(ROW))
        advanceUntilIdle()
        assertEquals(listOf("invoice_inbox:i1"), badges.reads)
        val form = assertNotNull(vm.state.value.inboxReview).form
        assertEquals("b1", form.bankId)
        assertEquals("co1", form.companyId)
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

    private fun viewModel(repo: Repo, files: Files, badges: TabBadgeSource = TabBadgeSource.None) = InvoicesViewModel(
        repository = repo,
        files = files,
        resolveViewer = { SENIOR },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { NOW },
        badges = badges,
    ).also {
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun inbox(repo: Repo, files: Files = Files(emptyList())) = viewModel(repo, files).also {
        it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Inbox))
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** Records the row reads the view model makes. */
    private class Reads : TabBadgeSource {
        val reads = mutableListOf<String>()
        override fun readEntity(key: String, entityId: String, kind: String?) {
            reads += "$key:$entityId"
        }
    }

    private class Repo(
        val rows: List<Invoice>,
        private val refuseVendor: Boolean = false,
        private val banks: List<BankAccount> = emptyList(),
    ) : InvoicesRepository {
        val createdVendors = mutableListOf<String>()
        val suggestionVendors = mutableListOf<Pair<String, String?>>()
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
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(banks)
        override suspend fun createVendor(name: String): ZillitResult<Vendor> {
            if (refuseVendor) return ZillitResult.Failure(ZillitError.Unknown("vendor refused"))
            createdVendors += name
            return ZillitResult.Success(Vendor("v-new", name))
        }
        override suspend fun poSuggestions(id: String, vendorId: String?): ZillitResult<PoSuggestions> {
            suggestionVendors += id to vendorId
            return ZillitResult.Success(PoSuggestions())
        }
    }

    private class Files(private val picked: List<PickedInvoiceFile>, private var failUploads: Int = 0) : InvoiceFiles {
        var attempts = 0
        override suspend fun pick(): List<PickedInvoiceFile> = picked
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> {
            attempts++
            if (failUploads > 0) {
                failUploads--
                return ZillitResult.Failure(ZillitError.Unknown("storage down"))
            }
            return ZillitResult.Success(ATTACHMENT.copy(name = file.name))
        }
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
