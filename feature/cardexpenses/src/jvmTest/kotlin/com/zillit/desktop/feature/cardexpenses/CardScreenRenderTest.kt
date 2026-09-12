package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
    private fun receiptQueueState(attachment: String?) =
        state(CardDestination.ReceiptInbox).let { base ->
            base.copy(
                receipts = listOf(receipt().copy(attachmentKey = attachment)),
                selectedReceiptId = "receipt-1",
            )
        }

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
                // The sidebar is the accountant frame; Overview is always in it.
                onNodeWithText("Overview").assertIsDisplayed()
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
                onNodeWithText("Overview").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the bulk screen says a blank override keeps each row's own coding`() {
        val item = com.zillit.desktop.feature.cardexpenses.domain.BulkItem(
            id = "bulk-1",
            holderId = "user-2",
            holderName = "Ada Lovelace",
            description = "Coffee run",
            merchant = "Cafe",
            amount = 18.40,
            currency = "GBP",
            date = 1_754_000_000_000,
            cardLastFour = "4821",
            nominalCode = "4100",
            codeDescription = null,
            episode = null,
            status = CardWorkflowStatus.ReadyToPost,
            assignedTo = null,
            urgent = false,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.BulkProcess).copy(
                            bulkItems = listOf(item),
                            selection = setOf("bulk-1"),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Leave a field blank to keep each row's own coding").assertExists()
            onNodeWithText("Post 1 item(s)").assertExists()
        }
    }

    @Test
    fun `a row assigned to someone else is named rather than silently unselectable`() {
        val theirs = com.zillit.desktop.feature.cardexpenses.domain.BulkItem(
            id = "bulk-2",
            holderId = "user-3",
            holderName = "Grace",
            description = "Parking",
            merchant = null,
            amount = 6.0,
            currency = "GBP",
            date = null,
            cardLastFour = null,
            nominalCode = null,
            codeDescription = null,
            episode = null,
            status = CardWorkflowStatus.ReadyToPost,
            assignedTo = "someone-else",
            urgent = false,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.BulkProcess).copy(bulkItems = listOf(theirs)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("1 row(s) are assigned to someone else and cannot be posted from here.")
                .assertIsDisplayed()
            onNodeWithText("Assigned elsewhere").assertExists()
        }
    }

    @Test
    fun `an unmatched statement row is called out as having nobody to ask`() {
        val orphan = com.zillit.desktop.feature.cardexpenses.domain.StatementRow(
            id = "row-1",
            merchant = "UNKNOWN MERCHANT",
            description = null,
            amount = 42.0,
            currency = "GBP",
            date = 1_754_000_000_000,
            cardLastFour = "4821",
            holderId = null,
            holderName = null,
            status = "new",
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.ImportStatement).copy(
                            imports = listOf(
                                com.zillit.desktop.feature.cardexpenses.domain.StatementImport(
                                    id = "imp-1",
                                    filename = "visa-august.csv",
                                    status = "completed",
                                    rowCount = 1,
                                    matchedCount = 0,
                                    importedAt = 1_754_000_000_000,
                                ),
                            ),
                            openImportId = "imp-1",
                            importRows = listOf(orphan),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(
                "1 row(s) could not be matched to a cardholder. " +
                    "They can be accepted into the ledger, but nobody can be asked for a receipt.",
            ).assertIsDisplayed()
            onNodeWithText("Unmatched").assertExists()
        }
    }

    @Test
    fun `the split editor shows what is left and refuses a save that does not add up`() {
        val open = com.zillit.desktop.feature.cardexpenses.ui.SplitDraft(
            receiptId = "receipt-1",
            receiptGross = 120.0,
            currency = "GBP",
            lines = listOf(
                com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine(
                    id = null,
                    description = "Batteries",
                    nominalCode = "4100",
                    net = 50.0,
                    taxAmount = 10.0,
                ),
            ),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.ProcessQueue).copy(splits = open),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("LEFT TO SPLIT").assertExists()
            onNodeWithText("Does not add up").assertExists()
        }
    }

    /**
     * The receipt is what the figures are checked against.
     *
     * The effect and the host's handler shipped with this module, but nothing
     * ever raised it — so the document was unreachable from the screen whose
     * whole job is comparing it with a statement line.
     */
    @Test
    fun `a receipt with a document offers to open it`() {
        val withDoc = receiptQueueState(attachment = "receipts/r1.jpg")

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(state = withDoc, onEvent = {})
                }
            }
            onNodeWithText("View receipt").assertExists()
        }
    }

    @Test
    fun `a pdf receipt is named as one`() {
        val withPdf = receiptQueueState(attachment = "receipts/r1.PDF")

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(state = withPdf, onEvent = {})
                }
            }
            onNodeWithText("Open receipt (PDF)").assertExists()
        }
    }

    @Test
    fun `a receipt still waiting for its document offers nothing to open`() {
        val none = receiptQueueState(attachment = null)

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(state = none, onEvent = {})
                }
            }
            onAllNodesWithText("View receipt").assertCountEquals(0)
            onAllNodesWithText("Open receipt (PDF)").assertCountEquals(0)
        }
    }

    @Test
    fun `a duplicate-flagged receipt shows the exception before the actions`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CardExpensesScreen(
                        state = state(CardDestination.ApprovalQueue),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Possible duplicate — 88% similar to another receipt.").assertIsDisplayed()
        }
    }

    @Test
    fun `an exhausted card refuses the upload and points at the top-up`() {
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
            // The notice itself, not the tab of the same name — the gate has to
            // say why it is refusing, not merely offer a link.
            onNodeWithText(
                "This card's limit is fully committed, so nothing further can be uploaded against it. " +
                    "Request a top-up first.",
            ).assertIsDisplayed()
        }
    }
}
