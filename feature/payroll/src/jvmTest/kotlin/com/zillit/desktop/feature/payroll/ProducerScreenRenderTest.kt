package com.zillit.desktop.feature.payroll

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollCrewRow
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardMeal
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollScreen
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProducerBoardEvent
import com.zillit.desktop.feature.payroll.ui.ProducerBoardState
import com.zillit.desktop.feature.payroll.ui.ProductionReportEvent
import com.zillit.desktop.feature.payroll.ui.ProductionReportState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the two producer boards — the screens the Payroll tool tile opens.
 */
@OptIn(ExperimentalTestApi::class)
class ProducerScreenRenderTest {

    private val week = 1_785_715_200_000L
    private val now = week + 3 * PayPeriod.DAY_MILLIS

    private val producer = PayrollViewer("me", "department_production", null, canView = true, rightsLoaded = true)

    private val people = mapOf(
        "u-a" to PayrollPerson("u-a", "Ada Reeve", "department_camera", "designation_focus_puller", hasDeal = true),
        "u-b" to PayrollPerson("u-b", "Bo Ives", "department_sparks", "designation_gaffer", hasDeal = true),
    )

    private fun state(destination: PayrollDestination) = PayrollUiState(
        viewer = producer,
        destination = destination,
        enteredAsTool = true,
        metadata = PayrollMetadata(),
        metadataLoaded = true,
        now = now,
        people = people,
        hasPayEngine = true,
    )

    private fun timecard(id: String, status: TimecardStatus) = PayrollTimecard(
        id = id,
        userId = "u-a",
        status = status,
        weekStarting = week,
        currency = "GBP",
        basicPay = 700.0,
        totalHours = 10.5,
        days = listOf(
            TimecardDay(
                date = week,
                dayType = "SWD",
                callTime = week + 7 * HOUR,
                wrapTime = week + 19 * HOUR,
                loginTime = week + 7 * HOUR,
                logoutTime = week + 20 * HOUR,
                minutesWorked = 630,
                meals = listOf(TimecardMeal(week + 13 * HOUR, week + 14 * HOUR)),
                rates = listOf(
                    PayLine(identifier = "basic", label = "Basic", rateAmount = 350.0),
                    PayLine(identifier = "overtime", label = "OT", rateAmount = 62.5, workDuration = 90),
                ),
                allowances = listOf(PayLine(identifier = "perdiem", label = "Per Diem", rateAmount = 25.0)),
            ),
            TimecardDay(date = week + PayPeriod.DAY_MILLIS, dayType = "REST"),
        ),
    )

    @Test
    fun `the board lists the week's crew by department and reads the open week`() {
        runComposeUiTest {
            var event: PayrollEvent? = null
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(PayrollDestination.ProducerBoard).copy(
                            producer = ProducerBoardState(
                                weekStarting = week,
                                crew = listOf(
                                    PayrollCrewRow("t1", "u-a", TimecardStatus.Submitted, 700.0, 5),
                                    PayrollCrewRow("t2", "u-b", TimecardStatus.Approved, 800.0, 5),
                                ),
                                selectedId = "t1",
                                timecard = timecard("t1", TimecardStatus.Submitted),
                            ),
                        ),
                        onEvent = { event = it },
                    )
                }
            }
            onNodeWithText("Producer Board Payroll Status").assertIsDisplayed()
            // Grouped by department: the header is the department's own label.
            onAllNodesWithText("CAMERA", substring = true).assertCountEquals(1)
            onAllNodesWithText("SPARKS", substring = true).assertCountEquals(1)
            // The KPI strip and the day table are the read-only week.
            onNodeWithText("EST. GROSS").assertExists()
            onNodeWithText("Monday").assertExists()
            // A day off collapses rather than showing empty time columns.
            onNodeWithText("DAY OFF").assertExists()
            onNodeWithText("Bo Ives").performClick()
            assertEquals(ProducerBoardEvent.Select("t2"), event)
        }
    }

    /** Read-only: the board offers no way to approve, override or submit. */
    @Test
    fun `the board offers no writes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    PayrollScreen(
                        state(PayrollDestination.ProducerBoard).copy(
                            producer = ProducerBoardState(
                                weekStarting = week,
                                crew = listOf(PayrollCrewRow("t1", "u-a", TimecardStatus.Submitted)),
                                selectedId = "t1",
                                timecard = timecard("t1", TimecardStatus.Submitted),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            // Deliberately not "Submit": that is a substring of the STATUS
            // "Submitted", which the rail and the hero both show.
            listOf("Approve", "Override", "Mark Paid", "Unlock", "Query").forEach { action ->
                onAllNodesWithText(action, substring = true).assertCountEquals(0)
            }
        }
    }

    @Test
    fun `an empty week says so rather than drawing a blank rail`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(PayrollDestination.ProducerBoard)
                            .copy(producer = ProducerBoardState(weekStarting = week)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No timecards yet").assertIsDisplayed()
        }
    }

    @Test
    fun `the estimate board rosters the crew with deals and offers the fill`() {
        runComposeUiTest {
            var event: PayrollEvent? = null
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(PayrollDestination.ProductionReport).copy(
                            estimate = ProductionReportState(
                                weekStarting = week,
                                crew = people.values.toList(),
                                selectedUserId = "u-a",
                            ),
                        ),
                        onEvent = { event = it },
                    )
                }
            }
            onNodeWithText("Production Report Payroll").assertIsDisplayed()
            onNodeWithText("Auto-fill from production report").assertIsDisplayed()
            // No timecard and no estimate yet: the rail says so in its own
            // words, on every crew member and again on the open one's hero.
            onAllNodesWithText("No timecard")[0].assertIsDisplayed()
            onNodeWithText("Bo Ives").performClick()
            assertEquals(ProductionReportEvent.Select("u-b"), event)
        }
    }

    @Test
    fun `no crew with a deal is said out loud`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    PayrollScreen(
                        state(PayrollDestination.ProductionReport)
                            .copy(estimate = ProductionReportState(weekStarting = week)),
                        onEvent = {},
                    )
                }
            }
            // Said twice: once by the empty rail, once where the week would be.
            onAllNodesWithText("No crew with deals")[0].assertIsDisplayed()
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
