package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cardexpenses.domain.CardApproval
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardCurrencies
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardDepartment
import com.zillit.desktop.feature.cardexpenses.domain.CardInboxHost
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DealRate
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptMedia
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.StoredStatement
import com.zillit.desktop.feature.cardexpenses.domain.TierConfig
import com.zillit.desktop.feature.cardexpenses.domain.TierRule
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.ReceiptDetailSource
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
 * One read-only receipt detail for every page that shows a receipt
 * (`ReceiptDetailModal.jsx`): a card's receipt rows, both approval queues and
 * My Transactions open it, and the process editor is no longer where a card's
 * receipt row sends an accountant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSharedReceiptDetailTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- a card's receipt rows ---------------------------------------------------

    /** `CardDetailModal.jsx:180-198`: every viewer gets the same inspect, and nothing in it writes. */
    @Test
    fun `an accountant's receipt row in a card opens the read-only detail, not the process editor`() =
        runTest(dispatcher) {
            val repository = FakeCardRepository(cards = listOf(card()))
            repository.cardReceipts = listOf(receipt("r1", CardWorkflowStatus.AwaitingApproval))
            val vm = viewModel(repository, accountant)
            vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
            advanceUntilIdle()
            vm.onEvent(CardsEvent.OpenCard("c1"))
            advanceUntilIdle()

            vm.onEvent(CardsEvent.OpenReceipt("r1"))
            advanceUntilIdle()

            val open = assertNotNull(vm.state.value.inbox.detail, "the shared detail opened")
            assertEquals(ReceiptDetailSource.Card, open.source)
            assertFalse(open.loading)
            assertNull(vm.state.value.process, "no process editor, so no Save")
            assertTrue("inboxReceiptDetail" in repository.calls, "it reads /receipts/:id/detail")
            assertTrue("saveProcessReceipt" !in repository.calls)
        }

    @Test
    fun `a cardholder's receipt row in their card opens the same detail`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card()))
        repository.cardReceipts = listOf(receipt("r1", CardWorkflowStatus.PendingReceipt))
        val vm = viewModel(repository, crew)
        vm.onEvent(CardEvent.Open(CardDestination.MyCards))
        advanceUntilIdle()
        vm.onEvent(CardsEvent.OpenCard("c1"))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.OpenReceipt("r1"))
        advanceUntilIdle()

        assertEquals(ReceiptDetailSource.Card, vm.state.value.inbox.detail?.source)
        assertTrue(ReceiptDetailSource.Card.allowQuery, "Query stays on a card's receipts")
    }

    /** A card's receipts drilldown carries lines and approvals on every row — the view shows them even unread. */
    @Test
    fun `the slim row alone still shows the lines and approvals it carries`() {
        val row = receipt("r1", CardWorkflowStatus.AwaitingApproval).copy(
            approvals = listOf(CardApproval("u1", 1), CardApproval("u2", 0)),
            processing = ReceiptProcessing(
                lines = listOf(
                    ProcessLine(id = "a", description = "Batteries", account = "4100", net = 10.0, taxRate = 20.0),
                    ProcessLine(id = "b", description = "Tape", account = "4100", net = 5.0, splitParentId = "a"),
                    ProcessLine(),
                ),
            ),
        )
        val detail = ReceiptDetail.of(row)

        assertEquals(listOf("Batteries", "Tape"), detail.lines.map { it.description })
        assertEquals(2.0, detail.lines.first().tax)
        assertTrue(detail.lines.last().splitChild)
        assertEquals(listOf(false, true), detail.approvals.map { it.override }, "tier 0 is an override")
    }

    // -- the Approval Queue ---------------------------------------------------------

    @Test
    fun `an Approval Queue row opens the shared detail with Query off, and Reject closes it`() =
        runTest(dispatcher) {
            val repository = FakeCardRepository(
                metadata = CardMetadata(tierConfigs = listOf(chainOf("acc-1"))),
                receipts = listOf(receipt("r1", CardWorkflowStatus.AwaitingApproval)),
            )
            val vm = viewModel(repository, accountant.copy(metadata = repository.metadata))
            vm.onEvent(CardEvent.Open(CardDestination.ApprovalQueue))
            advanceUntilIdle()

            vm.onEvent(ProcessPageEvent.OpenDetail("r1"))
            advanceUntilIdle()
            val open = assertNotNull(vm.state.value.inbox.detail)
            assertEquals(ReceiptDetailSource.ApprovalQueue, open.source)
            assertFalse(open.source.allowQuery, "this whole page is the queue (ZL-20913)")

            vm.onEvent(ProcessPageEvent.AskReject("r1"))
            assertNull(vm.state.value.inbox.detail, "the reason dialog replaces the detail")
            assertNotNull(vm.state.value.processPages.reject)
        }

    @Test
    fun `approving from the detail signs the next tier and closes it`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            metadata = CardMetadata(tierConfigs = listOf(chainOf("acc-1"))),
            receipts = listOf(receipt("r1", CardWorkflowStatus.AwaitingApproval)),
        )
        val vm = viewModel(repository, accountant.copy(metadata = repository.metadata))
        vm.onEvent(CardEvent.Open(CardDestination.ApprovalQueue))
        advanceUntilIdle()
        vm.onEvent(ProcessPageEvent.OpenDetail("r1"))
        advanceUntilIdle()

        vm.onEvent(ProcessPageEvent.ApproveRow("r1"))
        advanceUntilIdle()

        assertEquals(listOf("approveReceipt:1"), repository.calls.filter { it.startsWith("approveReceipt") })
        assertNull(vm.state.value.inbox.detail)
    }

    // -- My Transactions and the crew Approval Queue ------------------------------------

    @Test
    fun `My Transactions opens the shared detail, and Edit Receipt swaps it for the edit dialog`() =
        runTest(dispatcher) {
            val repository = FakeCardRepository(
                receipts = listOf(receipt("r1", CardWorkflowStatus.PendingCode)),
                cards = listOf(card()),
            )
            val vm = viewModel(repository, crew)

            vm.onEvent(CrewEvent.OpenReceipt("r1"))
            advanceUntilIdle()
            assertEquals(ReceiptDetailSource.MyTransactions, vm.state.value.inbox.detail?.source)

            vm.onEvent(CrewEvent.OpenEdit("r1"))
            assertNull(vm.state.value.inbox.detail)
            assertNotNull(vm.state.value.crew.edit)
        }

    @Test
    fun `a receipt in the crew Approval Queue opens with Query off`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            metadata = CardMetadata(isApprover = true),
            receipts = listOf(receipt("r1", CardWorkflowStatus.AwaitingApproval)),
        )
        val vm = viewModel(repository, approver)
        vm.onEvent(CardEvent.Open(CardDestination.CardsForApproval))
        advanceUntilIdle()

        vm.onEvent(CrewEvent.OpenApprovalReceipt("r1"))
        advanceUntilIdle()

        val open = assertNotNull(vm.state.value.inbox.detail)
        assertEquals(ReceiptDetailSource.CrewApproval, open.source)
        assertFalse(open.source.allowQuery)

        vm.onEvent(CrewEvent.AskReject("r1", receipt = true))
        assertNull(vm.state.value.inbox.detail)
        assertNotNull(vm.state.value.crew.reject)
    }

    // -- a card request's History -----------------------------------------------------

    @Test
    fun `a card request in the Approval Queue opens its History and closes it again`() = runTest(dispatcher) {
        val repository = FakeCardRepository(metadata = CardMetadata(isApprover = true))
        repository.approvalCards = listOf(card().copy(id = "c9", status = CardStatus.Pending))
        val vm = viewModel(repository, approver)
        vm.onEvent(CardEvent.Open(CardDestination.CardsForApproval))
        advanceUntilIdle()
        vm.onEvent(CrewEvent.OpenApprovalCard("c9"))

        vm.onEvent(CrewEvent.ShowApprovalCardHistory(open = true))
        assertTrue(assertNotNull(vm.state.value.crew.approvalCardHistory).loading)
        advanceUntilIdle()
        assertFalse(assertNotNull(vm.state.value.crew.approvalCardHistory).loading)

        vm.onEvent(CrewEvent.ShowApprovalCardHistory(open = false))
        assertNull(vm.state.value.crew.approvalCardHistory)

        vm.onEvent(CrewEvent.ShowApprovalCardHistory(open = true))
        vm.onEvent(CrewEvent.OpenApprovalCard(null))
        assertNull(vm.state.value.crew.approvalCardHistory, "leaving the request drops its trail")
    }

    // -- the document, inline -------------------------------------------------------------

    @Test
    fun `the process editor fetches the receipt's document for its preview pane`() = runTest(dispatcher) {
        val fetched = mutableListOf<ReceiptMedia>()
        val repository = FakeCardRepository(
            receipts = listOf(receipt("r1", CardWorkflowStatus.Approved).copy(attachmentKey = "k/tape.jpg")),
        )
        val vm = viewModel(repository, senior, host(fetched))
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()

        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()

        assertEquals(listOf("k/tape.jpg"), fetched.map { it.key })
        assertEquals(3, vm.state.value.process?.media?.bytes?.size)
    }

    @Test
    fun `the Code Receipt dialog fetches the document too, and drops a late answer for a closed one`() =
        runTest(dispatcher) {
            val fetched = mutableListOf<ReceiptMedia>()
            val repository = FakeCardRepository(
                receipts = listOf(receipt("r1", CardWorkflowStatus.PendingCode).copy(attachmentKey = "k/a.pdf")),
                cards = listOf(card()),
            )
            val vm = viewModel(repository, crew, host(fetched))

            vm.onEvent(CrewEvent.OpenCode("r1"))
            advanceUntilIdle()
            assertEquals(3, vm.state.value.crew.code?.media?.bytes?.size)

            vm.onEvent(CrewEvent.OpenCode("r1"))
            vm.onEvent(CrewEvent.OpenCode(null))
            advanceUntilIdle()
            assertNull(vm.state.value.crew.code, "closed before the document came back")
        }

    @Test
    fun `a receipt without a document is never fetched`() = runTest(dispatcher) {
        val fetched = mutableListOf<ReceiptMedia>()
        val repository = FakeCardRepository(
            receipts = listOf(receipt("r1", CardWorkflowStatus.PendingCode)),
            cards = listOf(card()),
        )
        val vm = viewModel(repository, crew, host(fetched))
        vm.onEvent(CrewEvent.OpenCode("r1"))
        advanceUntilIdle()
        assertTrue(fetched.isEmpty())
    }

    // -- the weekly-salary cap ------------------------------------------------------------------

    /** `useRequestCapGuard.js:57-68`: only a salary basis reads a deal. */
    @Test
    fun `a salary-basis cap reads the requester's active deal when the request form opens`() =
        runTest(dispatcher) {
            val cap = RequestCap(enabled = true, basis = RequestCapBasis.WeeklySalary, salaryMultiplier = 2.0)
            val repository = FakeCardRepository(metadata = CardMetadata(requestCap = cap))
            repository.activeDeal = DealRate(weekly = 800.0, currency = "GBP")
            val vm = viewModel(repository, crew.copy(metadata = repository.metadata))
            vm.onEvent(CardEvent.Open(CardDestination.MyCards))
            advanceUntilIdle()

            vm.onEvent(CardsEvent.OpenCrewRequest)
            advanceUntilIdle()

            assertEquals(1, repository.dealReads)
            assertEquals(DealRate(800.0, "GBP"), vm.state.value.cardsArea.deal)
        }

    @Test
    fun `a flat cap makes no deal request at all`() = runTest(dispatcher) {
        val cap = RequestCap(enabled = true, basis = RequestCapBasis.MaxAmount, maxAmount = 500.0)
        val repository = FakeCardRepository(metadata = CardMetadata(requestCap = cap))
        repository.activeDeal = DealRate(weekly = 800.0, currency = "GBP")
        val vm = viewModel(repository, crew.copy(metadata = repository.metadata))
        vm.onEvent(CardEvent.Open(CardDestination.MyCards))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.OpenCrewRequest)
        advanceUntilIdle()

        assertEquals(0, repository.dealReads)
        assertNull(vm.state.value.cardsArea.deal)
    }

    // -- fixtures ------------------------------------------------------------------------------------

    private val accountant = CardViewer(
        userId = "acc-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = null,
    )

    private val senior = accountant.copy(designationIdentifier = "designation_production_accountant_accounts")

    private val crew =
        CardViewer(userId = "crew-1", departmentIdentifier = "department_art", designationIdentifier = null)

    private val approver = crew.copy(metadata = CardMetadata(isApprover = true))

    private fun chainOf(userId: String) = TierConfig(
        scope = "all",
        departmentId = null,
        tiers = listOf(ApprovalTier(1, listOf(TierRule("default", null, listOf(userId))))),
    )

    private fun host(fetched: MutableList<ReceiptMedia>) = object : CardInboxHost {
        override suspend fun pickStatement(): ZillitResult<StatementFile?> = ZillitResult.Success(null)

        override suspend fun storeStatement(file: StatementFile): ZillitResult<StoredStatement> =
            ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("not here"))

        override suspend fun media(media: ReceiptMedia): ZillitResult<ByteArray> {
            fetched += media
            return ZillitResult.Success(ByteArray(3))
        }

        override suspend fun departments(): List<CardDepartment> = emptyList()

        override suspend fun currencies(): CardCurrencies = CardCurrencies()
    }

    private fun viewModel(repository: FakeCardRepository, viewer: CardViewer, inboxHost: CardInboxHost? = null) =
        CardExpensesViewModel(
            repository = repository,
            inboxHost = inboxHost,
            banks = { emptyList<CardBank>() },
            viewer = { viewer },
        ).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun card() = ExpenseCard(
        id = "c1",
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = CardStatus.Active,
        type = CardType.Physical,
        lastFour = "4821",
        issuer = "bank-1",
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

    private fun receipt(id: String, status: CardWorkflowStatus) = CardReceipt(
        id = id,
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Taxi",
        merchant = null,
        amount = 20.0,
        currency = "GBP",
        date = CardDates.toMillis("2026-09-20"),
        status = status,
        matchStatus = MatchStatus.Unmatched,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = null,
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
        assignedTo = "acc-1",
    )
}
