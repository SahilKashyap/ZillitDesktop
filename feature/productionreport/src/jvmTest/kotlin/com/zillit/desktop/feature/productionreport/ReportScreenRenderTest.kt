package com.zillit.desktop.feature.productionreport

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.productionreport.ReportRenderFixtures.NOW
import com.zillit.desktop.feature.productionreport.ReportRenderFixtures.state
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportHistory
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import com.zillit.desktop.feature.productionreport.ui.ConfirmAction
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ListView
import com.zillit.desktop.feature.productionreport.ui.PdfOverlay
import com.zillit.desktop.feature.productionreport.ui.ProductionReportScreen
import com.zillit.desktop.feature.productionreport.ui.PublishDestination
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.Workspace
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes every list, row menu and dialog, light and dark, and clicks the
 * menus open — a lazy list or an intrinsic measurement inside a popup only
 * throws once the popup is on screen.
 */
@OptIn(ExperimentalTestApi::class)
class ReportScreenRenderTest {

    private val events = mutableListOf<ReportEvent>()

    private fun render(
        ui: ReportUiState,
        dark: Boolean = false,
        chat: (@Composable () -> Unit)? = null,
        body: ComposeUiTest.() -> Unit = {},
    ) {
        events.clear()
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent {
                ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                    ProductionReportScreen(ui, onEvent = { events += it }, chat = chat, nowMillis = { NOW })
                }
            }
            waitForIdle()
            body()
        }
    }

    private fun ComposeUiTest.seen(text: String) {
        val found = onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()
        assertTrue(found.isNotEmpty(), "expected to find \"$text\" on screen")
    }

    private fun SemanticsNodeInteraction.tap() {
        runCatching { performScrollTo() }
        performClick()
    }

    /** Opens the first row's kebab, then picks [item] from the menu. */
    private fun ComposeUiTest.pickFromRowMenu(item: String) {
        onAllNodesWithContentDescription("Actions").onFirst().tap()
        waitForIdle()
        onAllNodesWithText(item).onLast().performClick()
        waitForIdle()
    }

    private val approvals = state.copy(tab = ManageTab.Approvals)

    @Test
    fun `every list composes in both themes, as a table and as cards`() {
        val screens = listOf(
            state to "Day 14 — Studio 3",
            state.copy(draftsView = ListView.Cards) to "Day 13 — Harbour exterior",
            // The approvals tables name no report, as on the web: the creator and status tell rows apart.
            approvals.copy(section = ApprovalSection.Sent) to "Created By",
            approvals.copy(section = ApprovalSection.Received) to "Maya Fernandes",
            approvals.copy(section = ApprovalSection.Finalized, approvalsView = ListView.Cards) to "Final Approved",
            approvals.copy(section = ApprovalSection.Received, approvalsView = ListView.Cards) to "Pending Signature",
            state.copy(tab = ManageTab.Published) to "Day 10 — Rooftop",
        )
        listOf(false, true).forEach { dark ->
            screens.forEach { (ui, name) -> render(ui, dark) { seen(name) } }
        }
    }

    @Test
    fun `the Chat workspace shows the unit chat under the switch, and the switch moves to Manage`() {
        val onChat = state.copy(hasChat = true, workspace = Workspace.Chat, badges = ReportBadges(drafts = 2))
        listOf(false, true).forEach { dark ->
            render(onChat, dark, chat = { Text("Unit chat stand-in") }) {
                seen("Unit chat stand-in")
                seen("Manage Reports")
            }
        }
        render(onChat, chat = { Text("Unit chat stand-in") }) {
            onAllNodesWithText("Manage Reports").onFirst().performClick()
            waitForIdle()
        }
        assertTrue(ListEvent.SetWorkspace(Workspace.Manage) in events, "got $events")
        render(onChat.copy(workspace = Workspace.Manage), chat = { Text("Unit chat stand-in") }) {
            seen("Day 14 — Studio 3")
            onAllNodesWithText("Chat").onFirst().performClick()
            waitForIdle()
        }
        assertTrue(ListEvent.SetWorkspace(Workspace.Chat) in events, "got $events")
    }

    @Test
    fun `a draft's menu opens and deletes through the confirm`() {
        render(state) {
            pickFromRowMenu("Delete")
        }
        assertTrue(events.single() is ListEvent.Delete, "got $events")
    }

    @Test
    fun `the approvals menus open and route to reminder, approve and publish`() {
        render(approvals.copy(section = ApprovalSection.Sent)) { pickFromRowMenu("Send Reminder") }
        assertTrue(events.single() is WorkflowEvent.OpenReminder, "got $events")

        render(approvals.copy(section = ApprovalSection.Received)) { pickFromRowMenu("Approve") }
        assertTrue(events.single() is WorkflowEvent.OpenApprove, "got $events")

        render(approvals.copy(section = ApprovalSection.Finalized)) { pickFromRowMenu("Publish") }
        assertTrue(events.single() is WorkflowEvent.OpenPublish, "got $events")
    }

    private fun dialogs(): List<Pair<ReportDialog, String>> {
        val report = ReportRenderFixtures.received
        val request = report.approvals.single()
        val history = ReportHistory.entries(ReportDetail(ReportRenderFixtures.finalized, SheetPayload()))
        return listOf(
            ReportDialog.Confirm(
                ConfirmAction.DeleteReport(report),
                "Delete Production Report",
                "Sure?",
                "Delete",
                true,
            )
                to "Delete Production Report",
            ReportDialog.TemplatePicker(ReportRenderFixtures.templates, 1) to "Feature Film",
            ReportDialog.DraftName("Day 15") to "Draft Name",
            ReportDialog.SendPicker("PR-014", fromEditor = true, choosing = true, emptySet(), emptySet())
                to "For Signature",
            ReportDialog.SendPicker("PR-014", fromEditor = false, choosing = false, setOf("u2"), setOf("u3"))
                to "Select Recipients for Comments",
            ReportDialog.SendPicker(
                reportId = "PR-014",
                fromEditor = false,
                choosing = false,
                selected = setOf("u2"),
                initial = setOf("u3"),
                pendingRemoval = listOf(ReportRenderFixtures.members[2]),
            ) to "Remove from comments?",
            ReportDialog.Publish(report) to "Where would you like to publish",
            ReportDialog.Publish(report, choosingDestination = false, PublishDestination.Both, continuation = true)
                to "Publish as Continuation",
            ReportDialog.Comments("PR-013", "Day 13", readOnly = false, ReportRenderFixtures.comments, loading = false)
                to "Double-check the second unit wrap.",
            ReportDialog.Comments("PR-013", "Day 13", readOnly = true) to "Day 13",
            ReportDialog.History("History", history) to "History",
            ReportDialog.Approve(report, request) to "Approve Production Report",
            ReportDialog.Reject(report, request, reason = "Wrong call") to "Reject Production Report",
            ReportDialog.ReminderCompose(report) to "Send Reminder",
            ReportDialog.Reminders(report.reminders) to "Please sign before call time",
            ReportDialog.ChatPicker("Chat with Approver", listOf("u2", "u3")) to "Oliver Grant",
            ReportDialog.DocDistConfirm(report, "Day 8.pdf", fromDraft = true) to "Publish to Document Distribution",
            ReportDialog.DocDistDone("Day 8.pdf") to "Day 8.pdf",
        )
    }

    @Test
    fun `every dialog composes in both themes`() {
        listOf(false, true).forEach { dark ->
            dialogs().forEach { (dialog, text) -> render(state.copy(dialog = dialog), dark) { seen(text) } }
        }
    }

    @Test
    fun `my own comment's actions menu opens and starts an edit`() {
        val thread = ReportDialog.Comments("PR-013", "Day 13", false, ReportRenderFixtures.comments, loading = false)
        render(state.copy(dialog = thread)) {
            onAllNodesWithContentDescription("Comment actions").onLast().performClick()
            waitForIdle()
            onAllNodesWithText("Edit").onLast().performClick()
            waitForIdle()
        }
        assertTrue(events.contains(DialogEvent.StartCommentEdit("c2")), "got $events")
    }

    @Test
    fun `the PDF overlay composes while generating and with pages`() {
        render(state.copy(pdf = PdfOverlay("PR-010", "Day 10"))) { seen("Generating PDF") }
        val page = SheetPdfPage(page = 0, imageBytes = ByteArray(0), widthPx = 0, heightPx = 0)
        render(state.copy(pdf = PdfOverlay("PR-010", "Day 10", loading = false, pages = listOf(page)))) {
            seen("PDF View")
        }
    }

    private companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1100
    }
}
