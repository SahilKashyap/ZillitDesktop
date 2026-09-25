package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUps
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.ReconItem
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.domain.TopUpHistoryEntry
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ExtensionState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import com.zillit.desktop.feature.cashexpenses.ui.FundsState
import com.zillit.desktop.feature.cashexpenses.ui.FundsUiState
import com.zillit.desktop.feature.cashexpenses.ui.PartialTopUpDraft
import kotlin.test.Test
import kotlin.test.assertTrue

/** Top-Ups, Cash Extension, Fund Requests and Cash Recon, composed as the web lays them out. */
@OptIn(ExperimentalTestApi::class)
class CashFundsRenderTest {

    private val adaId = "6a2beb3023a3156e75c3ef85"

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun state(destination: CashDestination, viewer: CashViewer = accountant) = CashUiState(
        viewer = viewer,
        destination = destination,
        pipeline = destination.expenseType,
        currencies = CashCurrencies(defaultCode = "GBP"),
        assignees = listOf(AssigneeOption(userId = adaId, fullName = "Ada Lovelace", designation = "Art Director")),
    )

    private fun topUp(id: String, status: String, note: String? = null) = CashTopUp(
        id = id, userId = adaId, holderName = "", amount = 150.0, currency = "GBP", status = status, note = note,
        floatRequestNumber = "PC-001", floatIssued = 1_000.0, floatBalance = 400.0, floatRequestedAmount = 1_000.0,
        createdAt = 1_754_000_000_000,
        history = listOf(TopUpHistoryEntry(action = "requested", actionBy = adaId, reason = "Fuel run")),
    )

    private fun float(id: String, number: String) = CashFloat(
        id = id, requestNumber = number, userId = adaId, holderName = "", departmentId = null,
        status = FloatStatus.Active, currency = "GBP", requestedAmount = 500.0, issuedAmount = 500.0, balance = 300.0,
        receiptsAmount = 0.0, receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null,
        duration = null, durationType = null, purpose = "Set dressing", createdAt = null,
    )

    @Test
    fun `the top-up inbox shows pending cards with their actions and settled ones with their details`() {
        var events = listOf<CashEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.TopUps).copy(
                            topUps = listOf(
                                topUp("t1", CashTopUps.PENDING, note = "Van hire"),
                                topUp("t2", CashTopUps.COMPLETED),
                            ),
                        ),
                        onEvent = { events = events + it },
                    )
                }
            }
            onNodeWithText("PENDING TOP-UPS").assertExists()
            onNodeWithText("Mark topped up").assertExists()
            onNodeWithText("Partial top-up").assertExists()
            onNodeWithText("Note: Van hire").assertExists()
            onNodeWithText("RECENTLY SETTLED").assertExists()
            onNodeWithText("Topped up").assertExists()
            assertTrue(onAllNodesWithText("Art Director").fetchSemanticsNodes().isNotEmpty(), "no designation")
            assertTrue(onAllNodesWithText("Spent £600.00 of £1,000.00").fetchSemanticsNodes().isNotEmpty())

            onNodeWithText("Mark topped up").performClick()
            assertTrue(CashEvent.Funds(FundsAction.CompleteTopUp("t1")) in events, "no confirmation step")

            onNodeWithText("Topped up").performClick()
            waitForIdle()
            onNodeWithText("Top-Up Details").assertExists()
        }
    }

    @Test
    fun `the partial top-up dialog asks for the amount actually handed over and a note`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.TopUps).copy(
                            topUps = listOf(topUp("t1", CashTopUps.PENDING)),
                            fundsUi = FundsUiState(partial = PartialTopUpDraft("t1", "150")),
                        ),
                        onEvent = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Actual amount topped up").assertExists()
            onNodeWithText("Submit Partial Top-Up").assertExists()
            onNodeWithText("For Ada Lovelace · Float #PC-001").assertExists()
        }
    }

    @Test
    fun `cash extension lists the selected float's top-ups with their history`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CashExtension, accountant.copy(departmentIdentifier = "art"))
                            .copy(
                                myFloats = listOf(float("a", "PC-1"), float("b", "PC-2")),
                                fundsUi = FundsUiState(
                                    extension = ExtensionState(floatId = "b", rows = listOf(topUp("t1", "partial"))),
                                ),
                            ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Float PC-2 — Set dressing").assertExists()
            onNodeWithText("Request top-up").assertExists()
            onNodeWithText("Partial").assertExists()
            onNodeWithText("£150.00").assertExists()
        }
    }

    @Test
    fun `fund requests show the custodian read-only, the chain on open rows and duplicate on cancelled`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.ActiveFloats).copy(
                            funds = FundsState(
                                loading = false,
                                fundAccount = "1100",
                                requests = listOf(
                                    fund("r1", 200.0, FundRequest.REQUESTED),
                                    fund("r2", 50.0, FundRequest.CANCELLED),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("PETTY CASH").assertExists()
            onNodeWithText("Mark received").assertExists()
            onNodeWithText("Duplicate").assertExists()
            onNodeWithText("In progress").assertExists()
            onNodeWithText("Awaiting").assertExists()
            onNodeWithText("2 requests").assertExists()
            onNodeWithText("Request funds").assertExists()
        }
    }

    @Test
    fun `the recon list colours its variance and says balanced`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CashReconciliation).copy(
                            reconciliations = listOf(recon("r1", variance = 0.0), recon("r2", variance = 12.5)),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Reconciliation Periods").assertExists()
            onNodeWithText("Balanced").assertExists()
            onNodeWithText("+£12.50").assertExists()
        }
    }

    @Test
    fun `the recon count names its denominations and the variance it leaves`() {
        val draft = ReconDraft(
            id = "r1",
            currency = "GBP",
            openingBalance = "100",
            year = 2026,
            month = 9,
            denominations = ReconDraft.seedDenominations(1).map { if (it.value == "50") it.copy(count = "1") else it },
            items = listOf(ReconItem(description = "Taxi", type = ReconItem.OUT, amount = "10")),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CashReconciliation).copy(recon = draft),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Physical Cash Count").assertExists()
            onNodeWithText("£50 note").assertExists()
            onNodeWithText("50p").assertExists()
            onNodeWithText("Variance — shortfall").assertExists()
            onNodeWithText("Sign Off Period", substring = true).assertExists()
        }
    }

    @Test
    fun `a signed-off period reads only, with its audit trail`() {
        val saved = recon("r1", variance = 0.0).copy(
            status = ReconDraft.SIGNED_OFF,
            createdBy = adaId,
            signedBy = adaId,
            signedAt = 1_754_000_000_000,
        )
        val draft = ReconDraft.of(saved, 2026 to 9)
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CashReconciliation).copy(recon = draft),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Audit Trail").assertExists()
            onNodeWithText("SIGNED OFF BY").assertExists()
            onAllNodesWithText("Save Draft").assertCountEquals(0)
            assertTrue(onAllNodesWithText("Ada Lovelace").fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun fund(id: String, amount: Double, status: String) =
        FundRequest(id, "1100", "GBP", amount, null, status, adaId, null, null, null)

    private fun recon(id: String, variance: Double) = Reconciliation(
        id = id, reference = null, status = ReconDraft.DRAFT, periodStart = 1_788_000_000_000, periodEnd = null,
        bookBalance = 100.0, countedBalance = 100.0 + variance, currency = "GBP", note = null,
        createdAt = 1_754_000_000_000, openingBalance = 100.0, storedVariance = variance,
    )
}
