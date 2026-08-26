package com.zillit.desktop.feature.timecard

import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Deduction
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun day(type: DayType = DayType.Worked, hours: Double = 10.0) = TimecardDay(
    date = 1_754_000_000_000,
    dayType = type,
    callTime = "07:00",
    wrapTime = "19:00",
    workedHours = hours,
    note = null,
)

@Suppress("LongParameterList") // A test builder: one default per field the tests vary.
private fun card(
    status: TimecardStatus = TimecardStatus.Draft,
    days: List<TimecardDay> = listOf(day(), day()),
    basic: Double = 1_000.0,
    overtime: Double = 200.0,
    allowances: Double = 50.0,
    fees: Double = 0.0,
    deductions: List<Deduction> = emptyList(),
    totalPay: Double = 0.0,
    locked: Boolean = false,
) = Timecard(
    id = "tc-1",
    userId = "user-1",
    crewName = "Ada",
    departmentId = null,
    designation = "Gaffer",
    weekStarting = 1_754_000_000_000,
    weekNumber = 3,
    status = status,
    currency = "GBP",
    days = days,
    basicPay = basic,
    overtimePay = overtime,
    totalAllowances = allowances,
    additionalFees = fees,
    deductions = deductions,
    totalPay = totalPay,
    totalDays = days.size.toDouble(),
    notes = null,
    queryNote = null,
    rejectionReason = null,
    lastApprovedBy = null,
    paidAt = null,
    updatedAt = null,
    locked = locked,
)

class TimecardTest {

    @Test
    fun `gross is the sum of every earning component`() {
        assertEquals(1_250.0, card().gross)
    }

    @Test
    fun `net falls back to gross less deductions when the server omits a total`() {
        // The field is present on some endpoints and absent on others, and a
        // blank total on a payroll screen reads as "nothing owed".
        val withDeduction = card(deductions = listOf(Deduction("d-1", "Advance", 100.0, null)))

        assertEquals(1_150.0, withDeduction.net)
    }

    @Test
    fun `a server-computed total wins over the client's arithmetic`() {
        // The server knows about tax treatments this client does not model.
        assertEquals(999.0, card(totalPay = 999.0).net)
    }

    @Test
    fun `worked hours count every day, whatever its type`() {
        val week = card(days = listOf(day(hours = 10.0), day(DayType.Travel, 4.0), day(DayType.Rest, 0.0)))
        assertEquals(14.0, week.workedHours)
    }

    @Test
    fun `only worked and travel days are paid work`() {
        assertTrue(DayType.Worked.isPaidWork)
        assertTrue(DayType.Travel.isPaidWork)
        listOf(DayType.Rest, DayType.Holiday, DayType.Sick, DayType.Idle, DayType.NotWorked).forEach {
            assertFalse(it.isPaidWork, "$it should not be paid work")
        }
    }

    @Test
    fun `a locked week cannot be edited whatever its status says`() {
        assertTrue(card(status = TimecardStatus.Draft).isEditable)
        assertFalse(card(status = TimecardStatus.Draft, locked = true).isEditable)
        assertFalse(card(status = TimecardStatus.Approved).isEditable)
        assertTrue(card(status = TimecardStatus.Queried).isEditable)
    }

    @Test
    fun `payable statuses are the ones a run can draw on`() {
        // final_approved → locked is the run's intake; there is no
        // `sent_to_payroll` status on the wire (timecardStatus.js:12-51).
        listOf(TimecardStatus.FinalApproved, TimecardStatus.Locked).forEach {
            assertTrue(it.isPayable, "$it should be payable")
        }
        listOf(
            TimecardStatus.Draft,
            TimecardStatus.Submitted,
            TimecardStatus.Pending,
            TimecardStatus.Approved,
            TimecardStatus.Paid,
        ).forEach {
            assertFalse(it.isPayable, "$it should not be payable")
        }
    }

    @Test
    fun `an allowance with no quantity is claimed once`() {
        assertEquals(25.0, Allowance("meal", "Meal", 25.0).total)
        assertEquals(50.0, Allowance("meal", "Meal", 25.0, quantity = 2.0).total)
    }

    @Test
    fun `a draft names the first thing wrong with it`() {
        val noWeek = TimecardDraft(null, null, listOf(day()))
        assertEquals("Pick the week this timecard covers.", noWeek.validationError())

        val noDays = TimecardDraft(null, 1_754_000_000_000, emptyList())
        assertEquals("A timecard needs at least one day.", noDays.validationError())

        val missingHours = TimecardDraft(null, 1_754_000_000_000, listOf(day(hours = 0.0)))
        assertEquals("Every worked day needs its hours.", missingHours.validationError())

        // A week of rest days is a real submission — a crew member on standby
        // still files one — so hours are not required outright.
        val restWeek = TimecardDraft(null, 1_754_000_000_000, listOf(day(DayType.Rest, 0.0)))
        assertNull(restWeek.validationError())
    }

    @Test
    fun `approving is a head of department right and processing belongs to payroll`() {
        val crew = TimecardViewer("u", "department_camera", null)
        val head = TimecardViewer("u", "department_camera", null, TimecardMetadata(isApprover = true))
        val payroll = TimecardViewer("u", "department_accounts", null)

        assertTrue(TimecardDestination.MyWeeks.visibleTo(crew))
        assertFalse(TimecardDestination.ApprovalQueue.visibleTo(crew))
        assertTrue(TimecardDestination.ApprovalQueue.visibleTo(head))
        assertFalse(TimecardDestination.Processing.visibleTo(head))
        assertTrue(TimecardDestination.Processing.visibleTo(payroll))
        assertTrue(TimecardDestination.Outstanding.visibleTo(payroll))
    }

    @Test
    fun `an accountant entering from the tools grid files their own week`() {
        val fromGrid = TimecardViewer("u", "department_accounts", null, enteredAsTool = true)

        assertFalse(fromGrid.isAccountant)
        assertFalse(fromGrid.isFinalApprover)
        assertFalse(TimecardDestination.Processing.visibleTo(fromGrid))
        assertTrue(TimecardDestination.MyWeeks.visibleTo(fromGrid))
    }

    @Test
    fun `unknown statuses and day types degrade rather than throw`() {
        assertEquals(TimecardStatus.Unknown, TimecardStatus.from("brand_new"))
        assertEquals(DayType.NotWorked, DayType.from(null), "an untouched day has no type at all")
        assertEquals(DayType.Unknown, DayType.from("Prep"), "web-only vocabulary degrades, never mislabels")
        assertEquals(TimecardStatus.FinalApproved, TimecardStatus.from("FINAL_APPROVED"))
    }
}
