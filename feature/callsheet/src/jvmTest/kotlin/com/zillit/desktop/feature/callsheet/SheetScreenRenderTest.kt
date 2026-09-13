package com.zillit.desktop.feature.callsheet

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
import com.zillit.desktop.feature.callsheet.SheetRenderFixtures.NOW
import com.zillit.desktop.feature.callsheet.SheetRenderFixtures.state
import com.zillit.desktop.feature.callsheet.domain.AccessPerson
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.MissingTitle
import com.zillit.desktop.feature.callsheet.domain.PickedDocument
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetHistory
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.approvalStatusEntries
import com.zillit.desktop.feature.callsheet.ui.CallSheetScreen
import com.zillit.desktop.feature.callsheet.ui.ConfirmAction
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.ListView
import com.zillit.desktop.feature.callsheet.ui.PdfOverlay
import com.zillit.desktop.feature.callsheet.ui.PermissionState
import com.zillit.desktop.feature.callsheet.ui.PublishDestination
import com.zillit.desktop.feature.callsheet.ui.PublishStep
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes every list, row menu and dialog, light and dark, and clicks the
 * menus open — a lazy list or an intrinsic measurement inside a popup only
 * throws once the popup is on screen.
 */
@OptIn(ExperimentalTestApi::class)
class SheetScreenRenderTest {

    private val events = mutableListOf<SheetEvent>()

    private fun render(ui: SheetUiState, dark: Boolean = false, body: ComposeUiTest.() -> Unit = {}) {
        events.clear()
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent {
                ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                    CallSheetScreen(ui, onEvent = { events += it }, nowMillis = { NOW })
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

    private val approvals = state.copy(tab = SheetTab.Approvals)

    private val permission = state.copy(
        tab = SheetTab.Permission,
        permission = PermissionState(
            people = listOf(
                AccessPerson("u2", "Maya Fernandes", "Producer", "Production", canView = true, canPost = true),
                AccessPerson("u4", "Priya Nair", "DOP", "Camera"),
            ),
            total = 2,
            loaded = true,
        ),
    )

    @Test
    fun `every list composes in both themes, as a table and as cards`() {
        val screens = listOf(
            state to "Day 14 — Studio 3",
            state.copy(draftsView = ListView.Cards) to "Day 13 — Harbour exterior",
            // The approvals tables name no sheet, as on the web: the creator and status tell rows apart.
            approvals.copy(section = ApprovalSection.Sent) to "Created By",
            approvals.copy(section = ApprovalSection.Received) to "Maya Fernandes",
            approvals.copy(section = ApprovalSection.Finalized, approvalsView = ListView.Cards) to "Sahil Kashyap",
            approvals.copy(section = ApprovalSection.Received, approvalsView = ListView.Cards) to "Maya Fernandes",
            state.copy(tab = SheetTab.Published) to "Day 10 — Rooftop",
            permission to "Priya Nair",
        )
        listOf(false, true).forEach { dark ->
            screens.forEach { (ui, name) -> render(ui, dark) { seen(name) } }
        }
    }

    @Test
    fun `the tab bar shows the badges and a viewer without posting rights lands on Approvals`() {
        render(state.copy(badges = SheetBadges(drafts = 2, approvals = 1))) {
            seen("Call Sheet Creation")
            onAllNodesWithText("Published Call sheet").onFirst().performClick()
            waitForIdle()
        }
        assertTrue(ListEvent.OpenTab(SheetTab.Published) in events, "got $events")

        val viewer = state.copy(viewer = state.viewer.copy(canPost = false, canViewGrid = false))
        render(viewer) { seen("Drafts Call Sheet") }
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

    private fun dialogs(): List<Pair<SheetDialog, String>> {
        val sheet = SheetRenderFixtures.received
        val request = sheet.approvals.single()
        val finalized = SheetRenderFixtures.finalized
        val history = SheetHistory.entries(CallSheetDetail(finalized, SheetPayload()), SheetRenderFixtures.members)
        val status = approvalStatusEntries(finalized.approvals, "FINAL", SheetRenderFixtures.members)
        val picked = PickedDocument("Day 8 map.pdf", "application/pdf", ByteArray(4))
        return listOf(
            SheetDialog.Confirm(ConfirmAction.DeleteSheet(sheet), "Delete Call Sheet", "Sure?", "Delete", true)
                to "Delete Call Sheet",
            SheetDialog.TemplatePicker(SheetRenderFixtures.templates, 1) to "Comfort and Joy",
            SheetDialog.DraftName("Day 15") to "Draft Name",
            SheetDialog.MissingTitles(listOf(MissingTitle(0, 1, "Project Name"))) to "Section name required",
            SheetDialog.SendPicker("CS-014", fromEditor = true, selected = emptySet(), initial = emptySet())
                to "Send for Comments",
            SheetDialog.SendPicker("CS-014", fromEditor = false, selected = setOf("u2"), initial = setOf("u3"))
                to "Previously Selected",
            SheetDialog.SendPicker(
                sheetId = "CS-014",
                fromEditor = false,
                selected = setOf("u2"),
                initial = setOf("u3"),
                pendingRemoval = listOf(SheetRenderFixtures.members[2]),
            ) to "Remove from comments?",
            SheetDialog.ChatSend(sheet, selected = "u3") to "Send for Chat",
            SheetDialog.Publish(sheet) to "Where would you like",
            SheetDialog.Publish(sheet, PublishStep.Type, PublishDestination.Both, continuation = true)
                to "Publish as Continuation",
            SheetDialog.AttachDocument(sheet, picked, caption = "Map") to "Attach Document",
            SheetDialog.AttachDocument(sheet, picked, withPublish = true) to "Publish with a Document",
            SheetDialog.Comments("CS-013", "Day 13", readOnly = false, SheetRenderFixtures.comments, loading = false)
                to "Double-check the second unit wrap.",
            SheetDialog.Comments("CS-013", "Day 13", readOnly = true) to "Day 13",
            SheetDialog.History("History", history) to "History",
            SheetDialog.ApprovalStatus("Approval Status", status) to "Oliver Grant",
            SheetDialog.Approve(sheet, request) to "Approve Call Sheet",
            SheetDialog.Reject(sheet, request, reason = "Wrong call") to "Reject Call Sheet",
            SheetDialog.ReminderCompose(sheet) to "Send Reminder",
            SheetDialog.ReminderView(sheet.reminders.single()) to "Please sign before call time",
            SheetDialog.ChatPicker("Chat with Approver", listOf("u2", "u3")) to "Oliver Grant",
            SheetDialog.DocDistConfirm(sheet, "Day 8.pdf", fromDraft = true) to "Publish to Document Distribution",
            SheetDialog.DocDistDone("Day 8.pdf") to "Day 8.pdf",
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
        val thread = SheetDialog.Comments("CS-013", "Day 13", false, SheetRenderFixtures.comments, loading = false)
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
        render(state.copy(pdf = PdfOverlay("CS-010", "Day 10"))) { seen("Generating PDF") }
        val page = SheetPdfPage(page = 0, imageBytes = ByteArray(0), widthPx = 0, heightPx = 0)
        render(state.copy(pdf = PdfOverlay("CS-010", "Day 10", loading = false, pages = listOf(page)))) {
            seen("PDF View")
        }
    }

    private companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1100
    }
}
