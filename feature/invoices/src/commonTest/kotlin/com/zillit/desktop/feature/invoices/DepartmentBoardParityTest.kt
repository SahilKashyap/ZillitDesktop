package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.Approval
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.RunSignOff
import com.zillit.desktop.feature.invoices.domain.TeamMember
import com.zillit.desktop.feature.invoices.domain.TierLevel
import com.zillit.desktop.feature.invoices.domain.TierRule
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.QuickFilter
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

/**
 * The department board (`DepartmentInvoiceModule.jsx`) and the rules it
 * shares: the queue's delete, the settings' seniority, the run tab, and the
 * row-by-row badge reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepartmentBoardParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- canDeleteInvoice (utils/approval-helpers.js:404-418) ---------------

    private val me = InvoiceViewer(userId = "me")
    private val tiers = listOf(ResolvedTier(1, listOf("hod")), ResolvedTier(2, listOf("fc")))
    private val fresh = Invoice(id = "i1", userId = "someone", status = InvoiceStatus.Approval)

    @Test
    fun `nothing is deleted in a locked period, or once approved or overridden`() {
        val mine = fresh.copy(userId = "me")
        assertFalse(InvoiceRules.canDeleteFromQueue(mine, tiers, me, canOverride = true, locked = true))
        assertFalse(
            InvoiceRules.canDeleteFromQueue(
                mine.copy(approvalStatus = ApprovalStatus.Approved),
                tiers,
                me,
                canOverride = true,
                locked = false,
            ),
        )
        listOf(InvoiceStatus.Approved, InvoiceStatus.Override).forEach { status ->
            assertFalse(
                InvoiceRules.canDeleteFromQueue(mine.copy(status = status), tiers, me, true, locked = false),
            )
        }
    }

    @Test
    fun `a part-approved invoice takes override rights, whoever raised it`() {
        val signed = fresh.copy(userId = "me", approvals = listOf(Approval("hod", 1, 1L)))
        assertFalse(InvoiceRules.canDeleteFromQueue(signed, tiers, me, canOverride = false, locked = false))
        assertTrue(InvoiceRules.canDeleteFromQueue(signed, tiers, me, canOverride = true, locked = false))
        val approver = InvoiceViewer(userId = "fc")
        assertFalse(InvoiceRules.canDeleteFromQueue(signed, tiers, approver, canOverride = false, locked = false))
    }

    @Test
    fun `an untouched chain may be deleted by its creator, any approver on it, or override rights`() {
        assertTrue(InvoiceRules.canDeleteFromQueue(fresh.copy(userId = "me"), tiers, me, false, locked = false))
        assertTrue(InvoiceRules.canDeleteFromQueue(fresh, tiers, InvoiceViewer(userId = "fc"), false, false))
        assertTrue(InvoiceRules.canDeleteFromQueue(fresh, tiers, me, canOverride = true, locked = false))
        assertFalse(InvoiceRules.canDeleteFromQueue(fresh, tiers, me, canOverride = false, locked = false))
        // A rejected invoice is dead and wants clearing — the queue keeps its delete.
        val rejected = fresh.copy(status = InvoiceStatus.Rejected, approvalStatus = ApprovalStatus.Rejected)
        assertTrue(InvoiceRules.canDeleteFromQueue(rejected, tiers, InvoiceViewer(userId = "hod"), false, false))
    }

    // -- approval_status (only a missing one is pending) ---------------------

    @Test
    fun `only an absent approval status is pending`() {
        assertEquals(ApprovalStatus.Pending, ApprovalStatus.from(null))
        assertEquals(ApprovalStatus.Pending, ApprovalStatus.from(""))
        assertEquals(ApprovalStatus.Pending, ApprovalStatus.from("Pending"))
        assertEquals(ApprovalStatus.Approved, ApprovalStatus.from("approved"))
        assertEquals(ApprovalStatus.Other, ApprovalStatus.from("on_hold"))
        val odd = fresh.copy(userId = "me", status = InvoiceStatus.Inbox, approvalStatus = ApprovalStatus.Other)
        assertFalse(QuickFilter.Pending.keeps(odd))
        assertFalse(InvoiceRules.canDeleteOwn(odd, me))
        assertTrue(InvoiceRules.canDeleteOwn(odd.copy(approvalStatus = ApprovalStatus.Pending), me))
    }

    // -- the settings' seniority and override (InvoicesModule.jsx:293-318) ---

    @Test
    fun `seniority is the me block's only, never the team_members row`() {
        val legacy = InvoiceSettings(teamMembers = listOf(TeamMember("u1", overrideAccess = true, isSenior = true)))
        assertFalse(legacy.seniorFor("u1"))
        assertTrue(legacy.overrideFor("u1"), "override still falls back without a me block")
        assertFalse(legacy.serverOverride, "the department board has no fallback")

        // A me block without can_override does not reach for the team row.
        val withMe = legacy.copy(isSenior = false, hasMe = true)
        assertFalse(withMe.overrideFor("u1"))
        assertTrue(InvoiceSettings(isSenior = true).overrideFor("anyone"), "is_senior carries override")
        assertTrue(InvoiceSettings(isSenior = true).serverOverride)

        val viewer = InvoiceViewer(userId = "u1").withSettings(legacy)
        assertFalse(viewer.isSenior)
        assertFalse(viewer.serverOverride)
        assertTrue(viewer.canOverride)
    }

    // -- the board ------------------------------------------------------------

    @Test
    fun `the run tab lists only the runs whose next tier the reader signs`() = runTest(dispatcher) {
        val chain = listOf(RunAuthLevel(1, listOf("me")), RunAuthLevel(2, listOf("fc")))
        val repo = Repo(
            settings = InvoiceSettings(runAuthorisation = chain),
            runs = listOf(
                PaymentRun(id = "r-mine", number = "PR-001", status = PaymentRunStatus.Pending),
                PaymentRun(
                    id = "r-signed",
                    number = "PR-002",
                    status = PaymentRunStatus.Pending,
                    approvals = listOf(RunSignOff(1, "me")),
                ),
                PaymentRun(id = "r-done", number = "PR-003", status = PaymentRunStatus.Approved),
            ),
        )
        val badges = Badges()
        val vm = viewModel(repo, badges)
        assertTrue(vm.state.value.viewer.isRunApprover)

        vm.onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.RunApproval))
        advanceUntilIdle()
        assertEquals(listOf("r-mine"), vm.state.value.runsAwaitingMe.map { it.id })

        vm.onEvent(InvoicesEvent.OpenRun(vm.state.value.runsAwaitingMe.single()))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.ApproveRun(vm.state.value.runsAwaitingMe.single()))
        advanceUntilIdle()
        assertEquals(listOf(Triple("r-mine", 1, 2)), repo.runApprovals)
        // The decision reads the run, under the run tab — never the opening (ZL-21219).
        assertEquals(listOf<Triple<String, String, String?>>(Triple("payment_runs", "r-mine", "invoice_label")), badges.entityReads)
    }

    @Test
    fun `somebody off the run chain is sent back to the approval queue`() = runTest(dispatcher) {
        // A deep link to the run tab, answered by settings that do not list the reader.
        val vm = viewModel(Repo(), Badges(), route = "/film-tools/invoices?tab=runApproval")
        assertFalse(vm.state.value.viewer.isRunApprover)
        assertEquals(DepartmentTab.ApprovalQueue, vm.state.value.departmentTab)
    }

    @Test
    fun `the tab comes from the route and a new tab starts on All`() = runTest(dispatcher) {
        val vm = viewModel(Repo(), Badges())
        vm.onEvent(InvoicesEvent.SelectQuickFilter(QuickFilter.Rejected))
        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices?tab=my"))
        advanceUntilIdle()
        assertEquals(DepartmentTab.MyInvoices, vm.state.value.departmentTab)
        assertEquals(QuickFilter.All, vm.state.value.quickFilter)

        vm.onEvent(InvoicesEvent.SelectQuickFilter(QuickFilter.Pending))
        vm.onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.MyDepartment))
        advanceUntilIdle()
        assertEquals(QuickFilter.All, vm.state.value.quickFilter)

        vm.onEvent(InvoicesEvent.OpenRoute("/film-tools/invoices?tab=nonsense"))
        advanceUntilIdle()
        assertEquals(DepartmentTab.ApprovalQueue, vm.state.value.departmentTab)
    }

    @Test
    fun `rows are read one by one, and an approval closes the detail`() = runTest(dispatcher) {
        val repo = Repo()
        val badges = Badges()
        val vm = viewModel(repo, badges)
        assertTrue(badges.tabReads.isEmpty(), "the department board never reads a whole tab")

        vm.onEvent(InvoicesEvent.Open(repo.pending))
        advanceUntilIdle()
        assertEquals(Triple("invoice_approval_queue", "i-pending", "invoice_label"), badges.entityReads.single())

        vm.onEvent(InvoicesEvent.Approve(repo.pending))
        advanceUntilIdle()
        assertEquals(listOf("i-pending"), repo.approvals)
        assertNull(vm.state.value.detail)
        assertEquals(2, badges.entityReads.size)
        assertTrue(badges.tabReads.isEmpty())
    }

    @Test
    fun `the department board overrides nothing, whatever its rights`() = runTest(dispatcher) {
        val repo = Repo(settings = InvoiceSettings(canOverride = true, isSenior = true))
        val vm = viewModel(repo, Badges())
        assertTrue(vm.state.value.viewer.serverOverride)
        vm.onEvent(InvoicesEvent.Override(repo.pending))
        vm.onEvent(InvoicesEvent.OverrideAndPay(repo.pending))
        advanceUntilIdle()
        assertTrue(repo.overrides.isEmpty())
    }

    @Test
    fun `a delete waits for its answer and My Department asks by department alone`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = viewModel(repo, Badges())
        vm.onEvent(InvoicesEvent.RequestDelete(repo.pending))
        vm.onEvent(InvoicesEvent.ConfirmDelete)
        assertNotNull(vm.state.value.confirmDelete, "the confirmation stays up while the delete is out")
        advanceUntilIdle()
        assertEquals(listOf("i-pending"), repo.deleted)
        assertNull(vm.state.value.confirmDelete)

        vm.onEvent(InvoicesEvent.SelectDepartmentTab(DepartmentTab.MyDepartment))
        advanceUntilIdle()
        assertEquals(InvoiceQuery(departmentId = "d-cam", perPage = null), repo.queries.last())
    }

    // -- harness ----------------------------------------------------------------

    private val crew = InvoiceViewer(
        userId = "me",
        departmentId = "d-cam",
        departmentIdentifier = "camera",
        ready = true,
    )

    private fun viewModel(repo: Repo, badges: Badges, route: String? = null) = InvoicesViewModel(
        repository = repo,
        files = Files,
        resolveViewer = { crew },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { NOW },
        badges = badges,
    ).also {
        // A route that arrives before the viewer is known waits for start().
        route?.let { path -> it.onEvent(InvoicesEvent.OpenRoute(path)) }
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class Badges : TabBadgeSource {
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

    private class Repo(
        private val settings: InvoiceSettings = InvoiceSettings(),
        private val runs: List<PaymentRun> = emptyList(),
    ) : InvoicesRepository {
        val approvals = mutableListOf<String>()
        val overrides = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val queries = mutableListOf<InvoiceQuery>()
        val runApprovals = mutableListOf<Triple<String, Int, Int>>()

        val pending = Invoice(
            id = "i-pending",
            invoiceNumber = "INV-7",
            vendorId = "v1",
            grossAmount = 100.0,
            status = InvoiceStatus.Approval,
            departmentId = "d-cam",
            userId = "someone",
        )

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> {
            queries += query
            return ZillitResult.Success(listOf(pending))
        }
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = ZillitResult.Success(listOf(pending))
        override suspend fun mine(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())
        override suspend fun invoice(id: String): ZillitResult<Invoice> = ZillitResult.Success(pending)
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun delete(id: String): ZillitResult<Unit> {
            deleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> {
            approvals += id
            return ZillitResult.Success(null)
        }
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = ZillitResult.Success(null)
        override suspend fun override(id: String): ZillitResult<Unit> {
            overrides += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun chase(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(settings)
        override suspend fun paymentRuns(): ZillitResult<List<PaymentRun>> = ZillitResult.Success(runs)
        override suspend fun paymentRun(id: String): ZillitResult<PaymentRunDetail> =
            ZillitResult.Success(PaymentRunDetail(runs.first { it.id == id }))
        override suspend fun approvePaymentRun(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> {
            runApprovals += Triple(id, tierNumber, totalTiers)
            return ZillitResult.Success(Unit)
        }
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
    }
}
