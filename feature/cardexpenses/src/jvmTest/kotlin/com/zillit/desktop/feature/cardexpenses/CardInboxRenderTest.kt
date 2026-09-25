package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CrewSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ImportedRow
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.feature.cardexpenses.domain.MatchCandidate
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetailApproval
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetailLine
import com.zillit.desktop.feature.cardexpenses.domain.StatementCurrencyOptions
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportInfo
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportResult
import com.zillit.desktop.feature.cardexpenses.domain.StatementSummary
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesScreen
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ImportState
import com.zillit.desktop.feature.cardexpenses.ui.InboxState
import com.zillit.desktop.feature.cardexpenses.ui.LedgerState
import com.zillit.desktop.feature.cardexpenses.ui.ManualMatchState
import com.zillit.desktop.feature.cardexpenses.ui.ReceiptDetailState
import kotlin.test.Test
import kotlin.test.assertTrue

/** The reconciliation pages composed with their dialogs open, the way an accountant reaches them. */
@OptIn(ExperimentalTestApi::class)
class CardInboxRenderTest {

    @Test
    fun `the inbox draws its four sections, the banner and the row menu`() = screen(
        state(CardDestination.ReceiptInbox),
    ) {
        exists("System Matched — Confirm & Attach")
        exists("No personal expense receipts flagged.")
        exists("Action needed")
        exists("Match Suggested")
        exists("Re-run Match")
        exists("Linked Txn:")
        exists("Possible Duplicate")
    }

    @Test
    fun `the receipt detail shows the inbox badge, its lines and its approvals`() = screen(
        state(CardDestination.ReceiptInbox).let { base ->
            base.copy(
                inbox = InboxState(
                    detail = ReceiptDetailState(
                        receiptId = "r1",
                        loading = false,
                        detail = ReceiptDetail(
                            receipt = receipt("r1", MatchStatus.Suggested),
                            lines = listOf(ReceiptDetailLine("4100", "Batteries", 10.0, 2.0, listOf("ASSET"))),
                            approvals = listOf(ReceiptDetailApproval("u2", 0, override = true)),
                        ),
                    ),
                ),
            )
        },
    ) {
        exists("Receipt Details")
        exists("Override")
        exists("ASSET")
        exists("No receipt uploaded")
    }

    @Test
    fun `manual match lists the candidates and counts them`() = screen(
        state(CardDestination.ReceiptInbox).copy(
            inbox = InboxState(
                manualMatch = ManualMatchState(
                    receipt = receipt("r1", MatchStatus.Unmatched),
                    loading = false,
                    candidates = listOf(MatchCandidate("t1", "TESCO", null, "4821", 9.99, "GBP", 63)),
                    selectedId = "t1",
                ),
            ),
        ),
    ) {
        exists("TESCO")
        exists("63%")
        exists("1 candidate found")
        exists("Confirm Match")
    }

    @Test
    fun `an imported statement shows its tiles, rows and the submit bar`() = screen(
        state(CardDestination.ImportStatement).copy(
            inbox = InboxState(
                import = ImportState(
                    currenciesLoading = false,
                    result = StatementImportResult(
                        info = StatementImportInfo("i1", "march.csv", "Barclaycard", null, null, "20-00-00", "GBP"),
                        summary = StatementSummary(totalRows = 2, newCount = 1, duplicateCount = 1),
                        rowsProcessed = 1,
                        rows = listOf(
                            ImportedRow("t1", 1, null, "TESCO", "u2", "4821", 12.5, null, ImportedRow.NEW),
                            ImportedRow("t2", 2, null, "SHELL", null, null, 40.0, "EUR", "duplicate"),
                        ),
                    ),
                    selected = setOf("t1"),
                ),
            ),
        ),
    ) {
        exists("1 transaction imported successfully")
        exists("Total Rows", ignoreCase = true)
        exists("Sort Code", ignoreCase = true)
        exists("Submit 1 to Crew Portals")
        exists("FX")
    }

    @Test
    fun `the import dialog asks for the currency only when there is a choice`() {
        val pending = ImportState(
            pendingFile = StatementFile("march.csv", ByteArray(0)),
            currenciesLoading = false,
            currencies = StatementCurrencyOptions(listOf("GBP", "EUR"), complete = true),
        )
        screen(state(CardDestination.ImportStatement).copy(inbox = InboxState(import = pending))) {
            exists("Statement file")
            exists("Select currency…")
        }
        screen(
            state(CardDestination.ImportStatement).copy(
                inbox = InboxState(import = pending.copy(currencies = StatementCurrencyOptions(emptyList(), true))),
            ),
        ) {
            onAllNodesWithText("Select currency…").assertCountEquals(0)
        }
    }

    @Test
    fun `a submitted statement names who was told`() = screen(
        state(CardDestination.ImportStatement).copy(
            inbox = InboxState(
                import = ImportState(
                    currenciesLoading = false,
                    result = StatementImportResult(
                        StatementImportInfo(null, "march.csv", null, null, null, null, "GBP"),
                        StatementSummary(),
                        rowsProcessed = null,
                        rows = emptyList(),
                    ),
                    submission = CrewSubmission(1, 0, 12.5, listOf("u2" to 1)),
                ),
            ),
        ),
    ) {
        exists("Submitted to Crew Portals")
        exists("Notified: Ada Lovelace (1 txn)")
        exists("No rows extracted from file.")
    }

    @Test
    fun `deleting a transaction asks in the web's words`() = screen(
        state(CardDestination.AllTransactions).copy(
            inbox = InboxState(ledger = LedgerState(deleteTarget = transaction("t1"))),
        ),
    ) {
        exists("Delete Transaction?")
        exists(
            "Delete this transaction from TESCO? Any matched receipt is returned to the inbox, not deleted. " +
                "This action cannot be undone.",
        )
    }

    @Test
    fun `a ticked transaction raises the selection bar`() = screen(
        state(CardDestination.AllTransactions).copy(selection = setOf("t1")),
    ) {
        exists("1 transaction selected")
        exists("Delete selected")
    }

    // -- harness -------------------------------------------------------------------

    /** At least one node says [text] — a page behind a dialog may say it too. */
    private fun ComposeUiTest.exists(text: String, ignoreCase: Boolean = false) {
        assertTrue(onAllNodesWithText(text, ignoreCase = ignoreCase).fetchSemanticsNodes().isNotEmpty(), text)
    }

    private fun screen(state: CardUiState, assertions: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                CardExpensesScreen(state = state, onEvent = {})
            }
        }
        assertions()
    }

    private val accountant = CardViewer(
        userId = "u1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
    )

    private fun state(destination: CardDestination) = CardUiState(
        viewer = accountant,
        destination = destination,
        people = listOf(CardPerson(id = "u2", name = "Ada Lovelace", designation = "Gaffer")),
        receipts = listOf(
            receipt("r1", MatchStatus.Suggested).copy(inboxSection = InboxSection.SystemMatched),
            receipt("r2", MatchStatus.Unmatched).copy(
                transactionId = null,
                inboxSection = InboxSection.NoMatch,
                duplicateScore = 60,
            ),
        ),
        transactions = listOf(transaction("t1"), transaction("t2").copy(status = CardWorkflowStatus.Posted)),
    )

    private fun receipt(id: String, match: MatchStatus) = CardReceipt(
        id = id,
        cardId = "c1",
        holderId = "u2",
        holderName = "",
        description = "Batteries",
        merchant = null,
        amount = 12.0,
        currency = "GBP",
        date = 1_754_000_000_000,
        status = CardWorkflowStatus.PendingReceipt,
        matchStatus = match,
        transactionId = "t1",
        transactionMerchant = "TESCO",
        transactionAmount = 12.0,
        transactionDate = 1_754_000_000_000,
        transactionCardLastFour = "4821",
        nominalCode = "4100",
        codeDescription = null,
        episode = null,
        attachmentKey = null,
        urgent = false,
        matchScore = 82,
        duplicateScore = null,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = 1_754_000_000_000,
    )

    private fun transaction(id: String) = CardTransaction(
        id = id,
        cardId = "c1",
        cardLastFour = "4821",
        holderId = "u2",
        holderName = "",
        merchant = "TESCO",
        description = null,
        amount = 12.0,
        currency = "GBP",
        date = 1_754_000_000_000,
        status = CardWorkflowStatus.New,
        nominalCode = null,
        codeDescription = null,
        episode = null,
        vatAmount = 0.0,
        matchStatus = MatchStatus.Unmatched,
        receiptId = null,
        personal = false,
    )
}
