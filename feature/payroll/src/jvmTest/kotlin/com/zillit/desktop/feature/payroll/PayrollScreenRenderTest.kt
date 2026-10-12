package com.zillit.desktop.feature.payroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.payroll.domain.AuditEvent
import com.zillit.desktop.feature.payroll.domain.ChartAccount
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DeductionLine
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollTaxType
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.TimecardAttachment
import com.zillit.desktop.feature.payroll.domain.TimecardNote
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.AdjustmentDialog
import com.zillit.desktop.feature.payroll.ui.AdjustmentKind
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.TrackingNode
import com.zillit.desktop.feature.payroll.domain.TrackingSet
import com.zillit.desktop.feature.payroll.ui.HistoryState
import com.zillit.desktop.feature.payroll.ui.JournalState
import com.zillit.desktop.feature.payroll.ui.NotesView
import com.zillit.desktop.feature.payroll.ui.OutstandingDetail
import com.zillit.desktop.feature.payroll.ui.HistoryTab
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollScreen
import com.zillit.desktop.feature.payroll.ui.PayrollTile
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingState
import com.zillit.desktop.feature.payroll.ui.ProcessingView
import com.zillit.desktop.feature.payroll.ui.RunConfirm
import com.zillit.desktop.feature.payroll.ui.RunEvent
import com.zillit.desktop.feature.payroll.ui.RunState
import com.zillit.desktop.feature.payroll.ui.TimecardView
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

    /** The Account Hub entry: an accountant's grid, and no producer boards on it. */
    @Test
    fun `the hub landing offers the accountant grid with the web's tiles`() {
        runComposeUiTest {
            var opened: PayrollEvent? = null
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state(destination = PayrollDestination.Landing), onEvent = { opened = it })
                }
            }
            onNodeWithText("Payroll Management", substring = true, ignoreCase = true).assertExists()
            PayrollTile.entries.filterNot { it in PayrollTile.PRODUCER }
                .forEach { onNodeWithText(it.title).assertExists() }
            PayrollTile.PRODUCER.forEach { onAllNodesWithText(it.title).assertCountEquals(0) }
            onNodeWithText("Payroll History").performClick()
            assertEquals(PayrollEvent.OpenTile(PayrollTile.History), opened)
        }
    }

    /**
     * The Film Tools entry: the SAME accountant is offered the producer boards
     * and nothing else, because the tool tile is the producer entry point.
     */
    @Test
    fun `the tool landing offers the producer boards, even to an accountant`() {
        runComposeUiTest {
            var opened: PayrollEvent? = null
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Landing).copy(enteredAsTool = true),
                        onEvent = { opened = it },
                    )
                }
            }
            PayrollTile.PRODUCER.forEach { onNodeWithText(it.title).assertExists() }
            onAllNodesWithText("Payroll Run").assertCountEquals(0)
            onAllNodesWithText("Payroll Entry Setup").assertCountEquals(0)
            onNodeWithText("Producer Board Payroll Status").performClick()
            assertEquals(PayrollEvent.OpenTile(PayrollTile.ProducerBoard), opened)
        }
    }

    /** A non-accountant with view access gets the producer boards, either way in. */
    @Test
    fun `a producer is offered the producer boards and no accountant screens`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    PayrollScreen(state(viewer = producer, destination = PayrollDestination.Landing), onEvent = {})
                }
            }
            onNodeWithText("Producer Board Payroll Status").assertIsDisplayed()
            onNodeWithText("Production Report Payroll").assertIsDisplayed()
            onAllNodesWithText("Payroll Run").assertCountEquals(0)
            onAllNodesWithText("Payroll Processing").assertCountEquals(0)
        }
    }

    /** No access at all is said out loud, rather than drawn as an empty grid. */
    @Test
    fun `a viewer with no payroll access is told so`() {
        runComposeUiTest {
            val stranger = producer.copy(canView = false, rightsLoaded = true)
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state(viewer = stranger, destination = PayrollDestination.Landing), onEvent = {})
                }
            }
            onNodeWithText("No payroll views here").assertIsDisplayed()
            onAllNodesWithText("Producer Board Payroll Status").assertCountEquals(0)
        }
    }

    /**
     * The Run's crew drawer opens on a day, not on the week: the question it
     * exists to answer is which lines made that day's money.
     */
    @Test
    fun `the crew drawer breaks a day into its own pay lines`() {
        runComposeUiTest {
            val open = card("a", TimecardStatus.Approved)
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Run).copy(
                            run = RunState(
                                weekStarting = week,
                                timecards = listOf(open),
                                drawerId = open.id,
                                drawer = open,
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("PAY BREAKDOWN").assertExists()
            // Monday's own line, with the hours it covers — not a week total —
            // and again on Monday's card in the Daily Breakdown below it.
            onAllNodesWithText("Basic").assertCountEquals(2)
            onNodeWithText("DAY TOTAL").assertExists()
            // Every day of the week is offered, so the reader can move between them.
            onAllNodesWithText("MON").assertCountEquals(1)
            onAllNodesWithText("SUN").assertCountEquals(1)
        }
    }

    /**
     * The Journal Ledger codes a line four ways — account, layers, tags and
     * date — and splits it into allocations that each carry their own.
     */
    @Test
    fun `the journal offers layers, tags and a split on each line`() {
        runComposeUiTest {
            val paid = card("a", TimecardStatus.Paid)
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Run).copy(
                            run = RunState(
                                weekStarting = week,
                                timecards = listOf(paid),
                                journalOpen = true,
                                journal = JournalState(
                                    reference = JournalReference(
                                        trackingSets = listOf(
                                            TrackingSet("s1", "Department", listOf(TrackingNode("CAM", "Camera"))),
                                        ),
                                        assetTags = listOf("Recharge"),
                                    ),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("LAYERS").assertExists()
            onNodeWithText("TAGS").assertExists()
            // Nine columns of coding overflow a laptop, so Actions sits beyond
            // the viewport until the ledger is scrolled — which is the point of
            // the horizontal scroll, and why the click has to scroll first.
            // One Split per line, and the tax line is offered once per
            // timecard. Clicking is asserted in PayrollViewModelTest: a
            // synthetic click cannot reach a control inside a TooltipArea,
            // though a real pointer does (the pattern ships in Sides).
            onAllNodesWithText("Split line").assertCountEquals(3)
            onAllNodesWithText("Add tax").assertCountEquals(1)
        }
    }

    @Test
    fun `history composes every tab for a paid week, and offers no posting`() {
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
                                ),
                            ),
                            onEvent = {},
                        )
                    }
                }
                onNodeWithText("Pay Code Breakdown").assertExists()
                // Posting is the Run's Journal Ledger's now, as on the web.
                onNodeWithText("Post All Ready", substring = true).assertDoesNotExist()
                onNodeWithText("Post Selected", substring = true).assertDoesNotExist()
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

    /**
     * The tax line carries a type picker, and Other opens a percentage field.
     * Picking and typing are asserted through the view model — a synthetic
     * click cannot be relied on inside these popups.
     */
    @Test
    fun `a tax line offers the type picker, and Other adds the percentage field`() {
        runComposeUiTest {
            val paid = card("a", TimecardStatus.Paid)
            val journal = { type: String ->
                JournalState(
                    taxAdded = setOf("a"),
                    reference = JournalReference(taxTypes = listOf(PayrollTaxType("vat_20", "VAT", 20.0, "UK"))),
                    edits = mapOf("tax::a" to JournalEdit(taxType = type, taxRate = 12.5)),
                )
            }
            var type by mutableStateOf("")
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Run).copy(
                            metadata = PayrollMetadata(chart = listOf(ChartAccount("5000", "Crew costs"))),
                            run = RunState(
                                weekStarting = week,
                                timecards = listOf(paid),
                                journalOpen = true,
                                journal = journal(type),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("Select…").assertCountEquals(1)
            onAllNodesWithText("Tax percentage").assertCountEquals(0)
            type = "other"
            waitForIdle()
            onAllNodesWithText("Select…").assertCountEquals(0)
            onNodeWithText("12.5").assertExists()
        }
    }

    /** Send to Payroll Run: a checkbox only where the Run would accept the week, and only for an accountant. */
    @Test
    fun `the outstanding detail offers send to run for sendable weeks to an accountant only`() {
        val detail = OutstandingDetail(
            userId = "u-a",
            weeks = listOf(card("a", TimecardStatus.Submitted), card("b", TimecardStatus.Approved)),
            loading = false,
        )
        listOf(controller to 1, producer to 0).forEach { (viewer, offered) ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        PayrollScreen(
                            state(viewer, PayrollDestination.Processing).copy(
                                processing = ProcessingState(weekStarting = week, detail = detail),
                            ),
                            onEvent = {},
                        )
                    }
                }
                onAllNodesWithText("Select all 1").assertCountEquals(offered)
                onAllNodesWithText("Send to Payroll Run").assertCountEquals(offered)
            }
        }
    }

    private fun drawerState(open: PayrollTimecard, viewer: PayrollViewer = controller) =
        state(viewer, PayrollDestination.Run).copy(
            run = RunState(weekStarting = week, timecards = listOf(open), drawerId = open.id, drawer = open),
        )

    /**
     * Below the open day the drawer lists the whole week: every day as a card,
     * and the claims and deductions with the pill that says where each came from.
     */
    @Test
    fun `the crew drawer lists every day, the claims and the deductions with their pills`() {
        runComposeUiTest {
            val open = card("a", TimecardStatus.Approved).copy(
                weeklyAllowances = listOf(PayLine(identifier = "kit", label = "Kit", rateAmount = 30.0)),
                weeklyExtras = listOf(PayLine(label = "Stunt Upgrade", rateAmount = 40.0, kind = "upgrade")),
            )
            setContent { ZillitTheme(darkTheme = false) { PayrollScreen(drawerState(open), onEvent = {}) } }
            onNodeWithText("DAILY BREAKDOWN").assertExists()
            onNodeWithText("ALLOWANCES, RENTALS, UPGRADES & EXTRAS").assertExists()
            onNodeWithText("Stunt Upgrade").assertExists()
            onNodeWithText("Upgrade").assertExists()
            onNodeWithText("Taxi").assertExists()
            onNodeWithText("CASH").assertExists()
            onNodeWithText("Advance").assertExists()
            onNodeWithText("FLAT").assertExists()
        }
    }

    /** The footer's View Time Card, the invoice only where one is attached, and the header's Notes. */
    @Test
    fun `the crew drawer offers notes, the week and an invoice only where one exists`() {
        listOf(null, TimecardAttachment(name = "inv.pdf", url = null, media = "m", bucket = "b", region = "r"))
            .forEach { attachment ->
                runComposeUiTest {
                    val open = card("a", TimecardStatus.Approved).copy(attachment = attachment)
                    val events = mutableListOf<PayrollEvent>()
                    setContent {
                        ZillitTheme(darkTheme = false) { PayrollScreen(drawerState(open), onEvent = { events += it }) }
                    }
                    onAllNodesWithText("View Invoice").assertCountEquals(if (attachment == null) 0 else 1)
                    onNodeWithText("Notes").performClick()
                    onNodeWithText("View Time Card").performClick()
                    assertEquals(
                        listOf(PayrollEvent.OpenNotes(open, "Crew a"), PayrollEvent.OpenTimecardView(open)),
                        events,
                    )
                }
            }
    }

    /** Notes are an accountant's, and never on their own card. */
    @Test
    fun `the notes button hides on the accountant's own card`() {
        runComposeUiTest {
            val mine = card("a", TimecardStatus.Approved).copy(userId = "me")
            setContent { ZillitTheme(darkTheme = false) { PayrollScreen(drawerState(mine), onEvent = {}) } }
            onAllNodesWithText("Notes").assertCountEquals(0)
            onAllNodesWithText("View Time Card").assertCountEquals(1)
        }
    }

    @Test
    fun `view time card opens the week with its notes button and the notes list opens over it`() {
        runComposeUiTest {
            val open = card("a", TimecardStatus.Approved)
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Processing).copy(
                            timecardView = TimecardView(open),
                            notesView = NotesView("Crew a", listOf(TimecardNote("Left early", week))),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Crew a — W/E", substring = true).assertExists()
            onNodeWithText("Notes").assertExists()
            onNodeWithText("Left early").assertExists()
        }
    }

    /** Export CSV with something to approve names what it would do, and offers to export without doing it. */
    @Test
    fun `the export csv confirm offers export only beside the action`() {
        runComposeUiTest {
            val events = mutableListOf<PayrollEvent>()
            val confirm = RunConfirm(
                action = RunAction.FinalApproveAndLock,
                ids = listOf("a"),
                selectedTotal = 3,
                runScope = true,
                thenExport = true,
            )
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state(destination = PayrollDestination.Run)
                            .copy(run = RunState(weekStarting = week, confirm = confirm)),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("Final Approve & Lock 1 & Export", substring = true).assertExists()
            onNodeWithText("The payroll CSV downloads", substring = true).assertExists()
            onNodeWithText("Export Only").performClick()
            assertEquals(listOf<PayrollEvent>(RunEvent.ExportOnly), events)
        }
    }

    /** The grid draws a header per department with its crew count; one department chosen draws none. */
    @Test
    fun `processing groups the crew under department headers and summarises the week in its sidebar`() {
        listOf<String?>(null, "department_camera").forEach { chosen ->
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
                                    view = ProcessingView.Weekly,
                                    department = chosen,
                                ),
                            ),
                            onEvent = {},
                        )
                    }
                }
                onAllNodesWithText("WEEK SUMMARY").assertCountEquals(1)
                onAllNodesWithText("Total Crew").assertCountEquals(1)
                onAllNodesWithText("(2 crew)").assertCountEquals(if (chosen == null) 1 else 0)
            }
        }
    }
}
