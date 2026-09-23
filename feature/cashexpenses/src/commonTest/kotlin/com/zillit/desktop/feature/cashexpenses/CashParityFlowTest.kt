package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTierConfig
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.TierRule
import com.zillit.desktop.feature.cashexpenses.domain.TierStep
import com.zillit.desktop.feature.cashexpenses.ui.AmountAction
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.ReturnReasons
import com.zillit.desktop.feature.cashexpenses.ui.TeamMemberDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * The web's cash rules, through the view model — what reaches the server and
 * what is refused before it.
 *
 * Each handler is driven by its event directly, not through the screen: the
 * port's recurring defect was a screen that gated an action and a handler
 * that did not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashParityFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun TestScope.viewModel(repository: FakeCash, viewer: CashViewer = accountant) =
        CashExpensesViewModel(repository = repository, viewer = { viewer }).also {
            it.start()
            advanceUntilIdle()
        }

    private fun TestScope.confirm(vm: CashExpensesViewModel, action: ConfirmAction, id: String) {
        vm.onEvent(CashEvent.Ask(CashPrompt.Confirm(action, id, "", "")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
    }

    // -- 1. entry ------------------------------------------------------------------

    /** `?entry=tool`: the tile gives an accountant the crew view, and the handlers follow it. */
    @Test
    fun `an accountant who opened the tool on its own is crew`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(float(FloatStatus.Spent))
        }
        val vm = viewModel(repository)
        assertEquals(CashDestination.PettyCashOverview, vm.state.value.destination)
        // The register is loaded while in the console, so the float is there
        // to act on — what stops the close below is the viewer, not its absence.
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()
        assertEquals(listOf("f1"), vm.state.value.activeFloats.map { it.id })

        vm.onEvent(CashEvent.Enter(asTool = true))
        advanceUntilIdle()

        assertFalse(vm.state.value.viewer.isAccountant)
        assertEquals(CashDestination.SubmitReceipts, vm.state.value.destination)
        confirm(vm, ConfirmAction.CloseFloat, "f1")
        assertTrue(repository.calls.none { it.startsWith("closeFloat") }, "the crew view closed a float")

        // Back inside the hub, the console returns.
        vm.onEvent(CashEvent.Enter(asTool = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.viewer.isAccountant)
    }

    /** A deep link re-checks, as the web's bounce does. */
    @Test
    fun `a deep link the viewer cannot open lands them on their own page`() = runTest(dispatcher) {
        val crew = CashViewer(userId = "u1", departmentIdentifier = "camera", designationIdentifier = null)
        val vm = viewModel(FakeCash(writesSucceed = true), crew)

        vm.onEvent(CashEvent.Open(CashDestination.Settings))
        advanceUntilIdle()

        assertEquals(CashDestination.SubmitReceipts, vm.state.value.destination)
    }

    // -- 2. post ---------------------------------------------------------------------

    @Test
    fun `a post carries the ledger date and the claims`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()
        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()
        vm.onEvent(CashEvent.EditEffectiveDate(CashDates.today()))

        confirm(vm, ConfirmAction.PostBatch, "b1")

        val post = assertNotNull(repository.lastPost, "nothing was posted")
        assertEquals(CashDates.utcMillis(CashDates.today()), post.effectiveDate)
        assertEquals(listOf("c1"), post.claims?.map { it.id })
    }

    @Test
    fun `a post dated inside the lock or undated is refused`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost))
            lock = CashDates.today()
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()
        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()

        vm.onEvent(CashEvent.EditEffectiveDate(CashDates.today()))
        confirm(vm, ConfirmAction.PostBatch, "b1")
        vm.onEvent(CashEvent.EditEffectiveDate(""))
        confirm(vm, ConfirmAction.PostBatch, "b1")

        assertNull(repository.lastPost, "posted on a locked or missing date")
    }

    /** Post & Ledger lists what is postable; escalated batches are the senior's. */
    @Test
    fun `post and ledger lists only postable batches`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(
                queuedBatch(id = "ready", status = BatchStatus.ReadyToPost),
                queuedBatch(id = "escalated", status = BatchStatus.Escalated),
                queuedBatch(id = "review", status = BatchStatus.UnderReview),
            )
        }
        val vm = viewModel(repository)

        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()

        assertEquals(listOf("ready", "review"), vm.state.value.queueBatches.map { it.id })
    }

    /** A row assigned to someone else is theirs and a senior's — not a non-senior's. */
    @Test
    fun `a batch assigned to someone else cannot be opened or posted`() = runTest(dispatcher) {
        val junior = accountant.copy(designationIdentifier = "designation_assistant_accountant_accounts")
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isTeamMember = true, postingLimitUnlimited = true)
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "someone-else"))
        }
        val vm = viewModel(repository, junior)
        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()

        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBatchId)
        confirm(vm, ConfirmAction.PostBatch, "b1")

        assertNull(repository.lastPost)
    }

    // -- 3, 7. the senior routes ---------------------------------------------------------

    @Test
    fun `sign-off returns an escalated batch to accounts`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(requireSeniorSignOff = true)
            queueRows = listOf(queuedBatch(status = BatchStatus.Escalated))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.PettyCashSignOff))
        advanceUntilIdle()

        confirm(vm, ConfirmAction.ReturnToAccounts, "b1")

        assertTrue("deescalate:b1" in repository.calls)
    }

    @Test
    fun `escalating sends the reason and is refused to a senior`() = runTest(dispatcher) {
        val junior = accountant.copy(designationIdentifier = null)
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(requireSeniorSignOff = true)
            queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
        }
        val vm = viewModel(repository, junior)
        vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()

        vm.onEvent(
            CashEvent.Ask(
                CashPrompt.WithReason(
                    com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction.EscalateBatch,
                    "b1",
                    "",
                    "",
                    reason = "Over my limit",
                ),
            ),
        )
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue("escalate:b1:Over my limit" in repository.calls)

        val senior = viewModel(repository)
        senior.onEvent(CashEvent.Open(CashDestination.PostLedger))
        advanceUntilIdle()
        repository.calls.clear()
        senior.onEvent(
            CashEvent.Ask(
                CashPrompt.WithReason(
                    com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction.EscalateBatch,
                    "b1",
                    "",
                    "",
                    reason = "x",
                ),
            ),
        )
        senior.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty(), "a senior escalated to themselves")
    }

    @Test
    fun `send for approval waits for every receipt to be verified`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            queueRows = listOf(queuedBatch(status = BatchStatus.InAudit))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.AuditQueue))
        advanceUntilIdle()
        vm.onEvent(CashEvent.SelectBatch("b1"))
        advanceUntilIdle()

        confirm(vm, ConfirmAction.SaveAndVerify, "b1")
        assertTrue(repository.calls.none { it.startsWith("saveAndVerify") }, "sent unverified")

        vm.onEvent(CashEvent.ToggleVerify("c1"))
        advanceUntilIdle()
        assertTrue(repository.calls.any { it.startsWith("saveClaims:b1:{c1=true}") })
        confirm(vm, ConfirmAction.SaveAndVerify, "b1")
        assertTrue("saveAndVerify:b1" in repository.calls)
    }

    // -- 6. floats ---------------------------------------------------------------------

    @Test
    fun `a float closes only when spent, and never issues past its chain`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(
                float(FloatStatus.PendingReturn, id = "pending"),
                float(FloatStatus.Spent, id = "spent"),
            )
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()

        confirm(vm, ConfirmAction.CloseFloat, "pending")
        confirm(vm, ConfirmAction.CloseFloat, "spent")

        assertEquals(listOf("closeFloat:spent"), repository.calls.filter { it.startsWith("closeFloat") })
    }

    @Test
    fun `ready to collect needs a company and sends the bs code`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(float(FloatStatus.Approved))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()

        vm.onEvent(CashEvent.Ask(CashPrompt.ReadyToCollect(floatId = "f1")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertNotNull(vm.state.value.prompt, "a missing company keeps the dialog open")

        vm.onEvent(CashEvent.UpdatePrompt(CashPrompt.ReadyToCollect("f1", companyId = "co-1", bsCode = "1145")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue("readyToCollect:f1:co-1:1145" in repository.calls)
    }

    @Test
    fun `a return that closes the float must be the whole balance`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            activeFloatRows = listOf(float(FloatStatus.PendingReturn, balance = 40.0))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()
        val prompt = CashPrompt.RecordReturn("f1", amount = "30", receivedDate = "2026-09-01")

        vm.onEvent(CashEvent.Ask(prompt))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertNull(repository.lastReturn, "a partial amount closed the float")

        vm.onEvent(CashEvent.UpdatePrompt(prompt.copy(amount = "40")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertEquals(40.0, repository.lastReturn?.amount)
        assertEquals(ReturnReasons.CLOSE_FULL, repository.lastReturn?.reason)
    }

    // -- 7. approvals ---------------------------------------------------------------------

    @Test
    fun `an approval signs the next level, and only its approvers may`() = runTest(dispatcher) {
        val chain = listOf(
            ApprovalTierConfig(
                scope = "all",
                departmentId = null,
                tiers = listOf(
                    ApprovalTier(listOf(TierRule("default", null, listOf("someone")))),
                    ApprovalTier(listOf(TierRule("default", null, listOf("me")))),
                ),
            ),
        )
        val repository = FakeCash(writesSucceed = true).apply {
            metadata = CashMetadata(isApprover = true, approvalTierConfigs = chain)
            activeFloatRows = listOf(
                float(FloatStatus.AwaitingApproval, id = "first"),
                float(FloatStatus.AwaitingApproval, id = "second").copy(
                    approvals = listOf(com.zillit.desktop.feature.cashexpenses.domain.TierApproval("someone", 1)),
                ),
            )
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.ApprovalQueue))
        advanceUntilIdle()

        confirm(vm, ConfirmAction.ApproveFloat, "first")
        assertTrue(repository.calls.none { it == "approveFloat:first" }, "level 1 is not mine")

        confirm(vm, ConfirmAction.ApproveFloat, "second")
        assertEquals(TierStep(tierNumber = 2, totalTiers = 2), repository.lastApprovalTier)
    }

    // -- 8. top-ups ---------------------------------------------------------------------

    @Test
    fun `a partial top-up needs its note`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            topUpRows = listOf(topUp())
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.TopUps))
        advanceUntilIdle()
        val prompt = CashPrompt.WithAmount(AmountAction.PartialTopUp, "t1", "", "", amount = "50")

        vm.onEvent(CashEvent.Ask(prompt))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue(repository.calls.none { it.startsWith("partialTopUp") }, "a partial with no reason")

        vm.onEvent(CashEvent.UpdatePrompt(prompt.copy(note = "Only 50 in the safe")))
        vm.onEvent(CashEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue("partialTopUp:t1:50.0:Only 50 in the safe" in repository.calls)
    }

    // -- 9. settings ------------------------------------------------------------------

    @Test
    fun `a team member saves the team at once, a senior unlimited`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.Open(CashDestination.Settings))
        advanceUntilIdle()

        vm.onEvent(CashEvent.EditTeamMember(TeamMemberDraft(userId = "u9", postingLimit = "250", isSenior = true)))
        vm.onEvent(CashEvent.SaveTeamMember)
        advanceUntilIdle()

        val member = repository.lastTeam?.single()
        assertEquals("u9", member?.userId)
        assertNull(member?.postingLimit, "a senior is unlimited")
        assertTrue(member?.canOverride == true)
        assertNull(vm.state.value.teamEditor)
    }

    @Test
    fun `the team is a senior accountant's to change`() = runTest(dispatcher) {
        val junior = accountant.copy(designationIdentifier = null)
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = viewModel(repository, junior)

        vm.onEvent(CashEvent.EditTeamMember(TeamMemberDraft(userId = "u9")))
        vm.onEvent(CashEvent.SaveTeamMember)
        advanceUntilIdle()

        assertNull(vm.state.value.teamEditor)
        assertNull(repository.lastTeam, "a non-senior rewrote the team")
    }

    // -- fixtures ------------------------------------------------------------------------

    private fun float(status: FloatStatus, id: String = "f1", balance: Double = 40.0) = CashFloat(
        id = id, requestNumber = "PC-1", userId = "u2", holderName = "", departmentId = null, status = status,
        currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0, balance = balance, receiptsAmount = 0.0,
        receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null, duration = null,
        durationType = null, purpose = null, createdAt = null,
    )

    private fun topUp() = CashTopUp(
        id = "t1", userId = "u2", holderName = "", amount = 100.0, currency = "GBP", status = "pending", note = null,
        floatRequestNumber = "PC-1", floatIssued = 100.0, floatBalance = 20.0, floatRequestedAmount = 100.0,
        createdAt = null,
    )
}
