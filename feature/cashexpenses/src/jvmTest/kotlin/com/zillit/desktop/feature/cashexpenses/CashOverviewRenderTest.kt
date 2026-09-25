package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashStats
import com.zillit.desktop.feature.cashexpenses.domain.CashSummary
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCategorySpend
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentStats
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.RecentClaim
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The three overview pages, drawn with the web's sections, copy and actions. */
@OptIn(ExperimentalTestApi::class)
class CashOverviewRenderTest {

    private val accountant = CashViewer(
        userId = "acc",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(isSenior = true, requireSeniorSignOff = true),
    )

    private val crew = CashViewer(userId = "ada", departmentIdentifier = "department_art", designationIdentifier = null)

    private val coordinator = crew.copy(metadata = CashMetadata(isCoordinator = true), departmentId = "dept-art")

    private val people = listOf(
        AssigneeOption(userId = "ada", fullName = "Ada Lovelace", designation = "Art Director"),
        AssigneeOption(userId = "grace", fullName = "Grace Hopper", designation = "Props Buyer"),
    )

    private fun float(
        id: String = "f1",
        status: FloatStatus = FloatStatus.Spending,
        userId: String = "grace",
        createdAt: Long = 1_754_000_000_000,
    ) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = userId, holderName = "", departmentId = "dept-art",
        status = status, currency = "GBP", requestedAmount = 500.0, issuedAmount = 500.0, balance = 200.0,
        receiptsAmount = 300.0, receiptsCommits = 300.0, returnAmount = 0.0, bsCode = null, companyId = null,
        duration = null, durationType = null, purpose = null, createdAt = createdAt,
    )

    private fun base(destination: CashDestination, viewer: CashViewer) = CashUiState(
        viewer = viewer,
        destination = destination,
        pipeline = ExpenseType.PettyCash,
        assignees = people,
        currencies = CashCurrencies(defaultCode = "GBP"),
    )

    @Test
    fun `the petty cash overview draws the action queue, period summary and float register`() {
        val events = mutableListOf<CashEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = base(CashDestination.PettyCashOverview, accountant).copy(
                            pettyCashOverview = PettyCashOverview(
                                stats = CashStats(
                                    activeFloats = 1, awaitingAudit = 2, awaitingApproval = 1, readyToPost = 1,
                                    readyToPostAmount = 99.0, totalOutstanding = 200.0,
                                ),
                                summary = CashSummary(cashToAccount = 200.0),
                                floats = listOf(float()),
                            ),
                        ),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("Accounts Overview", substring = true).assertExists()
            onNodeWithText("£200.00 outstanding").assertExists()
            onNodeWithText("Receipt batches in queue").assertExists()
            onNodeWithText("2 batches awaiting accounts audit").assertExists()
            onNodeWithText("1 batch awaiting approver sign-off").assertExists()
            onNodeWithText("1 batch approved — ready to post").assertExists()
            onNodeWithText("3 items").assertExists()
            onNodeWithText("Period Summary").assertExists()
            onNodeWithText("Cash still to be accounted for").assertExists()
            onNodeWithText("Float Register").assertExists()
            onNodeWithText("Grace Hopper").assertExists()
            onNodeWithText("PC-f1").assertExists()

            onNodeWithText("2 batches awaiting accounts audit").performClick()
            onNodeWithText("New Float").performClick()
            onNodeWithText("View full register").performClick()
        }
        assertTrue(CashEvent.Open(CashDestination.AuditQueue) in events)
        assertTrue(CashEvent.RaiseFloatForCrew in events)
        assertTrue(CashEvent.Open(CashDestination.ActiveFloats) in events)
    }

    @Test
    fun `an empty action queue says all clear`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = base(CashDestination.PettyCashOverview, accountant)
                            .copy(pettyCashOverview = PettyCashOverview()),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No action items — all clear").assertExists()
            onNodeWithText("No active floats.").assertExists()
        }
    }

    @Test
    fun `my overview shows the float position, came-back receipts, recent claims and floats`() {
        val events = mutableListOf<CashEvent>()
        val claim = RecentClaim(
            id = "c1", batchReference = "PB-7", description = "Gaffer tape", settlementType = "REDUCE_FLOAT",
            category = "materials", date = 1_754_000_000_000, grossAmount = 12.5, currency = "GBP",
            status = BatchStatus.Queried,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = base(CashDestination.MyOverview, crew).copy(
                            myFloats = listOf(float(id = "old", userId = "ada"), float(id = "new", userId = "ada")),
                            myOverview = MyCashOverview(
                                pettyCashClaims = listOf(claim),
                                floats = listOf(float(id = "recent", userId = "ada")),
                            ),
                        ),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("My Overview — Ada Lovelace", substring = true).assertExists()
            onNodeWithText("Float #PC-old").assertExists()
            onNodeWithText("2 floats open — see the list below").assertExists()
            onNodeWithText("60% spent").assertExists()
            onNodeWithText("1 receipt needs attention", substring = true).assertExists()
            onNodeWithText("#PB-7").assertExists()
            onNodeWithText("No out-of-pocket claims yet.").assertExists()
            onNodeWithText("Quick actions").assertExists()

            onNodeWithText("#PC-recent").performClick()
            onNodeWithText("Request new float").performClick()
            onAllNodesWithText("View more")[0].performClick()
        }
        assertTrue(CashEvent.OpenFloatDetail("recent") in events)
        assertTrue(CashEvent.Open(CashDestination.FloatRequest) in events)
        assertTrue(CashEvent.Open(CashDestination.ReceiptsHistory) in events)
    }

    @Test
    fun `the department overview draws the web's tiles, tables and categories`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = base(CashDestination.DepartmentOverview, coordinator).copy(
                            departmentOverview = DepartmentOverview(
                                departmentId = "dept-art",
                                floats = listOf(float()),
                                outOfPocketBatches = listOf(oopBatch()),
                                stats = DepartmentStats(
                                    activeFloats = 1, activeFloatHolders = listOf("grace"), cashIssued = 500.0,
                                    receiptsApproved = 300.0, outOfPocketCount = 3, outOfPocketPending = 1,
                                    outOfPocketApproved = 2,
                                ),
                                spendByCategory = listOf(DepartmentCategorySpend("fuel", 60.0, 180.0)),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Department View — Ada Lovelace", substring = true).assertExists()
            onNodeWithText("1 pending · 2 approved").assertExists()
            onNodeWithText("OOP Claims — Your Dept").assertExists()
            onNodeWithText("Bacs").assertExists()
            onNodeWithText("Spend by Category").assertExists()
            onNodeWithText("£180.00").assertExists()
            assertEquals(1, onAllNodesWithText("Props Buyer").fetchSemanticsNodes().size)

            onNodeWithText("PC-f1").performClick()
            onNodeWithText("Float #PC-f1", substring = true).assertExists()
        }
    }

    private fun oopBatch() = ClaimBatch(
        id = "b1", reference = "OOP-9", userId = "grace", holderName = "", departmentId = "dept-art",
        status = BatchStatus.Posted, expenseType = ExpenseType.OutOfPocket, claimCount = 1, totalGross = 80.0,
        reimbursementAmount = 80.0, currency = "GBP", settlementType = "REIMBURSE", paymentMethod = "BACS",
        notes = null, assignedTo = null, assignedBy = null, assignmentReason = null, createdAt = 1_754_000_000_000,
    )
}
