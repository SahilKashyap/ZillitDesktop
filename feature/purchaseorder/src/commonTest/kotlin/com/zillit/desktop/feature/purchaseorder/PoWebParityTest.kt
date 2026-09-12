package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoQuickFilter
import com.zillit.desktop.feature.purchaseorder.domain.PoRelief
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoTotals
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.RENTAL_EXPENDITURE_TYPE
import com.zillit.desktop.feature.purchaseorder.domain.splitCadence
import com.zillit.desktop.feature.purchaseorder.ui.PoAudience
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.periodsIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules this port exists to match, pinned.
 *
 * Every one of them fails *silently* when it is wrong — the web's own
 * `po-permissions.js` says so twice — so a button simply never appears on an
 * order that should have had it, and nothing anywhere says why.
 */
class PoWebParityTest {

    private val senior = PoViewer(
        userId = "acct-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val assistant = PoViewer(
        userId = "acct-2",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_accounts_assistant_accounts",
    )

    private val crew = PoViewer(
        userId = "crew-1",
        departmentIdentifier = "department_camera",
        designationIdentifier = "designation_focus_puller_camera",
    )

    private fun order(
        status: PoStatus = PoStatus.Approved,
        assignedTo: String? = "acct-2",
        raisedBy: String? = "crew-1",
        emailAt: Long? = null,
        gross: Double = 1_000.0,
        paid: Double = 0.0,
    ) = PurchaseOrder(
        id = "po-1",
        number = "PO-0001",
        vendorId = "v-1",
        vendorName = "Panavision",
        description = "Camera package",
        departmentId = "dept-cam",
        companyId = null,
        status = status,
        currency = "GBP",
        total = gross,
        vatTreatment = null,
        nominalCode = "4100",
        episode = null,
        notes = null,
        effectiveDate = null,
        createdAt = null,
        raisedBy = raisedBy,
        assignedTo = assignedTo,
        reassignmentReason = null,
        deliveryAddress = null,
        grossAmount = gross,
        paidAmount = paid,
        emailAt = emailAt,
    )

    // -- the tab strip ---------------------------------------------------------

    /**
     * The accounts console is the web's `TABS`; the department view is its
     * `DepartmentPOModule`'s. Neither role sees the other's.
     */
    @Test
    fun `each role sees its own module's tabs`() {
        val console = PoDestination.entries.filter { it.visibleTo(senior) }.map { it.label }
        assertEquals(
            listOf("All POs", "Queue", "PO Entry", "Posted", "Reports", "Settings"),
            console.take(6),
        )
        val department = PoDestination.entries
            .filter { it.visibleTo(crew) && it.audience == PoAudience.Department }
            .map { it.label }
        assertEquals(
            listOf("Approval Queue", "My POs", "My Department POs", "Vendors", "Invoices"),
            department,
        )
    }

    /** Both roles get the same right-hand group. */
    @Test
    fun `the register tabs are shared`() {
        val expected = listOf("Templates", "PO Drafts", "Delivery Addresses")
        assertEquals(expected, PoDestination.entries.filter { it.isRegisterTab }.map { it.label })
        assertTrue(PoDestination.entries.filter { it.isRegisterTab }.all { it.visibleTo(crew) })
        assertTrue(PoDestination.entries.filter { it.isRegisterTab }.all { it.visibleTo(senior) })
    }

    /** Posted and Settings are the senior designation's, and the web says so in a banner. */
    @Test
    fun `an accounts assistant loses Posted and Settings`() {
        assertTrue(PoDestination.Posted.visibleTo(senior))
        assertTrue(PoDestination.Settings.visibleTo(senior))
        assertFalse(PoDestination.Posted.visibleTo(assistant))
        assertFalse(PoDestination.Settings.visibleTo(assistant))
        assertTrue(PoUiState(viewer = assistant).showAssistantBanner)
        assertFalse(PoUiState(viewer = senior).showAssistantBanner)
    }

    /**
     * The department view's All POs shows every order on the production, so it
     * wants `is_admin` **or** the tool's posting right — and the default is
     * closed, because defaulting open leaks the whole production's spend for
     * the length of a fetch.
     */
    @Test
    fun `the department All POs tab needs admin or the posting right`() {
        assertFalse(PoDestination.DepartmentAllPos.visibleTo(crew))
        assertTrue(PoDestination.DepartmentAllPos.visibleTo(crew.copy(isProjectAdmin = true)))
        assertTrue(PoDestination.DepartmentAllPos.visibleTo(crew.copy(canPostPurchaseOrders = true)))
    }

    /** The action button says different things to the two roles, as the web's does. */
    @Test
    fun `the action button is Enter PO for accounts and Create PO for everyone else`() {
        assertEquals("Enter PO", PoUiState(viewer = senior).createLabel)
        assertEquals("Create PO", PoUiState(viewer = crew).createLabel)
    }

    /** Everyone lands on the page with work on it, not a summary. */
    @Test
    fun `the landing page is the role's own queue`() {
        assertEquals(PoDestination.Queue, PoDestination.landingFor(senior))
        assertEquals(PoDestination.MyPos, PoDestination.landingFor(crew))
    }

    /**
     * Departments and roles arrive as translation keys, not words.
     *
     * All three read verbatim on the first live run —
     * `accounts_department_label` in the department column and the form's
     * picker, `production_accountant_label` in the Reassign list. With no
     * dictionary loaded the fallback title-cases them, which is what these
     * assert; with one loaded the server's wording wins.
     */
    @Test
    fun `a department or role key is read as words, never as a key`() {
        val state = PoUiState(
            viewer = senior,
            departments = listOf(PoDepartment("d1", "accounts_department_label")),
            team = listOf(PoTeamMember("acct-9", "Rudra Pratap", "production_accountant_label")),
        )

        assertEquals("Accounts Department", state.departmentName("d1"))
        assertEquals("Rudra Pratap · Production Accountant", state.memberLabel(state.team.first()))
    }

    /** An id nothing can name is blank, never 24 characters of hex. */
    @Test
    fun `an unnameable department is blank rather than an id`() {
        val state = PoUiState(viewer = senior)

        assertEquals("", state.departmentName("6a1545afb6a3f82234c88ea4"))
        assertEquals("", state.personName("6a1545afb6a3f82234c88ea4"))
    }

    // -- the chips -------------------------------------------------------------

    /**
     * Chips are per page, because the endpoints differ: the approval queue
     * never returns a posted order, so offering the chip there would render a
     * control that can never match anything.
     */
    @Test
    fun `each page offers the chips its endpoint can answer`() {
        fun chips(destination: PoDestination) =
            PoUiState(viewer = senior, destination = destination).quickFilters.map { it.label }

        assertEquals(
            listOf("All", "Pending", "Approved", "Rejected", "Posted", "Closed"),
            chips(PoDestination.AllPos),
        )
        assertEquals(listOf("All", "Pending", "Approved", "Rejected"), chips(PoDestination.Queue))
        assertEquals(
            listOf("All", "Open", "Fully Relieved", "Partially Relieved", "Closed", "Invoice"),
            chips(PoDestination.Posted),
        )
    }

    /**
     * The Approved chip gathers three statuses.
     *
     * `APPROVED`, `ACCT_ENTERED` and `QUEUED` are one situation to a reader,
     * and the web pairs them at every site that checks one — getting it wrong
     * hides orders already entered in the books.
     */
    @Test
    fun `the Approved chip covers every post-approval status`() {
        listOf(PoStatus.Approved, PoStatus.AccountsEntered, PoStatus.Queued).forEach { status ->
            assertTrue(PoQuickFilter.Approved.matches(order(status = status)), status.name)
        }
        assertFalse(PoQuickFilter.Approved.matches(order(status = PoStatus.AwaitingApproval)))
    }

    // -- the permissions -------------------------------------------------------

    /** An approver who is not the assignee may decide on an order but never process it. */
    @Test
    fun `processing is the senior's or the assignee's`() {
        assertTrue(PoAccess.canProcess(order(), senior))
        assertTrue(PoAccess.canProcess(order(assignedTo = "acct-2"), assistant))
        assertFalse(PoAccess.canProcess(order(assignedTo = "acct-9"), assistant))
    }

    /** Pre-approval and terminal statuses are not processable, whoever is looking. */
    @Test
    fun `a pending or dead order cannot be processed`() {
        listOf(PoStatus.Draft, PoStatus.AwaitingApproval, PoStatus.Rejected, PoStatus.Closed).forEach { status ->
            assertFalse(PoAccess.canProcess(order(status = status), senior), status.name)
        }
        // Posted is deliberately absent from that list: the Posted tab's own
        // Process button has always routed a posted order to the coding page.
        assertTrue(PoAccess.canProcess(order(status = PoStatus.Posted), senior))
    }

    /**
     * The vendor email is one-shot everywhere except the processing page.
     *
     * That page is where the order is corrected, so the vendor has to be able
     * to get the amended copy; the read surface would only duplicate it.
     */
    @Test
    fun `the vendor email is one-shot unless a resend is asked for`() {
        val sent = order(emailAt = 1_772_000_000_000)
        assertTrue(PoAccess.canSendVendorEmail(order(), senior))
        assertFalse(PoAccess.canSendVendorEmail(sent, senior))
        assertTrue(PoAccess.canSendVendorEmail(sent, senior, allowResend = true))
        // Status still applies with a resend — it is the only condition dropped.
        assertFalse(
            PoAccess.canSendVendorEmail(order(status = PoStatus.AwaitingApproval), senior, allowResend = true),
        )
    }

    /** A closed order can still be emailed, though it cannot be processed. */
    @Test
    fun `a closed order is emailable but not processable`() {
        assertTrue(PoAccess.canSendVendorEmail(order(status = PoStatus.Closed), senior))
        assertFalse(PoAccess.canProcess(order(status = PoStatus.Closed), senior))
    }

    /** Reassignment is refused pre-approval and on a dead order; posted and closed stay open. */
    @Test
    fun `reassignment follows the web's denylist`() {
        assertFalse(PoAccess.canReassign(order(status = PoStatus.AwaitingApproval), senior))
        assertFalse(PoAccess.canReassign(order(status = PoStatus.Rejected), senior))
        assertTrue(PoAccess.canReassign(order(status = PoStatus.Posted), senior))
        assertTrue(PoAccess.canReassign(order(status = PoStatus.Closed), senior))
        assertFalse(PoAccess.canReassign(order(), crew), "a department user reassigns nothing")
    }

    /**
     * Amendments are paused, so a fully-approved order has no Edit button even
     * with the project setting on. The wire key keeps round-tripping.
     */
    @Test
    fun `amendments stay switched off`() {
        assertFalse(PoAccess.AMENDMENTS_ENABLED)
        assertTrue(PoAccess.canEdit(order(status = PoStatus.Draft), crew, allowAmendAfterApproval = false))
        assertFalse(PoAccess.canEdit(order(status = PoStatus.Approved), crew, allowAmendAfterApproval = true))
        assertFalse(PoAccess.canEdit(order(status = PoStatus.Posted), crew, allowAmendAfterApproval = true))
        assertFalse(
            PoAccess.canEdit(order(), senior, allowAmendAfterApproval = false),
            "only the raiser edits",
        )
    }

    // -- the money -------------------------------------------------------------

    /**
     * A split child carries its parent's tax, so summing the child's rate too
     * would double-count it. The persisted tax row is skipped for the mirror
     * reason: it *is* this sum.
     */
    @Test
    fun `totals skip split children and the tax row`() {
        val lines = listOf(
            PoLine("p", "Parent", 1.0, 100.0, null, 20.0),
            PoLine("c1", "Child", 1.0, 50.0, null, 20.0, splitParentId = "p"),
            PoLine("c2", "Child", 1.0, 50.0, null, 20.0, splitParentId = "p"),
            PoLine("t", "VAT", 1.0, 20.0, null, null, isTax = true),
        )
        val totals = PoTotals.of(lines)
        assertEquals(100.0, totals.net)
        assertEquals(20.0, totals.tax)
        assertEquals(120.0, totals.gross)
    }

    /** Relief is derived, not stored — the Posted tab's column and its chips read it. */
    @Test
    fun `relief is read off the invoiced sum`() {
        assertEquals(PoRelief.Open, order(status = PoStatus.Posted, paid = 0.0).relief)
        assertEquals(PoRelief.PartiallyRelieved, order(status = PoStatus.Posted, paid = 400.0).relief)
        assertEquals(PoRelief.FullyRelieved, order(status = PoStatus.Posted, paid = 1_000.0).relief)
        assertEquals(PoRelief.Closed, order(status = PoStatus.Closed, paid = 400.0).relief)
        assertEquals(600.0, order(status = PoStatus.Posted, paid = 400.0).remaining)
    }

    /** Relief is judged to the half-penny: these are sums of converted decimals. */
    @Test
    fun `a penny of float noise does not read as partially relieved`() {
        assertEquals(PoRelief.FullyRelieved, order(status = PoStatus.Posted, gross = 1_000.0, paid = 999.999).relief)
    }

    // -- the rental split ------------------------------------------------------

    /** The split-by-period button is only ever offered on a window it can cut. */
    @Test
    fun `only a dated rental with a real window is divisible`() {
        val base = PoLine(null, "Stage hire", 1.0, 700.0, null, null)
        assertFalse(base.isDivisibleRental)
        assertFalse(base.copy(expenditureType = RENTAL_EXPENDITURE_TYPE).isDivisibleRental)
        assertFalse(
            base.copy(
                expenditureType = RENTAL_EXPENDITURE_TYPE,
                rentalStart = "2026-03-01",
                rentalEnd = "2026-03-01",
            ).isDivisibleRental,
            "a same-day rental has no window to divide",
        )
        assertTrue(
            base.copy(
                expenditureType = RENTAL_EXPENDITURE_TYPE,
                rentalStart = "2026-03-01",
                rentalEnd = "2026-03-15",
            ).isDivisibleRental,
        )
    }

    /**
     * The last window is clipped to the line's own end.
     *
     * A 10-day hire split weekly is 7 days and 3, not 7 and 7 — a period that
     * runs past the hire is one the vendor will not invoice.
     */
    @Test
    fun `a period split clips its last window`() {
        val line = PoLine(
            null,
            "Stage hire",
            1.0,
            1_000.0,
            null,
            null,
            expenditureType = RENTAL_EXPENDITURE_TYPE,
            rentalStart = "2026-03-01",
            rentalEnd = "2026-03-11",
        )
        assertEquals(
            listOf("2026-03-01" to "2026-03-08", "2026-03-08" to "2026-03-11"),
            periodsIn(line, days = 7),
        )
    }

    /** Auto-split off means the legacy monthly cadence, whatever type is stored. */
    @Test
    fun `the split cadence falls back to monthly when auto-split is off`() {
        assertEquals(
            PoSplitType.Weekly,
            PoSettings(autoSplitRentals = true, splitType = PoSplitType.Weekly).splitCadence,
        )
        assertEquals(
            PoSplitType.Monthly,
            PoSettings(autoSplitRentals = false, splitType = PoSplitType.Weekly).splitCadence,
        )
    }
}
