package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun card(
    id: String = "card-1",
    holderId: String = "user-1",
    status: CardStatus = CardStatus.Active,
    limit: Double = 1_000.0,
    balance: Double? = 1_000.0,
    commit: Double? = null,
    requestedBy: String? = "user-1",
) = ExpenseCard(
    id = id,
    holderId = holderId,
    holderName = "Ada",
    departmentId = null,
    companyId = null,
    status = status,
    type = CardType.Physical,
    lastFour = "4821",
    issuer = "Visa",
    providerId = null,
    currency = "GBP",
    limit = limit,
    monthlyLimit = null,
    balance = balance,
    receiptsCommit = commit,
    bsControlCode = null,
    requestedBy = requestedBy,
    rejectedBy = null,
    rejectionReason = null,
    createdAt = null,
)

/** The one-card-per-user rule and its two exceptions. */
class CardRequestRuleTest {

    @Test
    fun `a live card blocks its holder from requesting another`() {
        listOf(
            CardStatus.Requested,
            CardStatus.Pending,
            CardStatus.Approved,
            CardStatus.Rejected,
            CardStatus.Active,
        ).forEach { status ->
            assertTrue(CardRules.blocksNewRequest(card(status = status)), "$status should block")
        }
    }

    @Test
    fun `terminal statuses free the holder`() {
        listOf(CardStatus.Suspended, CardStatus.Cancelled, CardStatus.Closed).forEach { status ->
            assertFalse(CardRules.blocksNewRequest(card(status = status)), "$status should free")
        }
    }

    @Test
    fun `a maxed-out active card does not block a replacement`() {
        // The holder may ask for a new one while the spent card stays live
        // until the accounts team rotates it.
        assertFalse(CardRules.blocksNewRequest(card(status = CardStatus.Active, balance = 0.0)))
        assertFalse(CardRules.blocksNewRequest(card(status = CardStatus.Active, balance = -5.0)))
    }

    @Test
    fun `a card with no balance figure still blocks`() {
        // Fail closed: an unfunded or unknown card must not wrongly free its
        // holder to hold two cards at once.
        assertTrue(CardRules.blocksNewRequest(card(status = CardStatus.Active, balance = null)))
        assertFalse(CardRules.balanceExhausted(card(balance = null)))
    }

    @Test
    fun `canRequestCard looks only at that holder's cards`() {
        val cards = listOf(
            card(id = "a", holderId = "user-1", status = CardStatus.Active),
            card(id = "b", holderId = "user-2", status = CardStatus.Closed),
        )
        assertFalse(CardRules.canRequestCard(cards, "user-1"))
        assertTrue(CardRules.canRequestCard(cards, "user-2"))
        assertTrue(CardRules.canRequestCard(cards, "user-3"))
    }

    @Test
    fun `only pre-approval requests can be edited`() {
        listOf(CardStatus.Requested, CardStatus.Pending, CardStatus.Rejected).forEach { status ->
            assertTrue(
                CardRules.canEditRequest(card(status = status), "user-1", isAccountant = false),
                "$status should be editable by its requester",
            )
        }
        listOf(CardStatus.Active, CardStatus.Approved, CardStatus.Suspended).forEach { status ->
            assertFalse(
                CardRules.canEditRequest(card(status = status), "user-1", isAccountant = true),
                "$status is past editing",
            )
        }
    }

    @Test
    fun `a card with no requester is nobody's to edit`() {
        // Without the blank check, `null == null` makes every viewer the owner.
        val orphan = card(status = CardStatus.Requested, requestedBy = null)

        assertFalse(CardRules.canEditRequest(orphan, viewerId = null, isAccountant = false))
        assertFalse(CardRules.canEditRequest(orphan, viewerId = "user-9", isAccountant = false))
        // An accountant may still fix it.
        assertTrue(CardRules.canEditRequest(orphan, viewerId = null, isAccountant = true))
    }
}

/** The upload gate. */
class UploadHeadroomTest {

    @Test
    fun `headroom is the limit less what is already committed`() {
        val headroom = UploadHeadroom.of(card(limit = 1_000.0, commit = 250.0))

        assertEquals(750.0, headroom.available)
        assertFalse(headroom.exhausted)
    }

    @Test
    fun `a card with no limit fails closed`() {
        // Both operands default to zero, so an unset limit refuses uploads
        // rather than permitting unlimited ones.
        val headroom = UploadHeadroom.of(card(limit = 0.0, commit = null))

        assertEquals(0.0, headroom.available)
        assertTrue(headroom.exhausted)
        assertTrue(headroom.batchExceeds(0.01))
    }

    @Test
    fun `no card at all has no headroom`() {
        assertTrue(UploadHeadroom.of(null).exhausted)
    }

    @Test
    fun `a batch exactly meeting the headroom is allowed`() {
        val headroom = UploadHeadroom.of(card(limit = 500.0, commit = 100.0))

        assertFalse(headroom.batchExceeds(400.0))
        assertTrue(headroom.batchExceeds(400.01))
    }

    @Test
    fun `editing counts only the increase`() {
        val headroom = UploadHeadroom.of(card(limit = 500.0, commit = 500.0))

        // Fully committed, yet lowering or leaving an amount alone is still
        // allowed — otherwise a receipt on a maxed card could never have its
        // image attached.
        assertFalse(headroom.editExceeds(currentAmount = 100.0, newAmount = 100.0))
        assertFalse(headroom.editExceeds(currentAmount = 100.0, newAmount = 40.0))
        assertTrue(headroom.editExceeds(currentAmount = 100.0, newAmount = 100.01))
    }
}

/** The reconciliation vocabulary that overrides the workflow one. */
class ReceiptReconciliationTest {

    private fun receipt(
        match: MatchStatus,
        attachment: String?,
        status: CardWorkflowStatus = CardWorkflowStatus.PendingReceipt,
    ) = CardReceipt(
        id = "r-1",
        cardId = null,
        holderId = null,
        holderName = "Ada",
        description = "Batteries",
        merchant = null,
        amount = 12.0,
        currency = "GBP",
        date = null,
        status = status,
        matchStatus = match,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = null,
        codeDescription = null,
        episode = null,
        attachmentKey = attachment,
        urgent = false,
        matchScore = null,
        duplicateScore = null,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = null,
    )

    @Test
    fun `an unmatched receipt reads unreconciled whatever its workflow state`() {
        assertEquals(
            "Unreconciled",
            receipt(MatchStatus.Unmatched, "key", CardWorkflowStatus.AwaitingApproval).reconciliationLabel(),
        )
    }

    @Test
    fun `a matched receipt with no document reads reconciled`() {
        assertEquals("Reconciled", receipt(MatchStatus.Matched, null).reconciliationLabel())
        assertEquals("Reconciled", receipt(MatchStatus.Matched, "").reconciliationLabel())
    }

    @Test
    fun `a matched receipt with a document falls back to the workflow badge`() {
        assertNull(receipt(MatchStatus.Matched, "receipts/abc.jpg").reconciliationLabel())
    }

    @Test
    fun `a missing match status is unmatched rather than matched-like`() {
        assertEquals(MatchStatus.Unmatched, MatchStatus.from(null))
        assertEquals(MatchStatus.Unmatched, MatchStatus.from(""))
        // Legacy leftovers behave as matched, per product direction.
        assertEquals(MatchStatus.Matched, MatchStatus.from("suggested_match"))
        assertEquals(MatchStatus.Matched, MatchStatus.from("matched"))
    }
}

/** Who sees which card surface. */
class CardAccessTest {

    private fun viewer(
        department: String? = "department_accounts",
        designation: String? = null,
        metadata: CardMetadata = CardMetadata(),
        enteredAsTool: Boolean = false,
    ) = CardViewer("user-1", department, designation, metadata, enteredAsTool)

    @Test
    fun `an accountant gets the processing surfaces and a cardholder gets their own`() {
        val accountant = viewer()
        assertTrue(CardDestination.ReceiptInbox.visibleTo(accountant))
        assertFalse(CardDestination.MyTransactions.visibleTo(accountant))

        val crew = viewer(department = "department_camera")
        assertTrue(CardDestination.MyTransactions.visibleTo(crew))
        assertFalse(CardDestination.ReceiptInbox.visibleTo(crew))
    }

    @Test
    fun `entering from the tools grid gives an accountant the cardholder view`() {
        val fromGrid = viewer(enteredAsTool = true)

        assertFalse(fromGrid.isAccountant)
        assertTrue(CardDestination.MyTransactions.visibleTo(fromGrid))
        assertEquals(CardDestination.MyTransactions, CardDestination.landing(fromGrid))
    }

    @Test
    fun `settings needs a senior accountant`() {
        assertFalse(CardDestination.Settings.visibleTo(viewer()))
        assertTrue(
            CardDestination.Settings.visibleTo(
                viewer(designation = "designation_financial_controller_accounts"),
            ),
        )
        assertTrue(CardDestination.Settings.visibleTo(viewer(metadata = CardMetadata(isSenior = true))))
    }

    @Test
    fun `the coding queue needs a coordinator and a production that codes`() {
        val coordinator = CardMetadata(isCoordinator = true, codingRequired = true)
        assertTrue(
            CardDestination.CodingQueue.visibleTo(viewer(department = "department_art", metadata = coordinator)),
        )
        assertFalse(
            CardDestination.CodingQueue.visibleTo(
                viewer(department = "department_art", metadata = coordinator.copy(codingRequired = false)),
            ),
        )
    }

    @Test
    fun `every landing page is one the viewer can open`() {
        listOf(
            viewer(),
            viewer(department = "department_art"),
            viewer(enteredAsTool = true),
            viewer(department = "department_art", metadata = CardMetadata(isApprover = true)),
        ).forEach { person ->
            assertTrue(CardDestination.landing(person).visibleTo(person))
        }
    }

    @Test
    fun `an absent posting limit means no ceiling`() {
        assertTrue(viewer().canPost(1_000_000.0))
        assertFalse(viewer(metadata = CardMetadata(postingLimit = 250.0)).canPost(250.01))
    }
}
