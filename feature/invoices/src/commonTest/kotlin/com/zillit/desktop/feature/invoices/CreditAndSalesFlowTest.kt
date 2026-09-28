package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.data.creditNoteBody
import com.zillit.desktop.feature.invoices.data.parseCreditNote
import com.zillit.desktop.feature.invoices.data.parseSalesInvoices
import com.zillit.desktop.feature.invoices.data.salesInvoiceBody
import com.zillit.desktop.feature.invoices.domain.ClientAddress
import com.zillit.desktop.feature.invoices.domain.SalesTerms
import com.zillit.desktop.feature.invoices.ui.SalesEvent
import com.zillit.desktop.feature.invoices.ui.SalesField
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.CreditNoteSort
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.CreditNoteWrite
import com.zillit.desktop.feature.invoices.domain.CreditNotes
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.domain.LineItems
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceWrite
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.CreditEvent
import com.zillit.desktop.feature.invoices.ui.CreditField
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

/** Credit Notes & Disputes and Sales Invoices: the web's checks, payloads and confirmations. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditAndSalesFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- rules -----------------------------------------------------------------------

    @Test
    fun `lines need one description, then an account and an amount on every described one`() {
        assertTrue(LineItems.check(listOf(CodedLine("a"))).noDescription)
        val check = LineItems.check(
            listOf(
                CodedLine("a", description = "Lamps", account = "", amount = 10.0),
                CodedLine("b"),
                CodedLine("c", description = "Cable", account = "2400", amount = 0.0),
                CodedLine("d", description = "Split", splitParentId = "c"),
            ),
        )
        assertEquals(listOf(1, 3), check.problems.map { it.position })
        assertTrue(check.problems.first().needsAccount && !check.problems.first().needsAmount)
        assertTrue(check.problems.last().needsAmount)
        assertTrue(LineItems.check(listOf(CodedLine("a", description = "x", account = "1", amount = 1.0))).ok)
    }

    @Test
    fun `only a numbered invoice that is not ready to pay can be credited`() {
        val invoices = listOf(
            Invoice(id = "1", invoiceNumber = "INV-1", description = "Lamps"),
            Invoice(id = "2", invoiceNumber = ""),
            Invoice(id = "3", invoiceNumber = "INV-3", status = InvoiceStatus.ReadyToPay),
            Invoice(id = "4", invoiceNumber = "INV-4", description = "Catering"),
        )
        assertEquals(listOf("1", "4"), CreditNotes.creditable(invoices, "") { "" }.map { it.id })
        assertEquals(listOf("4"), CreditNotes.creditable(invoices, "cater") { "" }.map { it.id })
    }

    @Test
    fun `the date filter counts back from now and the sort orders by the web's keys`() {
        val day = 86_400_000L
        val old = CreditNote("a", effectiveDateMs = NOW - 40 * day, grossAmount = 5.0, vendorName = "b")
        val recent = CreditNote("b", effectiveDateMs = NOW - 2 * day, grossAmount = 9.0, vendorName = "a")
        assertFalse(DateWindow.Last30.keeps(old.effectiveDateMs, NOW))
        assertTrue(DateWindow.Week.keeps(recent.effectiveDateMs, NOW))
        assertTrue(DateWindow.Last90.keeps(old.effectiveDateMs, NOW))
        assertTrue(DateWindow.Week.keeps(null, NOW), "a row with no date is never filtered out")
        assertEquals(listOf("b", "a"), CreditNoteSort.Newest.sort(listOf(old, recent)).map { it.id })
        assertEquals(listOf("a", "b"), CreditNoteSort.AmountLow.sort(listOf(recent, old)).map { it.id })
        assertEquals(listOf("b", "a"), CreditNoteSort.Vendor.sort(listOf(old, recent)).map { it.id })
    }

    // -- the wire -------------------------------------------------------------------------

    @Test
    fun `a credit note sends its lines with their tax, and a gross that adds them up`() {
        val body = creditNoteBody(
            WRITE.copy(
                attachments = listOf(
                    CreditAttachment("kept.pdf", storedJson = """{"media":"k","bucket":"b","region":"r","size":9}"""),
                    CreditAttachment(
                        "new.png",
                        4,
                        stored = InvoiceAttachment("m", "b", "r", "new.png", "image", "png"),
                    ),
                ),
            ),
        )
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
        assertEquals(120.0, body["gross_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("Lamps", body["description"]!!.jsonPrimitive.content)
        assertEquals("2026-09-20", body["effective_date"]!!.jsonPrimitive.content)
        val line = body["line_items"]!!.jsonArray.single().jsonObject
        assertEquals(20.0, line["tax_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals(100.0, line["amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("0", line["sort_order"]!!.jsonPrimitive.content)
        assertFalse("id" in line, "the web sends no line ids")
        val files = body["attachments"]!!.jsonArray.map { it.jsonObject }
        assertEquals("9", files.first()["size"]!!.jsonPrimitive.content, "a stored file goes back as it came")
        assertEquals("new.png", files.last()["original_filename"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a dispute sends its amount, its reason as the description, and no lines`() {
        val body = creditNoteBody(WRITE.copy(type = CreditNoteType.Dispute, disputeAmount = 55.0, reason = "Short"))
        assertEquals("disputed", body["status"]!!.jsonPrimitive.content)
        assertEquals(55.0, body["gross_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("Short", body["description"]!!.jsonPrimitive.content)
        assertFalse("line_items" in body)
        assertEquals(JsonNull, creditNoteBody(WRITE.copy(invoiceReference = ""))["invoice_reference"])
    }

    @Test
    fun `a saved note reads its lines, and keeps only complete stored files`() {
        val note = parseCreditNote(
            Json.parseToJsonElement(
                """{"id":"c1","type":"dispute","status":"disputed","effective_date":"2026-09-20",
                  "line_items":[{"id":"l1","description":"Lamps","amount":100,"tax_rate":"20%"}],
                  "attachments":[{"media":"k","bucket":"b","region":"r","original_filename":"a.pdf","size":10},
                                 {"media":"k2"}],"created_by":"u1"}""",
            ).jsonObject,
        )
        assertNotNull(note)
        assertEquals(CreditNoteType.Dispute, note.type)
        assertEquals(20.0, note.lineItems.single().taxRate)
        assertEquals(listOf("a.pdf"), note.attachments.map { it.name })
        assertEquals(10L, note.attachments.single().sizeBytes)
        assertEquals("C1", note.displayRef)
    }

    @Test
    fun `a sales invoice is raised as a draft, dated as typed, its gross the lines'`() {
        val body = salesInvoiceBody(
            SalesInvoiceWrite(
                reference = "SI-1",
                clientName = "Channel 4",
                clientAddress = ClientAddress(line1 = "1 High St", city = "London", postalCode = "W1", country = "UK"),
                currency = "GBP",
                invoiceDate = "2026-09-23",
                dueDate = "2026-10-23",
                lines = listOf(CodedLine("l", description = "Fees", account = "4000", amount = 100.0, taxRate = 20.0)),
            ),
        )
        assertEquals("draft", body["status"]!!.jsonPrimitive.content)
        assertEquals("2026-09-23", body["invoice_date"]!!.jsonPrimitive.content)
        assertEquals(120.0, body["gross_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1, body["line_items"]!!.jsonArray.size)
        assertEquals("SI-1", body["reference"]!!.jsonPrimitive.content)
        // `client_address` as the web's form builds it; no description and no pay_terms.
        val address = body["client_address"]!!.jsonObject
        assertEquals(
            listOf("line1", "line2", "city", "state", "country", "postal_code"),
            address.keys.toList(),
        )
        assertEquals("London", address["city"]!!.jsonPrimitive.content)
        assertEquals("W1", address["postal_code"]!!.jsonPrimitive.content)
        assertEquals("", address["line2"]!!.jsonPrimitive.content)
        assertFalse("description" in body)
        assertFalse("pay_terms" in body)
    }

    @Test
    fun `an update sends no reference, and a stored address reads back from an object or a string`() {
        val update = salesInvoiceBody(
            SalesInvoiceWrite(
                clientName = "Channel 4",
                clientAddress = ClientAddress(),
                currency = "GBP",
                invoiceDate = "2026-09-23",
                dueDate = "2026-10-23",
                lines = emptyList(),
            ),
        )
        assertFalse("reference" in update)
        val stored = parseSalesInvoices(
            Json.parseToJsonElement(
                """[{"id":"s1","client_address":"{\"line1\":\"1 High St\",\"city\":\"London\"}","pay_terms":""},
                   {"id":"s2","client_address":{"line1":"2 Low Rd","postal_code":"E1"}}]""",
            ),
        )
        assertEquals("1 High St, London", stored.first().addressLine)
        assertEquals("30 days", stored.first().termsLabel)
        assertEquals("2 Low Rd, E1", stored.last().addressLine)
    }

    @Test
    fun `each register is exported with its own type`() {
        assertEquals("credit_note", InvoiceExport.CreditNotes.type)
        assertEquals("dispute", InvoiceExport.Disputes.type)
        assertNull(InvoiceExport.Register.type)
    }

    // -- through the view model -------------------------------------------------------

    @Test
    fun `a credit note is refused with the web's reasons, then saved with its files uploaded first`() =
        runTest(dispatcher) {
            val repo = Repo()
            val files = Files(listOf(PickedInvoiceFile("proof.pdf", "application/pdf", ByteArray(3))))
            val vm = credits(repo, files)
            vm.onEvent(CreditEvent.New(CreditNoteType.CreditNote))
            advanceUntilIdle()
            vm.onEvent(CreditEvent.Save)
            val errors = assertNotNull(vm.state.value.credit.form).errors
            assertEquals(setOf(CreditField.Vendor, CreditField.EffectiveDate, CreditField.Lines), errors.keys)
            assertTrue(repo.writes.isEmpty())

            val form = vm.state.value.credit.form!!
            vm.onEvent(CreditEvent.Change(form.copy(vendorId = "v1", effectiveDate = "2026-09-20")))
            val line = vm.state.value.credit.form!!.lines.lines.single()
            vm.onEvent(CreditEvent.Lines(LineEdit.Change(line.copy(description = "Lamps", account = "2400"))))
            vm.onEvent(
                CreditEvent.Lines(LineEdit.Change(line.copy(description = "Lamps", account = "2400", amount = 50.0))),
            )
            vm.onEvent(CreditEvent.AddAttachment)
            advanceUntilIdle()
            vm.onEvent(CreditEvent.Save)
            advanceUntilIdle()
            val (verb, write) = repo.writes.single()
            assertEquals("create", verb)
            assertEquals("Lamps Ltd", write.vendorName)
            assertEquals(listOf("proof.pdf"), files.uploaded)
            assertNotNull(write.attachments.single().stored, "the picked file went to storage before the write")
            assertNull(vm.state.value.credit.form)
        }

    @Test
    fun `a dispute needs the invoice it is against and a reason`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = credits(repo)
        vm.onEvent(CreditEvent.New(CreditNoteType.Dispute))
        advanceUntilIdle()
        val form = vm.state.value.credit.form!!
        vm.onEvent(CreditEvent.Change(form.copy(vendorId = "v1", effectiveDate = "2026-09-20")))
        vm.onEvent(CreditEvent.Save)
        assertEquals(setOf(CreditField.InvoiceRef, CreditField.Reason), vm.state.value.credit.form?.errors?.keys)
        vm.onEvent(CreditEvent.PickInvoice(Invoice(id = "i9", invoiceNumber = "INV-9", currency = "EUR")))
        vm.onEvent(CreditEvent.Change(vm.state.value.credit.form!!.copy(reason = "Short delivery")))
        vm.onEvent(CreditEvent.Save)
        advanceUntilIdle()
        val write = repo.writes.single().second
        assertEquals("INV-9", write.invoiceReference)
        assertEquals("i9", write.invoiceId)
        assertEquals("EUR", write.currency)
    }

    @Test
    fun `a note in a closed period opens read-only and cannot be deleted or applied`() = runTest(dispatcher) {
        val locked = CreditNote("c1", status = CreditNoteStatus.Pending, effectiveDateMs = LOCKED_DAY, vendorId = "v1")
        val repo = Repo(notes = listOf(locked), lock = PeriodLock(lockedThrough = "2026-09-13"))
        val vm = credits(repo)
        vm.onEvent(CreditEvent.Edit(locked))
        val form = assertNotNull(vm.state.value.credit.form)
        assertTrue(form.locked)
        vm.onEvent(CreditEvent.Change(form.copy(reason = "changed")))
        vm.onEvent(CreditEvent.Save)
        assertEquals("", vm.state.value.credit.form?.reason)
        vm.onEvent(CreditEvent.CloseForm)
        vm.onEvent(CreditEvent.Preview(locked))
        vm.onEvent(CreditEvent.RequestDelete)
        assertNull(vm.state.value.credit.confirmDelete)
        vm.onEvent(InvoicesEvent.ActOnCreditNote(locked))
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty() && repo.deleted.isEmpty() && repo.applied.isEmpty())
    }

    @Test
    fun `delete asks first, then deletes and reloads`() = runTest(dispatcher) {
        val note = CreditNote("c1", status = CreditNoteStatus.Disputed, type = CreditNoteType.Dispute)
        val repo = Repo(notes = listOf(note))
        val vm = credits(repo)
        vm.onEvent(CreditEvent.Preview(note))
        vm.onEvent(CreditEvent.RequestDelete)
        assertEquals(note, vm.state.value.credit.confirmDelete)
        assertTrue(repo.deleted.isEmpty())
        vm.onEvent(CreditEvent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf("c1"), repo.deleted)
        assertNull(vm.state.value.credit.preview)
    }

    @Test
    fun `a sales invoice opens on today, is refused without coded lines, and gets a generated reference`() =
        runTest(dispatcher) {
            val repo = Repo()
            val vm = credits(repo)
            vm.onEvent(InvoicesEvent.StartSalesInvoice)
            val draft = assertNotNull(vm.state.value.salesDraft)
            assertEquals(TODAY, draft.invoiceDate)
            val line = draft.lines.lines.single()
            vm.onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(clientName = "Channel 4")))
            vm.onEvent(InvoicesEvent.EditSalesLines(LineEdit.Change(line.copy(description = "Fees", amount = 100.0))))
            vm.onEvent(InvoicesEvent.ConfirmSalesInvoice)
            advanceUntilIdle()
            assertEquals("Line 1: account", vm.state.value.salesDraft?.lineError)
            assertTrue(repo.sales.isEmpty())

            val coded = line.copy(description = "Fees", amount = 100.0, account = "4000")
            vm.onEvent(InvoicesEvent.EditSalesLines(LineEdit.Change(coded)))
            vm.onEvent(InvoicesEvent.ConfirmSalesInvoice)
            advanceUntilIdle()
            val sent = repo.sales.single()
            assertTrue(sent.reference.orEmpty().startsWith("SI-"))
            assertEquals(TODAY, sent.invoiceDate)
            assertEquals("2026-10-23", sent.dueDate, "a blank due date is today plus 30 days")
            assertNull(vm.state.value.salesDraft)
        }

    @Test
    fun `a draft sales invoice is deleted only after the confirmation`() = runTest(dispatcher) {
        val draft = SalesInvoice("s1", reference = "SI-1", status = SalesInvoiceStatus.Draft)
        val sent = SalesInvoice("s2", status = SalesInvoiceStatus.Sent)
        val repo = Repo()
        val vm = credits(repo)
        vm.onEvent(InvoicesEvent.DeleteSalesInvoice(sent))
        assertNull(vm.state.value.confirmSalesDelete)
        vm.onEvent(InvoicesEvent.DeleteSalesInvoice(draft))
        assertEquals(draft, vm.state.value.confirmSalesDelete)
        assertTrue(repo.salesDeleted.isEmpty())
        vm.onEvent(InvoicesEvent.ConfirmDeleteSales)
        advanceUntilIdle()
        assertEquals(listOf("s1"), repo.salesDeleted)
    }

    @Test
    fun `the due date follows the invoice date and the terms`() = runTest(dispatcher) {
        val vm = credits(Repo())
        vm.onEvent(InvoicesEvent.StartSalesInvoice)
        val draft = assertNotNull(vm.state.value.salesDraft)
        assertEquals("2026-10-23", draft.dueDate, "today plus the thirty-day default")
        vm.onEvent(InvoicesEvent.EditSalesInvoice(draft.copy(invoiceDate = "2026-11-01")))
        assertEquals("2026-12-01", vm.state.value.salesDraft?.dueDate)
        vm.onEvent(InvoicesEvent.EditSalesInvoice(vm.state.value.salesDraft!!.copy(payTerms = SalesTerms.Days14)))
        assertEquals("2026-11-15", vm.state.value.salesDraft?.dueDate)
        vm.onEvent(InvoicesEvent.EditSalesInvoice(vm.state.value.salesDraft!!.copy(payTerms = SalesTerms.OnReceipt)))
        assertEquals("2026-11-01", vm.state.value.salesDraft?.dueDate)
        // A typed due date stands until the date or the terms move again.
        vm.onEvent(InvoicesEvent.EditSalesInvoice(vm.state.value.salesDraft!!.copy(dueDate = "2027-01-05")))
        assertEquals("2027-01-05", vm.state.value.salesDraft?.dueDate)
    }

    @Test
    fun `a sales invoice without a client or lines is refused with the web's messages`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = credits(repo)
        vm.onEvent(InvoicesEvent.StartSalesInvoice)
        vm.onEvent(InvoicesEvent.ConfirmSalesInvoice)
        val draft = assertNotNull(vm.state.value.salesDraft)
        assertEquals("Client name is required", draft.errors[SalesField.ClientName])
        assertNotNull(draft.lineError)
        assertTrue(repo.sales.isEmpty())
    }

    @Test
    fun `a draft opened from its preview is edited and updated in place`() = runTest(dispatcher) {
        val stored = SalesInvoice(
            "s1",
            reference = "SI-7",
            clientName = "Channel 4",
            status = SalesInvoiceStatus.Draft,
            invoiceDateMs = NOW,
            dueDateMs = NOW,
            clientAddress = ClientAddress(city = "London"),
            lineItems = listOf(CodedLine("l1", description = "Fees", account = "4000", amount = 100.0)),
            lineItemsJson = """[{"id":"l1","tracking_codes":{"set1":"LOC-LON"},"tags":["a"]}]""",
        )
        val repo = Repo(salesRecord = stored)
        val vm = credits(repo)
        vm.onEvent(SalesEvent.Preview(stored.copy(clientName = "stale")))
        advanceUntilIdle()
        assertEquals("Channel 4", vm.state.value.sales.preview?.clientName, "the preview reads GET /:id")
        vm.onEvent(SalesEvent.Edit)
        val draft = assertNotNull(vm.state.value.salesDraft)
        assertEquals("s1", draft.editingId)
        assertEquals("London", draft.address.city)
        assertNull(vm.state.value.sales.preview)
        vm.onEvent(InvoicesEvent.ConfirmSalesInvoice)
        advanceUntilIdle()
        val (id, write) = repo.salesUpdates.single()
        assertEquals("s1", id)
        assertNull(write.reference, "an update keeps the stored reference")
        val line = salesInvoiceBody(write)["line_items"]!!.jsonArray.single().jsonObject
        assertEquals("LOC-LON", line["tracking_codes"]!!.jsonObject["set1"]!!.jsonPrimitive.content)
        assertTrue(repo.sales.isEmpty())
    }

    // -- harness ---------------------------------------------------------------------------

    private fun credits(repo: Repo, files: Files = Files(emptyList())) = InvoicesViewModel(
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
        it.onEvent(InvoicesEvent.SelectPage(AccountantPage.Credits))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class Repo(
        private val notes: List<CreditNote> = emptyList(),
        private val lock: PeriodLock = PeriodLock(),
        private val salesRecord: SalesInvoice? = null,
    ) : InvoicesRepository {
        val salesUpdates = mutableListOf<Pair<String, SalesInvoiceWrite>>()

        override suspend fun salesInvoice(id: String): ZillitResult<SalesInvoice> =
            salesRecord?.let { ZillitResult.Success(it) } ?: ZillitResult.Success(SalesInvoice(id))

        override suspend fun updateSalesInvoice(id: String, invoice: SalesInvoiceWrite): ZillitResult<Unit> {
            salesUpdates += id to invoice
            return ZillitResult.Success(Unit)
        }

        val writes = mutableListOf<Pair<String, CreditNoteWrite>>()
        val deleted = mutableListOf<String>()
        val applied = mutableListOf<String>()
        val sales = mutableListOf<SalesInvoiceWrite>()
        val salesDeleted = mutableListOf<String>()

        override suspend fun creditNotes(): ZillitResult<List<CreditNote>> = ZillitResult.Success(notes)
        override suspend fun createCreditNote(write: CreditNoteWrite): ZillitResult<Unit> {
            writes += "create" to write
            return ZillitResult.Success(Unit)
        }
        override suspend fun updateCreditNote(id: String, write: CreditNoteWrite): ZillitResult<Unit> {
            writes += "update:$id" to write
            return ZillitResult.Success(Unit)
        }
        override suspend fun deleteCreditNote(id: String): ZillitResult<Unit> {
            deleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun applyCreditNote(id: String): ZillitResult<Unit> {
            applied += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun createSalesInvoice(invoice: SalesInvoiceWrite): ZillitResult<Unit> {
            sales += invoice
            return ZillitResult.Success(Unit)
        }
        override suspend fun deleteSalesInvoice(id: String): ZillitResult<Unit> {
            salesDeleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun periodLock(): ZillitResult<PeriodLock> = ZillitResult.Success(lock)
        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
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
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
    }

    private class Files(private val picked: List<PickedInvoiceFile>) : InvoiceFiles {
        val uploaded = mutableListOf<String>()

        override suspend fun pick(): List<PickedInvoiceFile> = picked
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> {
            uploaded += file.name
            return ZillitResult.Success(InvoiceAttachment("k/${file.name}", "b", "r", file.name, "file", "pdf"))
        }
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private companion object {
        /** 2026-09-23 12:00 UTC. */
        const val NOW = 1_790_164_800_000L
        const val TODAY = "2026-09-23"

        /** 2026-09-13, inside a lock through that day. */
        const val LOCKED_DAY = 1_789_300_000_000L

        val SENIOR = InvoiceViewer(
            userId = "me",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            ready = true,
        )

        val WRITE = CreditNoteWrite(
            type = CreditNoteType.CreditNote,
            vendorId = "v1",
            vendorName = "Lamps Ltd",
            invoiceReference = "INV-1",
            invoiceId = "i1",
            reason = "Overcharged",
            effectiveDate = "2026-09-20",
            currency = "GBP",
            disputeAmount = 0.0,
            notes = "",
            lines = listOf(CodedLine("l1", description = "Lamps", account = "2400", amount = 100.0, taxRate = 20.0)),
            attachments = emptyList(),
        )
    }
}
