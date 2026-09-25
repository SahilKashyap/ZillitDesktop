package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
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
 * only — the web's `readScope` (`ReceiptInboxPage.jsx:745`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardRowBadgeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A ledger in memory that writes down every read it is asked for. */
    private class RecordingBadges : TabBadgeSource {
        override val counts = MutableStateFlow(mapOf("receipt_inbox" to 3))
        override val entityCounts = MutableStateFlow(mapOf("receipt_inbox" to mapOf("r1" to 2, "r2" to 1)))
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

    private val accountant = CardViewer(
        userId = "acc-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
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
