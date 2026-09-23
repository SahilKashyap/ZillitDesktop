package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cardexpenses.domain.CardApproval
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.FixedLine
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.TierConfig
import com.zillit.desktop.feature.cardexpenses.domain.TierRule
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import com.zillit.desktop.feature.cardexpenses.domain.canDelete
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.enteredThrough
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules the web-parity work added, each pinned against the web's own reading. */
class CardParityRulesTest {

    // -- the door the tool was entered by --------------------------------------

    /**
     * An accountant who opens the tile is crew here (`useIsCardAccountant.js`);
     * the same accountant through the hub is the console.
     */
    @Test
    fun `an accountant who opened the tool alone gets the crew view`() {
        val hub = CardUiState(viewer = accountant(), destination = CardDestination.Overview)

        val alone = hub.enteredThrough(asTool = true)

        assertFalse(alone.viewer.isAccountant)
        assertTrue(alone.viewer.isAccountsRole, "the role is unchanged; only the door differs")
        assertEquals(CardDestination.MyTransactions, alone.destination, "the console page is not the crew's")
        assertFalse(CardDestination.Settings.visibleTo(alone.viewer))
        assertTrue(hub.enteredThrough(asTool = false).viewer.isAccountant)
    }

    // -- approval chains ---------------------------------------------------------

    private val chains = listOf(
        TierConfig("all", null, listOf(tier(1, "u1"), tier(2, "u2"))),
        TierConfig(
            "department",
            "d-camera",
            listOf(
                ApprovalTier(
                    1,
                    listOf(
                        TierRule("default", null, listOf("u3")),
                        TierRule("amount", 1_000.0, listOf("u4")),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a department's own chain wins over the production's`() {
        assertEquals(listOf(listOf("u3")), ApprovalTiers.resolve(chains, "d-camera", 500.0))
        assertEquals(
            listOf(listOf("u4")),
            ApprovalTiers.resolve(chains, "d-camera", 1_500.0),
            "an amount rule replaces the defaults",
        )
        assertEquals(listOf(listOf("u1"), listOf("u2")), ApprovalTiers.resolve(chains, "d-art", 500.0))
        assertNull(ApprovalTiers.resolve(emptyList(), "d-art", 500.0))
    }

    /** Only the person the next unsigned tier names may approve (`approval-helpers.js`). */
    @Test
    fun `the next tier's approver may sign, nobody else`() {
        val chain = ApprovalTiers.resolve(chains, "d-art", null)

        assertTrue(ApprovalTiers.visibility(chain, emptyList(), "u1").canApprove)
        assertFalse(ApprovalTiers.visibility(chain, emptyList(), "u2").canApprove, "tier 2 waits for tier 1")
        val second = ApprovalTiers.visibility(chain, listOf(CardApproval("u1", 1)), "u2")
        assertTrue(second.canApprove)
        assertEquals(2, second.nextTier)
        assertEquals(2, second.totalTiers)
        assertFalse(ApprovalTiers.visibility(null, emptyList(), "u1").canApprove, "no chain, no approver")
    }

    /** A pending card only; any accountant used to be offered Approve on a request. */
    @Test
    fun `approving a card needs the chain, not the department`() {
        val state = CardUiState(
            viewer = accountant("u9", CardMetadata(tierConfigs = chains)),
            destination = CardDestination.CardRegister,
            cards = listOf(card(CardStatus.Pending), card(CardStatus.Requested, id = "c2")),
        )

        assertFalse(state.cardApproval("c1").canApprove, "an accountant outside the chain")
        val approver = state.copy(viewer = accountant("u1", CardMetadata(tierConfigs = chains)))
        assertTrue(approver.cardApproval("c1").canApprove)
        assertFalse(approver.cardApproval("c2").canApprove, "a requested card is not in its chain yet")
    }

    @Test
    fun `the approval queue's next approver has signed-off predecessors`() {
        val receipt = receipt().copy(departmentId = "d-art", approvals = listOf(CardApproval("u1", 1)))

        assertTrue(ApprovalTiers.isNextApprover(chains, receipt, "u2"))
        assertFalse(ApprovalTiers.isNextApprover(chains, receipt, "u1"))
        val forged = receipt.copy(approvals = listOf(CardApproval("u5", 1)))
        assertFalse(ApprovalTiers.isNextApprover(chains, forged, "u2"), "tier 1 was signed by someone it does not name")
    }

    // -- overriding --------------------------------------------------------------

    /** The person's grant and the production's switch, both (`CardRegisterPage.jsx:187`). */
    @Test
    fun `overriding needs the grant and the production's switch`() {
        assertFalse(accountant(metadata = CardMetadata(canOverride = true)).canOverrideCard)
        assertTrue(accountant(metadata = CardMetadata(canOverride = true, cardOverride = true)).canOverrideCard)
        assertFalse(accountant(metadata = CardMetadata(cardOverride = true)).canOverrideCard)
        assertTrue(accountant(metadata = CardMetadata(isSenior = true, receiptOverride = true)).canOverrideReceipt)
    }

    // -- processing --------------------------------------------------------------

    @Test
    fun `a non-senior opens only what is assigned to them`() {
        val row = receipt().copy(assignedTo = "u1")

        assertTrue(ProcessRules.canOpen(accountant("u1"), row))
        assertFalse(ProcessRules.canOpen(accountant("u2"), row))
        val unassigned = row.copy(assignedTo = null)
        assertFalse(ProcessRules.canOpen(accountant("u2"), unassigned), "unassigned is a senior's to hand out")
        assertTrue(ProcessRules.canOpen(accountant("u2", CardMetadata(isSenior = true)), row))
    }

    /** Post is hidden from a non-senior on a flagged receipt and above a posting limit. */
    @Test
    fun `posting honours review rules and posting limits`() {
        val flagged = ReceiptProcessing(flags = setOf(ReceiptProcessing.REVIEW))

        assertFalse(ProcessRules.canPost(accountant(), flagged, 50.0))
        assertTrue(ProcessRules.canPost(accountant(metadata = CardMetadata(isSenior = true)), flagged, 50.0))
        val limited = accountant(metadata = CardMetadata(postingLimit = 100.0))
        assertTrue(ProcessRules.canPost(limited, ReceiptProcessing(), 100.0))
        assertFalse(ProcessRules.canPost(limited, ReceiptProcessing(), 100.01))
        val blocked = accountant(metadata = CardMetadata(postingLimit = 0.0))
        assertFalse(ProcessRules.canPost(blocked, ReceiptProcessing(), 1.0))
        val senior = accountant(metadata = CardMetadata(isSenior = true))
        assertFalse(ProcessRules.canHandUp(senior), "a senior does not escalate")
    }

    @Test
    fun `the figures reconcile gross to the receipt, to the penny`() {
        val figures = ProcessFigures(
            receiptAmount = 120.0,
            lines = listOf(ProcessLine(account = "4100", net = 100.0, taxRate = 20.0)),
            fixedLines = listOf(FixedLine(JsonObject(emptyMap()), gross = 0.0, tax = 0.0, countsInTotal = false)),
            cardLimit = 500.0,
            cardBalance = 400.0,
        )

        assertFalse(figures.mismatch)
        assertEquals(120.0, figures.gross)
        assertEquals(220.0, figures.topUpAmount(TopUpMethod.Restore), "back to the limit: 500 − 400 + 120")
        assertEquals(120.0, figures.topUpAmount(TopUpMethod.Expense))
        assertFalse(figures.topUpOverfills(TopUpMethod.Restore))
        assertTrue(figures.copy(lines = listOf(ProcessLine(account = "4100", net = 90.0))).mismatch)
        val short = figures.copy(lines = listOf(ProcessLine(net = 90.0)))
        assertEquals(90.0, short.effectiveAmount, "a lower total is what is debited")
        assertEquals(listOf(1), figures.copy(lines = listOf(ProcessLine(net = 120.0))).linesMissingNominal)
    }

    @Test
    fun `a top-up past the card's limit is refused`() {
        val figures = ProcessFigures(
            receiptAmount = 100.0,
            lines = listOf(ProcessLine(account = "4100", net = 100.0)),
            fixedLines = emptyList(),
            cardLimit = 500.0,
            cardBalance = 550.0,
        )

        assertTrue(figures.topUpOverfills(TopUpMethod.Expense))
        assertFalse(figures.topUpOverfills(TopUpMethod.None))
    }

    // -- All Transactions' filters -------------------------------------------------

    /** Blank filters are omitted, and the window is UTC and end-inclusive (`transactionQuery.js`). */
    @Test
    fun `transaction filters send only what is set, in UTC`() {
        val query = TransactionFilters(cardId = "c1", from = "2026-08-01", to = "2026-08-31").query()

        assertEquals(
            mapOf("card_id" to "c1", "from" to "2026-08-01T00:00:00Z", "to" to "2026-08-31T23:59:59Z"),
            query,
        )
        assertTrue(TransactionFilters().query().isEmpty(), "no filters reads everything")
        assertEquals(2, TransactionFilters(statementId = "s", from = "2026-08-01", to = "2026-08-31").count)
    }

    /** A month back from the 31st lands on the shorter month's last day, not in the next one. */
    @Test
    fun `the default window is the last month, clamped`() {
        val thirtyFirstOfMarch = 1_774_915_200_000L // 2026-03-31T00:00:00Z

        val window = TransactionFilters.lastMonth(thirtyFirstOfMarch)

        assertEquals("2026-02-28", window.from)
        assertEquals("2026-03-31", window.to)
    }

    // -- deleting ----------------------------------------------------------------

    /** Approved and posted lines are bookkeeping (`transactionQuery.js:55-58`). */
    @Test
    fun `approved and posted statement lines cannot be deleted`() {
        assertFalse(CardWorkflowStatus.Approved.canDelete)
        assertFalse(CardWorkflowStatus.Posted.canDelete)
        assertTrue(CardWorkflowStatus.Queried.canDelete)
        assertTrue(CardWorkflowStatus.Rejected.canDelete)
    }

    @Test
    fun `only the requester deletes a card request`() {
        val mine = card(CardStatus.Pending).copy(requestedBy = "u1")

        assertTrue(CardRules.canDeleteRequest(mine, "u1"))
        assertFalse(CardRules.canDeleteRequest(mine, "u2"))
        assertFalse(CardRules.canDeleteRequest(mine.copy(status = CardStatus.Active), "u1"))
    }

    /** Only once a completed read has proved the card unspent (`CardDetailModal.jsx:219-243`). */
    @Test
    fun `a control code is corrected only on an unspent live card`() {
        val live = card(CardStatus.Active)

        assertTrue(CardRules.canCorrectBsCode(live, receiptsProvenEmpty = true, isAccountant = true))
        assertFalse(CardRules.canCorrectBsCode(live, receiptsProvenEmpty = false, isAccountant = true))
        assertFalse(CardRules.canCorrectBsCode(live.copy(status = CardStatus.Suspended), true, isAccountant = true))
        assertFalse(CardRules.canCorrectBsCode(live, receiptsProvenEmpty = true, isAccountant = false))
    }

    // -- fixtures ----------------------------------------------------------------

    private fun tier(order: Int, vararg users: String) =
        ApprovalTier(order, listOf(TierRule("default", null, users.toList())))

    private fun accountant(id: String = "u1", metadata: CardMetadata = CardMetadata()) =
        CardViewer(id, "department_accounts", "designation_assistant_accountant", metadata)

    private fun card(status: CardStatus, id: String = "c1") = ExpenseCard(
        id = id,
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = status,
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
        requestedBy = null,
        rejectedBy = null,
        rejectionReason = null,
        createdAt = null,
    )

    private fun receipt() = CardReceipt(
        id = "r1",
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Batteries",
        merchant = null,
        amount = 120.0,
        currency = "GBP",
        date = null,
        status = CardWorkflowStatus.Approved,
        matchStatus = MatchStatus.Matched,
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
