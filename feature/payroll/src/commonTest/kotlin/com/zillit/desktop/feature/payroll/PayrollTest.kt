package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.DepartmentTotal
import com.zillit.desktop.feature.payroll.domain.PayrollLine
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.PayrollWeek
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.WeekStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WEEK = 1_754_000_000_000

@Suppress("LongParameterList") // A test builder: every field a case might vary.
private fun line(
    id: String = "tc-1",
    status: TimecardStatus = TimecardStatus.Approved,
    basic: Double = 1_000.0,
    overtime: Double = 200.0,
    allowances: Double = 50.0,
    gross: Double = 1_250.0,
    deductions: Double = 0.0,
    departmentId: String? = null,
    departmentName: String? = null,
) = PayrollLine(
    id = id,
    crewId = "user-$id",
    crewName = "Ada",
    departmentId = departmentId,
    departmentName = departmentName,
    designation = "Gaffer",
    status = status,
    currency = "GBP",
    basicPay = basic,
    overtimePay = overtime,
    allowances = allowances,
    deductions = deductions,
    gross = gross,
    net = gross - deductions,
    nominalCode = null,
    queryNote = null,
)

private fun week(vararg lines: PayrollLine) =
    PayrollWeek(weekStarting = WEEK, currency = "GBP", lines = lines.toList())

class PayrollWeekTest {

    @Test
    fun `a week's totals are the sum of its rows`() {
        val subject = week(
            line("a", deductions = 100.0),
            line("b", deductions = 50.0),
        )
        assertEquals(2, subject.crewCount)
        assertEquals(2_500.0, subject.grossTotal)
        assertEquals(150.0, subject.deductionsTotal)
        assertEquals(2_350.0, subject.netTotal)
    }

    @Test
    fun `a single query makes the whole week queried however much else is posted`() {
        // The queried row is the one somebody has to act on, so it wins over
        // nine posted ones that need nothing.
        val subject = week(
            line("a", status = TimecardStatus.Posted),
            line("b", status = TimecardStatus.Posted),
            line("c", status = TimecardStatus.Queried),
        )
        assertEquals(WeekStatus.Queried, subject.status)
    }

    @Test
    fun `a week reads as ready for whichever batch could actually move it`() {
        assertEquals(WeekStatus.Empty, week().status)
        assertEquals(WeekStatus.ReadyToPay, week(line(status = TimecardStatus.Approved)).status)
        assertEquals(WeekStatus.ReadyToPost, week(line(status = TimecardStatus.Paid)).status)
        assertEquals(WeekStatus.Posted, week(line(status = TimecardStatus.Posted)).status)
        // Nothing to do and nothing done: submitted work is still with an
        // approver, so payroll has no action on it yet.
        assertEquals(WeekStatus.InProgress, week(line(status = TimecardStatus.Submitted)).status)
    }

    @Test
    fun `ready-to-post beats ready-to-pay when both are present`() {
        // Posting clears the week; paying only advances it. Offering the one
        // that finishes first matches how the work is actually done.
        val subject = week(
            line("a", status = TimecardStatus.Approved),
            line("b", status = TimecardStatus.Paid),
        )
        assertEquals(WeekStatus.ReadyToPost, subject.status)
    }

    @Test
    fun `posted progress is zero-safe`() {
        assertEquals(0f, week().postedFraction)
        assertEquals(
            0.5f,
            week(line("a", status = TimecardStatus.Posted), line("b")).postedFraction,
        )
    }

    @Test
    fun `only approved rows are payable and only paid rows are postable`() {
        val subject = week(
            line("a", status = TimecardStatus.Approved),
            line("b", status = TimecardStatus.Paid),
            line("c", status = TimecardStatus.Draft),
        )
        assertEquals(listOf("a"), subject.payableLines.map { it.id })
        assertEquals(listOf("b"), subject.postableLines.map { it.id })
    }

    @Test
    fun `a line whose parts do not reach its gross is flagged`() {
        assertFalse(line(gross = 1_250.0).figuresDisagree)
        assertTrue(line(gross = 1_400.0).figuresDisagree)
        // Rounding noise is not a discrepancy.
        assertFalse(line(gross = 1_250.001).figuresDisagree)
    }
}

class DepartmentTotalTest {

    @Test
    fun `rows roll up by department, largest first`() {
        val totals = DepartmentTotal.from(
            listOf(
                line("a", departmentId = "d1", departmentName = "Camera", gross = 100.0),
                line("b", departmentId = "d1", gross = 200.0),
                line("c", departmentId = "d2", departmentName = "Grip", gross = 500.0),
            ),
        )
        assertEquals(listOf("Grip", "Camera"), totals.map { it.departmentName })
        assertEquals(2, totals.last().crewCount)
        assertEquals(300.0, totals.last().gross)
    }

    @Test
    fun `a department nobody named still gets a row`() {
        // Dropping it would lose the money; the rows carry the id without the
        // name often enough that this is the common case, not the edge.
        val totals = DepartmentTotal.from(listOf(line(gross = 100.0)))
        assertEquals("Unassigned", totals.single().departmentName)
    }
}

class PayrollAccessTest {

    private val crew = PayrollViewer("u", "department_camera", null)
    private val accountant = PayrollViewer("u", "department_accounts", "Assistant Accountant")
    private val controller =
        PayrollViewer("u", "department_accounts", "designation_financial_controller_accounts")

    @Test
    fun `only accountants operate the board`() {
        assertFalse(crew.canOperate)
        assertTrue(accountant.canOperate)
    }

    @Test
    fun `posting takes seniority or the production's own approver list`() {
        assertFalse(accountant.canPost)
        assertTrue(controller.canPost)
        // The server's allowlist is authority in its own right: a junior named
        // on it may post, whatever their designation reads as.
        assertTrue(accountant.copy(isFinalApprover = true).canPost)
        // But it does not make a non-accountant into one.
        assertFalse(crew.copy(isFinalApprover = true).canPost)
    }

    @Test
    fun `an accountant entering from the tools grid operates nothing`() {
        val fromGrid = PayrollViewer("u", "department_accounts", null, enteredAsTool = true)
        assertFalse(fromGrid.canOperate)
        assertFalse(fromGrid.canPost)
    }

    @Test
    fun `seniority matches the identifier and the display name alike`() {
        assertTrue(PayrollViewer("u", "department_accounts", "Production Accountant").isSenior)
        assertTrue(
            PayrollViewer("u", "department_accounts", "designation_production_accountant_accounts").isSenior,
        )
    }
}

class TimecardStatusTest {

    @Test
    fun `unknown statuses degrade rather than throw`() {
        assertEquals(TimecardStatus.Unknown, TimecardStatus.from("brand_new"))
        assertEquals(TimecardStatus.Unknown, TimecardStatus.from(null))
        assertEquals(TimecardStatus.Approved, TimecardStatus.from("APPROVED"))
        assertEquals(TimecardStatus.AwaitingApproval, TimecardStatus.from("awaiting_approval"))
    }

    @Test
    fun `an unknown status is never payable or postable`() {
        // A status we do not recognise could be anything, and putting it in a
        // batch means asking the server to move something we cannot describe.
        assertFalse(TimecardStatus.Unknown.isPayable)
        assertFalse(TimecardStatus.Unknown.isPostable)
    }
}

class BankAccountTest {

    @Test
    fun `an account shows its last four digits, or just its name`() {
        assertEquals(
            "Barclays Current ••••4471",
            BankAccount("b1", "Barclays Current", "20887714471", "GBP").display,
        )
        assertEquals("Barclays Current", BankAccount("b1", "Barclays Current", null, "GBP").display)
    }
}
