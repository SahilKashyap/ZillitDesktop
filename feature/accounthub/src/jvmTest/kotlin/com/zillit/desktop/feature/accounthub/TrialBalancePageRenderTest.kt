package com.zillit.desktop.feature.accounthub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.PeriodCloseState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.TrialBalanceState
import com.zillit.desktop.feature.accounthub.ui.pages.TrialBalancePage
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trial balance page, composed in each state it can be in.
 *
 * Rendered on its own rather than inside the console: the sidebar carries a
 * "Trial Balance" row of its own, and a page title that collides with it
 * proves nothing.
 */
@OptIn(ExperimentalTestApi::class)
class TrialBalancePageRenderTest {

    private val ledger = TrialBalance(
        listOf(
            TrialBalanceRow("1000", "Bank — Current Account", "asset", debit = 1_500.0, ending = 1_500.0),
            TrialBalanceRow("2100", "Accruals", "liability", credit = 1_500.0, ending = -1_500.0),
        ),
    )

    private fun hub(trial: TrialBalanceState) = AccountHubUiState(
        trialBalance = trial,
        periodClose = PeriodCloseState(lock = PeriodLock(lockedThrough = "2026-09-06")),
        setup = SetupState(
            companies = SectionEdit(listOf(Company(id = "co-1", name = "Zillit Films Ltd"))),
            currencies = SectionEdit(CurrencySettings(listOf(ProjectCurrency("GBP", "Pound Sterling", "£")), "GBP")),
        ),
    )

    private val opened = TrialBalanceState(
        today = "2026-09-12",
        fromText = "2026-01-01",
        toText = "2026-09-12",
        currency = "GBP",
    )

    /** Rows for exactly the filters on screen — nothing to refresh. */
    private fun ran(trial: TrialBalanceState = opened, report: TrialBalance = ledger) =
        trial.copy(report = report, applied = hub(trial).trialBalanceDraft)

    private fun ComposeUiTest.page(
        trial: TrialBalanceState,
        onEvent: (AccountHubEvent) -> Unit = {},
        canExport: Boolean = false,
        dark: Boolean = false,
    ) = setContent {
        ZillitTheme(darkTheme = dark) { TrialBalancePage(hub(trial), onEvent = onEvent, canExport = canExport) }
    }

    private fun ComposeUiTest.count(text: String): Int = onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `a balanced ledger shows its groups, subtotals, figures and the pinned verdict`() = runComposeUiTest {
        page(ran(), canExport = true)

        onNodeWithText("ASSET").assertExists()
        onNodeWithText("LIABILITY").assertExists()
        onNodeWithText("Bank — Current Account").assertExists()
        onNodeWithText("SUBTOTAL — ASSET").assertExists()
        assertEquals(2, count("(£1,500.00)"), "the liability's balance and its subtotal, bracketed")
        assertEquals(0, count("-1,500.00"), "never a bare minus sign")
        onNodeWithText("TRIAL BALANCE TOTAL").assertExists()
        onNodeWithText("BALANCED").assertExists()
        onNodeWithText("2 accounts").assertExists()
        onNodeWithText("6 Sep 2026 – 12 Sep 2026").assertExists()
        assertEquals(0, count("Refresh"), "nothing differs from what the rows answer")
    }

    /** Drawn in the web's order, not merely sorted in the model: Expense heads the ledger. */
    @Test
    fun `expense is drawn above the other groups, as on the web`() = runComposeUiTest {
        val withExpense = TrialBalance(
            ledger.rows + TrialBalanceRow("5000", "Camera Hire", "expense", debit = 900.0, ending = 900.0),
        )
        page(ran(report = withExpense))

        fun top(heading: String) = onNodeWithText(heading).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("EXPENSE") < top("ASSET"), "Expense sits above Asset")
        assertTrue(top("ASSET") < top("LIABILITY"), "the rest stay alphabetical")
    }

    @Test
    fun `an unbalanced ledger says so`() = runComposeUiTest {
        page(ran(report = TrialBalance(ledger.rows.take(1))))

        onNodeWithText("UNBALANCED").assertExists()
    }

    @Test
    fun `while loading there is no total and no count`() = runComposeUiTest {
        page(opened.copy(loading = true))

        onNodeWithText("CODE").assertExists()
        assertEquals(0, count("TRIAL BALANCE TOTAL"))
        assertEquals(0, count("No account balances"))
    }

    @Test
    fun `a failed run explains itself and can be tried again`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        page(ran().copy(failed = true, errorMessage = "Service unavailable"), { events += it }, canExport = true)

        onNodeWithText("Couldn't load trial balance").assertExists()
        onNodeWithText("Adjust the filters and Refresh.").assertExists()
        onNodeWithText("Service unavailable").assertExists()
        assertEquals(0, count("TRIAL BALANCE TOTAL"), "no verdict over rows that did not load")
        onNode(hasText("Export") and hasClickAction()).assertIsNotEnabled()

        onNodeWithText("Try again").performClick()
        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.RefreshTrialBalance), events)
    }

    @Test
    fun `an empty answer says there is nothing for these filters`() = runComposeUiTest {
        page(ran(report = TrialBalance()))

        onNodeWithText("No account balances").assertExists()
        onNodeWithText("No data for these filters.").assertExists()
    }

    @Test
    fun `a changed filter offers refresh, which runs the report`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        page(ran().copy(accountFromText = "1000"), { events += it })

        onNodeWithText("Refresh").performClick()
        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.RefreshTrialBalance), events)
    }

    @Test
    fun `date range shows the pickers and says when the range runs backwards`() = runComposeUiTest {
        page(ran().copy(periodMode = PeriodMode.Custom, fromText = "2026-09-30", toText = "2026-09-01"))

        onNodeWithText("2026-09-30").assertExists()
        onNodeWithText("2026-09-01").assertExists()
        onNodeWithText("Start is after end").assertExists()
    }

    @Test
    fun `the back arrow and the reports crumb both return to the hub`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        page(ran(), { events += it })

        onNodeWithContentDescription("Back to Account Hub").performClick()
        onNodeWithText("REPORTS").performClick()

        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.BackToHub, AccountHubEvent.BackToHub), events)
    }

    @Test
    fun `the period toggle and the zero-accounts box send their changes`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        page(ran(), { events += it })

        onNodeWithText("Date Range").performClick()
        onNodeWithText("Include Zero Accounts").performClick()

        assertEquals(
            listOf(
                AccountHubEvent.SetTrialBalancePeriodMode(PeriodMode.Custom),
                AccountHubEvent.SetTrialBalanceZeroAccounts(true),
            ),
            events,
        )
    }

    /** The web's export menu: a panel of three cards under the button, each naming what the file is for. */
    @Test
    fun `export opens the menu and a card asks for that format`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        setContent {
            var trial by remember { mutableStateOf(ran()) }
            ZillitTheme(darkTheme = false) {
                TrialBalancePage(
                    hub(trial),
                    onEvent = { event ->
                        events += event
                        if (event is AccountHubEvent.ToggleTrialBalanceExport) {
                            trial = trial.copy(exportOpen = event.open)
                        }
                    },
                    canExport = true,
                )
            }
        }

        onNodeWithText("Export").performClick()
        onNodeWithText("DOWNLOAD AS").assertExists()
        onNodeWithText("Editable spreadsheet with live data").assertExists()
        onNodeWithText("Export PDF").performClick()

        assertTrue(AccountHubEvent.ExportTrialBalance(ExportFormat.Pdf) in events)
    }

    @Test
    fun `the page composes in the dark theme`() = runComposeUiTest {
        page(ran(), canExport = true, dark = true)

        onNodeWithText("TRIAL BALANCE TOTAL").assertExists()
    }
}
