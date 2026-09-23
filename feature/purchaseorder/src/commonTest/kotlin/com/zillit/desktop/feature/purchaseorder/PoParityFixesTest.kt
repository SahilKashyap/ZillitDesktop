package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.purchaseorder.data.PoApprovalDto
import com.zillit.desktop.feature.purchaseorder.data.toApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoCashFlow
import com.zillit.desktop.feature.purchaseorder.domain.PoCurrencyRates
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoPeriodLock
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTaxType
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoQueueScope
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.ledger
import com.zillit.desktop.feature.purchaseorder.ui.pages.totalValue
import com.zillit.desktop.feature.purchaseorder.ui.requiresHeader
import com.zillit.desktop.feature.purchaseorder.ui.toEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ISO day as the UTC-midnight millis the service stores. */
private fun day(iso: String): Long? = iso.isoDayToUtcMidnight()

@Suppress("LongParameterList") // A fixture: every field a test varies, named at the call.
internal fun parityOrder(
    id: String = "po-1",
    status: PoStatus = PoStatus.AwaitingApproval,
    departmentId: String? = "dept-cam",
    gross: Double = 1_000.0,
    raisedBy: String? = "raiser",
    assignedTo: String? = null,
    approvals: List<PoApproval> = emptyList(),
    lines: List<PoLine> = emptyList(),
    effectiveDate: Long? = null,
    currency: String? = "GBP",
    paidAmount: Double = 0.0,
) = PurchaseOrder(
    id = id,
    number = "PO-0001",
    vendorId = "v-1",
    vendorName = "Panavision",
    description = "Camera package",
    departmentId = departmentId,
    companyId = null,
    status = status,
    currency = currency,
    total = gross,
    vatTreatment = null,
    nominalCode = "2400",
    episode = null,
    notes = null,
    effectiveDate = effectiveDate,
    createdAt = null,
    raisedBy = raisedBy,
    assignedTo = assignedTo,
    reassignmentReason = null,
    deliveryAddress = null,
    lines = lines,
    approvals = approvals,
    grossAmount = gross,
    paidAmount = paidAmount,
)

/** Two tiers for the production; camera has its own tier 1 with an amount rule. */
internal val TIERS_JSON = """
    [
      {"scope":"all","department_id":null,"tiers":[
        {"order":1,"rules":[{"type":"default","user_ids":["lp"]}]},
        {"order":2,"rules":[{"type":"default","user_ids":["accountant"]}]}
      ]},
      {"scope":"department","department_id":"dept-cam","tiers":"[{\"order\":1,\"rules\":[{\"type\":\"default\",\"user_ids\":[\"hod\"]},{\"type\":\"amount\",\"amount_threshold\":5000,\"user_ids\":[\"big\"]}]},{\"order\":2,\"rules\":[{\"type\":\"default\",\"user_ids\":[]}]}]"}
    ]
""".trimIndent()

internal fun parityTiers(): PoApprovalTiers = Json.parseToJsonElement(TIERS_JSON).toApprovalTiers()

class PoParityFixesTest {

    // -- 1. the senior rule ----------------------------------------------------

    /** The web's own test: an assistant production accountant is not senior. */
    @Test
    fun `an assistant production accountant is not senior in any spelling`() {
        listOf(
            "designation_assistant_production_accountant_accounts",
            "assistant_production_accountant_label",
            "Assistant Production Accountant",
            "designation_1st_assistant_accountant_accounts",
            "designation_financial_controller_assistant_accounts",
        ).forEach { designation ->
            val viewer = PoViewer("u", "department_accounts", designation)
            assertFalse(viewer.isSeniorAccountant, "$designation read as senior")
            assertFalse(PoDestination.Posted.visibleTo(viewer), "$designation saw Posted")
            assertFalse(PoDestination.Settings.visibleTo(viewer), "$designation saw Settings")
            assertFalse(viewer.hasFullAccess, "$designation got full access")
        }
    }

    @Test
    fun `the two senior designations are senior as identifier, label key and name`() {
        listOf(
            "designation_production_accountant_accounts",
            "designation_financial_controller_accounts",
            "production_accountant_label",
            "financial_controller_label",
            "Production Accountant",
            "Financial Controller",
        ).forEach { designation ->
            assertTrue(PoViewer("u", "department_accounts", designation).isSeniorAccountant, designation)
        }
    }

    // -- 2 and 3. approval tiers and the approvals' keys ----------------------------

    @Test
    fun `the tier chain resolves department first, with amount rules and the global fallback`() {
        val tiers = parityTiers()
        // Camera, a small order: its own tier 1 (the default rule), and its empty
        // tier 2 borrows the production's tier 2.
        val small = tiers.resolve("dept-cam", 1_000.0)
        assertEquals(listOf("hod"), small[0].map { it.userId })
        assertEquals(listOf("accountant"), small[1].map { it.userId })
        // A big one: the amount rule replaces the default approvers.
        assertEquals(listOf("big"), tiers.resolve("dept-cam", 9_000.0)[0].map { it.userId })
        // Another department falls back to the production's chain.
        assertEquals(listOf("lp"), tiers.resolve("dept-art", 1_000.0)[0].map { it.userId })
    }

    /** An accountant on tier 2 approves from the console once tier 1 has. */
    @Test
    fun `an accountant who sits on the next tier may approve on any tab`() {
        val tiers = parityTiers()
        val firstDone = parityOrder(approvals = listOf(PoApproval("hod", "", 1, "approved", null, 1L)))
        val step = tiers.visibility(firstDone, "accountant")
        assertTrue(step.canApprove)
        assertEquals(2, step.tierNumber)
        assertEquals(2, step.tierCount)

        assertFalse(tiers.visibility(firstDone, "hod").canApprove, "tier 1's approver is done")
        assertFalse(tiers.visibility(parityOrder(), "accountant").canApprove, "tier 1 has not approved yet")
        assertFalse(
            tiers.visibility(parityOrder(status = PoStatus.Approved), "accountant").canApprove,
            "only a pending order has a decision",
        )
        assertFalse(PoApprovalTiers().visibility(parityOrder(), "hod").canApprove, "no chain, nobody approves")
    }

    @Test
    fun `the approvals read the web's tier_number and approved_at`() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(
            PoApprovalDto.serializer(),
            """{"user_id":"hod","tier_number":2,"approved_at":1767225600000}""",
        )
        val approval = dto.toDomain()
        assertEquals(2, approval.level)
        assertTrue(approval.decided)
        assertEquals(1_767_225_600_000L, approval.at)

        val legacy = json.decodeFromString(PoApprovalDto.serializer(), """{"level":1,"decision":"approved"}""")
        assertEquals(1, legacy.toDomain().level)
        assertTrue(legacy.toDomain().decided)
    }

    @Test
    fun `the pending label counts approvals over the configured tiers`() {
        val order = parityOrder(approvals = listOf(PoApproval("hod", "", 1, "approved", null, 1L)))
        assertEquals("Pending (1/2)", order.statusLabel(parityTiers()))
        assertEquals("Pending", order.statusLabel(PoApprovalTiers()), "no configuration, no count")
    }

    @Test
    fun `the legacy tier shape still resolves`() {
        val legacy = Json.parseToJsonElement(
            """{"1":[{"user_id":"hod","department_id":"dept-cam"}],"2":[{"user_id":"accountant"}]}""",
        ).toApprovalTiers()
        assertTrue(legacy.visibility(parityOrder(), "hod").canApprove)
        assertFalse(legacy.visibility(parityOrder(departmentId = "dept-art"), "hod").canApprove)
    }

    // -- 5. the processing page's ledger ----------------------------------------------

    private val recoverable = PoTaxType("GB_vat_standard", "Standard", 20.0, recoverable = true)

    private fun coded(nominal: String? = "2400") = PoLine(
        id = "l1",
        description = "Camera body",
        quantity = 1.0,
        unitPrice = 100.0,
        nominalCode = nominal,
        vatRate = 20.0,
        taxType = recoverable.id,
        trackingCodes = JsonObject(mapOf("set-1" to JsonPrimitive("node-9"))),
    )

    private val taxRow = PoLine(
        id = "tax-row-1",
        description = "",
        quantity = 1.0,
        unitPrice = 20.0,
        nominalCode = "2200",
        vatRate = null,
        amount = 20.0,
        isTax = true,
    )

    /** The persisted tax row comes off the editable lines and goes back on every write. */
    @Test
    fun `opening keeps the tax row apart and every write appends it again`() {
        val order = parityOrder(status = PoStatus.AccountsEntered, gross = 120.0, lines = listOf(coded(), taxRow))
        val entry = order.toEntry()
        assertEquals(listOf("l1"), entry.lines.map { it.id }, "the tax row is not an editable line")
        assertEquals(20.0, entry.taxOverride)

        val state = PoUiState(viewer = PoViewer("u", "department_accounts", null), taxTypes = listOf(recoverable))
        val ledger = entry.ledger(state)
        assertTrue(ledger.balances(order))
        val written = ledger.linesWithTax
        assertEquals(2, written.size)
        val tax = written.last()
        assertTrue(tax.isTax)
        assertEquals("tax-row-1", tax.id, "the persisted row is updated in place")
        assertEquals("2200", tax.nominalCode)
        assertEquals(20.0, tax.total)
        // The coded line keeps what this client never edits.
        assertEquals("node-9", (written.first().trackingCodes?.get("set-1") as JsonPrimitive).content)
    }

    @Test
    fun `post names the rows with no nominal, the tax row included`() {
        val order = parityOrder(status = PoStatus.AccountsEntered, gross = 120.0, lines = listOf(coded(nominal = null)))
        val state = PoUiState(viewer = PoViewer("u", "department_accounts", null), taxTypes = listOf(recoverable))
        // A header-less order: no code to seed the line with.
        val entry = order.copy(nominalCode = null).toEntry()
        val ledger = entry.ledger(state)
        assertEquals(20.0, ledger.taxAmount, "the reclaimable tax is derived from the line")
        assertEquals(listOf(1, 2), ledger.missingCodes)
    }

    @Test
    fun `a line with no code starts on the order's header code`() {
        val order = parityOrder(status = PoStatus.AccountsEntered, lines = listOf(coded(nominal = null)))
        assertEquals("2400", order.toEntry().lines.single().nominalCode)
    }

    // -- 6. the form's required header fields ------------------------------------------

    @Test
    fun `vendor and description are required only where the template requires them`() {
        val hidden = FormLayout(
            FormTemplate(
                sections = listOf(
                    FormSection(
                        key = PoFormFields.DETAILS,
                        label = "PO Details",
                        fields = listOf(
                            FormField(
                                label = "vendor",
                                name = "Vendor",
                                required = true,
                                systemDefault = true,
                                hidden = true,
                            ),
                            FormField(label = "description", name = "Description", systemDefault = true),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(hidden.requiresHeader(PoFormFields.VENDOR), "a hidden field cannot be required")
        assertFalse(hidden.requiresHeader(PoFormFields.DESCRIPTION), "an optional field is optional")
        assertTrue(FormLayout(FormTemplate()).requiresHeader(PoFormFields.VENDOR), "no template read: both shown")
    }

    // -- 7. delete ---------------------------------------------------------------------

    @Test
    fun `delete is the raiser's before approval and accounts' only on Acct Entered`() {
        val senior = PoViewer("s", "department_accounts", "designation_production_accountant_accounts")
        val raiser = PoViewer("raiser", "department_camera", null)
        listOf(PoStatus.Posted, PoStatus.Closed, PoStatus.Approved, PoStatus.AwaitingApproval).forEach { status ->
            assertFalse(PoAccess.canDelete(parityOrder(status = status), senior), "a senior deleted $status")
        }
        assertTrue(PoAccess.canDelete(parityOrder(status = PoStatus.AccountsEntered), senior))
        assertTrue(PoAccess.canDelete(parityOrder(status = PoStatus.Queued), senior))
        assertTrue(PoAccess.canDelete(parityOrder(status = PoStatus.AwaitingApproval), raiser))
        assertTrue(PoAccess.canDelete(parityOrder(status = PoStatus.Draft), raiser))
        assertFalse(PoAccess.canDelete(parityOrder(status = PoStatus.Rejected), raiser), "rejected: edit, resubmit")
        assertFalse(PoAccess.canDelete(parityOrder(status = PoStatus.Approved), raiser))

        val assignee = PoViewer("acc", "department_accounts", null)
        val entered = parityOrder(status = PoStatus.AccountsEntered, assignedTo = "acc")
        assertFalse(PoAccess.canDelete(entered, assignee), "not from the detail")
        assertTrue(PoAccess.canDelete(entered, assignee, onProcessingPage = true), "from the page they process on")
    }

    // -- 8. the period lock --------------------------------------------------------------

    @Test
    fun `the lock covers its boundary day and everything before it`() {
        val lock = PoPeriodLock(lockedThrough = "2026-08-30")
        assertTrue(lock.locks("2026-08-30".isoDayToUtcMidnight()))
        assertTrue(lock.locks("2026-01-01".isoDayToUtcMidnight()))
        assertFalse(lock.locks("2026-08-31".isoDayToUtcMidnight()))
        assertFalse(lock.locks(null), "an undated order is never locked")
        assertFalse(PoPeriodLock().locks("2020-01-01".isoDayToUtcMidnight()), "no lock, nothing locked")
    }

    // -- 9. lists ---------------------------------------------------------------------------

    @Test
    fun `a mixed-currency total converts into the default through the rates`() {
        val state = PoUiState(
            viewer = PoViewer("u", "department_accounts", null),
            rates = PoCurrencyRates(defaultCode = "GBP", rates = mapOf("USD" to 1.25)),
        )
        val orders = listOf(
            parityOrder(id = "a", gross = 100.0, currency = "GBP"),
            parityOrder(id = "b", gross = 125.0, currency = "USD"),
        )
        assertEquals("£200.00", orders.totalValue(state))
    }

    @Test
    fun `the cash flow forecast buckets posted and committed orders by payment week`() {
        val today = "2026-09-01".isoDayToUtcMidnight()!!
        val orders = listOf(
            // Effective 5 Aug → paid 4 Sep → week 1, confirmed.
            parityOrder(id = "a", status = PoStatus.Posted, gross = 12_400.0, effectiveDate = day("2026-08-05")),
            // Effective 20 Aug → paid 19 Sep → week 3, committed.
            parityOrder(id = "b", status = PoStatus.Approved, gross = 3_000.0, effectiveDate = day("2026-08-20")),
            // Pending is neither.
            parityOrder(id = "c", status = PoStatus.AwaitingApproval, effectiveDate = day("2026-08-20")),
            // Undated is not in the forecast.
            parityOrder(id = "d", status = PoStatus.Posted, effectiveDate = null),
        )
        val weeks = PoCashFlow.weeks(orders, today)
        assertEquals(6, weeks.size)
        assertEquals(12L, weeks[0].confirmedK)
        assertEquals(3L, weeks[2].committedK)
        assertEquals(15L, weeks.sumOf { it.totalK })
    }

    // -- 10. the route ------------------------------------------------------------------

    @Test
    fun `a queue route names its half`() {
        assertEquals(PoQueueScope.All, PoDestination.queueScopeFor("/film-tools/purchase-order/queue/all"))
        assertEquals(PoQueueScope.Mine, PoDestination.queueScopeFor("/film-tools/purchase-order/queue/my"))
        assertNull(PoDestination.queueScopeFor("/film-tools/purchase-order/queue"))
        assertNull(PoDestination.queueScopeFor("/film-tools/purchase-order/posted"))
        assertNotNull(PoDestination.forRoute("/film-tools/purchase-order/queue/all"))
    }
}
