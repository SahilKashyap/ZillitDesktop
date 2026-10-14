package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import com.zillit.desktop.feature.cardexpenses.ui.TOPUP_TODO
import com.zillit.desktop.feature.cardexpenses.ui.topUpUnread
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-row badges on the Receipt Inbox: the page no longer reads itself whole
 * on open, and opening one receipt reads that receipt's `card_receipt` bucket
 * only — the web's `readScope` (`ReceiptInboxPage.jsx:745`). Top-Up To Do and
 * Card Extension read one top-up at a time, as iOS does; Smart Alerts alone is
 * still read whole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardRowBadgeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A ledger in memory that writes down every read it is asked for. */
    private class RecordingBadges(
        pages: Map<String, Int> = mapOf("receipt_inbox" to 3),
        rows: Map<String, Map<String, Int>> = mapOf("receipt_inbox" to mapOf("r1" to 2, "r2" to 1)),
    ) : TabBadgeSource {
        override val counts = MutableStateFlow(pages)
        override val entityCounts = MutableStateFlow(rows)
        val pageReads = mutableListOf<String>()
        val rowReads = mutableListOf<Triple<String, String, String?>>()

        override fun read(key: String) {
            pageReads += key
        }

        override fun readEntity(key: String, entityId: String, kind: String?) {
            rowReads += Triple(key, entityId, kind)
        }
    }

    @Test
    fun `the inbox is not read whole on open, and a receipt opened reads its own card_receipt bucket`() = runTest {
        val badges = RecordingBadges()
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(receipts = listOf(receipt("r1"), receipt("r2"))),
            badges = badges,
            viewer = { accountant },
        ).also { it.start() }
        advanceUntilIdle()

        vm.onEvent(CardEvent.Open(CardDestination.ReceiptInbox))
        advanceUntilIdle()
        assertEquals(CardDestination.ReceiptInbox, vm.state.value.destination)
        assertEquals(2, vm.state.value.unreadRow("receipt_inbox", "r1"))
        assertTrue(badges.pageReads.isEmpty(), "whole-page read: ${badges.pageReads}")

        vm.onEvent(InboxEvent.OpenDetail("r1"))
        advanceUntilIdle()
        assertEquals(listOf(Triple<String, String, String?>("receipt_inbox", "r1", "card_receipt")), badges.rowReads)
        assertTrue(badges.pageReads.isEmpty())
    }

    @Test
    fun `a receipt with nothing unread sends no read`() = runTest {
        val badges = RecordingBadges().apply { entityCounts.value = mapOf("receipt_inbox" to mapOf("r1" to 2)) }
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(receipts = listOf(receipt("r1"), receipt("r2"))),
            badges = badges,
            viewer = { accountant },
        ).also { it.start() }
        advanceUntilIdle()
        vm.onEvent(CardEvent.Open(CardDestination.ReceiptInbox))
        advanceUntilIdle()

        vm.onEvent(InboxEvent.OpenDetail("r2"))
        advanceUntilIdle()
        assertTrue(badges.rowReads.isEmpty(), "row reads: ${badges.rowReads}")
    }

    // -- top-ups and alerts (iOS `TopUpDetailPage.swift:145`, `CardExtensionTabView.swift:311`) --------

    @Test
    fun `Top-Up To Do is not read whole, and a request looked into reads itself by id and by entity`() = runTest {
        val badges = RecordingBadges(
            pages = mapOf("topup_todo" to 4),
            rows = mapOf("topup_todo" to mapOf("t1" to 1, "c9" to 2, "t2" to 1)),
        )
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(topUps = listOf(topUp("t1", cardId = "c9"), topUp("t2", cardId = "c7"))),
            badges = badges,
            viewer = { accountant },
        ).also { it.start() }
        advanceUntilIdle()

        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()
        assertEquals(CardDestination.TopUpQueue, vm.state.value.destination)
        assertTrue(badges.pageReads.isEmpty(), "whole-page read: ${badges.pageReads}")
        // The chip counts the request's own rows and its card's, as either client files them.
        val t1 = vm.state.value.topUps.first { it.id == "t1" }
        assertEquals(3, vm.state.value.topUpUnread(TOPUP_TODO, t1))

        vm.onEvent(InsightsEvent.OpenHistory("t1", "Sam"))
        advanceUntilIdle()
        assertEquals(
            listOf<Triple<String, String, String?>>(Triple("topup_todo", "t1", null), Triple("topup_todo", "c9", null)),
            badges.rowReads,
        )
        assertTrue(badges.pageReads.isEmpty())
    }

    @Test
    fun `Card Extension reads the tapped top-up only, and only when it has unread`() = runTest {
        val badges = RecordingBadges(
            pages = mapOf("card_extension" to 1),
            rows = mapOf("card_extension" to mapOf("t1" to 1)),
        )
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(
                cards = listOf(activeCard()),
                topUps = listOf(topUp("t1", cardId = "c1"), topUp("t2", cardId = "c1")),
            ),
            badges = badges,
            viewer = { crew },
        ).also { it.start() }
        advanceUntilIdle()

        vm.onEvent(CardEvent.Open(CardDestination.CardExtension))
        advanceUntilIdle()
        assertEquals(CardDestination.CardExtension, vm.state.value.destination)
        assertTrue(badges.pageReads.isEmpty(), "whole-page read: ${badges.pageReads}")

        vm.onEvent(CrewEvent.TapTopUp("t2"))
        advanceUntilIdle()
        assertTrue(badges.rowReads.isEmpty(), "nothing unread on t2: ${badges.rowReads}")
        vm.onEvent(CrewEvent.TapTopUp("t1"))
        advanceUntilIdle()
        assertEquals(listOf<Triple<String, String, String?>>(Triple("card_extension", "t1", null)), badges.rowReads)
        assertEquals(null, vm.state.value.crew.topUpTrailId, "no trail to open")
    }

    @Test
    fun `Smart Alerts keeps its whole read, iOS having none to leave the count falling`() = runTest {
        val badges = RecordingBadges(pages = mapOf("smart_alerts" to 2), rows = emptyMap())
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(),
            badges = badges,
            viewer = { accountant },
        ).also { it.start() }
        advanceUntilIdle()
        vm.onEvent(CardEvent.Open(CardDestination.Alerts))
        advanceUntilIdle()
        assertEquals(listOf("smart_alerts"), badges.pageReads)
    }

    private val crew = CardViewer(
        userId = "crew-1",
        departmentIdentifier = "department_art",
        designationIdentifier = null,
    )

    private fun topUp(id: String, cardId: String) =
        CardTopUp(id, cardId, "4821", "crew-1", "", 300.0, "GBP", "restore", "pending", 1L, entityType = "card")

    private fun activeCard() = ExpenseCard(
        id = "c1",
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = CardStatus.Active,
        type = CardType.Physical,
        lastFour = "4821",
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

    private val accountant = CardViewer(
        userId = "acc-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun receipt(id: String) = CardReceipt(
        id = id,
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Batteries",
        merchant = null,
        amount = 120.0,
        currency = "GBP",
        date = null,
        status = CardWorkflowStatus.New,
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
    )
}
