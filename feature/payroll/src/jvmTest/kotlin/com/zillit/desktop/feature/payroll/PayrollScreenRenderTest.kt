package com.zillit.desktop.feature.payroll

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.payroll.domain.AuditEvent
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DeductionLine
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.AdjustmentDialog
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.ui.HistoryPost
import com.zillit.desktop.feature.payroll.ui.HistoryState
import com.zillit.desktop.feature.payroll.ui.HistoryTab
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollScreen
import com.zillit.desktop.feature.payroll.ui.PayrollTile
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingState
import com.zillit.desktop.feature.payroll.ui.ProcessingView
import com.zillit.desktop.feature.payroll.ui.RunState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real payroll screens — the landing, History, the Run with its
 * journal, Processing in all four views — for both audiences and themes.
 */
@OptIn(ExperimentalTestApi::class)
class PayrollScreenRenderTest {

    private val week = 1_785_715_200_000L
    private val now = week + 9 * PayPeriod.DAY_MILLIS

    private val controller = PayrollViewer("me", "department_accounts", "designation_financial_controller_accounts")
    private val producer = PayrollViewer("me", "department_production", null, canView = true, rightsLoaded = true)

    private fun card(id: String, status: TimecardStatus) = PayrollTimecard(
        id = id,
        userId = "u-$id",
        status = status,
        weekStarting = week,
        currency = "GBP",
        basicPay = 700.0,
        totalDays = 2,
        days = listOf(
            TimecardDay(
                date = week,
                dayType = "SWD",
                basicHours = 10.0,
                callTime = week + 7 * 3_600_000,
                rates = listOf(PayLine(identifier = "basic", label = "Basic", rateAmount = 350.0)),
            ),
        ),
        claims = listOf(ClaimLine("c1", "Taxi", 20.0, "GBP", null, "b1")),
        deductions = listOf(DeductionLine("d1", "Advance", "flat", 50.0, 50.0, null)),
        history = listOf(AuditEvent(week, "u-a", "paid", "locked", "paid", null, null)),
    )

    private fun state(viewer: PayrollViewer = controller, destination: PayrollDestination) = PayrollUiState(
        viewer = viewer,
        destination = destination,
        metadata = PayrollMetadata(),
        metadataLoaded = true,
        now = now,
        people = listOf("a", "b", "l").associate { id ->
            "u-$id" to PayrollPerson("u-$id", "Crew $id", "department_camera", "designation_gaffer")
        },
    )

    @Test
    fun `the landing offers the accountant grid with the web's tiles`() {
        runComposeUiTest {
            var opened: PayrollEvent? = null
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state(destination = PayrollDestination.Landing), onEvent = { opened = it })
                }
            }
            onNodeWithText("Payroll Management", substring = true, ignoreCase = true).assertExists()
            PayrollTile.entries.forEach { onNodeWithText(it.title).assertExists() }
            onNodeWithText("Payroll History").performClick()
            assertEquals(PayrollEvent.OpenTile(PayrollTile.History), opened)
        }
    }

    @Test
    fun `a producer is told the producer views are not here rather than shown an empty grid`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    PayrollScreen(state(viewer = producer, destination = PayrollDestination.Landing), onEvent = {})
                }
            }
            onNodeWithText("No payroll views here").assertIsDisplayed()
            onAllNodesWithText("Payroll Run").assertCountEquals(0)
        }
    }

    @Test
    fun `history composes every tab for a paid week, with the post dialog over it`() {
        HistoryTab.entries.forEach { tab ->
            runComposeUiTest {
                val base = state(destination = PayrollDestination.History)
                val paid = card("a", TimecardStatus.Paid)
                setContent {
                    ZillitTheme(darkTheme = false) {
                        PayrollScreen(
                            base.copy(
                                history = HistoryState(
                                    weekStarting = week,
                                    rows = listOf(paid, card("b", TimecardStatus.Posted)),
                                    selectedId = "a",
                                    detail = paid,
                                    tab = tab,
                                    post = HistoryPost(
                                        ids = listOf("a"),
                                        fromSelection = false,
                                        effectiveDate = "2026-08-10",
                                    ),
                                ),
                            ),
                            onEvent = {},
                        )
                    }
                }
                onNodeWithText("Pay Code Breakdown").assertExists()
                onNodeWithText("Post All Ready — W/E 09 Aug 2026").assertExists()
            }
        }
    }

    @Test
    fun `the run composes its grid, toolbar and journal`() {
        listOf(false, true).forEach { journal ->
            runComposeUiTest {
                val base = state(destination = PayrollDestination.Run)
                setContent {
                    ZillitTheme(darkTheme = journal) {
                        PayrollScreen(
                            base.copy(
                                run = RunState(
                                    weekStarting = week,
                                    timecards = listOf(
                                        card("a", TimecardStatus.Approved),
                                        card("l", TimecardStatus.Locked),
                                        card("b", TimecardStatus.Paid),
                                    ),
                                    selected = setOf("a", "l"),
                                    journalOpen = journal,
                                ),
                            ),
                            onEvent = {},
                        )
                    }
                }
                if (journal) {
                    onNodeWithText("Balanced", substring = true).assertExists()
                } else {
                    onNodeWithText("Final Approve & Lock · 1").assertExists()
                    onNodeWithText("Mark Locked → Paid · 1").assertExists()
                    onNodeWithText("Crew a").assertExists()
                }
            }
        }
    }

    @Test
    fun `processing composes all four views`() {
        ProcessingView.entries.forEach { view ->
            runComposeUiTest {
                val base = state(destination = PayrollDestination.Processing)
                setContent {
                    ZillitTheme(darkTheme = false) {
                        PayrollScreen(
                            base.copy(
                                processing = ProcessingState(
                                    weekStarting = week,
                                    timecards = listOf(
                                        card("l", TimecardStatus.Locked),
                                        card("b", TimecardStatus.Paid),
                                    ),
                                    outstanding = listOf(card("a", TimecardStatus.Approved)),
                                    outstandingLoaded = true,
                                    view = view,
                                ),
                            ),
                            onEvent = {},
                        )
                    }
                }
                onNodeWithText("All Time Cards").assertExists()
            }
        }
    }

    @Test
    fun `the claims dialog composes over any screen`() {
        runComposeUiTest {
            val base = state(destination = PayrollDestination.Processing)
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        base.copy(adjustment = AdjustmentDialog(
                            AdjustmentKind.Claims,
                            card("l", TimecardStatus.Locked),
                        )),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("This timecard is locked", substring = true).assertExists()
            onNodeWithText("Taxi").assertExists()
        }
    }
}
