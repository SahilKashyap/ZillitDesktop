package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
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
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.EnterField
import com.zillit.desktop.feature.invoices.ui.EnterTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvoicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = InvoiceViewer(
        userId = "me",
        departmentId = "d-cam",
        departmentIdentifier = "camera",
        ready = true,
    )
    private val accountant = InvoiceViewer(
        userId = "acc",
        departmentId = "d-acc",
        departmentIdentifier = "accounts",
        ready = true,
    )

    private fun viewModel(repo: FakeRepo, files: FakeFiles = FakeFiles(), viewer: InvoiceViewer = crew) =
        InvoicesViewModel(
            repository = repo,
            files = files,
            resolveViewer = { viewer },
            projectMoney = { CurrencyRates("GBP") },
            resolveUser = { id -> if (id == "me") "Me Myself" else null },
            departmentName = { id -> if (id == "d-cam") "Camera" else null },
            nowMillis = { NOW },
        ).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `the department view opens on the approval queue and switches to my uploads`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        assertEquals(listOf("approval"), repo.calls)
        assertEquals(1, vm.state.value.invoices.size)
        assertEquals("Camera", vm.state.value.departmentNames["d-cam"])

        vm.onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.MyInvoices))
        advanceUntilIdle()
        assertEquals("my", repo.calls.last())
        assertEquals("i-mine", vm.state.value.invoices.single().id)
    }

    @Test
    fun `approve sends the next tier and the resolved total, then reloads`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.Approve(repo.pending))
        advanceUntilIdle()
        assertEquals(listOf("i-pending" to (1 to 2)), repo.approvals)
        assertTrue(repo.calls.count { it == "approval" } >= 2, "the queue reloads after approving")
    }

    @Test
    fun `reject needs a reason and then posts it`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.Open(repo.pending))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.StartReject)
        vm.onEvent(InvoicesEvent.ConfirmReject)
        advanceUntilIdle()
        assertTrue(repo.rejections.isEmpty())
        assertNotNull(vm.state.value.error)

        vm.onEvent(InvoicesEvent.RejectReasonChanged("Wrong PO"))
        vm.onEvent(InvoicesEvent.ConfirmReject)
        advanceUntilIdle()
        assertEquals(listOf("i-pending" to "Wrong PO"), repo.rejections)
        // The web's reject closes the detail once it lands (`InvoiceDetailModal.jsx:948`).
        assertNull(vm.state.value.detail)
    }

    /**
     * The department's Upload Invoices is the web's bulk upload: every file is
     * checked, a refused one holds the whole batch back until it is removed,
     * and the rest go to storage and are handed over under one batch id.
     */
    @Test
    fun `a department upload is a bulk batch, and a refused file never reaches storage`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val files = FakeFiles(
            picked = listOf(
                PickedInvoiceFile("notes.txt", "text/plain", ByteArray(3)),
                PickedInvoiceFile("acme.jpg", "image/jpeg", ByteArray(10)),
            ),
        )
        val vm = viewModel(repo, files)
        vm.onEvent(InvoicesEvent.UploadInvoice)
        advanceUntilIdle()
        val pick = assertNotNull(vm.state.value.bulkPick)
        assertEquals(1, pick.sendable)
        assertFalse(pick.allowPaid, "only the accountant's upload offers Paid")

        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertEquals(0, files.uploaded, "nothing is sent while a file is refused")
        vm.onEvent(InboxEvent.RemoveBulkFile(pick.files.first { it.problem != null }.ref))
        vm.onEvent(InboxEvent.SubmitBulk)
        advanceUntilIdle()
        assertEquals(1, files.uploaded)
        val sent = repo.bulk.single()
        assertTrue(sent.first.startsWith("inv-bulk-"))
        assertEquals("acme.jpg", sent.second)
        assertNull(vm.state.value.bulkPick)
        assertEquals(DepartmentTab.Uploads, vm.state.value.departmentTab)
        val batch = vm.state.value.bulkBatches.single()
        assertTrue(batch.postingDone)
        assertEquals(1, batch.sentCount)
    }

    /**
     * The web gates the department's Upload Invoices on nothing
     * (`DepartmentInvoiceModule.jsx:1050-1054`), so a crew member without the
     * tool's posting right still gets the picker.
     */
    @Test
    fun `a department upload needs no posting right`() = runTest(dispatcher) {
        val files = FakeFiles(picked = listOf(PickedInvoiceFile("acme.jpg", "image/jpeg", ByteArray(10))))
        val vm = viewModel(FakeRepo(), files, viewer = crew.copy(canPost = false))
        vm.onEvent(InvoicesEvent.UploadInvoice)
        advanceUntilIdle()
        assertEquals(1, assertNotNull(vm.state.value.bulkPick).sendable)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `accountants load the inbox by status and enter invoices with linked amounts`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val files = FakeFiles(picked = listOf(PickedInvoiceFile("inv.pdf", "application/pdf", ByteArray(4))))
        val vm = viewModel(repo, files, viewer = accountant)
        // The landing is the dashboard — the web redirects `/invoices` there —
        // and it lists nothing, so the register is opened for the first query.
        assertEquals(emptyList(), repo.queries)
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Register))
        advanceUntilIdle()
        assertEquals(listOf(InvoiceQuery()), repo.queries)

        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Inbox))
        advanceUntilIdle()
        assertEquals(listOf(InvoiceStatus.Inbox), repo.queries.last().statuses)

        vm.onEvent(InvoicesEvent.OpenEnter)
        vm.onEvent(InvoicesEvent.SelectEnterTab(EnterTab.Manual))
        vm.onEvent(InvoicesEvent.EnterPickFile)
        advanceUntilIdle()
        val form = assertNotNull(vm.state.value.enter)
        assertNotNull(form.attachment)
        assertEquals("b1", form.bankId, "a single production bank is picked")

        vm.onEvent(InvoicesEvent.EnterNetChanged("1000"))
        vm.onEvent(InvoicesEvent.EnterTaxChanged("200"))
        assertEquals("1200", vm.state.value.enter?.gross, "gross follows net + tax, in natural decimals")
        vm.onEvent(InvoicesEvent.EnterGrossChanged("1300"))
        vm.onEvent(InvoicesEvent.EnterNetChanged("1000"))
        assertEquals("300", vm.state.value.enter?.tax, "after a typed gross, tax follows gross − net")

        // The dates start empty and are required: every missing field is named at once.
        vm.onEvent(InvoicesEvent.SubmitEnter)
        assertEquals(
            setOf(EnterField.Vendor, EnterField.InvoiceNumber, EnterField.InvoiceDate, EnterField.EffectiveDate, EnterField.Department),
            vm.state.value.enter?.errors?.keys,
        )
        vm.onEvent(
            InvoicesEvent.EnterChanged(
                vm.state.value.enter!!.copy(
                    vendorId = "v1",
                    invoiceNumber = "INV-1",
                    departmentId = "d-cam",
                    invoiceDate = "2026-09-01",
                    effectiveDate = "2026-09-01",
                ),
            ),
        )
        assertTrue(vm.state.value.enter?.errors.orEmpty().isEmpty(), "filled fields clear their errors")
        vm.onEvent(InvoicesEvent.SubmitEnter)
        advanceUntilIdle()
        val entered = repo.entered.single()
        assertEquals("Invoice", entered.description, "a blank description is sent as the web sends it")
        assertEquals(1300.0, entered.grossAmount)
        assertEquals(1000.0, entered.netAmount)
        assertEquals(300.0, entered.taxAmount)
        assertEquals("b1", entered.bankId)
        assertEquals(PayMethod.Bacs, entered.payMethod)
        assertNull(vm.state.value.enter)
    }

    /**
     * One `POST /:id/override`, not the two PATCHes that walked past the
     * server's permission gate — and Override & Pay takes the same route.
     */
    @Test
    fun `override is one call to the override route and delete removes the row`() = runTest(dispatcher) {
        // Override rights come from the settings document's `me` block.
        val repo = FakeRepo(settings = InvoiceSettings(canOverride = true))
        val vm = viewModel(repo, viewer = accountant)
        vm.onEvent(InvoicesEvent.Override(repo.pending))
        advanceUntilIdle()
        assertEquals(listOf("i-pending"), repo.overrides)

        vm.onEvent(InvoicesEvent.OverrideAndPay(repo.pending))
        advanceUntilIdle()
        assertEquals(listOf("i-pending", "i-pending"), repo.overrides)

        vm.onEvent(InvoicesEvent.RequestDelete(repo.pending))
        vm.onEvent(InvoicesEvent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf("i-pending"), repo.deleted)
    }

    /** The button hides without override rights; the handler refuses as well. */
    @Test
    fun `override without override rights sends nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = viewModel(repo, viewer = accountant)
        vm.onEvent(InvoicesEvent.Override(repo.pending))
        vm.onEvent(InvoicesEvent.OverrideAndPay(repo.pending))
        advanceUntilIdle()
        assertTrue(repo.overrides.isEmpty())
    }

    // -- fakes -----------------------------------------------------------------

    private class FakeFiles(private val picked: List<PickedInvoiceFile> = emptyList()) : InvoiceFiles {
        var uploaded = 0
        override suspend fun pick(): List<PickedInvoiceFile> = picked
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> {
            uploaded++
            return ZillitResult.Success(
                InvoiceAttachment("k/${file.name}", "b", "r", file.name, "document", file.extension),
            )
        }
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> = ZillitResult.Success(
            ByteArray(1),
        )
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> = ZillitResult.Success(
            Unit,
        )
    }

    private class FakeRepo(
        private val settings: InvoiceSettings = InvoiceSettings(),
    ) : InvoicesRepository {
        val bulk = mutableListOf<Pair<String, String>>()

        override suspend fun bulkUpload(
            batchId: String,
            attachment: InvoiceAttachment,
            size: Long,
            paid: Boolean,
        ): ZillitResult<Unit> {
            bulk += batchId to attachment.name
            return ZillitResult.Success(Unit)
        }

        val calls = mutableListOf<String>()
        val queries = mutableListOf<InvoiceQuery>()
        val approvals = mutableListOf<Pair<String, Pair<Int, Int>>>()
        val rejections = mutableListOf<Pair<String, String>>()
        val overrides = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val entered = mutableListOf<EnteredInvoice>()

        val pending = Invoice(
            id = "i-pending",
            invoiceNumber = "INV-7",
            vendorId = "v1",
            grossAmount = 100.0,
            status = InvoiceStatus.Approval,
            approvalStatus = ApprovalStatus.Pending,
            departmentId = "d-cam",
            userId = "someone",
        )
        private val mine = pending.copy(id = "i-mine", userId = "me", status = InvoiceStatus.Inbox)

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> {
            calls += "list"
            queries += query
            return ZillitResult.Success(listOf(pending, mine))
        }
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> {
            calls += "approval"
            return ZillitResult.Success(listOf(pending))
        }
        override suspend fun mine(): ZillitResult<List<Invoice>> {
            calls += "my"
            return ZillitResult.Success(listOf(mine))
        }
        override suspend fun invoice(id: String): ZillitResult<Invoice> =
            ZillitResult.Success(if (id == mine.id) mine else pending)
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> {
            this.entered += entered
            return ZillitResult.Success(null)
        }
        override suspend fun override(id: String): ZillitResult<Unit> {
            overrides += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun delete(id: String): ZillitResult<Unit> {
            deleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> {
            approvals += id to (tierNumber to totalTiers)
            return ZillitResult.Success(null)
        }
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> {
            rejections += id to reason
            return ZillitResult.Success(null)
        }
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> =
            ZillitResult.Success(listOf(HistoryEntry("created", "someone", 1L)))
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(settings)
        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> = ZillitResult.Success(
            listOf(
                ApprovalTierConfig(
                    scope = TierScope.All,
                    tiers = listOf(
                        TierLevel(1, listOf(TierRule("default", userIds = listOf("me")))),
                        TierLevel(2, listOf(TierRule("default", userIds = listOf("fc")))),
                    ),
                ),
            ),
        )
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(listOf(Vendor("v1", "Acme")))
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(
            listOf(BankAccount("b1", "Main")),
        )
    }

    private companion object {
        const val NOW = 1_787_011_200_000L
    }
}
