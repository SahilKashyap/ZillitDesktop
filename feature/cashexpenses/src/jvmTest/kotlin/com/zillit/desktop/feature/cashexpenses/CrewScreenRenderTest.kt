package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.CrewState
import com.zillit.desktop.feature.cashexpenses.ui.SubmitDraft
import kotlin.test.Test
import kotlin.test.assertTrue

/** The crew pages compose with the web's content, and their controls send the crew's events. */
@OptIn(ExperimentalTestApi::class)
class CrewScreenRenderTest {

    private val crew =
        CashViewer(userId = "user-2", departmentIdentifier = "department_art", designationIdentifier = null)

    private fun float(id: String = "float-1", balance: Double = 40.0) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "user-2", holderName = "Ada", departmentId = null,
        status = FloatStatus.Spending, currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0,
        balance = balance, receiptsAmount = 60.0, receiptsCommits = 60.0, returnAmount = 0.0, bsCode = null,
        companyId = null, duration = null, durationType = null, purpose = null, createdAt = 1_754_000_000_000,
    )

    private fun batch() = ClaimBatch(
        id = "b1", reference = "RB-7", userId = "user-2", holderName = "Ada", departmentId = null,
        status = BatchStatus.InAudit, expenseType = ExpenseType.PettyCash, claimCount = 1, totalGross = 12.0,
        reimbursementAmount = 0.0, currency = "GBP", settlementType = "REDUCE_FLOAT", paymentMethod = null,
        notes = null, assignedTo = null, assignedBy = null, assignmentReason = null, createdAt = 1_754_000_000_000,
    )

    private val claim = Claim(
        id = "c1", batchId = "b1", description = "Gaffer tape", supplier = null, category = "materials",
        costCode = null, codedDescription = null, episode = null, receiptDate = null, grossAmount = 12.0,
        netAmount = 10.0, vatAmount = 2.0, taxRate = null, taxType = null, settlementType = null,
        status = BatchStatus.InAudit, receiptUrl = null,
    )

    private fun state(destination: CashDestination, build: CashUiState.() -> CashUiState = { this }) = CashUiState(
        viewer = crew,
        destination = destination,
        pipeline = destination.expenseType,
    ).build()

    @Test
    fun `submit receipts over the float's headroom offers the reimbursement with bank details`() {
        val events = mutableListOf<CashEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.SubmitReceipts) {
                            copy(
                                myFloats = listOf(float(), float("float-2")),
                                draft = SubmitDraft(listOf(DraftReceipt(description = "B&Q", amount = "60"))),
                                crew = CrewState(followUp = FollowUp.CLOSE),
                            )
                        },
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("Submitting against", ignoreCase = true).assertExists()
            onNodeWithText("Choose your settlement").assertExists()
            onNodeWithText("Reimburse Me").assertExists()
            onNodeWithText("Account Name", ignoreCase = true).assertExists()
            onNodeWithText("Close Float").assertExists()
            onNodeWithText("Drop or click").assertExists()
            onNodeWithText("Reimburse to Float").performScrollTo().performClick()
        }
        assertTrue(events.any { it == CrewEvent.ToggleFollowUp(FollowUp.TOP_UP) })
    }

    @Test
    fun `receipts history opens the batch with its timeline, receipts and actions`() {
        val events = mutableListOf<CashEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.ReceiptsHistory) {
                            copy(
                                myBatches = listOf(batch()),
                                selectedBatchId = "b1",
                                panel = BatchPanel(batchId = "b1", claims = listOf(claim)),
                            )
                        },
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("My Claims — Petty Cash").assertExists()
            onNodeWithText("Receipts (1)").assertExists()
            onNodeWithText("Gaffer tape").performClick()
            onNodeWithText("Query").performClick()
        }
        assertTrue(events.any { it == CrewEvent.OpenClaim("c1") })
        assertTrue(events.any { it == CrewEvent.ShowQuery(true) })
    }

    @Test
    fun `float request lands on the crew's floats, and a row opens its details`() {
        val events = mutableListOf<CashEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.FloatRequest) { copy(myFloats = listOf(float())) },
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("My Floats").assertExists()
            onNodeWithText("#PC-float-1", substring = true).performClick()
        }
        assertTrue(events.any { it == CashEvent.OpenFloatDetail("float-1") })
    }

    @Test
    fun `the float request form shows the template's fields`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CashExpensesScreen(
                        state = state(CashDestination.FloatRequest) { copy(crew = CrewState(floatFormOpen = true)) },
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Float details").assertExists()
            onNodeWithText("Collect Date", ignoreCase = true).assertExists()
            onNodeWithText("Submit float request").assertExists()
        }
    }
}
