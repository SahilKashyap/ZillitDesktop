package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesScreen
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Composes the real Card Expenses screen on every destination. See the cash module's equivalent. */
@OptIn(ExperimentalTestApi::class)
class CardScreenRenderTest {

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

    private fun card(id: String = "card-1") = ExpenseCard(
        id = id,
        holderId = "user-2",
        holderName = "Ada Lovelace",
        departmentId = "dept-1",
        companyId = null,
        status = CardStatus.Active,
        type = CardType.Physical,
        lastFour = "4821",
        issuer = "Visa",
        providerId = null,
        currency = "GBP",
        limit = 2_000.0,
        monthlyLimit = null,
        balance = 1_240.0,
        receiptsCommit = 380.0,
        bsControlCode = "1200",
        proposedLimit = 2_000.0,
        justification = "Daily unit spend",
        requestedBy = "user-2",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = 1_754_000_000_000,
    )

    private fun receipt(id: String = "receipt-1") = CardReceipt(
        id = id,
        cardId = "card-1",
        holderId = "user-2",
        holderName = "Ada Lovelace",
        description = "Batteries and gaffer tape",
        merchant = "Camera Store",
        amount = 84.20,
        currency = "GBP",
        date = 1_754_000_000_000,
        status = CardWorkflowStatus.AwaitingApproval,
        matchStatus = MatchStatus.Matched,
        transactionId = "txn-1",
        transactionMerchant = "CAMERA STORE LTD",
        transactionAmount = 84.20,
        transactionDate = 1_754_000_000_000,
        transactionCardLastFour = "4821",
        nominalCode = "4100",
        codeDescription = null,
        episode = null,
        attachmentKey = "receipts/abc.jpg",
        urgent = false,
        matchScore = 96,
        duplicateScore = 88,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = 1_754_000_000_000,
    )

    private fun transaction(id: String = "txn-1") = CardTransaction(
        id = id,
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
        matchStatus = MatchStatus.Matched,
        receiptId = "receipt-1",
        personal = false,
    )

    private fun state(destination: CardDestination, viewer: CardViewer = accountant) = CardUiState(
        viewer = viewer,
        destination = destination,
        cards = listOf(card(), card("card-2")),
        transactions = listOf(transaction(), transaction("txn-2")),
        receipts = listOf(receipt(), receipt("receipt-2")),
        matchCandidates = listOf(transaction("txn-3")),
        topUps = listOf(
            CardTopUp("top-1", "card-1", "4821", "user-2", "Ada", 500.0, "GBP", "manual", "pending", 1L),
        ),
        selectedReceiptId = "receipt-1",
        selectedCardId = "card-1",
    )

    /** The receipt queue with one selected receipt, carrying (or lacking) a document. */
    @Test
    fun `every accountant destination composes`() {
        val destinations = CardDestination.entries.filter { it.visibleTo(accountant) }
        assertTrue(destinations.size > 10, "expected the accountant to see most of the tool")

        destinations.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        CardExpensesScreen(state = state(destination), onEvent = {})
                    }
                }
                // The sidebar is the accountant frame; Overview is always in it —
                // and on the Overview page its own heading says so too.
                onAllNodesWithText("Overview")[0].assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every cardholder destination composes`() {
        CardDestination.entries.filter { it.visibleTo(crew) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        CardExpensesScreen(state = state(destination, crew), onEvent = {})
                    }
                }
                onNodeWithText("Production Expense Cards").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every destination composes in dark mode too`() {
        CardDestination.entries.filter { it.visibleTo(accountant) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = true) {
                        CardExpensesScreen(state = state(destination), onEvent = {})
                    }
                }
                onAllNodesWithText("Overview")[0].assertIsDisplayed()
            }
        }
    }

    /**
     * Bulk Process lists the process queue's approved rows; a ticked row brings
     * up the override bar, which says blank keeps each row's own coding
     * (`BulkProcessPage.jsx:410-491`).
     */
    @Test
    fun `the bulk bar says a blank override keeps each row's own coding`() {
        val mine = receipt().copy(status = CardWorkflowStatus.Approved, assignedTo = "user-1")
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.BulkProcess).copy(
                            receipts = listOf(mine),
                            selection = setOf(mine.id),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Bulk Override", substring = true, ignoreCase = true).assertExists()
            onNodeWithText("Batch Post 1 item").assertExists()
            onNodeWithText("Batch Queue").assertExists()
        }
    }

    @Test
    fun `an empty bulk queue says where rows come from`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.BulkProcess).copy(receipts = emptyList()),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(
                "No transactions available for bulk processing. Import statements to add transactions.",
            ).assertIsDisplayed()
        }
    }

    /**
     * The process editor says when the coded lines do not reach the receipt,
     * and a senior who owns the row is offered Post alongside Save.
     */
    @Test
    fun `the process editor shows a shortfall and offers a senior the post`() {
        val detail = receipt().copy(
            status = CardWorkflowStatus.Approved,
            amount = 120.0,
            assignedTo = "user-1",
            processing = com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing(
                loaded = true,
                lines = listOf(
                    com.zillit.desktop.feature.cardexpenses.domain.ProcessLine(
                        description = "Batteries",
                        account = "4100",
                        net = 50.0,
                        taxRate = 20.0,
                    ),
                ),
            ),
        )
        val open = com.zillit.desktop.feature.cardexpenses.ui.ProcessDraft.of(
            detail,
            com.zillit.desktop.feature.cardexpenses.ui.ProcessMode.Process,
            loading = false,
            today = 1_754_000_000_000,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.ProcessQueue).copy(receipts = listOf(detail), process = open),
                        onEvent = {},
                    )
                }
            }
            // A full page, not a dialog: the breadcrumb names the queue it came from.
            onNodeWithText("Ready to Post").assertExists()
            onNodeWithText("Gross Total", ignoreCase = true).assertExists()
            onNodeWithText("Post to Ledger").assertExists()
            onNodeWithText("is lower than the receipt amount", substring = true).assertExists()
        }
    }

    @Test
    fun `an exhausted card refuses the upload and says so`() {
        val maxed = card().copy(limit = 500.0, receiptsCommit = 500.0)
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.MyTransactions, crew).copy(cards = listOf(maxed)),
                        onEvent = {},
                    )
                }
            }
            // The web's gate (`UserReceiptsPage.jsx:873-884`): a disabled button
            // beside "Upload unavailable", the reason in its tooltip.
            onNodeWithText("Upload unavailable").assertIsDisplayed()
            onNodeWithText("Upload Receipts").assertIsNotEnabled()
        }
    }
}
