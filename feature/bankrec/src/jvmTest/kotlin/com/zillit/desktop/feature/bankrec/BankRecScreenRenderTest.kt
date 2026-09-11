package com.zillit.desktop.feature.bankrec

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecScreen
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.ExceptionsState
import com.zillit.desktop.feature.bankrec.ui.FraudState
import com.zillit.desktop.feature.bankrec.ui.FxState
import com.zillit.desktop.feature.bankrec.ui.ImportState
import com.zillit.desktop.feature.bankrec.ui.PendingMatch
import com.zillit.desktop.feature.bankrec.ui.PortalState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceState
import kotlin.test.Test

/**
 * Composes every tab, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; every tab because a layout that throws — a
 * full-width child inside a row, a list inside a scrolling column — takes the
 * window down rather than degrading.
 */
@OptIn(ExperimentalTestApi::class)
class BankRecScreenRenderTest {

    private val account = BankAccountRef(id = "b1", name = "Barclays", currencyCode = "GBP")

    private val period = BankPeriod(
        id = "p1",
        periodMillis = 1_743_465_600_000,
        bankAccountId = "b1",
        status = PeriodStatus.InProgress,
        totalTxns = 3,
        matchedCount = 1,
        unmatchedCount = 1,
        fraudCount = 1,
        closingBank = 12_500.0,
        closingZillit = 12_000.0,
        difference = 500.0,
    )

    private val txn = BankTransaction(
        id = "t1",
        periodId = "p1",
        vendorName = "Panavision",
        reference = "BACS · INV-88",
        debit = 1200.0,
        currency = "GBP",
        status = TxnStatus.Unmatched,
    )

    private val entry = LedgerEntry(
        id = "led-1",
        entityId = "inv-1",
        title = "Panavision",
        reference = "INV-88",
        amount = -1200.0,
    )

    private fun state(tab: BankTab, extra: BankRecUiState.() -> BankRecUiState = { this }) =
        BankRecUiState(
            tab = tab,
            periods = listOf(period),
            bankAccounts = listOf(account),
            rates = ProjectRates(defaultCode = "GBP"),
        ).extra()

    private fun render(
        state: BankRecUiState,
        dark: Boolean = false,
        body: suspend ComposeUiTest.() -> Unit,
    ) {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = dark) { BankRecScreen(state) {} } }
            body()
        }
    }

    @Test
    fun `every tab composes in both themes`() {
        listOf(false, true).forEach { dark ->
            BankTab.entries.forEach { tab ->
                render(state(tab), dark) {
                    onNodeWithText("Bank Reconciliation").assertExists()
                }
            }
        }
    }

    @Test
    fun `the overview shows the five figures and the fraud banner`() {
        render(state(BankTab.Overview)) {
            onNodeWithText("BANK BALANCE").assertExists()
            onNodeWithText("DIFFERENCE").assertExists()
            onNodeWithText("1 payment(s) flagged for review", substring = true).assertExists()
            // The KPI tile, the progress card and the history row all name it.
            onAllNodesWithText("Apr 2025").assertCountEquals(3)
        }
    }

    /**
     * A converted balance says so, and an unconvertible one says that instead.
     *
     * A figure quietly converted at a rate nobody set is the one number on
     * this page nobody could reconcile afterwards.
     */
    @Test
    fun `a foreign account with no rate is not converted silently`() {
        val euro = state(BankTab.Overview) {
            copy(bankAccounts = listOf(account.copy(currencyCode = "EUR")))
        }

        render(euro) {
            onNodeWithText("EUR — no exchange rate set").assertExists()
            onNodeWithText("Not comparable").assertExists()
        }
    }

    @Test
    fun `a converted balance shows the statement's own figure beside it`() {
        val euro = state(BankTab.Overview) {
            copy(
                bankAccounts = listOf(account.copy(currencyCode = "EUR")),
                rates = ProjectRates(defaultCode = "GBP", rates = mapOf("EUR" to 1.25)),
            )
        }

        render(euro) {
            onNodeWithText("converted", substring = true).assertExists()
        }
    }

    @Test
    fun `the workspace shows both sides and the filters`() {
        val workspace = state(BankTab.Workspace) {
            copy(
                workspace = WorkspaceState(
                    periodId = "p1",
                    transactions = listOf(txn),
                    ledger = listOf(entry),
                ),
            )
        }

        render(workspace) {
            // The section label draws in capitals.
            onNodeWithText("BANK STATEMENT · 1 LINE(S)").assertExists()
            onNodeWithText("ZILLIT LEDGER · 1 UNMATCHED").assertExists()
            onNodeWithText("Match by hand").assertExists()
            onNodeWithText("Sign off period").assertExists()
        }
    }

    /** The confirmation names both sides, which is the whole point of it. */
    @Test
    fun `the match confirmation names both records`() {
        val pending = state(BankTab.Workspace) {
            copy(
                workspace = WorkspaceState(
                    periodId = "p1",
                    transactions = listOf(txn),
                    ledger = listOf(entry),
                    pending = PendingMatch(txn, entry, wasSuggested = true),
                ),
            )
        }

        render(pending) {
            onNodeWithText("Reconcile these two?").assertExists()
            onNodeWithText("Bank").assertExists()
            onNodeWithText("Ledger").assertExists()
            onNodeWithText("suggested by the matching rules", substring = true).assertExists()
        }
    }

    /** Signing off over unreconciled lines says so and demands a reason. */
    @Test
    fun `the sign-off dialog names what is outstanding`() {
        val signing = state(BankTab.Workspace) {
            copy(
                workspace = WorkspaceState(
                    periodId = "p1",
                    transactions = listOf(txn),
                    confirmingSignOff = true,
                ),
            )
        }

        render(signing) {
            onNodeWithText("Sign off this period?").assertExists()
            onNodeWithText("1 line(s) are still unreconciled", substring = true).assertExists()
            onNodeWithText("Sign-off note (required)").assertExists()
        }
    }

    @Test
    fun `the exceptions tab splits outstanding from settled`() {
        val exceptions = state(BankTab.Exceptions) {
            copy(
                exceptions = ExceptionsState(
                    rows = listOf(
                        BankException(
                            id = "e1",
                            periodId = "p1",
                            type = ExceptionType.BankCharge,
                            status = ExceptionStatus.Open,
                            title = "Monthly service charge",
                            transaction = txn.copy(debit = 35.0),
                        ),
                        BankException(
                            id = "e2",
                            periodId = "p1",
                            type = ExceptionType.Interest,
                            status = ExceptionStatus.Resolved,
                            title = "Interest",
                        ),
                    ),
                ),
            )
        }

        render(exceptions) {
            onNodeWithText("Outstanding").assertExists()
            onNodeWithText("Settled").assertExists()
            onNodeWithText("Post to ledger").assertExists()
            // The title and the exception card both carry it.
            onAllNodesWithText("Monthly service charge", substring = true).assertCountEquals(2)
        }
    }

    @Test
    fun `a fraud alert shows its risk, its bank details and both actions`() {
        val fraud = state(BankTab.FraudAlerts) {
            copy(
                fraud = FraudState(
                    alerts = listOf(
                        FraudAlert(
                            id = "a1",
                            periodId = "p1",
                            alertType = FraudType.MandateFraud,
                            status = FraudStatus.Active,
                            title = "Bank details changed",
                            riskScore = 88,
                            vendorName = "Panavision",
                            vendorSortCode = "20-48-91",
                            transaction = txn,
                        ),
                    ),
                ),
            )
        }

        render(fraud) {
            onNodeWithText("Bank details changed").assertExists()
            onNodeWithText("Risk 88 · High risk").assertExists()
            onNodeWithText("Sort 20-48-91", substring = true).assertExists()
            onNodeWithText("Escalate").assertExists()
            onNodeWithText("Dismiss").assertExists()
        }
    }

    @Test
    fun `the FX tab shows the variance and offers to post it`() {
        val fx = state(BankTab.FxVariances) {
            copy(
                fx = FxState(
                    rows = listOf(
                        FxVariance(
                            id = "v1",
                            periodId = "p1",
                            invoiceCurrency = "USD",
                            foreignAmount = 1500.0,
                            budgetAmount = 1200.0,
                            paidAmount = 1240.0,
                            variance = -40.0,
                            vendorName = "Kodak",
                        ),
                    ),
                ),
            )
        }

        render(fx) {
            onNodeWithText("Kodak").assertExists()
            onNodeWithText("Post all unposted").assertExists()
            onAllNodesWithText("Post").assertCountEquals(1)
        }
    }

    @Test
    fun `the portal tab warns what a link with transaction detail shows`() {
        val portal = state(BankTab.GuarantorPortal) {
            copy(
                portal = PortalState(
                    links = listOf(
                        PortalLink(
                            id = "l1",
                            token = "abc",
                            recipientName = "James Whitford",
                            recipientEmail = "j@example.com",
                            periodId = "p1",
                        ),
                    ),
                    draft = PortalLinkDraft(
                        recipientName = "James Whitford",
                        recipientEmail = "j@example.com",
                        periodId = "p1",
                        permissions = setOf(
                            com.zillit.desktop.feature.bankrec.domain.PortalPermission.TransactionDetail,
                        ),
                    ),
                ),
            )
        }

        render(portal) {
            // The button behind the dialog, and the dialog's own title.
            onAllNodesWithText("Share a period").assertCountEquals(2)
            onNodeWithText("show individual payments", substring = true).assertExists()
            onNodeWithText("actually grants access", substring = true).assertExists()
        }
    }

    @Test
    fun `the rules tab shows both sections and a threshold only where there is one`() {
        render(state(BankTab.Settings)) {
            onNodeWithText("Matching rules").assertExists()
            onNodeWithText("Fraud detection").assertExists()
            onNodeWithText("Barclays").assertExists()
            // Two of the five checks carry an amount; the rest have no box.
            onAllNodesWithText("Threshold (GBP)").assertCountEquals(2)
        }
    }

    @Test
    fun `deleting a period says what goes with it`() {
        render(state(BankTab.History) { copy(deleting = listOf(period)) }) {
            onNodeWithText("Delete this period?").assertExists()
            onNodeWithText("Everything the reconciliation produced", substring = true).assertExists()
        }
    }

    @Test
    fun `the import dialog explains what a multi-month statement does`() {
        render(state(BankTab.Overview) { copy(import = ImportState(open = true)) }) {
            onNodeWithText("Import a statement").assertExists()
            onNodeWithText("opens a period for each", substring = true).assertExists()
        }
    }

    @Test
    fun `a production with no periods says how to start one`() {
        render(BankRecUiState(tab = BankTab.Overview)) {
            onNodeWithText("No period open").assertExists()
        }
    }
}
