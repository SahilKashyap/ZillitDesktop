package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes the real Cash Expenses screen on every destination.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screen exercises the same layout code
 * — and, crucially, catches the two failures unit tests cannot: a page that
 * throws while composing, and a nested-scroll arrangement that crashes on an
 * unbounded constraint. Both are how a tool ships looking fine in review and
 * blank in use.
 */
@OptIn(ExperimentalTestApi::class)
class CashScreenRenderTest {

    private val accountant = CashViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(
            isApprover = true,
            isCoordinator = true,
            isSenior = true,
            requireSeniorSignOff = true,
            codingRequired = true,
            viewDepartmentFloats = true,
            canOverride = true,
            overrideFloatRequest = true,
            overrideReceiptBatch = true,
            postingLimit = 5_000.0,
        ),
    )

    private val crew = CashViewer(
        userId = "user-2",
        departmentIdentifier = "department_art",
        designationIdentifier = null,
    )

    private fun sampleFloat() = CashFloat(
        id = "float-1",
        requestNumber = "PC-001",
        userId = "user-2",
        holderName = "Ada Lovelace",
        departmentId = "dept-1",
        status = FloatStatus.Spending,
        currency = "GBP",
        requestedAmount = 1_000.0,
        issuedAmount = 1_000.0,
        balance = 620.0,
        receiptsAmount = 380.0,
        receiptsCommits = 380.0,
        returnAmount = 0.0,
        bsCode = "1200",
        companyId = "co-1",
        duration = "2",
        durationType = "weeks",
        purpose = "Set dressing consumables",
        createdAt = 1_754_000_000_000,
    )

    private fun sampleBatch(
        status: BatchStatus = BatchStatus.AwaitingApproval,
        id: String = "batch-1",
    ) = ClaimBatch(
        id = id,
        reference = "RB-0042",
        userId = "user-2",
        holderName = "Ada Lovelace",
        departmentId = "dept-1",
        status = status,
        expenseType = ExpenseType.PettyCash,
        claimCount = 3,
        totalGross = 214.50,
        reimbursementAmount = 0.0,
        currency = "GBP",
        settlementType = "REDUCE_FLOAT",
        paymentMethod = null,
        notes = "Week 3 consumables",
        assignedTo = null,
        assignedBy = null,
        assignmentReason = null,
        createdAt = 1_754_000_000_000,
    )

    private fun state(destination: CashDestination, viewer: CashViewer = accountant) = CashUiState(
        viewer = viewer,
        destination = destination,
        pipeline = destination.expenseType,
        activeFloats = listOf(sampleFloat()),
        myFloats = listOf(sampleFloat()),
        floatApprovals = listOf(sampleFloat()),
        queueBatches = listOf(sampleBatch(), sampleBatch(BatchStatus.Posted, id = "batch-2")),
        myBatches = listOf(sampleBatch()),
        selectedBatchId = "batch-1",
    )

    /**
     * Every page the accountant can reach renders.
     *
     * Driven off the enum rather than a hand-written list, so a destination
     * added later is covered without anyone remembering to add it here.
     */
    @Test
    fun `every accountant destination composes`() {
        val destinations = CashDestination.entries.filter { it.visibleTo(accountant) }
        assertTrue(destinations.size > 10, "expected the accountant to see most of the tool")

        destinations.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        CashExpensesScreen(state = state(destination), onEvent = {})
                    }
                }
                // The header is constant across pages, so its presence proves
                // the frame and the page under it both composed.
                onNodeWithText("Cash Expenses").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every crew destination composes`() {
        CashDestination.entries.filter { it.visibleTo(crew) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        CashExpensesScreen(state = state(destination, crew), onEvent = {})
                    }
                }
                onNodeWithText("Cash Expenses").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every destination composes in dark mode too`() {
        // A tool that reads only semantic roles is correct in both themes
        // without a conditional; this is what proves none slipped through.
        CashDestination.entries.filter { it.visibleTo(accountant) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = true) {
                        CashExpensesScreen(state = state(destination), onEvent = {})
                    }
                }
                onNodeWithText("Cash Expenses").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the accountant dashboard shows the queues it links to`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.PettyCashOverview).copy(
                            pettyCashOverview = com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview(
                                stats = com.zillit.desktop.feature.cashexpenses.domain.CashStats(
                                    activeFloats = 4,
                                    awaitingAudit = 2,
                                    awaitingApproval = 7,
                                    readyToPost = 3,
                                    readyToPostAmount = 1_240.0,
                                    escalated = 1,
                                    totalOutstanding = 8_400.0,
                                ),
                                floats = listOf(sampleFloat()),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("ACTIVE FLOATS").assertIsDisplayed()
            onNodeWithText("AWAITING APPROVAL").assertIsDisplayed()
            onNodeWithText("READY TO POST").assertIsDisplayed()
            // The escalation notice only appears when there is one.
            onAllNodesWithText("Open sign-off")[0].assertIsDisplayed()
        }
    }

    /**
     * The tabs are wired to the events the view model expects.
     *
     * Clicking a tab must raise `Open` for that destination; a strip that
     * renders but reports the wrong page is worse than one that does not
     * render at all, because it looks like it works.
     */
    private fun claim(receipt: String?) = Claim(
        id = "claim-1",
        batchId = "batch-1",
        description = "Gaffer tape",
        supplier = null,
        category = null,
        costCode = "5010",
        codedDescription = null,
        episode = null,
        receiptDate = null,
        grossAmount = 24.0,
        netAmount = 20.0,
        vatAmount = 4.0,
        taxRate = 20.0,
        taxType = null,
        settlementType = null,
        status = BatchStatus.AwaitingApproval,
        receiptUrl = receipt,
    )

    private fun withClaim(receipt: String?) = state(CashDestination.ApprovalQueue).let { base ->
        base.copy(
            queueBatches = listOf(sampleBatch().copy(claims = listOf(claim(receipt)))),
            selectedBatchId = "batch-1",
        )
    }

    /**
     * The receipt is the thing being checked.
     *
     * The effect and the host's handler were wired when this module shipped,
     * but nothing raised it — so a reviewer could read a claim's figures and
     * never look at what they came from.
     */
    @Test
    fun `a claim with a receipt offers to open it`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(state = withClaim("receipts/r1.jpg"), onEvent = {})
                }
            }
            onNodeWithText("View receipt").assertExists()
        }
    }

    /** A PDF says so, because it opens in another application rather than inline. */
    @Test
    fun `a pdf receipt is named as one`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(state = withClaim("receipts/r1.pdf"), onEvent = {})
                }
            }
            onNodeWithText("Open receipt (PDF)").assertExists()
        }
    }

    @Test
    fun `a claim with no receipt offers nothing to open`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(state = withClaim(null), onEvent = {})
                }
            }
            onAllNodesWithText("View receipt").assertCountEquals(0)
            onAllNodesWithText("Open receipt (PDF)").assertCountEquals(0)
        }
    }

    @Test
    fun `the assign action is offered on the sign-off queue`() {
        val signOff = state(CashDestination.PettyCashSignOff)

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(state = signOff, onEvent = {})
                }
            }
            // Unassigned, so it reads "Assign" rather than "Reassign".
            onNodeWithText("Assign").assertExists()
        }
    }

    @Test
    fun `clicking a shared tab asks to open that destination`() {
        var opened: CashDestination? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.PettyCashOverview),
                        onEvent = { event -> if (event is CashEvent.Open) opened = event.destination },
                    )
                }
            }
            onNodeWithText("Audit Queue").performClick()
        }
        kotlin.test.assertEquals(CashDestination.AuditQueue, opened)
    }

    @Test
    fun `the coding editor opens with a running balance and refuses a save that does not add up`() {
        val claim = com.zillit.desktop.feature.cashexpenses.domain.Claim(
            id = "claim-1",
            batchId = "batch-1",
            description = "Gaffer tape",
            supplier = "Camera Store",
            category = "materials",
            costCode = null,
            codedDescription = null,
            episode = null,
            receiptDate = 1_754_000_000_000,
            grossAmount = 120.0,
            netAmount = 100.0,
            vatAmount = 20.0,
            taxRate = 20.0,
            taxType = "STANDARD",
            settlementType = null,
            status = BatchStatus.Coding,
            receiptUrl = null,
        )
        val open = com.zillit.desktop.feature.cashexpenses.ui.CodingDraft(
            batchId = "batch-1",
            claimId = "claim-1",
            receiptGross = 120.0,
            currency = "GBP",
            lines = listOf(
                com.zillit.desktop.feature.cashexpenses.domain.EditorLine(
                    id = "line-1",
                    description = "Gaffer tape",
                    unitPrice = 50.0,
                    account = "4100",
                    taxRatePercent = 20.0,
                ),
            ),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CodingQueue).copy(
                            queueBatches = listOf(sampleBatch().copy(claims = listOf(claim))),
                            coding = open,
                        ),
                        onEvent = {},
                    )
                }
            }
            // 60 coded against a 120 receipt: the difference is on screen and
            // the save is refused until it is zero.
            onNodeWithText("LEFT TO CODE").assertIsDisplayed()
            onNodeWithText("Does not add up").assertIsDisplayed()
        }
    }

    @Test
    fun `a balanced coding says so`() {
        val open = com.zillit.desktop.feature.cashexpenses.ui.CodingDraft(
            batchId = "batch-1",
            claimId = "claim-1",
            receiptGross = 120.0,
            currency = "GBP",
            lines = listOf(
                com.zillit.desktop.feature.cashexpenses.domain.EditorLine(
                    id = "line-1",
                    unitPrice = 100.0,
                    account = "4100",
                    taxRatePercent = 20.0,
                ),
            ),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.CodingQueue).copy(coding = open),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Balanced").assertIsDisplayed()
        }
    }

    @Test
    fun `the crew submit screen refuses without a float and offers the way out`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.SubmitReceipts, crew).copy(myFloats = emptyList()),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Request a float").assertIsDisplayed()
        }
    }
}
