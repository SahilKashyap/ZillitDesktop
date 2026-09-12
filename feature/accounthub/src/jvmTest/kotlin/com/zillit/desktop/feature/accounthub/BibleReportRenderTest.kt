package com.zillit.desktop.feature.accounthub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.BibleFilters
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.LedgerTransaction
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorPhone
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BibleReportState
import com.zillit.desktop.feature.accounthub.ui.BibleRun
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.pages.BibleReportPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the Bible Report in each of its states.
 *
 * The page holds a lazy list inside a bounded card and a lazy list inside a
 * popup — the two arrangements that throw rather than degrade when their
 * height is wrong — so each is composed, and the vendor picker is *opened*.
 */
@OptIn(ExperimentalTestApi::class)
class BibleReportRenderTest {

    private val accountant = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER),
    )

    private val report = BibleReport(
        accounts = listOf(
            BibleAccount(
                code = "7100",
                name = "Camera hire",
                total = -200.5,
                transactions = listOf(
                    LedgerTransaction(
                        "INV", 1_757_000_000_000, "INV-9", "PO-3", "Panavision", "Lens set", "USD", -250.5,
                    ),
                    LedgerTransaction("CRED", 1_757_000_000_000, "CN-2", "", "Panavision", "Credit", "GBP", 50.0),
                ),
            ),
            BibleAccount(
                code = BibleAccount.UNCODED,
                name = BibleAccount.UNCODED,
                total = 50.0,
                transactions = listOf(LedgerTransaction("CASH", null, "", "", "Runner", "Taxi", "", 50.0)),
            ),
        ),
        grandTotal = -150.5,
        currencyCode = "GBP",
        generatedAtMillis = 1_757_700_000_000,
        errors = mapOf("payroll" to "timed out"),
    )

    private fun state(bible: BibleReportState) = AccountHubUiState(
        viewer = accountant,
        sections = HubNavigation.visibleTo(accountant),
        area = HubArea.BibleReport,
        projectName = "Zillit Films",
        setup = SetupState(
            currencies = SectionEdit(
                CurrencySettings(
                    currencies = listOf(
                        ProjectCurrency("GBP", "Pound Sterling", "£"),
                        ProjectCurrency("USD", "US Dollar", "$"),
                    ),
                    defaultCode = "GBP",
                ),
            ),
        ),
        bible = bible,
    )

    private val prepared = BibleReportState(
        filters = BibleFilters(fromDate = "2026-01-01", toDate = "2026-09-12", currency = "GBP"),
        prepared = true,
        today = "2026-09-12",
        vendors = listOf(
            Vendor(id = "v1", name = "Panavision Ltd", contactPerson = "Sam Hart", verified = true),
            Vendor(id = "v2", name = "Arri Rental", phone = VendorPhone("+44", "2079460000")),
        ),
    )

    private val ran = prepared.copy(
        report = report,
        run = BibleRun(BibleQuery(periodStartMillis = 1, periodEndMillis = 2), "Till 12 Sep 2026"),
    )

    private fun compose(bible: BibleReportState, dark: Boolean = false, onEvent: (AccountHubEvent) -> Unit = {}) =
        @androidx.compose.runtime.Composable {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                Box(Modifier.size(WIDTH, HEIGHT)) {
                    BibleReportPage(state = state(bible), onEvent = onEvent, canExport = true)
                }
            }
        }

    @Test
    fun `the page opens on the card that asks for a run`() = runComposeUiTest {
        val events = mutableListOf<AccountHubEvent>()
        setContent(compose(prepared) { events += it })

        onNodeWithText("Set filters and run the report").assertIsDisplayed()
        onNodeWithText("Till 12 Sep 2026").assertIsDisplayed()
        onNodeWithText("Run Report").performClick()
        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.RunBibleReport), events)
    }

    @Test
    fun `a report lists its accounts, their lines and the grand total`() = runComposeUiTest {
        setContent(compose(ran))

        onNodeWithText("7100").assertIsDisplayed()
        onNodeWithText("Camera hire").assertIsDisplayed()
        onNodeWithText("Uncoded").assertIsDisplayed()
        onNodeWithText("INV").assertIsDisplayed()
        onNodeWithText("CRED").assertIsDisplayed()
        onNodeWithText("(£250.50)").assertIsDisplayed()
        onNodeWithText("TOTAL 7100 · CAMERA HIRE").assertIsDisplayed()
        onNodeWithText("GRAND TOTAL").assertIsDisplayed()
        onNodeWithText("(£150.50)").assertIsDisplayed()
        onNodeWithText("Some sources failed: Payroll: timed out").assertIsDisplayed()
        onNodeWithText("2 accounts · Generated", substring = true).assertIsDisplayed()
        // One line is its own total: the uncoded bucket gets no subtotal row.
        assertTrue(onAllNodesWithText("TOTAL UNCODED", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a folded account hides its lines and keeps its total`() = runComposeUiTest {
        setContent(compose(ran.copy(collapsed = setOf("7100"))))

        onNodeWithText("Camera hire").assertIsDisplayed()
        onNodeWithText("(£200.50)").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Lens set").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Runner").assertIsDisplayed()
        onNodeWithText("Collapse all").assertIsDisplayed()
    }

    @Test
    fun `a failed run says why above the previous figures`() = runComposeUiTest {
        setContent(compose(ran.copy(error = "Cost report service unavailable")))

        onNodeWithText("Cost report service unavailable").assertIsDisplayed()
        onNodeWithText("GRAND TOTAL").assertIsDisplayed()
    }

    @Test
    fun `a run that found nothing says so`() = runComposeUiTest {
        setContent(compose(ran.copy(report = BibleReport())))
        onNodeWithText("No transactions found for the selected filters.").assertIsDisplayed()
    }

    @Test
    fun `run waits for a date range that reads`() = runComposeUiTest {
        val halfTyped = prepared.copy(
            filters = prepared.filters.copy(periodMode = PeriodMode.Custom, toDate = "2026-09-1"),
        )
        setContent(compose(halfTyped))
        onNodeWithText("Run Report").assertIsNotEnabled()
    }

    /** Opened, not just composed: a lazy list measured for its intrinsic height inside a popup throws. */
    @Test
    fun `the vendor picker opens, lists rich rows and searches`() = runComposeUiTest {
        var picked: BibleFilters? = null
        setContent(compose(prepared) { if (it is AccountHubEvent.EditBibleFilters) picked = it.filters })

        onNodeWithText("All vendors").performClick()
        onNodeWithText("Panavision Ltd").assertIsDisplayed()
        onNodeWithText("VERIFIED").assertIsDisplayed()
        onAllNodesWithText("Sam Hart", substring = true).onFirst().assertIsDisplayed()
        onNodeWithText("Arri Rental").performClick()
        assertEquals("v2", picked?.vendorId)
    }

    @Test
    fun `a multi-select names one choice and counts several`() = runComposeUiTest {
        val one = prepared.copy(filters = prepared.filters.copy(accountTypes = listOf("expense")))
        setContent(compose(one))
        onNodeWithText("Expense").assertIsDisplayed()
    }

    @Test
    fun `several choices read as a count, and the list offers them all`() = runComposeUiTest {
        val several = prepared.copy(filters = prepared.filters.copy(sources = listOf("invoice", "payroll")))
        setContent(compose(several))
        onNodeWithText("2 selected").performClick()
        onNodeWithText("Manual Journal").assertIsDisplayed()
        onNodeWithText("Clear").assertIsDisplayed()
    }

    @Test
    fun `the search narrows a long list`() = runComposeUiTest {
        val many = prepared.copy(vendors = (1..30).map { Vendor(id = "v$it", name = "Vendor $it") })
        setContent(compose(many))
        onNodeWithText("All vendors").performClick()
        // The list's own field, focused on open so the reader can type straight away.
        onNode(hasSetTextAction() and isFocused()).performTextInput("29")
        onNodeWithText("Vendor 29").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Vendor 3").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the page composes in dark mode`() = runComposeUiTest {
        setContent(compose(ran, dark = true))
        onNodeWithText("GRAND TOTAL").assertIsDisplayed()
    }

    private companion object {
        val WIDTH = 1200.dp
        val HEIGHT = 820.dp
    }
}
