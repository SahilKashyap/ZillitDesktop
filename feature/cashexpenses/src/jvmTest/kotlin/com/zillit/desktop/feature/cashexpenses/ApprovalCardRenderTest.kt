package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrency
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashTaxType
import com.zillit.desktop.feature.cashexpenses.domain.CashTrackingNode
import com.zillit.desktop.feature.cashexpenses.domain.CashTrackingSet
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.CategorySpend
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.ProcessingRule
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.RequesterDeal
import com.zillit.desktop.feature.cashexpenses.domain.RoutingSplit
import com.zillit.desktop.feature.cashexpenses.ui.BatchPanel
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewState
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import com.zillit.desktop.feature.cashexpenses.ui.SentFloatRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Approval Queue's receipt card (rule banners, tax and the line-items table),
 * the OOP overview's filters and table, Payment Routing's payroll preview and
 * the float request's live cap hint — drawn as the web draws them.
 */
@OptIn(ExperimentalTestApi::class)
class ApprovalCardRenderTest {

    private val accountant = CashViewer(
        userId = "acc",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(isApprover = true, isSenior = true, requireSeniorSignOff = true),
    )

    private fun batch(
        id: String = "batch-1",
        status: BatchStatus = BatchStatus.AwaitingApproval,
        method: String? = null,
        count: Int = 2,
    ) = ClaimBatch(
        id = id, reference = "RB-$id", userId = "u2", holderName = "Ada Lovelace", departmentId = null,
        status = status, expenseType = ExpenseType.OutOfPocket, claimCount = count, totalGross = 120.0,
        reimbursementAmount = 120.0, currency = "GBP", settlementType = null, paymentMethod = method,
        notes = null, assignedTo = null, assignedBy = null, assignmentReason = null, createdAt = 1_754_000_000_000,
        totalNet = 100.0, totalVat = 20.0,
    )

    private val lines = listOf(
        ClaimLineItem(
            id = "l1", account = "5010", description = "Gaffer tape", total = 18.0, taxRate = 20.0,
            autoDeduction = false, unitPrice = 15.0, taxType = "vat",
            trackingCodes = Json.parseToJsonElement("""{"loc":"LOC-LON"}"""),
        ),
        ClaimLineItem(
            id = "l2", account = "5020", description = "Batteries", total = 4.0, taxRate = null,
            autoDeduction = false, unitPrice = 4.0,
        ),
    )

    private fun claim(rules: List<ProcessingRule> = emptyList(), taxType: String? = "vat") = Claim(
        id = "claim-1", batchId = "batch-1", description = "Gaffer tape", supplier = null, category = "materials",
        costCode = "5010", codedDescription = null, episode = null, receiptDate = null, grossAmount = 24.0,
        netAmount = 20.0, vatAmount = 4.0, taxRate = 20.0, taxType = taxType, settlementType = null,
        status = BatchStatus.AwaitingApproval, receiptUrl = null, lineItems = lines, processingRules = rules,
    )

    private fun approval(
        claim: Claim,
        trackingSets: List<CashTrackingSet> = emptyList(),
    ) = CashUiState(
        viewer = accountant,
        destination = CashDestination.ApprovalQueue,
        pipeline = ExpenseType.PettyCash,
        queueBatches = listOf(batch().copy(claims = listOf(claim), expenseType = ExpenseType.PettyCash)),
        selectedBatchId = "batch-1",
        panel = BatchPanel(batchId = "batch-1", claims = listOf(claim)),
        taxTypes = listOf(CashTaxType(identifier = "vat", label = "VAT", rate = 20.0)),
        trackingSets = trackingSets,
    )

    private fun render(state: CashUiState, onEvent: (CashEvent) -> Unit = {}, check: ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { CashExpensesScreen(state = state, onEvent = onEvent) } }
            check()
        }

    // -- the receipt card ----------------------------------------------------------------

    @Test
    fun `a query, a review and a deduction each draw their banner with the rule's own words`() = render(
        approval(
            claim(
                listOf(
                    ProcessingRule("query", "Petty cash", 200.0, "amount", "Receipts over this need a reason"),
                    ProcessingRule("review", "Big spend rule", 500.0),
                    ProcessingRule("deduct", "Meal", 10.0, "percentage"),
                    ProcessingRule("something_new", "Ignored"),
                ),
            ),
        ),
    ) {
        onNodeWithText(
            "Query required because receipt amount is more than £200.00 as per Petty cash rule.",
            substring = true,
        ).assertExists()
        onNodeWithText("Receipts over this need a reason", substring = true).assertExists()
        onNodeWithText(
            "Senior review required because receipt amount is more than £500.00 as per Big spend rule.",
        ).assertExists()
        onNodeWithText("10% deduction applied as per Meal rule.").assertExists()
        onAllNodesWithText("Ignored", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a rule with no threshold or title reads as the web's bare sentence`() = render(
        approval(claim(listOf(ProcessingRule("query"), ProcessingRule("deduct")))),
    ) {
        onNodeWithText("Query required as per rule.").assertExists()
        onNodeWithText("Auto deduction applied as per rule.").assertExists()
    }

    @Test
    fun `a receipt with no rules draws no banner`() = render(approval(claim())) {
        onAllNodesWithText("required", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the extracted tax shows under the receipt`() = render(approval(claim())) {
        onNodeWithText("Tax 20% · Net £20.00 · TAX £4.00 · Gross £24.00").assertExists()
    }

    @Test
    fun `line items stay folded until asked for, then show per-line tax and a matching total`() = render(
        approval(claim()),
    ) {
        onAllNodesWithText("Batteries").assertCountEquals(0)
        onNodeWithText("View Line Items").performScrollTo().performClick()

        onNodeWithText("Batteries").assertExists()
        onNodeWithText("VAT · 20%").assertExists()
        onNodeWithText("£22.00 / £24.00 (mismatch)").assertExists()
        // No tracking sets configured: no Layers column, as the web hides it.
        onAllNodesWithText("LAYERS").assertCountEquals(0)

        onNodeWithText("Hide Line Items").performScrollTo().performClick()
        onAllNodesWithText("Batteries").assertCountEquals(0)
    }

    @Test
    fun `a production with tracking sets gets the Layers column`() = render(
        approval(
            claim(),
            trackingSets = listOf(CashTrackingSet("loc", nodes = listOf(CashTrackingNode("n1", "LOC-LON")))),
        ),
    ) {
        onNodeWithText("View Line Items").performScrollTo().performClick()
        onNodeWithText("LAYERS").assertExists()
        onNodeWithText("LOC-LON").assertExists()
    }

    // -- OOP overview ---------------------------------------------------------------------

    private fun overview(vararg rows: ClaimBatch) = CashUiState(
        viewer = accountant,
        destination = CashDestination.OutOfPocketOverview,
        pipeline = ExpenseType.OutOfPocket,
        currencies = CashCurrencies(listOf(CashCurrency("GBP", "£"), CashCurrency("USD", "$")), defaultCode = "GBP"),
        outOfPocketOverview = OutOfPocketOverview(
            pendingClaims = 2,
            totalClaimed = 500.0,
            routing = RoutingSplit(bacs = 100.0, payroll = 300.0, total = 400.0),
            spendByCategory = listOf(CategorySpend("materials", 300.0), CategorySpend("fuel", 100.0)),
            batches = rows.toList(),
        ),
    )

    @Test
    fun `the claims table has the web's columns, receipts, net and routing`() = render(
        overview(
            batch("a", BatchStatus.ReadyToPost, "BACS", count = 1),
            batch("b", BatchStatus.Posted, "PAYROLL", count = 3),
        ),
    ) {
        listOf("CREW MEMBER", "REFERENCE", "DESCRIPTION", "GROSS", "NET", "ROUTING").forEach {
            onNodeWithText(it, ignoreCase = true).assertExists()
        }
        onNodeWithText("#RB-a").assertExists()
        onNodeWithText("1 receipt").assertExists()
        onNodeWithText("3 receipts").assertExists()
        // Gross £120.00 and net £100.00 for each of the two rows.
        assertTrue(onAllNodesWithText("£100.00").fetchSemanticsNodes().size >= 2, "the net column")
        onAllNodesWithText("£120.00").onFirst().assertExists()
        onAllNodesWithText("Payroll", ignoreCase = true).onFirst().assertExists()
    }

    @Test
    fun `categories read by name and tiles read in the default currency`() = render(
        overview(batch("a", BatchStatus.ReadyToPost, "BACS").copy(currency = "USD")),
    ) {
        onNodeWithText("Materials").assertExists()
        onNodeWithText("Fuel").assertExists()
        // The total claimed is across currencies, so it is the default's, not the first row's.
        onNodeWithText("£500.00").assertExists()
    }

    @Test
    fun `the status filter narrows the table and an empty result says the filter matched nothing`() = render(
        overview(batch("a", BatchStatus.ReadyToPost, "BACS"), batch("b", BatchStatus.Posted, "PAYROLL")),
    ) {
        onNodeWithText("#RB-a").assertExists()
        onNodeWithText("#RB-b").assertExists()

        onNodeWithText("All Status").performClick()
        onAllNodesWithText("Paid").onFirst().performClick()

        onNodeWithText("#RB-b").assertExists()
        onAllNodesWithText("#RB-a").assertCountEquals(0)

        onNodeWithText("Paid").performClick()
        onAllNodesWithText("Pending").onFirst().performClick()
        onNodeWithText("No claims match this filter.").assertExists()
    }

    // -- payment routing -------------------------------------------------------------------

    @Test
    fun `payment routing totals the payroll additions and previews the payroll run`() {
        val events = mutableListOf<CashEvent>()
        render(
            CashUiState(
                viewer = accountant,
                destination = CashDestination.PaymentRouting,
                pipeline = ExpenseType.OutOfPocket,
                paymentRouting = PaymentRouting(
                    payrollTotal = 230.0,
                    payrollCount = 2,
                    payrollBatches = listOf(batch("p", BatchStatus.ReadyToPost, "PAYROLL")),
                ),
            ),
            onEvent = { events += it },
        ) {
            onNodeWithText("2 additions").assertExists()
            onNodeWithText("Preview payroll run").performClick()
        }
        assertTrue(CashEvent.OpenPayrollRun in events)
    }

    // -- float request cap -----------------------------------------------------------------

    private fun floatRequest(cap: RequestCap, deal: RequesterDeal?, currency: String = "") = CashUiState(
        viewer = CashViewer(
            userId = "u2", departmentIdentifier = "department_art", designationIdentifier = null,
            metadata = CashMetadata(requestCap = cap),
        ),
        destination = CashDestination.FloatRequest,
        pipeline = ExpenseType.PettyCash,
        currencies = CashCurrencies(
            listOf(CashCurrency("GBP", "£"), CashCurrency("USD", "$"), CashCurrency("EUR", "€")),
            defaultCode = "GBP",
            rates = mapOf("USD" to 1.25),
        ),
        crew = CrewState(floatFormOpen = true, deal = deal),
        floatDraft = FloatRequestDraft(currency = currency),
    )

    @Test
    fun `a weekly cap shows the converted maximum and where it came from`() = render(
        floatRequest(
            RequestCap(enabled = true, basis = RequestCap.WEEKLY_SALARY, maxAmount = 100.0, salaryMultiplier = 2.0),
            RequesterDeal(weeklyRate = 500.0, currency = "USD"),
        ),
    ) {
        onNodeWithText("Maximum £800.00 — your weekly rate on your active deal × the project multiplier.")
            .assertExists()
    }

    @Test
    fun `a weekly cap with no deal names the fallback`() = render(
        floatRequest(
            RequestCap(enabled = true, basis = RequestCap.WEEKLY_SALARY, maxAmount = 150.0),
            deal = null,
        ),
    ) {
        onNodeWithText("Maximum £150.00 — project cap for requesters with no active deal.").assertExists()
    }

    @Test
    fun `a flat cap in a converted currency still shows, and an unrated currency says it is not applied`() {
        render(floatRequest(RequestCap(enabled = true, maxAmount = 200.0), deal = null, currency = "USD")) {
            onNodeWithText("Maximum £200.00 — project cap.").assertExists()
        }
        render(floatRequest(RequestCap(enabled = true, maxAmount = 200.0), deal = null, currency = "EUR")) {
            onNodeWithText("Project cap not applied — no exchange rate for EUR.").assertExists()
        }
    }

    // -- float request confirmation ---------------------------------------------------

    private fun sentFloat(vararg newFloats: CashFloat) = CashUiState(
        viewer = CashViewer(userId = "u2", departmentIdentifier = "department_art", designationIdentifier = null),
        destination = CashDestination.FloatRequest,
        pipeline = ExpenseType.PettyCash,
        currencies = CashCurrencies(listOf(CashCurrency("GBP", "£")), defaultCode = "GBP"),
        myFloats = newFloats.toList(),
        crew = CrewState(
            floatFormOpen = true,
            sentFloat = SentFloatRequest(
                draft = FloatRequestDraft(
                    amount = "250",
                    purpose = "Set dressing consumables",
                    durationType = "days",
                    duration = "5",
                    collectDate = "2099-01-01",
                ),
                currency = "GBP",
                knownFloatIds = setOf("old"),
            ),
        ),
    )

    private fun float(id: String, status: FloatStatus) = CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u2", holderName = "Ada", departmentId = null, status = status,
        currency = "GBP", requestedAmount = 250.0, issuedAmount = 0.0, balance = 0.0, receiptsAmount = 0.0,
        receiptsCommits = null, returnAmount = 0.0, bsCode = null, companyId = null, duration = null,
        durationType = null, purpose = null, createdAt = 1_754_000_000_000,
    )

    @Test
    fun `after sending a float request the page reads it back with the journey`() = render(sentFloat()) {
        onNodeWithText("Float request submitted — awaiting approval").assertExists()
        onNodeWithText("£250.00").assertExists()
        onNodeWithText("5 days").assertExists()
        onNodeWithText("Set dressing consumables").assertExists()
        onAllNodesWithText("Review & approve").assertCountEquals(1)
        // No new float has loaded yet, so there is no reference to show.
        onAllNodesWithText("Ref.", ignoreCase = true).assertCountEquals(0)
        // And it is not the form any more.
        onAllNodesWithText("Submit float request").assertCountEquals(0)
    }

    @Test
    fun `the new float, once the list reloads, supplies the reference and the status`() = render(
        sentFloat(float("old", FloatStatus.Closed), float("new", FloatStatus.ReadyToCollect)),
    ) {
        // Once in the previous-floats list and once as the confirmation's own Ref.
        onAllNodesWithText("#PC-new", substring = true).assertCountEquals(2)
        onNodeWithText("Your float is ready to collect — please visit the accounts office").assertExists()
    }
}
