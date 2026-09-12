package com.zillit.desktop.feature.invoices

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.invoices.domain.ActivityRow
import com.zillit.desktop.feature.invoices.domain.CostReportImpact
import com.zillit.desktop.feature.invoices.domain.CostReportRow
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.InvoiceOverview
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.OverviewStats
import com.zillit.desktop.feature.invoices.domain.PendingAction
import com.zillit.desktop.feature.invoices.domain.PipelineStage
import com.zillit.desktop.feature.invoices.domain.VendorAlert
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesScreen
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import kotlin.test.Test

/** Composes the real invoices screen on each department tab, in both themes. */
@OptIn(ExperimentalTestApi::class)
class InvoicesScreenRenderTest {

    private companion object {
        const val NOW = 1_786_950_000_000
    }

    @Test
    fun `each department tab composes in both themes`() {
        DepartmentTab.entries.forEach { tab ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            InvoicesScreen(
                                state = InvoicesUiState(
                                    viewer = InvoiceViewer(canView = true, ready = true),
                                    departmentTab = tab,
                                ),
                                onEvent = {},
                                nowMs = NOW,
                            )
                        }
                    }
                }
            }
        }
    }

    private val accountant = InvoiceViewer(
        canView = true,
        ready = true,
        departmentIdentifier = "department_accounts",
        designationIdentifier = "production_accountant",
    )

    /** A dashboard with something in every panel. */
    private fun dashboard() = InvoicesUiState(
            viewer = accountant,
            page = AccountantPage.Overview,
            overview = InvoiceOverview(
                stats = OverviewStats(
                    totalInvoices = 42,
                    awaitingMatch = 3,
                    awaitingMatchAmount = "£1,200.00",
                    inApproval = 5,
                    overdueCount = 2,
                    overdueAmount = "£800.00",
                    readyToPay = 7,
                    readyToPayAmount = "£9,000.00",
                    dueThisWeek = "£2,500.00",
                    dueThisWeekCount = 4,
                    totalAP = "£18,000.00",
                    vendorCount = 11,
                ),
                pipeline = listOf(
                    PipelineStage("inbox", "Inbox", 3, "amber"),
                    PipelineStage("approval", "Approval", 5, "blue"),
                    PipelineStage("paid", "Paid", 0, "green"),
                ),
                costReport = CostReportImpact(
                    pendingCount = 8,
                    pendingNet = "£4,000.00",
                    overBudgetDepts = 1,
                    underBudgetDepts = 6,
                    rows = listOf(CostReportRow("d1", "£5,000", "£900", "£5,900", 18.0)),
                ),
                vendorAlerts = listOf(
                    VendorAlert("danger", "Panavision on stop", "3 overdue", "today", "/invoices/register", "Review"),
                ),
                pendingActions = listOf(PendingAction(3, "Invoices to match", "amber", "/invoices/inbox")),
                totalActions = 3,
                recentActivity = listOf(ActivityRow("Paid", "green", "Movietech", "INV-9", "Grip", "£400.00")),
            ),
            duplicates = listOf(
                DuplicateFlag(
                    id = "f1",
                    status = DuplicateFlag.PENDING,
                    similarityScore = 92,
                    invoiceRef = "INV-12",
                    vendorName = "Panavision",
                    invoiceAmount = 1_200.50,
                    duplicateRef = "INV-9",
                    duplicateStatus = "paid",
                    duplicateDateMs = null,
                    matchReasons = listOf("same vendor", "same amount"),
                ),
            ),
        )

    /** The sidebar is the web's, and the dashboard behind it composes with real figures. */
    @Test
    fun `the sidebar and the overview compose`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    InvoicesScreen(state = dashboard(), onEvent = {}, nowMs = NOW)
                }
            }
            // Twice on purpose: the sidebar row and the page's own header,
            // which the web prints for every screen.
            onAllNodesWithText("Overview").assertCountEquals(2)
            onNodeWithText("Vendor invoice lifecycle — from receipt to payment.").assertExists()
            onNodeWithText("Invoices Pre-approval").assertExists()
            onNodeWithText("Payment Runs").assertExists()
            onNodeWithText("Settings").assertExists()
            onNodeWithText("Invoice Pipeline").assertExists()
            onNodeWithText("Possible Duplicates").assertExists()
            onNodeWithText("3 invoices need pre-approval").assertExists()
        }
    }

    /** Every page this build carries composes, for a senior accountant, in both themes. */
    @Test
    fun `every built page composes`() {
        AccountantPage.entries.forEach { page ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            InvoicesScreen(
                                state = dashboard().copy(page = page),
                                onEvent = {},
                                nowMs = NOW,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Every row in the sidebar now opens a page — Settings included. */
    @Test
    fun `the settings page composes for a senior`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    InvoicesScreen(
                        state = InvoicesUiState(viewer = accountant, page = AccountantPage.Settings),
                        onEvent = {},
                        nowMs = NOW,
                    )
                }
            }
            onNodeWithText("Team & Posting Rights").assertExists()
            onNodeWithText("Alert Preferences").assertExists()
            onNodeWithText("Run Authorization").assertExists()
            onNodeWithText("Auto-Assignment Rules").assertExists()
        }
    }

    /** An accountant sees a different screen entirely. */
    @Test
    fun `the accountant view composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    InvoicesScreen(
                        state = InvoicesUiState(
                            // An accountant is one by department, not by flag.
                            viewer = InvoiceViewer(
                                canView = true,
                                ready = true,
                                departmentIdentifier = "department_accounts",
                            ),
                        ),
                        onEvent = {},
                        nowMs = NOW,
                    )
                }
            }
        }
    }
}
