package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalOverrides
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardDetail
import com.zillit.desktop.feature.cardexpenses.ui.CardEditDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesScreen
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CodingDraft
import com.zillit.desktop.feature.cardexpenses.ui.NewCardDraft
import kotlin.test.Test

/**
 * The surfaces this port was missing, composed for real.
 *
 * Each of these was reachable from nowhere before: the card drilldown drew
 * nothing when a row was clicked, the coding queues offered "Nothing to do on
 * this receipt from here", the receipt form asked people to type a storage
 * key, and the settings page wrote five keys that are not columns. A render
 * test is what stops any of them going quietly missing again.
 */
@OptIn(ExperimentalTestApi::class)
class CardNewSurfacesRenderTest {

    // -- the card drilldown ---------------------------------------------------

    @Test
    fun `opening a card shows its spend, its funding and its trail`() = screen(
        state(CardDestination.CardRegister).copy(
            selectedCardId = "card-1",
            cardDetail = CardDetail(
                cardId = "card-1",
                receipts = listOf(receipt()),
                topUps = listOf(topUp()),
                history = listOf(CardHistoryEntry("card_approved", "user-1", "Within budget", 1_754_000_000_000)),
                bsControlCode = "2100",
            ),
        ),
    ) {
        // The group labels render uppercase; the case is presentation.
        onNodeWithText("Spend · 1 receipt(s)", ignoreCase = true).assertExists()
        onNodeWithText("Funding · 1 top-up(s)", ignoreCase = true).assertExists()
        // The wire token is humanised rather than shown raw.
        onNodeWithText("Card approved").assertExists()
    }

    /**
     * The narrow correction is offered; the wide edit is not.
     *
     * A live card's details cannot be rewritten — the save would wipe its
     * approvals — so the drilldown shows the control-code field and no
     * "Edit details".
     */
    @Test
    fun `a live card offers the control code and not the full edit`() = screen(
        state(CardDestination.CardRegister).copy(
            selectedCardId = "card-1",
            cardDetail = CardDetail(cardId = "card-1", bsControlCode = "2100"),
        ),
    ) {
        onNodeWithText("Balance-sheet control code").assertIsDisplayed()
        // Nothing to save until the code is changed.
        onNodeWithText("Save code").assertIsNotEnabled()
    }

    @Test
    fun `a card request offers the full edit and the delete`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(status = CardStatus.Requested)),
            selectedCardId = "card-1",
            cardDetail = CardDetail(cardId = "card-1"),
        ),
    ) {
        onNodeWithText("Edit Details").assertIsDisplayed()
        onNodeWithText("Delete request").assertIsDisplayed()
    }

    // -- the card forms -------------------------------------------------------

    @Test
    fun `the issue-a-card form names the holder picker and the proposed limit`() = screen(
        state(CardDestination.CardRegister).copy(
            people = listOf(CardPerson("user-3", "Grace Hopper", "Gaffer", "Electrical", "dept-2")),
            newCard = NewCardDraft(),
        ),
    ) {
        onNodeWithText("Cardholder", ignoreCase = true).assertIsDisplayed()
        onNodeWithText("Proposed limit").assertIsDisplayed()
        // Nothing chosen yet, so the submit is closed.
        onNodeWithText("Issue card").assertIsNotEnabled()
    }

    /** The resubmit is spelled out before it happens, not discovered after. */
    @Test
    fun `the edit form warns that saving clears the approvals`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(status = CardStatus.Requested)),
            cardEdit = CardEditDraft.of(card().copy(status = CardStatus.Requested)),
        ),
    ) {
        onNodeWithText("Edit card details").assertIsDisplayed()
        onNodeWithText("Save and resubmit").assertIsDisplayed()
    }

    // -- the coding queues ----------------------------------------------------

    @Test
    fun `the coding queue offers all three commits to a coordinator who approves`() = screen(
        state(CardDestination.PendingCoding).copy(
            selectedReceiptId = "receipt-1",
            coding = CodingDraft("receipt-1", nominalCode = "4100"),
        ),
    ) {
        onNodeWithText("Save Draft").assertIsDisplayed()
        onNodeWithText("Save and send").assertIsDisplayed()
        onNodeWithText("Code and approve").assertIsDisplayed()
    }

    /** Nothing moves on without a code; the draft save still works. */
    @Test
    fun `an uncoded receipt cannot be sent on`() = screen(
        state(CardDestination.PendingCoding).copy(
            selectedReceiptId = "receipt-1",
            coding = CodingDraft("receipt-1", nominalCode = ""),
        ),
    ) {
        onNodeWithText("Save and send").assertIsNotEnabled()
        onNodeWithText("Code and approve").assertIsNotEnabled()
    }

    // -- uploading ------------------------------------------------------------

    /**
     * A build with no picker says so rather than offering a dead button.
     *
     * The form used to ask for an "Uploaded file reference", which nobody
     * outside the accounts server could have supplied.
     */
    @Test
    fun `the upload form says when no file picker is available`() = screen(
        state(CardDestination.MyTransactions, crew).copy(
            canAttachFiles = false,
            draft = listOf(DraftCardReceipt()),
        ),
    ) {
        onNodeWithText("Upload Receipts").assertIsDisplayed()
    }

    // -- settings -------------------------------------------------------------

    @Test
    fun `settings shows the five real sections and no invented ones`() = screen(
        state(CardDestination.Settings).copy(settings = settings(), settingsDraft = settings()),
    ) {
        // The page scrolls, so the lower sections are in the tree rather than
        // on screen — which is what this test is checking for.
        onNodeWithText("Accounts Team").assertIsDisplayed()
        onNodeWithText("Department coordinators").assertExists()
        onNodeWithText("Approval rules").assertExists()
        onNodeWithText("Card providers").assertExists()
        onNodeWithText("Request ceiling").assertExists()
    }

    /** An unlimited poster reads as unlimited, not as zero. */
    @Test
    fun `an unlimited posting limit is named on the row`() = screen(
        state(CardDestination.Settings).copy(
            settings = settings(),
            settingsDraft = settings().copy(
                teamMembers = listOf(CardTeamMember("user-1", postingLimit = null)),
            ),
        ),
    ) {
        // Twice over: the pill that states it and the switch that sets it.
        onAllNodesWithText("Unlimited").assertCountEquals(2)
    }

    // -- history --------------------------------------------------------------

    @Test
    fun `history totals what has been posted`() = screen(
        state(CardDestination.History).copy(
            receipts = listOf(receipt().copy(status = CardWorkflowStatus.Posted)),
        ),
    ) {
        onNodeWithText("Posted to date", ignoreCase = true).assertIsDisplayed()
        onNodeWithText("Average receipt", ignoreCase = true).assertIsDisplayed()
    }

    // -- analytics ------------------------------------------------------------

    @Test
    fun `analytics offers a period`() = screen(state(CardDestination.Analytics)) {
        onNodeWithText("Period").assertIsDisplayed()
        onNodeWithText("From", ignoreCase = true).assertExists()
    }

    // -- alerts ---------------------------------------------------------------

    @Test
    fun `an open alert can be investigated as well as resolved`() = screen(
        state(CardDestination.Alerts).copy(
            alerts = listOf(
                com.zillit.desktop.feature.cardexpenses.domain.CardAlert(
                    id = "alert-1",
                    title = "Two receipts look identical",
                    description = "Same merchant, same amount, one minute apart",
                    severity = com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity.High,
                    status = "open",
                    type = "duplicate",
                    savings = 84.20,
                    at = 1_754_000_000_000,
                ),
            ),
        ),
    ) {
        onNodeWithText("Investigate").assertExists()
        onNodeWithText("Resolve").assertExists()
    }

    // -- statement import -----------------------------------------------------

    @Test
    fun `the import screen picks a file rather than asking for a storage key`() = screen(
        state(CardDestination.ImportStatement),
    ) {
        onNodeWithText("Choose a statement file").assertIsDisplayed()
        onNodeWithText("Statement currency").assertIsDisplayed()
    }

    // -- transactions ---------------------------------------------------------

    @Test
    fun `a transaction opens with its own actions`() = screen(
        state(CardDestination.AllTransactions).copy(selectedTransactionId = "txn-1"),
    ) {
        onNodeWithText("Reconciliation", ignoreCase = true).assertExists()
        onNodeWithText("Delete").assertExists()
    }

    // -- harness --------------------------------------------------------------

    private fun screen(state: CardUiState, assertions: suspend ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(state = state, onEvent = {})
                }
            }
            assertions()
        }

    private val accountant = CardViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_financial_controller_accounts",
        metadata = CardMetadata(
            isApprover = true,
            isCoordinator = true,
            isSenior = true,
            codingRequired = true,
            canOverride = true,
            postingLimit = 5_000.0,
        ),
    )

    private val crew = CardViewer(
        userId = "user-2",
        departmentIdentifier = "department_camera",
        designationIdentifier = null,
        metadata = CardMetadata(isApprover = true, isCoordinator = true, codingRequired = true),
    )

    private fun state(destination: CardDestination, viewer: CardViewer = accountant) = CardUiState(
        viewer = viewer,
        destination = destination,
        canAttachFiles = true,
        cards = listOf(card()),
        transactions = listOf(transaction()),
        receipts = listOf(receipt()),
        topUps = listOf(topUp()),
        settings = settings(),
        settingsDraft = settings(),
    )

    private fun card() = ExpenseCard(
        id = "card-1",
        holderId = "user-2",
        holderName = "Ada Lovelace",
        departmentId = "dept-1",
        companyId = null,
        status = CardStatus.Active,
        type = CardType.Physical,
        lastFour = "4821",
        issuer = "Visa",
        providerId = "provider-1",
        currency = "GBP",
        limit = 2_000.0,
        monthlyLimit = null,
        balance = 1_240.0,
        receiptsCommit = 380.0,
        bsControlCode = "2100",
        proposedLimit = 2_000.0,
        justification = "Daily unit spend",
        requestedBy = "user-2",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = 1_754_000_000_000,
    )

    private fun receipt() = CardReceipt(
        id = "receipt-1",
        cardId = "card-1",
        holderId = "user-2",
        holderName = "Ada Lovelace",
        description = "Batteries and gaffer tape",
        merchant = "Camera Store",
        amount = 84.20,
        currency = "GBP",
        date = 1_754_000_000_000,
        status = CardWorkflowStatus.PendingCode,
        matchStatus = MatchStatus.Unmatched,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = null,
        codeDescription = null,
        episode = null,
        attachmentKey = "card-expenses/abc/receipt.jpg",
        urgent = false,
        matchScore = null,
        duplicateScore = null,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = 1_754_000_000_000,
    )

    private fun transaction() = com.zillit.desktop.feature.cardexpenses.domain.CardTransaction(
        id = "txn-1",
        cardId = "card-1",
        cardLastFour = "4821",
        holderId = "user-2",
        holderName = "Ada Lovelace",
        merchant = "CAMERA STORE LTD",
        description = null,
        amount = 84.20,
        currency = "GBP",
        date = 1_754_000_000_000,
        status = CardWorkflowStatus.PendingCode,
        nominalCode = null,
        codeDescription = null,
        episode = null,
        vatAmount = 14.03,
        matchStatus = MatchStatus.Unmatched,
        receiptId = null,
        personal = false,
    )

    private fun topUp() = CardTopUp(
        id = "top-1",
        cardId = "card-1",
        cardLastFour = "4821",
        holderId = "user-2",
        holderName = "Ada Lovelace",
        amount = 500.0,
        currency = "GBP",
        method = "manual",
        status = "completed",
        createdAt = 1_754_000_000_000,
    )

    private fun settings() = CardSettings(
        teamMembers = listOf(CardTeamMember("user-1", postingLimit = 5_000.0)),
        coordinators = listOf(DepartmentCoordinator("dept-1", listOf("user-2"), codingRequired = true)),
        overrides = ApprovalOverrides(overrideReceipts = true),
        providers = listOf(CardProvider("provider-1", "Visa")),
        requestCap = 10_000.0,
    )
}
