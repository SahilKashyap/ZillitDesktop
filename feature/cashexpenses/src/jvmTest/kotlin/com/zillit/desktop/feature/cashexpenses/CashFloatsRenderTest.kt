package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatDetails
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.FloatTotals
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FloatDetailState
import com.zillit.desktop.feature.cashexpenses.ui.FloatExpansion
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Approval Queue, Sign-off, Active Floats and Float Details, composed as the web draws them. */
@OptIn(ExperimentalTestApi::class)
class CashFloatsRenderTest {

    private val accountant = CashViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(
            isApprover = true,
            isSenior = true,
            requireSeniorSignOff = true,
            canOverride = true,
            overrideFloatRequest = true,
            overrideReceiptBatch = true,
        ),
    )

    private fun float(status: FloatStatus = FloatStatus.Spending) = CashFloat(
        id = "float-1", requestNumber = "PC-001", userId = "user-2", holderName = "Ada Lovelace",
        departmentId = "dept-1", status = status, currency = "GBP", requestedAmount = 1_000.0,
        issuedAmount = 1_000.0, balance = 620.0, receiptsAmount = 380.0, receiptsCommits = 380.0,
        returnAmount = 0.0, bsCode = "1200", companyId = null, duration = "5", durationType = "days",
        purpose = "Set dressing consumables", createdAt = 1_754_000_000_000, collectionMethod = "production_office",
    )

    private fun batch(status: BatchStatus) = ClaimBatch(
        id = "batch-1", reference = "RB-0042", userId = "user-2", holderName = "Ada Lovelace",
        departmentId = "dept-1", status = status, expenseType = ExpenseType.OutOfPocket, claimCount = 1,
        totalGross = 24.0, reimbursementAmount = 0.0, currency = "GBP", settlementType = "REDUCE_FLOAT",
        paymentMethod = null, notes = "Week 3", assignedTo = null, assignedBy = null, assignmentReason = null,
        createdAt = 1_754_000_000_000, escalationReason = "Needs a senior eye", escalatedBy = "user-1",
    )

    private val claim = Claim(
        id = "claim-1", batchId = "batch-1", description = "Gaffer tape", supplier = null, category = null,
        costCode = "5010", codedDescription = null, episode = null, receiptDate = null, grossAmount = 24.0,
        netAmount = 20.0, vatAmount = 4.0, taxRate = 20.0, taxType = null, settlementType = null,
        status = BatchStatus.Escalated, receiptUrl = null,
        lineItems = listOf(
            ClaimLineItem(id = "l1", account = "5010", description = "Tape roll", total = 24.0, taxRate = 20.0,
                autoDeduction = false, unitPrice = 20.0),
            ClaimLineItem(id = "l2", account = "2200", description = "VAT", total = 4.0, taxRate = null,
                autoDeduction = false, isTax = true),
        ),
    )

    private fun render(state: CashUiState, checks: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { CashExpensesScreen(state = state, onEvent = {}) } }
            checks()
        }

    @Test
    fun `the approval queue shows the web's two cards and its float rows`() = render(
        CashUiState(
            viewer = accountant,
            destination = CashDestination.ApprovalQueue,
            floatApprovals = listOf(float(FloatStatus.AwaitingApproval)),
            queueBatches = listOf(batch(BatchStatus.AwaitingApproval)),
        ),
    ) {
        onNodeWithText("Float Requests").assertExists()
        onNodeWithText("Receipt Batches & OOP").assertExists()
        onNodeWithText("Float request").assertExists()
        // No chain covers it: the accountant is sent to set one; Override stands in for Approve.
        onNodeWithText("Set Approval Level").assertExists()
        onNodeWithText("Override").assertExists()
        onNodeWithText("Pending").assertExists()
    }

    @Test
    fun `the batches card lists by pipeline`() = render(
        CashUiState(
            viewer = accountant,
            destination = CashDestination.ApprovalQueue,
            queueBatches = listOf(batch(BatchStatus.AwaitingApproval)),
            selectedBatchId = "batch-1",
        ),
    ) {
        // The pipeline switcher carries the words too; the filter chip is the second.
        assertTrue(onAllNodesWithText("Out of Pocket").fetchSemanticsNodes().size >= 2)
        onNodeWithText("OOP").assertExists()
        // The row's pill, and the open batch's own.
        assertTrue(onAllNodesWithText("Awaiting Approval").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a float request opens its detail`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                CashExpensesScreen(
                    state = CashUiState(
                        viewer = accountant,
                        destination = CashDestination.ApprovalQueue,
                        floatApprovals = listOf(float(FloatStatus.AwaitingApproval)),
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("#PC-001", substring = true).performClick()
        onNodeWithText("PC-001 — Float Request").assertExists()
        onNodeWithText("Collect from production office").assertExists()
        onNodeWithText("5 days").assertExists()
    }

    @Test
    fun `sign-off shows the tiles, the line items and the web's fields`() = render(
        CashUiState(
            viewer = accountant,
            destination = CashDestination.PettyCashSignOff,
            queueBatches = listOf(batch(BatchStatus.Escalated).copy(claims = listOf(claim))),
            selectedBatchId = "batch-1",
            panel = BatchPanel(batchId = "batch-1", claims = listOf(claim)),
        ),
    ) {
        onNodeWithText("AWAITING SIGN-OFF").assertExists()
        onNodeWithText("1 escalated · 0 for review · 0 approved").assertExists()
        onNodeWithText("CLAIMS & LINE ITEMS").assertExists()
        onNodeWithText("Tape roll").assertExists()
        onAllNodesWithText("VAT").assertCountEqualsZero()
        onNodeWithText("Effective Date *").assertExists()
        onNodeWithText("Approve & Post to Ledger").assertExists()
        onNodeWithText("← Return to Accounts — further amendments").assertExists()
    }

    @Test
    fun `an active float card shows its figures and opens onto its batches`() = render(
        CashUiState(
            viewer = accountant,
            destination = CashDestination.ActiveFloats,
            activeFloats = listOf(float()),
            floatExpansions = mapOf(
                "float-1" to FloatExpansion(batches = listOf(batch(BatchStatus.InAudit)), selectedBatchId = "batch-1"),
            ),
        ),
    ) {
        onNodeWithText("Active floats register").assertExists()
        onNodeWithText("Spent / Returned".uppercase()).assertExists()
        onNodeWithText("Spent £380.00 of £1,000.00", substring = true).assertExists()
        assertTrue(onAllNodesWithText("History").fetchSemanticsNodes().size >= 2, "the card's History")
        onNodeWithText("Go to Audit Queue →").assertExists()
        onNodeWithText("Coordinator Coding").assertExists()
    }

    @Test
    fun `float details render their sections, and an accountant may correct the BS code`() = render(
        CashUiState(
            viewer = accountant,
            destination = CashDestination.ActiveFloats,
            activeFloats = listOf(float(FloatStatus.Active).copy(receiptsCommits = null)),
            floatDetail = FloatDetailState(
                floatId = "float-1",
                loading = false,
                details = FloatDetails(
                    float = float(FloatStatus.Active).copy(receiptsCommits = null),
                    totals = FloatTotals(requested = 1_000.0, issued = 1_000.0),
                ),
            ),
        ),
    ) {
        onNodeWithText("Float PC-001").assertExists()
        onNodeWithText("POSTED BATCHES · 0").assertExists()
        onNodeWithText("No cash returns were recorded against this float.").assertExists()
        onNodeWithText("Edit").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertTrue(fetchSemanticsNodes().isEmpty())
}
