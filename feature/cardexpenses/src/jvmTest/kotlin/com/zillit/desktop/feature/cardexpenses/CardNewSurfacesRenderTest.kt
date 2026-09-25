package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalOverrides
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
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
import com.zillit.desktop.feature.cardexpenses.ui.CardsArea
import com.zillit.desktop.feature.cardexpenses.ui.CodeReceiptDraft
import com.zillit.desktop.feature.cardexpenses.ui.CodingDraft
import com.zillit.desktop.feature.cardexpenses.ui.CrewState
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

    // -- the card detail, full-page --------------------------------------------

    @Test
    fun `opening a card takes over the page with its receipts and its history`() = screen(
        state(CardDestination.CardRegister).copy(
            cardsArea = CardsArea(openCardId = "card-1", historyOpen = true),
            cardDetail = CardDetail(
                cardId = "card-1",
                receipts = listOf(receipt()),
                receiptsRead = true,
                history = listOf(CardHistoryEntry("card_approved", "user-1", "Within budget", 1_754_000_000_000)),
                bsControlCode = "2100",
            ),
        ),
    ) {
        onNodeWithText("Batteries and gaffer tape").assertExists()
        onNodeWithText("Card History").assertExists()
        // The wire token is humanised rather than shown raw.
        onNodeWithText("Card approved").assertExists()
        // The register's own heading steps aside for the card.
        onAllNodesWithText("Production expense cards with balance sheet", substring = true).assertCountEquals(0)
    }

    /** A completed read that found no receipts is the only licence to re-point the code. */
    @Test
    fun `a live card with no receipts offers the control-code pencil`() = screen(
        state(CardDestination.CardRegister).copy(
            cardsArea = CardsArea(openCardId = "card-1"),
            cardDetail = CardDetail(cardId = "card-1", bsControlCode = "2100", receiptsRead = true),
        ),
    ) {
        onNodeWithContentDescription("Edit BS control code").assertExists()
    }

    @Test
    fun `a card with receipts does not offer the control code for change`() = screen(
        state(CardDestination.CardRegister).copy(
            cardsArea = CardsArea(openCardId = "card-1"),
            cardDetail = CardDetail(cardId = "card-1", receiptsRead = true, receipts = listOf(receipt())),
        ),
    ) {
        onAllNodesWithContentDescription("Edit BS control code").assertCountEquals(0)
    }

    /** A request's author may bin it; the accountant reviews it from the Action Needed block. */
    @Test
    fun `a card request is reviewed and deleted from its detail`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(status = CardStatus.Requested, requestedBy = "user-1")),
            cardsArea = CardsArea(openCardId = "card-1"),
            cardDetail = CardDetail(cardId = "card-1"),
        ),
    ) {
        onNodeWithText("Action Needed", ignoreCase = true).assertIsDisplayed()
        onNodeWithText("Review").assertIsDisplayed()
        onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun `somebody else's card request is not the accountant's to delete`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(status = CardStatus.Requested)),
            cardsArea = CardsArea(openCardId = "card-1"),
            cardDetail = CardDetail(cardId = "card-1"),
        ),
    ) {
        onAllNodesWithText("Delete").assertCountEquals(0)
    }

    // -- the register ---------------------------------------------------------

    /** Live on its virtual number only: the tile says so and offers the plastic. */
    @Test
    fun `a digital-only live card offers Assign Physical Card`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(digitalCardNumber = "4000123456789010")),
        ),
    ) {
        onNodeWithText("Digital Active", ignoreCase = true).assertIsDisplayed()
        onNodeWithText("Assign Physical Card").assertIsDisplayed()
    }

    /** A card that still blocks a new one hides the request, with no explanation — the web's. */
    @Test
    fun `the Card tab hides Request New Card while a card blocks one`() = screen(
        state(CardDestination.MyCards, crew),
    ) {
        onAllNodesWithText("Request New Card").assertCountEquals(0)
    }

    // -- the card forms -------------------------------------------------------

    @Test
    fun `the request form names the holder picker and the proposed limit`() = screen(
        state(CardDestination.CardRegister).copy(
            people = listOf(CardPerson("user-3", "Grace Hopper", "Gaffer", "Electrical", "dept-2")),
            newCard = NewCardDraft(),
        ),
    ) {
        onNodeWithText("Search user...").assertIsDisplayed()
        onNodeWithText("Auto-filled from user").assertIsDisplayed()
        // Nothing chosen yet, so the submit is closed.
        onNodeWithText("Submit Request").assertIsNotEnabled()
    }

    @Test
    fun `the accountant's edit form submits for approval`() = screen(
        state(CardDestination.CardRegister).copy(
            cards = listOf(card().copy(status = CardStatus.Requested)),
            cardEdit = CardEditDraft.of(card().copy(status = CardStatus.Requested)),
        ),
    ) {
        onNodeWithText("Edit Card Details").assertIsDisplayed()
        onNodeWithText("Submit for Approval").assertIsDisplayed()
    }

    // -- the coding queues ----------------------------------------------------

    /** The web's Code Receipt dialog: Save Draft, and Approve & Submit for an approver. */
    @Test
    fun `the coding queue offers save draft and approve and submit to an approver`() = screen(
        state(CardDestination.CodingQueue, crew).copy(
            crew = CrewState(code = CodeReceiptDraft.of(receipt()).copy(costCode = "4100")),
        ),
    ) {
        onNodeWithText("Save Draft").assertIsDisplayed()
        onNodeWithText("Approve & Submit").assertIsDisplayed()
        onAllNodesWithText("Submit for Approval").assertCountEquals(0)
    }

    /** Nothing moves on without a code; the draft save still works. */
    @Test
    fun `an uncoded receipt cannot be sent on`() = screen(
        state(CardDestination.CodingQueue, crew).copy(
            crew = CrewState(code = CodeReceiptDraft.of(receipt()).copy(costCode = "")),
        ),
    ) {
        onNodeWithText("Approve & Submit").assertIsNotEnabled()
        onNodeWithText("Save Draft").assertIsEnabled()
    }

    /** Pending Coding is the accountant's read-only view; coding is the crew's to do. */
    @Test
    fun `pending coding shows the coding without the commits`() = screen(
        state(CardDestination.PendingCoding).copy(
            selectedReceiptId = "receipt-1",
            coding = CodingDraft("receipt-1", nominalCode = "4100"),
        ),
    ) {
        onAllNodesWithText("Save and send").assertCountEquals(0)
        onAllNodesWithText("Code and approve").assertCountEquals(0)
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
            crew = CrewState(uploadOpen = true),
        ),
    ) {
        onNodeWithText("Add Your Receipts").assertIsDisplayed()
        onNodeWithText("No file picker is available in this build, so receipts cannot be uploaded here.")
            .assertExists()
    }

    // -- settings -------------------------------------------------------------

    /** The web's sections, in the web's order (`SettingsPage.jsx:505-1301`). */
    @Test
    fun `settings shows the web's sections and no invented ones`() = screen(
        state(CardDestination.Settings).copy(settings = settings(), settingsDraft = settings()),
    ) {
        // The page scrolls, so the lower sections are in the tree rather than
        // on screen — which is what this test is checking for.
        onNodeWithText("Card Accounts & Custodian").assertIsDisplayed()
        onNodeWithText("Team & Posting Rights").assertExists()
        onNodeWithText("Department Coordinator Designations").assertExists()
        onNodeWithText("Approval & Override Settings").assertExists()
        onNodeWithText("Request Cap").assertExists()
        onNodeWithText("Auto-Assignment Rules").assertExists()
        // Nothing unsaved, so no Save anywhere; the web has no Discard.
        onAllNodesWithText("Save").assertCountEquals(0)
        onAllNodesWithText("Discard").assertCountEquals(0)
    }

    /** An unlimited poster reads as unlimited, not as zero. */
    @Test
    fun `an unlimited posting limit is named on the row`() = screen(
        state(CardDestination.Settings).copy(
            settings = settings().copy(teamMembers = listOf(CardTeamMember("user-1", postingLimit = null))),
        ),
    ) {
        onAllNodesWithText("Unlimited").assertCountEquals(1)
    }

    // -- history --------------------------------------------------------------

    /** One History card, each row Posted — no totals tiles, no detail pane (`HistoryPage.jsx`). */
    @Test
    fun `history lists what has been posted`() = screen(
        state(CardDestination.History).copy(
            receipts = listOf(receipt().copy(status = CardWorkflowStatus.Posted)),
        ),
    ) {
        onAllNodesWithText("Posted to date", ignoreCase = true).assertCountEquals(0)
        onNodeWithText("1 receipt", substring = true).assertExists()
        onNodeWithText("Posted").assertExists()
    }

    // -- analytics ------------------------------------------------------------

    /** No period picker — the web reads `/analytics/overview` with no window. */
    @Test
    fun `analytics draws the web's cards and no period`() = screen(
        state(CardDestination.Analytics).copy(
            analytics = com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics(totalSpend = 1_200.0),
        ),
    ) {
        onNodeWithText("Cash Flow & Forecast").assertExists()
        onNodeWithText("Cost Report Impact").assertExists()
        onNodeWithText("Processing Performance").assertExists()
        onAllNodesWithText("Period").assertCountEquals(0)
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
                    status = "active",
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
    fun `the import screen opens on the drop zone`() = screen(
        state(CardDestination.ImportStatement),
    ) {
        onNodeWithText("Drop CSV, OFX or QIF file here").assertIsDisplayed()
        onNodeWithText("CSV").assertExists()
    }

    // -- transactions ---------------------------------------------------------

    @Test
    fun `all transactions draws the register with its filters and export`() = screen(
        state(CardDestination.AllTransactions),
    ) {
        onNodeWithText("Filters").assertExists()
        onNodeWithText("Export").assertExists()
        onNodeWithText("Awaiting Approval").assertExists()
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
        requestCap = RequestCap(enabled = true, maxAmount = 10_000.0),
    )
}
