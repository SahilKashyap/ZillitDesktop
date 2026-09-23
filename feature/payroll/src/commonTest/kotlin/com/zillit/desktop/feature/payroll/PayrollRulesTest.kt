package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.Employment
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.RunRow
import com.zillit.desktop.feature.payroll.domain.RunSelection
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.rowActionAllowed
import com.zillit.desktop.feature.payroll.domain.rowActionFor
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val crew = PayrollViewer("u", "department_camera", "designation_gaffer")
internal val accountant = PayrollViewer("u", "department_accounts", "designation_assistant_accountant_accounts")
internal val approver = accountant.copy(onApproverList = true)
internal val payrollAccountant = PayrollViewer("u", "department_accounts", "designation_payroll_accounts")
internal val controller = PayrollViewer("u", "department_accounts", "designation_financial_controller_accounts")

class TimecardStatusTest {

    @Test
    fun `the payroll half of the lifecycle reads`() {
        assertEquals(TimecardStatus.FinalApproved, TimecardStatus.from("final_approved"))
        assertEquals(TimecardStatus.Unpaid, TimecardStatus.from("UNPAID"))
        assertEquals(TimecardStatus.Unknown, TimecardStatus.from("brand_new"))
        assertEquals(TimecardStatus.Unknown, TimecardStatus.from(null))
    }

    /** The web's `paidEligibleIds`: locked after the run, unpaid after a reversal — nothing else. */
    @Test
    fun `only locked and unpaid weeks are payable`() {
        val payable = TimecardStatus.entries.filter { it.isPayable }.toSet()
        assertEquals(setOf(TimecardStatus.Locked, TimecardStatus.Unpaid), payable)
        assertFalse(TimecardStatus.Approved.isPayable)
        assertTrue(TimecardStatus.Paid.isUnpayable)
        assertTrue(TimecardStatus.Paid.isPostable)
        assertFalse(TimecardStatus.Unknown.isPostable)
    }

    @Test
    fun `the server refuses writes to locked, paid, posted and rejected weeks`() {
        val refused = TimecardStatus.entries.filter { it.refusesWrites }.toSet()
        assertEquals(
            setOf(TimecardStatus.Locked, TimecardStatus.Paid, TimecardStatus.Posted, TimecardStatus.Rejected),
            refused,
        )
    }
}

class PayrollViewerTest {

    @Test
    fun `the approver is on the list or senior`() {
        assertFalse(accountant.isFinalApprover)
        assertTrue(approver.isFinalApprover)
        assertTrue(controller.isFinalApprover)
        assertTrue(PayrollViewer("u", "department_accounts", "Production Accountant").isFinalApprover)
    }

    @Test
    fun `locking is the payroll accountant's, or senior`() {
        assertFalse(approver.isPayrollAccountant)
        assertTrue(payrollAccountant.isPayrollAccountant)
        assertTrue(controller.isPayrollAccountant)
        assertTrue(
            PayrollViewer("u", "department_accounts", "designation_office_production_assistant_additional_crew")
                .isPayrollAccountant,
        )
    }

    @Test
    fun `the accountant screens are the accounts department's`() {
        assertFalse(crew.seesAccountantViews)
        assertTrue(accountant.seesAccountantViews)
    }
}

class RunRulesTest {

    private fun row(id: String, status: TimecardStatus) =
        RunRow(PayrollTimecard(id = id, userId = "u-$id", status = status), name = id, role = "", department = "")

    private val selection = RunSelection.of(
        listOf(
            row("a", TimecardStatus.Approved),
            row("f", TimecardStatus.FinalApproved),
            row("l", TimecardStatus.Locked),
            row("p", TimecardStatus.Unpaid),
            row("d", TimecardStatus.Draft),
        ),
        setOf("a", "f", "l", "p", "d"),
    )

    /** The web's `PayrollRunToolbar` matrix. */
    @Test
    fun `each role is offered its own approval button`() {
        assertEquals(RunAction.FinalApproveAndLock to 1, selection.approvalFor(controller))
        assertEquals(RunAction.FinalApprove to 1, selection.approvalFor(approver))
        assertEquals(RunAction.Lock to 1, selection.approvalFor(payrollAccountant))
        assertNull(selection.approvalFor(accountant))
        // Both roles, nothing at Approved: the button flips to Lock.
        val onlyFinal = RunSelection.of(listOf(row("f", TimecardStatus.FinalApproved)), setOf("f"))
        assertEquals(RunAction.Lock to 1, onlyFinal.approvalFor(controller))
    }

    @Test
    fun `mark paid takes the locked and the unpaid rows together`() {
        assertTrue(selection.offersMarkPaid)
        assertEquals(listOf("l", "p"), selection.paidEligibleIds)
        assertFalse(selection.markPaidFromLockedOnly)
        assertEquals(2, selection.count(RunAction.MarkPaid))
    }

    /** First match wins, as on the web (`PayrollRunModule.jsx` 3630-3771). */
    @Test
    fun `a row's button follows the web's order`() {
        assertEquals(RowAction.Override, rowActionFor(TimecardStatus.Submitted, accountant, canOverride = true))
        assertEquals(RowAction.View, rowActionFor(TimecardStatus.Submitted, accountant, canOverride = false))
        assertEquals(RowAction.FinalApprove, rowActionFor(TimecardStatus.Approved, approver, false))
        assertEquals(RowAction.FinalApproveAndLock, rowActionFor(TimecardStatus.Approved, controller, false))
        assertEquals(RowAction.Lock, rowActionFor(TimecardStatus.FinalApproved, payrollAccountant, false))
        // A locked week is anyone's to pay; an unpaid one only the approver's.
        assertEquals(RowAction.MarkPaid, rowActionFor(TimecardStatus.Locked, accountant, false))
        assertEquals(RowAction.View, rowActionFor(TimecardStatus.Unpaid, accountant, false))
        assertEquals(RowAction.MarkPaid, rowActionFor(TimecardStatus.Unpaid, approver, false))
        assertEquals(RowAction.MarkUnpaid, rowActionFor(TimecardStatus.Paid, approver, false))
        assertEquals(RowAction.View, rowActionFor(TimecardStatus.Paid, accountant, false))
    }

    @Test
    fun `a row event the row would not show is not allowed`() {
        assertFalse(rowActionAllowed(RowAction.MarkUnpaid, TimecardStatus.Paid, accountant, false))
        assertFalse(rowActionAllowed(RowAction.Lock, TimecardStatus.FinalApproved, approver, false))
        assertTrue(rowActionAllowed(RowAction.Lock, TimecardStatus.FinalApproved, payrollAccountant, false))
    }

    @Test
    fun `employment reads the web's order`() {
        assertEquals(Employment.BuyOut, Employment.of("loanout", "buy-out"))
        assertEquals(Employment.Paye, Employment.of(null, null))
        assertEquals(Employment.LoanOut, Employment.of("ltd-inside", null))
        assertEquals(Employment.ScheduleD, Employment.of("schedule_d", null))
        assertEquals(Employment.Paye, Employment.of("employee", null))
    }

    /** Holiday pay and NIC are the grid's estimates only when the server sends no fringes. */
    @Test
    fun `the run row's cost follows the web's figures`() {
        val estimated = RunRow(
            PayrollTimecard(id = "a", userId = "u", status = TimecardStatus.Locked, basicPay = 1000.0, totalDays = 5),
            "a", "", "",
        )
        assertEquals(1000.0, estimated.gross)
        assertEquals(120.7, estimated.holidayPay)
        assertEquals(123.75, estimated.employerNic)
        val fringed = estimated.copy(timecard = estimated.timecard.copy(fringesTotal = 90.0))
        assertEquals(90.0, fringed.holidayPay)
        assertEquals(0.0, fringed.employerNic)
    }
}

class PayPeriodTest {

    // Wednesday 2026-05-06 12:00 UTC.
    private val wednesdayNoon = 1_778_068_800_000

    @Test
    fun `a period starts on the production's own day, in UTC`() {
        assertEquals("2026-05-04", PayPeriod.isoDate(PayPeriod.startOf(wednesdayNoon, PayPeriod.MONDAY)))
        assertEquals("2026-05-06", PayPeriod.isoDate(PayPeriod.startOf(wednesdayNoon, 3)))
        assertEquals("2026-05-03", PayPeriod.isoDate(PayPeriod.startOf(wednesdayNoon, 7)))
    }

    @Test
    fun `stepping a week keeps the boundary`() {
        val monday = PayPeriod.startOf(wednesdayNoon, PayPeriod.MONDAY)
        assertEquals("2026-04-27", PayPeriod.isoDate(PayPeriod.shift(monday, -1, PayPeriod.MONDAY)))
        assertEquals("2026-05-11", PayPeriod.isoDate(PayPeriod.shift(monday, 1, PayPeriod.MONDAY)))
    }

    @Test
    fun `the labels read like the web's`() {
        val monday = PayPeriod.startOf(wednesdayNoon, PayPeriod.MONDAY)
        assertEquals("04 May – 10 May 2026", PayPeriod.rangeLabel(monday))
        assertEquals("04 May–10 May 2026", PayPeriod.compactRangeLabel(monday))
        assertEquals("10 May 2026", PayPeriod.weekEnding(monday))
        assertEquals("Mon 04 May", PayPeriod.dayLabel(monday))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), PayPeriod.weekdayLetters(monday))
    }

    @Test
    fun `dates parse strictly and the lock leaves the next day open`() {
        assertEquals(PayPeriod.startOf(wednesdayNoon, 3), PayPeriod.parseIsoDate("2026-05-06"))
        assertNull(PayPeriod.parseIsoDate("2026-02-30"))
        assertNull(PayPeriod.parseIsoDate("06/05/2026"))
        assertEquals("2026-05-15", PayPeriod.dayAfter("2026-05-14"))
        assertEquals("2026-03-01", PayPeriod.dayAfter("2026-02-28"))
    }

    @Test
    fun `a worked time is read in UTC`() {
        assertEquals("07:30", PayPeriod.hhmm(PayPeriod.startOf(wednesdayNoon, 3) + 7 * 3_600_000 + 30 * 60_000))
        assertEquals("—", PayPeriod.hhmm(null))
    }
}

class PayrollRouteTest {

    @Test
    fun `a route names the web's tile slugs under the tool`() {
        assertEquals(PayrollDestination.Landing, PayrollDestination.forRoute("/film-tools/payroll"))
        assertEquals(PayrollDestination.Processing, PayrollDestination.forRoute("/film-tools/payroll/processing"))
        assertEquals(PayrollDestination.Run, PayrollDestination.forRoute("/film-tools/payroll/run?entry=tool"))
        assertEquals(PayrollDestination.History, PayrollDestination.forRoute("/film-tools/payroll/accountant-payroll/"))
        // An unknown tile is the landing, as the web's router navigates it.
        assertEquals(PayrollDestination.Landing, PayrollDestination.forRoute("/film-tools/payroll/producer-board"))
    }

    @Test
    fun `the tool-tile marker is read off the query`() {
        assertTrue(PayrollDestination.enteredAsTool("/film-tools/payroll?entry=tool"))
        assertFalse(PayrollDestination.enteredAsTool("/film-tools/payroll"))
    }

    /** The web's filter (`PayrollLandingPage.jsx` 150-166). */
    @Test
    fun `the accountant grid is the accountant's, from the hub only`() {
        assertEquals(PayrollTile.entries, PayrollTile.visibleTo(accountant, enteredAsTool = false))
        assertTrue(PayrollTile.visibleTo(accountant, enteredAsTool = true).isEmpty())
        val producer = crew.copy(canView = true, rightsLoaded = true)
        assertTrue(PayrollTile.visibleTo(producer, enteredAsTool = false).isEmpty())
        assertFalse(PayrollDestination.Run.visibleTo(crew))
        assertTrue(PayrollDestination.Landing.visibleTo(crew))
    }
}
