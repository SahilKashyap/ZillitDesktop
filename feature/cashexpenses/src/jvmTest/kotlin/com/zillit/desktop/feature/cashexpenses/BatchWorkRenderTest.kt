@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.BatchEvent
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The batch view on Post & Ledger, Audit, History and Coding, composed: the
 * web's notices, tiles, rows, action bar and receipt cards are on screen.
 */
@OptIn(ExperimentalTestApi::class)
class BatchWorkRenderTest {

    private val accountant = CashViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(isCoordinator = true, codingRequired = true, requireSeniorSignOff = true),
    )

    private val claim = Claim(
        id = "claim-1",
        batchId = "batch-1",
        description = "Camera Store",
        supplier = null,
        category = "materials",
        costCode = null,
        codedDescription = "Gaffer tape",
        episode = null,
        receiptDate = 1_754_000_000_000,
        grossAmount = 120.0,
        netAmount = 100.0,
        vatAmount = 20.0,
        taxRate = 20.0,
        taxType = null,
        settlementType = null,
        status = BatchStatus.InAudit,
        receiptUrl = "receipts/r1.jpg",
    )

    private fun batch(status: BatchStatus, type: ExpenseType = ExpenseType.PettyCash, id: String = "batch-1") =
        ClaimBatch(
            id = id,
            reference = "RB-0042",
            userId = "ada",
            holderName = "",
            departmentId = null,
            status = status,
            expenseType = type,
            claimCount = 1,
            totalGross = 120.0,
            reimbursementAmount = 0.0,
            currency = "GBP",
            settlementType = "REDUCE_FLOAT",
            paymentMethod = null,
            notes = "Week 3 consumables",
            assignedTo = null,
            assignedBy = null,
            assignmentReason = null,
            createdAt = 1_754_000_000_000,
            claims = listOf(claim),
        )

    private fun state(destination: CashDestination, vararg rows: ClaimBatch) = CashUiState(
        viewer = accountant,
        destination = destination,
        pipeline = destination.expenseType,
        queueBatches = rows.toList(),
        assignees = listOf(AssigneeOption(userId = "ada", fullName = "Ada Lovelace", designation = "Gaffer")),
        selectedBatchId = "batch-1",
        panel = BatchPanel(batchId = "batch-1", claims = listOf(claim), effectiveDate = "2026-09-24"),
    )

    private fun render(state: CashUiState, onEvent: (CashEvent) -> Unit = {}, check: ComposeCheck) =
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { CashExpensesScreen(state = state, onEvent = onEvent) } }
            check(this)
        }

    @Test
    fun `post and ledger shows the web's notice, tiles, row and actions`() = render(
        state(CashDestination.PostLedger, batch(BatchStatus.ReadyToPost)),
    ) {
        onNodeWithText("Approved batches ready to post to the general ledger.", substring = true).assertExists()
        onNodeWithText("TOTAL TO POST", ignoreCase = true).assertExists()
        onNodeWithText("Review").assertExists()
        onNodeWithText("Post to Ledger").assertExists()
        onNodeWithText("Save").assertExists()
        onNodeWithText("Assign").assertExists()
        onNodeWithText("RECEIPT 01").assertExists()
        onNodeWithText("Gaffer tape", substring = true).assertExists()
    }

    @Test
    fun `the audit queue verifies receipts and sends for approval`() = render(
        state(CashDestination.AuditQueue, batch(BatchStatus.InAudit)),
    ) {
        onNodeWithText("Verify receipts, extract Tax", substring = true).assertExists()
        onNodeWithText("In audit · Unassigned").assertExists()
        onNodeWithText("Save Progress").assertExists()
        onNodeWithText("Send for Approval").assertExists()
        onNodeWithText("Verify").assertExists()
        onNodeWithText("Code & send this batch for approval", substring = true).assertExists()
    }

    @Test
    fun `history counts both pipelines and filters by type`() {
        val rows = arrayOf(
            batch(BatchStatus.Posted),
            batch(BatchStatus.Posted, ExpenseType.OutOfPocket, id = "batch-2"),
        )
        render(state(CashDestination.History, *rows)) {
            onNodeWithText("POSTED THIS PERIOD", ignoreCase = true).assertExists()
            assertTrue(onAllNodesWithText("OOP").fetchSemanticsNodes().isNotEmpty())
            // The pipeline switcher above says the same; the page's own filter is the last.
            onAllNodesWithText("Out of Pocket").onLast().performClick()
            onAllNodesWithText("PC").assertCountEquals(0)
        }
    }

    @Test
    fun `coding locks the claimant's facts and forwards to accounts`() = render(
        state(CashDestination.CodingQueue, batch(BatchStatus.Pending)),
    ) {
        onNodeWithText("Needs Coding").assertExists()
        onNodeWithText("Save Draft").assertExists()
        onNodeWithText("Forward to Accounts").assertExists()
        onAllNodesWithText("Assign").assertCountEquals(0)
        onNodeWithText("“Week 3 consumables”").assertExists()
    }

    @Test
    fun `save and split raise the batch view's own events`() {
        val seen = mutableListOf<CashEvent>()
        render(state(CashDestination.PostLedger, batch(BatchStatus.ReadyToPost)), onEvent = { seen += it }) {
            onNodeWithText("Save").performClick()
            onNodeWithText("Split into lines").performScrollTo().performClick()
        }
        assertTrue(BatchEvent.Save("batch-1") in seen)
        assertTrue(BatchEvent.OpenSplit("claim-1") in seen)
    }

    @Test
    fun `the escalation and reassignment dialogs carry the web's copy`() {
        val escalate = state(CashDestination.PostLedger, batch(BatchStatus.ReadyToPost)).copy(
            prompt = CashPrompt.WithReason(ReasonedAction.EscalateBatch, "batch-1", "Escalate #RB-0042 to Senior", ""),
        )
        render(escalate) {
            onNodeWithText("This batch will be flagged for senior accountant review", substring = true).assertExists()
            onNodeWithText("Confirm Escalation").assertExists()
        }
        val reassign = state(CashDestination.PostLedger, batch(BatchStatus.ReadyToPost).copy(assignedTo = "ada")).copy(
            prompt = CashPrompt.Assign(batchId = "batch-1", title = "Reassign #RB-0042", label = "Reassign"),
        )
        render(reassign) {
            onNodeWithText("Currently assigned to Ada Lovelace (Gaffer)").assertExists()
            onNodeWithText("Select a reason...").assertExists()
        }
    }
}

private typealias ComposeCheck = androidx.compose.ui.test.ComposeUiTest.() -> Unit
