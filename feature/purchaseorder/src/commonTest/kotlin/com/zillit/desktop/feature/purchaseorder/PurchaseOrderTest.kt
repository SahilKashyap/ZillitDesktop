package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun order(
    total: Double = 100.0,
    lines: List<PoLine> = emptyList(),
    status: PoStatus = PoStatus.Draft,
    raisedBy: String? = "user-1",
    assignedTo: String? = null,
) = PurchaseOrder(
    id = "po-1",
    number = "PO-0001",
    vendorId = "v-1",
    vendorName = "Panavision",
    description = "Camera package",
    departmentId = null,
    companyId = null,
    status = status,
    currency = "GBP",
    total = total,
    vatTreatment = null,
    nominalCode = null,
    episode = null,
    notes = null,
    effectiveDate = null,
    createdAt = null,
    raisedBy = raisedBy,
    assignedTo = assignedTo,
    reassignmentReason = null,
    deliveryAddress = null,
    lines = lines,
)

private fun line(quantity: Double, price: Double) =
    PoLine(id = null, description = "Line", quantity = quantity, unitPrice = price, nominalCode = null, vatRate = null)

class PurchaseOrderTest {

    @Test
    fun `a header total that disagrees with the lines is flagged`() {
        val mismatched = order(total = 100.0, lines = listOf(line(2.0, 40.0)))
        assertTrue(mismatched.totalsDisagree)
        assertEquals(80.0, mismatched.lineTotal)

        val consistent = order(total = 80.0, lines = listOf(line(2.0, 40.0)))
        assertFalse(consistent.totalsDisagree)
    }

    @Test
    fun `an order with no lines never disagrees with itself`() {
        // Header-only orders are ordinary on some productions; flagging every
        // one of them would make the warning meaningless.
        assertFalse(order(total = 500.0, lines = emptyList()).totalsDisagree)
    }

    @Test
    fun `rounding noise is not a disagreement`() {
        val order = order(total = 80.001, lines = listOf(line(2.0, 40.0)))
        assertFalse(order.totalsDisagree)
    }

    @Test
    fun `status decides what may still be edited and what is committed`() {
        assertTrue(PoStatus.Draft.isEditable)
        assertTrue(PoStatus.Rejected.isEditable)
        assertFalse(PoStatus.AwaitingApproval.isEditable)
        assertFalse(PoStatus.Approved.isEditable)

        assertTrue(PoStatus.Approved.isCommitted)
        assertTrue(PoStatus.AccountsEntered.isCommitted)
        assertTrue(PoStatus.Posted.isCommitted)
        assertFalse(PoStatus.Draft.isCommitted)
        assertFalse(PoStatus.Rejected.isCommitted)

        // The server's spelling: upper-case, and the names it actually uses.
        assertEquals(PoStatus.AwaitingApproval, PoStatus.from("PENDING"))
        assertEquals(PoStatus.AccountsEntered, PoStatus.from("ACCT_ENTERED"))
        assertEquals(PoStatus.Queued, PoStatus.from("QUEUED"))
        assertEquals(PoStatus.Unknown, PoStatus.from("something_new"))
    }

    @Test
    fun `unknown statuses degrade rather than throw`() {
        assertEquals(PoStatus.Unknown, PoStatus.from("something_new"))
        assertEquals(PoStatus.Approved, PoStatus.from("APPROVED"))
    }

    @Test
    fun `an order is yours if you raised it, hold it, or have full access`() {
        val raiser = PoViewer("user-1", "department_art", null)
        val stranger = PoViewer("user-9", "department_art", null)
        val assignee = PoViewer("user-2", "department_art", null)
        val controller = PoViewer("user-9", "department_accounts", "designation_financial_controller_accounts")

        val row = order(assignedTo = "user-2")
        assertTrue(raiser.owns(row))
        assertTrue(assignee.owns(row))
        assertFalse(stranger.owns(row))
        assertTrue(controller.owns(row))
    }

    @Test
    fun `the production-wide list is gated and everything else is not`() {
        val crew = PoViewer("user-1", "department_camera", null)
        val accountant = PoViewer("user-2", "department_accounts", null)

        assertFalse(PoDestination.AllPos.visibleTo(crew))
        assertTrue(PoDestination.AllPos.visibleTo(accountant))
        assertTrue(PoDestination.MyPos.visibleTo(crew))
        assertTrue(PoDestination.Form.visibleTo(crew))
    }

    @Test
    fun `seniority is matched on identifiers and on display names alike`() {
        assertTrue(PoViewer("u", "department_accounts", "designation_production_accountant_accounts").hasFullAccess)
        assertTrue(PoViewer("u", "department_accounts", "Financial Controller").hasFullAccess)
        assertFalse(PoViewer("u", "department_accounts", "Assistant Accountant").hasFullAccess)
    }

    @Test
    fun `a new order names the first thing wrong with it`() {
        val blank = NewPurchaseOrder(null, "", "", null, null, null, null, null, null, null, emptyList())
        assertEquals("Choose the vendor this order is with.", blank.validationError())

        val noDescription = blank.copy(vendorName = "Panavision")
        assertEquals("Describe what is being ordered.", noDescription.validationError())

        val noLines = noDescription.copy(description = "Camera package")
        assertEquals("Add at least one line.", noLines.validationError())

        val blankLine = noLines.copy(lines = listOf(line(1.0, 10.0).copy(description = "")))
        assertEquals("Every line needs a description.", blankLine.validationError())

        val freeLine = noLines.copy(lines = listOf(line(0.0, 10.0)))
        assertEquals("Every line needs a quantity and a price.", freeLine.validationError())

        val good = noLines.copy(lines = listOf(line(2.0, 40.0)))
        assertNull(good.validationError())
        assertEquals(80.0, good.total)
    }

    @Test
    fun `a valid order survives the whole gauntlet`() {
        val order = NewPurchaseOrder(
            vendorId = "v-1",
            vendorName = "Panavision",
            description = "Camera package",
            departmentId = null,
            companyId = null,
            currency = "GBP",
            nominalCode = "4100",
            episode = null,
            notes = null,
            effectiveDate = null,
            lines = listOf(line(1.0, 2_500.0), line(2.0, 125.0)),
        )
        assertNull(order.validationError())
        assertEquals(2_750.0, order.total)
        assertNotNull(order.nominalCode)
    }
    /**
     * Full access is two rules, not one.
     *
     * The web gates the accountant console on the senior designation and says
     * twice that `is_admin` must not bypass it ("Admin alone must NOT bypass
     * the assignment-based row gate"), while the department queue is gated on
     * `is_admin` alone. This port ORed them together, so any admin in the
     * accounts department saw every order on the production.
     */
    @Test
    fun `an accounts admin who is not senior does not see every order`() {
        val adminInAccounts = PoViewer(
            userId = "u1",
            departmentIdentifier = "designation_1st_assistant_accountant_accounts",
            designationIdentifier = "designation_1st_assistant_accountant_accounts",
            isProjectAdmin = true,
        )

        assertTrue(adminInAccounts.isAccountant, "accounts department puts them in the console view")
        assertFalse(adminInAccounts.hasFullAccess, "admin bypassed the accountant gate")
    }

    @Test
    fun `a production accountant sees every order`() {
        val senior = PoViewer(
            userId = "u1",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
        )

        assertTrue(senior.hasFullAccess)
    }

    /** The department queue keeps its own rule: the project-owner flag. */
    @Test
    fun `outside accounts the project admin still sees every order`() {
        val admin = PoViewer(
            userId = "u1",
            departmentIdentifier = "department_art",
            designationIdentifier = "designation_art_director_art",
            isProjectAdmin = true,
        )

        assertFalse(admin.isAccountant)
        assertTrue(admin.hasFullAccess, "the department queue is gated on is_admin")
    }

    @Test
    fun `outside accounts a non-admin sees only their own`() {
        val crew = PoViewer("u1", "department_art", "designation_art_director_art")

        assertFalse(crew.hasFullAccess)
    }

}
