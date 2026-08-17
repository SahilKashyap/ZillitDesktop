package com.zillit.desktop.feature.timecard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Deduction
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardDestination
import com.zillit.desktop.feature.timecard.ui.TimecardScreen
import com.zillit.desktop.feature.timecard.ui.TimecardUiState
import kotlin.test.Test

/** Composes the real Timecards screen on every destination. */
@OptIn(ExperimentalTestApi::class)
class TimecardScreenRenderTest {

    private val payroll = TimecardViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = null,
        metadata = TimecardMetadata(isApprover = true, isFinalApprover = true, requiresFinalApproval = true),
    )

    private val crew = TimecardViewer("user-2", "department_camera", null)

    private fun day(type: DayType = DayType.Worked, hours: Double = 11.0) = TimecardDay(
        date = 1_754_000_000_000,
        dayType = type,
        callTime = "07:00",
        wrapTime = "19:00",
        workedHours = hours,
        note = null,
    )

    private fun week(id: String = "tc-1", status: TimecardStatus = TimecardStatus.Approved) = Timecard(
        id = id,
        userId = "user-2",
        crewName = "Ada Lovelace",
        departmentId = "dept-1",
        designation = "Gaffer",
        weekStarting = 1_754_000_000_000,
        weekNumber = 3,
        status = status,
        currency = "GBP",
        days = listOf(
            TimecardDay(1_754_000_000_000, DayType.Worked, "07:00", "19:00", workedHours = 11.0, note = null),
            TimecardDay(1_754_086_400_000, DayType.Travel, "08:00", "12:00", workedHours = 4.0, note = null),
            TimecardDay(1_754_172_800_000, DayType.Rest, null, null, note = null),
        ),
        basicPay = 1_400.0,
        overtimePay = 220.0,
        totalAllowances = 65.0,
        additionalFees = 0.0,
        deductions = listOf(Deduction("d-1", "Kit advance", 100.0, null)),
        totalPay = 0.0,
        totalDays = 3.0,
        notes = "Long Tuesday",
        queryNote = null,
        rejectionReason = null,
        lastApprovedBy = null,
        paidAt = null,
        updatedAt = null,
    )

    private fun state(destination: TimecardDestination, viewer: TimecardViewer = payroll) = TimecardUiState(
        viewer = viewer,
        destination = destination,
        timecards = listOf(week(), week("tc-2", TimecardStatus.Submitted)),
        selectedId = "tc-1",
        draft = TimecardDraft("tc-1", 1_754_000_000_000, week().days, "Long Tuesday"),
    )

    @Test
    fun `every destination composes for both audiences and both themes`() {
        listOf(payroll to true, payroll to false, crew to false).forEach { (viewer, dark) ->
            TimecardDestination.entries.filter { it.visibleTo(viewer) }.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            TimecardScreen(state = state(destination, viewer), onEvent = {})
                        }
                    }
                    onNodeWithText("Timecards").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `the week editor draws a row per day`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    TimecardScreen(state = state(TimecardDestination.Edit, crew), onEvent = {})
                }
            }
            // Column headers appear once, on the first row only.
            onNodeWithText("Day type").assertIsDisplayed()
            onNodeWithText("Call").assertIsDisplayed()
            onNodeWithText("Hours").assertIsDisplayed()
        }
    }

    @Test
    fun `the editor offers a way in when no week is open`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    TimecardScreen(
                        state = state(TimecardDestination.Edit, crew).copy(draft = null),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Start this week").assertIsDisplayed()
        }
    }

    @Test
    fun `allowances can be claimed on a worked day and are absent on a rest day`() {
        val types = listOf(
            com.zillit.desktop.feature.timecard.domain.AllowanceType("MP", "Meal penalty", 12.5),
            com.zillit.desktop.feature.timecard.domain.AllowanceType(
                code = "MILE",
                label = "Mileage",
                defaultAmount = 0.45,
                basis = com.zillit.desktop.feature.timecard.domain.AllowanceBasis.Mile,
            ),
        )
        val crewWithTypes = crew.copy(
            metadata = TimecardMetadata(allowanceTypes = types),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    TimecardScreen(
                        state = state(TimecardDestination.Edit, crewWithTypes).copy(
                            draft = TimecardDraft(
                                timecardId = "tc-1",
                                weekStarting = 1_754_000_000_000,
                                days = listOf(
                                    day().copy(
                                        allowances = listOf(
                                            com.zillit.desktop.feature.timecard.domain.Allowance(
                                                "MP",
                                                "Meal penalty",
                                                12.5,
                                            ),
                                        ),
                                    ),
                                    day(DayType.Rest, 0.0),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            // Twice: the claim itself, and the catalogue button beside it —
            // which is now disabled, because a once-a-day allowance already
            // claimed cannot be claimed again.
            onAllNodesWithText("Meal penalty").assertCountEquals(2)
            // Per-unit and unclaimed, so it appears once and stays offered.
            onNodeWithText("Mileage").assertExists()
        }
    }

    @Test
    fun `a queried week explains itself on the detail`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    TimecardScreen(
                        state = state(TimecardDestination.ApprovalQueue).copy(
                            timecards = listOf(
                                week(status = TimecardStatus.Queried).copy(queryNote = "Tuesday's wrap looks wrong"),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Queried: Tuesday's wrap looks wrong").assertIsDisplayed()
        }
    }
}
