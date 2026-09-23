package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import com.zillit.desktop.feature.cardexpenses.ui.ActivationDraft
import com.zillit.desktop.feature.cardexpenses.ui.AssignDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardAmountAction
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
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
 * The rights the handlers hold, whatever the screen offered.
 *
 * Each case sends the event a button would — or one no button offers — and
 * asserts which route the fake service saw. A handler that trusts its screen
 * is this module's defining defect; these are the new actions' proof it does
 * not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardHandlerGatingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- processing ----------------------------------------------------------------

    @Test
    fun `a senior posts through save-process with the lines and the top-up`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(approved(assignedTo = "someone-else")))
        val vm = viewModel(repository, senior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()

        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.process, "a senior opens any row")
        vm.onEvent(CardEvent.EditProcess(draft.copy(topUp = TopUpMethod.Expense)))
        vm.onEvent(CardEvent.PostProcess)
        advanceUntilIdle()

        assertTrue("postReceipt" !in repository.calls, "never the empty-bodied /post")
        val sent = repository.submissions.single()
        assertEquals(ProcessSubmission.POSTED, sent.status)
        assertEquals(TopUpMethod.Expense, sent.topUpMethod)
        assertEquals(120.0, sent.topUpAmount)
        assertEquals(120.0, sent.gross)
        assertNotNull(sent.effectiveDate, "posting needs a ledger date, and a fresh editor has today's")
        assertNull(vm.state.value.process, "the editor closes once it is sent")
    }

    /** The row lock (`ProcessPage.jsx:436-438`): someone else's row does not open. */
    @Test
    fun `a non-senior cannot open a row assigned to somebody else`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(approved(assignedTo = "someone-else")))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()

        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()

        assertNull(vm.state.value.process)
        assertTrue("receiptDetail" !in repository.calls, "not even read")
    }

    /** A review rule hides Post from a non-senior; the handler refuses it as well. */
    @Test
    fun `a non-senior cannot post a receipt under a review rule`() = runTest(dispatcher) {
        val flagged = approved(assignedTo = junior.userId).let {
            it.copy(processing = it.processing.copy(flags = setOf(ReceiptProcessing.REVIEW)))
        }
        val repository = FakeCardRepository(receipts = listOf(flagged))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()

        vm.onEvent(CardEvent.PostProcess)
        advanceUntilIdle()
        assertTrue(repository.submissions.isEmpty(), "no post")

        vm.onEvent(CardEvent.SubmitProcessForReview)
        advanceUntilIdle()
        assertEquals(ProcessSubmission.UNDER_REVIEW, repository.submissions.single().status, "but it can hand it up")
    }

    @Test
    fun `lines that do not reach the receipt cannot be posted`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(approved(assignedTo = "x")))
        val vm = viewModel(repository, senior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()

        vm.onEvent(CardEvent.EditProcessLine(0, ProcessLine(description = "Batteries", account = "4100", net = 90.0)))
        vm.onEvent(CardEvent.PostProcess)
        vm.onEvent(CardEvent.SaveProcess)
        advanceUntilIdle()

        assertTrue(repository.submissions.isEmpty())
        assertNotNull(vm.state.value.process, "the editor stays open for the correction")
    }

    @Test
    fun `an escalation needs a reason, and goes with it`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(approved(assignedTo = junior.userId)))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.process)

        vm.onEvent(CardEvent.EditProcess(draft.copy(escalation = " ")))
        vm.onEvent(CardEvent.ConfirmEscalation)
        advanceUntilIdle()
        assertTrue(repository.submissions.isEmpty())

        vm.onEvent(CardEvent.EditProcess(draft.copy(escalation = "Needs a senior eye")))
        vm.onEvent(CardEvent.ConfirmEscalation)
        advanceUntilIdle()
        val sent = repository.submissions.single()
        assertEquals(ProcessSubmission.ESCALATED, sent.status)
        assertEquals("Needs a senior eye", sent.escalationReason)
    }

    @Test
    fun `reassigning a row uses the reassign route`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(approved(assignedTo = "someone-else")))
        val vm = viewModel(repository, senior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.process)

        vm.onEvent(CardEvent.EditProcess(draft.copy(assign = AssignDraft("user-3", AssignDraft.CUSTOM, "Cover"))))
        vm.onEvent(CardEvent.ConfirmAssign)
        advanceUntilIdle()

        assertEquals(listOf("reassignReceipt"), repository.calls.filter { it.contains("assign") })
    }

    /** History corrects a posted receipt: Save only. */
    @Test
    fun `history can save a posted receipt but not post it again`() = runTest(dispatcher) {
        val posted = approved(assignedTo = null).copy(status = CardWorkflowStatus.Posted)
        val repository = FakeCardRepository(receipts = listOf(posted))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.History))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1", ProcessMode.History))
        advanceUntilIdle()

        vm.onEvent(CardEvent.PostProcess)
        advanceUntilIdle()
        assertTrue(repository.submissions.isEmpty())
        vm.onEvent(CardEvent.SaveProcess)
        advanceUntilIdle()
        assertNull(repository.submissions.single().status)
    }

    // -- the door ----------------------------------------------------------------

    /** Entered from the tile, an accountant is crew: no console page, no console write. */
    @Test
    fun `an accountant entering from the tile is refused the console`() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        val vm = viewModel(repository, senior)

        vm.onEvent(CardEvent.Enter(asTool = true))
        advanceUntilIdle()
        assertEquals(CardDestination.MyTransactions, vm.state.value.destination)

        vm.onEvent(CardEvent.Open(CardDestination.Settings))
        vm.onEvent(CardEvent.SaveSettings(SettingsSection.Team))
        advanceUntilIdle()
        assertEquals(CardDestination.MyTransactions, vm.state.value.destination)
        assertTrue("updateSettings" !in repository.calls)

        vm.onEvent(CardEvent.Enter(asTool = false))
        vm.onEvent(CardEvent.Open(CardDestination.Settings))
        advanceUntilIdle()
        assertEquals(CardDestination.Settings, vm.state.value.destination, "through the hub it is the console again")
    }

    // -- cards -----------------------------------------------------------------

    /** Approving a card is the chain's; an accountant outside it is refused. */
    @Test
    fun `an accountant outside the chain cannot approve a card`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card(CardStatus.Pending)))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardEvent.Ask(CardPrompt.Confirm(CardConfirmAction.ApproveCard, "c1", "", "")))
        vm.onEvent(CardEvent.ConfirmPrompt)
        advanceUntilIdle()

        assertTrue("approveCard" !in repository.calls)
    }

    @Test
    fun `activation needs a type and sixteen digits`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card(CardStatus.Override)))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardEvent.OpenActivation("c1"))
        vm.onEvent(CardEvent.EditActivation(ActivationDraft("c1", CardType.Physical, "4000 1234")))
        vm.onEvent(CardEvent.SubmitActivation)
        advanceUntilIdle()
        assertTrue(repository.activations.isEmpty())

        vm.onEvent(CardEvent.EditActivation(ActivationDraft("c1", CardType.Physical, "4000 1234 5678 9010")))
        vm.onEvent(CardEvent.SubmitActivation)
        advanceUntilIdle()
        assertEquals("4000123456789010", repository.activations.single().cardNumber)
    }

    // -- top-ups -----------------------------------------------------------------

    /** The note is required and sent; the amount may be left out (`TopUpToDoPage.jsx:208-225`). */
    @Test
    fun `a part-payment needs its note`() = runTest(dispatcher) {
        val repository = FakeCardRepository(topUps = listOf(topUp()))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()

        val prompt = CardPrompt.WithAmount(CardAmountAction.PartialTopUp, "t1", "", "")
        vm.onEvent(CardEvent.Ask(prompt.copy(amount = "100")))
        vm.onEvent(CardEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertTrue(repository.partials.isEmpty())
        assertNotNull(vm.state.value.prompt, "the prompt stays up for the note")

        vm.onEvent(CardEvent.UpdatePrompt(prompt.copy(amount = "", note = "Issuer capped it")))
        vm.onEvent(CardEvent.ConfirmPrompt)
        advanceUntilIdle()
        assertEquals(null to "Issuer capped it", repository.partials.single())
    }

    @Test
    fun `funding past the card's limit is refused`() = runTest(dispatcher) {
        val repository = FakeCardRepository(topUps = listOf(topUp().copy(cardLimit = 1_000.0, cardBalance = 900.0)))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()

        vm.onEvent(CardEvent.Ask(CardPrompt.Confirm(CardConfirmAction.CompleteTopUp, "t1", "", "")))
        vm.onEvent(CardEvent.ConfirmPrompt)
        advanceUntilIdle()

        assertFalse("completeTopUp" in repository.calls)
    }

    // -- queries and funds ---------------------------------------------------------

    /** The holder of a receipt may answer a query on it; a stranger to it may not. */
    @Test
    fun `a query is the accounts team's or the holder's`() = runTest(dispatcher) {
        val crew = CardViewer("crew-1", "department_camera", null)
        val stranger = CardViewer("crew-2", "department_camera", null)
        val mine = FakeCardRepository(receipts = listOf(approved(assignedTo = null)))
        val holder = viewModel(mine, crew)
        holder.onEvent(CardEvent.OpenQuery("r1"))
        advanceUntilIdle()
        holder.onEvent(CardEvent.EditQuery("It was the pickup van"))
        holder.onEvent(CardEvent.SendQuery)
        advanceUntilIdle()
        assertEquals(listOf("sendQuery"), mine.calls)
        assertEquals("", holder.state.value.query?.text, "the composer clears once it is sent")

        val theirs = FakeCardRepository(receipts = listOf(approved(assignedTo = null)))
        val other = viewModel(theirs, stranger)
        other.onEvent(CardEvent.OpenQuery("r1"))
        advanceUntilIdle()
        assertNull(other.state.value.query)
    }

    @Test
    fun `fund requests are an accountant's, and only an open one is received`() = runTest(dispatcher) {
        val received = com.zillit.desktop.feature.cardexpenses.domain.FundRequest(
            id = "fr-1", bankId = "b1", fundAccount = "2100", amount = 100.0, receivedAmount = 100.0,
            currency = "GBP", status = "received", requestedBy = null, requestedAt = null,
            receivedBy = null, receivedAt = null,
        )
        val repository = FakeCardRepository(fundRequests = listOf(received))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.OpenFunds)
        advanceUntilIdle()

        vm.onEvent(CardEvent.ReceiveFundRequest("fr-1"))
        vm.onEvent(CardEvent.SubmitFundRequest)
        advanceUntilIdle()
        assertTrue(repository.calls.none { it.contains("Fund") }, "received already; and the form is empty")

        val crewVm = viewModel(FakeCardRepository(), CardViewer("crew-1", "department_camera", null))
        crewVm.onEvent(CardEvent.OpenFunds)
        advanceUntilIdle()
        assertNull(crewVm.state.value.funds)
    }

    // -- fixtures ----------------------------------------------------------------

    private val senior = CardViewer(
        userId = "senior-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
    )

    private val junior = CardViewer(
        userId = "junior-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_assistant_accountant",
    )

    private fun viewModel(repository: FakeCardRepository, viewer: CardViewer) =
        CardExpensesViewModel(repository = repository, today = { TODAY }, viewer = { viewer }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun approved(assignedTo: String?) = CardReceipt(
        id = "r1",
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Batteries",
        merchant = null,
        amount = 120.0,
        currency = "GBP",
        date = TODAY,
        status = CardWorkflowStatus.Approved,
        matchStatus = MatchStatus.Matched,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = "4100",
        codeDescription = null,
        episode = null,
        attachmentKey = null,
        urgent = false,
        matchScore = null,
        duplicateScore = null,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = null,
        processing = ReceiptProcessing(
            loaded = true,
            lines = listOf(ProcessLine(id = "l1", description = "Batteries", account = "4100", net = 120.0)),
            cardLimit = 500.0,
            cardBalance = 500.0,
            requestTopUp = true,
        ),
        assignedTo = assignedTo,
    )

    private fun card(status: CardStatus) = ExpenseCard(
        id = "c1",
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = status,
        type = CardType.Physical,
        lastFour = null,
        issuer = null,
        providerId = null,
        currency = "GBP",
        limit = 500.0,
        monthlyLimit = 500.0,
        balance = 500.0,
        receiptsCommit = 0.0,
        bsControlCode = "2100",
        proposedLimit = null,
        justification = null,
        requestedBy = "crew-1",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = null,
    )

    private fun topUp() = CardTopUp("t1", "c1", "4821", "crew-1", "", 300.0, "GBP", "manual", "pending", 1L)

    private companion object {
        const val TODAY = 1_754_006_400_000L
    }
}
