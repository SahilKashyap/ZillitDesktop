package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAlert
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceDirectory
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceSetup
import com.zillit.desktop.feature.invoices.domain.InvoiceSetupBundle
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoiceSetupSection
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.invoices.ui.TeamMemberDraft
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

/** The Settings page and the pre-approval review, driven through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceSetupFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val senior = InvoiceViewer(
        userId = "acc",
        departmentIdentifier = "accounts",
        designationIdentifier = "production_accountant",
        ready = true,
    )

    private fun viewModel(repo: FakeSetupRepo) = InvoicesViewModel(
        repository = repo,
        files = NoFiles,
        resolveViewer = { senior },
        projectMoney = { CurrencyRates("GBP") },
        resolveUser = { null },
        departmentName = { null },
        nowMillis = { 1_789_000_000_000 },
        directory = InvoiceDirectory(
            accountsTeam = { listOf(InvoiceAssignee("acc", "Sahil"), InvoiceAssignee("u2", "Jane")) },
            everyone = {
                listOf(
                    InvoiceAssignee("acc", "Sahil"),
                    InvoiceAssignee("u2", "Jane"),
                    InvoiceAssignee("p1", "Producer"),
                )
            },
        ),
    ).also {
        it.start()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun openSettings(repo: FakeSetupRepo): InvoicesViewModel {
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.SelectPage(AccountantPage.Settings))
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    /** Opening the page reads the document, its rules, and the two directories the pickers need. */
    @Test
    fun `the settings page loads its own data`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = openSettings(repo)
        val setup = vm.state.value.setup
        assertFalse(setup.loading)
        assertEquals(1, setup.edited.teamMembers.size)
        assertEquals(1, setup.rules.size)
        assertEquals(1, setup.nominals.size)
        // The sign-off picker offers the whole crew; the rules offer the accounts team.
        assertEquals(3, setup.people.size)
        assertEquals(2, vm.state.value.assignees.size)
        // No invoice list is fetched for this page.
        assertTrue(repo.listed.isEmpty())
    }

    /** Alerts are dirty until saved, then say "Saved" for a moment. */
    @Test
    fun `an alert toggle is dirty until it is saved`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = openSettings(repo)
        assertFalse(vm.state.value.setup.isDirty(InvoiceSetupSection.Alerts))

        vm.onEvent(InvoicesEvent.ToggleAlert(InvoiceAlert.DuplicateDetection))
        assertTrue(vm.state.value.setup.isDirty(InvoiceSetupSection.Alerts))

        vm.onEvent(InvoicesEvent.SaveSetupSection(InvoiceSetupSection.Alerts))
        advanceUntilIdle()
        assertEquals(setOf("invoice_overdue", "duplicate_detection"), repo.savedAlerts)
        assertFalse(vm.state.value.setup.isDirty(InvoiceSetupSection.Alerts))
    }

    /** A member is written the moment the sheet is confirmed, not on a section Save. */
    @Test
    fun `a team member persists straight away`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = openSettings(repo)
        vm.onEvent(InvoicesEvent.AddTeamMember)
        val draft = assertNotNull(vm.state.value.setup.memberDraft)
        vm.onEvent(
            InvoicesEvent.ChangeTeamMemberDraft(
                draft.copy(row = draft.row.copy(userId = "u2"), unlimited = false, limitText = "2500"),
            ),
        )
        vm.onEvent(InvoicesEvent.CommitTeamMember)
        advanceUntilIdle()

        assertNull(vm.state.value.setup.memberDraft, "the sheet closes once the write lands")
        val written = repo.savedTeam.last()
        assertEquals(listOf("acc", "u2"), written.map { it.userId })
        assertEquals(2500.0, written.last().postingLimit)
        // The team card has no Save of its own, so it is never dirty.
        assertFalse(vm.state.value.setup.isDirty(InvoiceSetupSection.Team))
    }

    /** A refusal keeps the sheet open with what was typed. */
    @Test
    fun `a refused member save keeps the sheet`() = runTest(dispatcher) {
        val repo = FakeSetupRepo(teamFails = true)
        val vm = openSettings(repo)
        vm.onEvent(InvoicesEvent.AddTeamMember)
        val draft = assertNotNull(vm.state.value.setup.memberDraft)
        vm.onEvent(InvoicesEvent.ChangeTeamMemberDraft(draft.copy(row = draft.row.copy(userId = "u2"))))
        vm.onEvent(InvoicesEvent.CommitTeamMember)
        advanceUntilIdle()

        val still = assertNotNull(vm.state.value.setup.memberDraft)
        assertEquals("u2", still.row.userId)
        assertFalse(still.busy)
        assertNotNull(vm.state.value.error)
    }

    /**
     * Saving the rules keeps the ids the server gave the rows it took, so a
     * retry after a refusal patches them instead of creating a second copy.
     */
    @Test
    fun `a partial rule save keeps the ids already issued`() = runTest(dispatcher) {
        val repo = FakeSetupRepo(failRuleFrom = 1)
        val vm = openSettings(repo)
        vm.onEvent(InvoicesEvent.AddAssignmentRule)
        vm.onEvent(InvoicesEvent.AddAssignmentRule)
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.SaveSetupSection(InvoiceSetupSection.Rules))
        advanceUntilIdle()

        val rules = vm.state.value.setup.rules
        assertEquals(3, rules.size)
        assertTrue(rules[0].persisted)
        assertTrue(rules[1].persisted, "the row the server took keeps the id it was given")
        assertFalse(rules[2].persisted)
        assertNotNull(vm.state.value.error)
    }

    /** A stored rule asks before it goes; one never saved just goes. */
    @Test
    fun `removing a rule asks only when the server holds it`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = openSettings(repo)
        vm.onEvent(InvoicesEvent.AddAssignmentRule)
        val local = vm.state.value.setup.rules.last()
        vm.onEvent(InvoicesEvent.RequestRemoveRule(local.id))
        assertNull(vm.state.value.setup.removingRule)
        assertEquals(1, vm.state.value.setup.rules.size)

        vm.onEvent(InvoicesEvent.RequestRemoveRule("r1"))
        assertNotNull(vm.state.value.setup.removingRule)
        vm.onEvent(InvoicesEvent.ConfirmRemoveRule)
        advanceUntilIdle()
        assertEquals(listOf("r1"), repo.deletedRules)
        assertTrue(vm.state.value.setup.rules.isEmpty())
    }

    // -- the review overlay --------------------------------------------------

    /** Opening a review re-reads the invoice, because the list row is a summary. */
    @Test
    fun `the review re-reads the invoice`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.OpenReview(repo.matching))
        advanceUntilIdle()
        val review = assertNotNull(vm.state.value.review)
        assertFalse(review.loading)
        assertTrue(review.hasPo)
        assertEquals("PO-0042", review.activePo?.poNumber)
        assertEquals(listOf("i-match"), repo.read)
    }

    /** Confirming sends it for approval and closes the overlay. */
    @Test
    fun `confirming from the review sends for approval`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.OpenReview(repo.matching))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.ReviewSendToApproval)
        advanceUntilIdle()
        assertEquals(listOf("i-match"), repo.sent)
        assertNull(vm.state.value.review)
    }

    /** Overriding takes the same row past the chain. */
    @Test
    fun `overriding from the review calls the override route`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.OpenReview(repo.matching))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.ReviewOverride)
        advanceUntilIdle()
        assertEquals(listOf("i-match"), repo.overridden)
        assertNull(vm.state.value.review)
    }

    /** Holding from inside the review closes the overlay with it. */
    @Test
    fun `a hold decided in the review closes it`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.OpenReview(repo.matching))
        advanceUntilIdle()
        vm.onEvent(InvoicesEvent.StartHold(repo.matching))
        assertNotNull(vm.state.value.holdFor)
        assertNotNull(vm.state.value.review, "the overlay stays under the hold dialog")

        vm.onEvent(InvoicesEvent.HoldReasonChanged(HoldReason.entries.first()))
        vm.onEvent(InvoicesEvent.ConfirmHold)
        advanceUntilIdle()
        assertNull(vm.state.value.holdFor)
        assertNull(vm.state.value.review)
        assertEquals(listOf("i-match"), repo.held)
    }

    /** The picker asks the server for this vendor's orders, and links the one chosen. */
    @Test
    fun `the po picker matches the order it is given`() = runTest(dispatcher) {
        val repo = FakeSetupRepo()
        val vm = viewModel(repo)
        vm.onEvent(InvoicesEvent.OpenPoSuggestions(repo.unmatched))
        advanceUntilIdle()
        val picker = assertNotNull(vm.state.value.poPicker)
        assertFalse(picker.loading)
        assertEquals(1, picker.suggestions.total)
        assertEquals("v1", repo.suggestedFor.single().second)

        vm.onEvent(InvoicesEvent.MatchToPo(picker.suggestions.vendorPos.single()))
        advanceUntilIdle()
        assertEquals(listOf("i-open" to "p1"), repo.matched)
        assertNull(vm.state.value.poPicker)
    }

    private object NoFiles : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = emptyList()
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    @Suppress("TooManyFunctions")
    private class FakeSetupRepo(
        private val teamFails: Boolean = false,
        /** Index of the first rule the server turns down. */
        private val failRuleFrom: Int = -1,
    ) : InvoicesRepository {
        val listed = mutableListOf<InvoiceQuery>()
        val read = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val overridden = mutableListOf<String>()
        val matched = mutableListOf<Pair<String, String>>()
        val suggestedFor = mutableListOf<Pair<String, String?>>()
        val savedTeam = mutableListOf<List<InvoiceTeamRow>>()
        val held = mutableListOf<String>()
        val deletedRules = mutableListOf<String>()
        var savedAlerts: Set<String>? = null
        var savedChain: List<RunAuthLevel>? = null
        private var created = 0

        val matching = Invoice(
            id = "i-match",
            invoiceNumber = "INV-1",
            vendorId = "v1",
            status = InvoiceStatus.Matching,
            linkedPos = listOf(LinkedPo("p1", "PO-0042")),
        )
        val unmatched = Invoice(id = "i-open", invoiceNumber = "INV-2", vendorId = "v1", currency = "GBP")

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> {
            listed += query
            return ZillitResult.Success(listOf(matching, unmatched))
        }
        override suspend fun approvalQueue() = ZillitResult.Success(emptyList<Invoice>())
        override suspend fun mine() = ZillitResult.Success(emptyList<Invoice>())
        override suspend fun invoice(id: String): ZillitResult<Invoice> {
            read += id
            return ZillitResult.Success(if (id == matching.id) matching else unmatched)
        }
        override suspend fun createFromUpload(upload: com.zillit.desktop.feature.invoices.domain.DepartmentUpload) =
            ZillitResult.Success<Invoice?>(null)
        override suspend fun createEntered(entered: com.zillit.desktop.feature.invoices.domain.EnteredInvoice) =
            ZillitResult.Success<Invoice?>(null)
        override suspend fun patchStatus(
            id: String,
            status: InvoiceStatus,
            approvalStatus: com.zillit.desktop.feature.invoices.domain.ApprovalStatus,
        ) = ZillitResult.Success<Invoice?>(null)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int) =
            ZillitResult.Success<Invoice?>(null)
        override suspend fun reject(id: String, reason: String) = ZillitResult.Success<Invoice?>(null)
        override suspend fun chase(id: String) = ZillitResult.Success(Unit)
        override suspend fun history(id: String) = ZillitResult.Success(emptyList<HistoryEntry>())
        override suspend fun extract(attachment: InvoiceAttachment) =
            ZillitResult.Success(InvoiceExtraction())
        override suspend fun settings() = ZillitResult.Success(InvoiceSettings(isSenior = true))
        override suspend fun approvalTiers() = ZillitResult.Success(emptyList<ApprovalTierConfig>())
        override suspend fun vendors() = ZillitResult.Success(listOf(Vendor("v1", "Panavision")))
        override suspend fun bankAccounts() = ZillitResult.Success(emptyList<BankAccount>())

        override suspend fun sendToApproval(id: String): ZillitResult<Unit> {
            sent += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun override(id: String): ZillitResult<Unit> {
            overridden += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun hold(
            id: String,
            reason: com.zillit.desktop.feature.invoices.domain.HoldReason,
            notes: String,
        ): ZillitResult<Unit> {
            held += id
            return ZillitResult.Success(Unit)
        }

        override suspend fun setup() = ZillitResult.Success(
            InvoiceSetupBundle(
                setup = InvoiceSetup(
                    teamMembers = listOf(InvoiceTeamRow("acc", isSenior = true)),
                    alerts = setOf("invoice_overdue"),
                    runAuthorisation = listOf(RunAuthLevel(1, listOf("acc"))),
                ),
                rules = listOf(InvoiceAssignmentRule("r1", assignTo = "acc", persisted = true)),
            ),
        )

        override suspend fun saveTeam(rows: List<InvoiceTeamRow>): ZillitResult<Unit> {
            savedTeam += rows
            return if (teamFails) ZillitResult.Failure(ZillitError.Unknown("no")) else ZillitResult.Success(Unit)
        }

        override suspend fun saveAlerts(alerts: Set<String>): ZillitResult<Unit> {
            savedAlerts = alerts
            return ZillitResult.Success(Unit)
        }

        override suspend fun saveRunAuthorisation(levels: List<RunAuthLevel>): ZillitResult<Unit> {
            savedChain = levels
            return ZillitResult.Success(Unit)
        }

        override suspend fun createRule(rule: InvoiceAssignmentRule): ZillitResult<InvoiceAssignmentRule> {
            if (failRuleFrom >= 0 && created >= failRuleFrom) {
                return ZillitResult.Failure(ZillitError.Unknown("no"))
            }
            created++
            return ZillitResult.Success(rule.copy(id = "server-$created", persisted = true))
        }

        override suspend fun updateRule(rule: InvoiceAssignmentRule) = ZillitResult.Success(rule)

        override suspend fun deleteRule(id: String): ZillitResult<Unit> {
            deletedRules += id
            return ZillitResult.Success(Unit)
        }

        override suspend fun nominalCodes() =
            ZillitResult.Success(listOf(com.zillit.desktop.feature.invoices.domain.InvoiceNominal("2400", "Camera")))

        override suspend fun poSuggestions(id: String, vendorId: String?): ZillitResult<PoSuggestions> {
            suggestedFor += id to vendorId
            return ZillitResult.Success(PoSuggestions(vendorPos = listOf(PoSuggestion("p1", "PO-0042"))))
        }

        override suspend fun match(id: String, suggestion: PoSuggestion): ZillitResult<Unit> {
            matched += id to suggestion.poId
            return ZillitResult.Success(Unit)
        }
    }
}
