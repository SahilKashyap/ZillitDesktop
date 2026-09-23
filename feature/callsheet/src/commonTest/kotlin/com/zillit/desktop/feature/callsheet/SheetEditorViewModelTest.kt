package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.BadgeKind
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.InsertKind
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.ui.ConfirmAction
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.DocumentEvent
import com.zillit.desktop.feature.callsheet.ui.EditorEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
import com.zillit.desktop.feature.callsheet.ui.SaveIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor, its saves and document edits, and the comment thread — through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = SheetHarness(dispatcher)
    private val repository = harness.repository
    private val toasts = harness.toasts

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun CallSheetViewModel.thread() = assertIs<SheetDialog.Comments>(currentState.dialog)

    private fun CallSheetViewModel.editor() = assertNotNull(currentState.editor)

    private fun titles(vm: CallSheetViewModel) = vm.editor().document.rows.map { row -> row.cells.map { it.title } }

    @Test
    fun `create picks a layout, and a new draft saves only once it has a name`() = runTest(dispatcher) {
        repository.stock = listOf(
            Samples.stock("standard", "Create your own", createYourOwn = true),
            Samples.stock("feature", "Feature Film"),
        )
        repository.metadata = SheetMetadata(currentShootDay = 4)
        val vm = harness.start(this)

        vm.onEvent(DialogEvent.CreateTemplate)
        assertEquals(1, assertIs<SheetDialog.TemplatePicker>(vm.currentState.dialog).selected)
        vm.onEvent(DialogEvent.UseTemplate(1))
        settle()
        assertTrue(vm.editor().isNew)
        assertEquals("5", vm.editor().document.shared.shootDayNumber)
        assertFalse(vm.editor().dirty, "the opened document is the clean baseline")

        vm.onEvent(EditorEvent.SaveAs)
        vm.onEvent(DialogEvent.EditDraftName("   "))
        vm.onEvent(DialogEvent.ConfirmDraftName)
        settle()
        assertIs<SheetDialog.DraftName>(vm.currentState.dialog, "a draft never saves nameless")
        assertEquals("Please enter a draft name.", toasts.last().message)

        vm.onEvent(DialogEvent.EditDraftName("Day 5"))
        vm.onEvent(DialogEvent.ConfirmDraftName)
        settle()
        assertEquals("Day 5", repository.created.single().first)
        assertEquals(MetadataUpdate(currentShootDay = 5), repository.metadataWrites.single(), "the counter advances")
        assertNull(vm.currentState.editor)
        assertEquals(SheetTab.Drafts, vm.currentState.tab)
        assertTrue(repository.queries.count { CallSheetStatus.Draft in it.statuses } >= 2, "Drafts reloads")
        assertEquals("Draft created!", toasts.last().message)
    }

    @Test
    fun `saving a sheet out for review asks before restarting it, then saves a revision in place`() =
        runTest(dispatcher) {
        val sheet = Samples.row("r1", "Day 1", CallSheetStatus.PendingInternalApproval)
        repository.details["r1"] = CallSheetDetail(sheet, Samples.document())
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Edit(sheet))
        settle()
        assertEquals("r1", vm.editor().sheetId)
        vm.onEvent(EditorEvent.Save)
        assertEquals(
            ConfirmAction.RestartReview(SaveIntent.Save),
            assertIs<SheetDialog.Confirm>(vm.currentState.dialog).action,
        )
        settle()
        assertTrue(repository.revisions.isEmpty())

        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), repository.revisions, "an edit is a revision, never a create")
        assertTrue(repository.created.isEmpty())
        assertNotNull(vm.currentState.editor, "a revision save keeps the editor open, as the web does")
        assertEquals(CallSheetStatus.Draft, vm.editor().status, "the review restarted: the sheet is a draft again")
        assertFalse(vm.editor().dirty)
        assertEquals("Revision saved!", toasts.last().message)
        }

    @Test
    fun `removing every approver writes an emptied list, but a fresh document never clears the default`() =
        runTest(dispatcher) {
            val sheet = Samples.row("r1", "Day 1")
            repository.details["r1"] = CallSheetDetail(
                sheet,
                Samples.document().copy(shared = SharedHeader(approverIds = listOf("u2"), approverIdsStated = true)),
            )
            repository.metadata = SheetMetadata(finalApproverIds = listOf("u2"))
            val vm = harness.start(this)

            vm.onEvent(ListEvent.Edit(sheet))
            settle()
            assertEquals(listOf("u2"), vm.editor().initialApproverIds)
            vm.onEvent(DocumentEvent.ToggleApprover("u2"))
            assertTrue(vm.editor().document.shared.approverIds.isEmpty())
            vm.onEvent(EditorEvent.Save)
            settle()
            assertEquals(
                emptyList<String>(),
                repository.metadataWrites.single().finalApproverIds,
                "ZL-21468: the removal reaches the merging PUT",
            )
            assertTrue(vm.currentState.metadata.finalApproverIds.isEmpty())

            repository.metadataWrites.clear()
            repository.metadata = SheetMetadata(totalDays = "50")
            vm.onEvent(EditorEvent.Back)
            settle()
            vm.onEvent(DialogEvent.CreateTemplate)
            settle()
            assertTrue(vm.editor().isNew && vm.editor().document.shared.approverIds.isEmpty())
            vm.onEvent(EditorEvent.SaveAs)
            vm.onEvent(DialogEvent.EditDraftName("Day 2"))
            vm.onEvent(DialogEvent.ConfirmDraftName)
            settle()
            assertEquals(1, repository.created.size)
            assertTrue(
                repository.metadataWrites.all { it.finalApproverIds == null },
                "a template's empty list is no statement of intent",
            )
        }

    @Test
    fun `Save as Template is create-only - an existing sheet neither offers nor honours it`() = runTest(dispatcher) {
        val sheet = Samples.row("r1", "Day 1")
        repository.details["r1"] = CallSheetDetail(sheet, Samples.document())
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Edit(sheet))
        settle()
        assertFalse(vm.editor().offersSaveAsTemplate, "ZL-21539")
        assertNull(vm.editor().template, "editing a sheet never holds a template to update")
        vm.onEvent(EditorEvent.SaveAsTemplate)
        settle()
        assertNotNull(vm.currentState.editor, "nothing saved, nothing closed")
        assertTrue(toasts.none { it.message.startsWith("Saved as") })

        vm.onEvent(EditorEvent.Back)
        settle()
        vm.onEvent(DialogEvent.CreateTemplate)
        settle()
        assertTrue(vm.editor().offersSaveAsTemplate, "a new document may become a template")
    }

    @Test
    fun `a locked sheet never opens in the editor`() = runTest(dispatcher) {
        val vm = harness.start(this)
        repository.detailRequests.clear()
        vm.onEvent(ListEvent.Edit(Samples.row("r1", "Day 1", CallSheetStatus.ApprovedForPublish)))
        settle()
        assertNull(vm.currentState.editor)
        assertTrue(repository.detailRequests.isEmpty())
    }

    @Test
    fun `a removed row comes back in place with undo, and Back asks about unsaved changes`() = runTest(dispatcher) {
        val vm = harness.start(this)
        vm.onEvent(DialogEvent.CreateTemplate)
        settle()
        val opened = titles(vm)

        vm.onEvent(DocumentEvent.InsertRow(InsertKind.Section, afterIndex = -1))
        assertEquals(listOf("New Section"), titles(vm).first())
        assertEquals(EditorSelection.Cell(0, 0), vm.editor().selection)
        assertTrue(vm.editor().dirty)
        val inserted = titles(vm)

        vm.onEvent(DocumentEvent.RemoveRow(0))
        assertEquals(opened, titles(vm))
        assertEquals("New Section", vm.editor().undo?.label)
        assertNull(vm.editor().selection)

        vm.onEvent(EditorEvent.Undo)
        assertEquals(inserted, titles(vm))
        assertNull(vm.editor().undo)

        vm.onEvent(EditorEvent.Back)
        assertEquals(ConfirmAction.LeaveEditor, (vm.currentState.dialog as? SheetDialog.Confirm)?.action)
        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertNull(vm.currentState.editor, "Discard leaves without saving")
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun `Restore Fields brings a removed row's system sections back side by side`() = runTest(dispatcher) {
        val vm = harness.start(this)
        vm.onEvent(DialogEvent.CreateTemplate)
        settle()
        val opened = titles(vm)
        assertEquals(listOf("Executives Names", "Company Details", "Call Times"), opened.first())

        vm.onEvent(DocumentEvent.RemoveRow(0))
        vm.onEvent(EditorEvent.Undo)
        assertEquals(opened, titles(vm))
        assertTrue(vm.editor().removedDefaults.isEmpty(), "an undone removal leaves nothing to restore")

        vm.onEvent(DocumentEvent.RemoveRow(0))
        vm.onEvent(EditorEvent.ExpireUndo(assertNotNull(vm.editor().undo).serial))
        assertEquals(3, vm.editor().removedDefaults.size)
        repeat(3) { vm.onEvent(EditorEvent.RestoreDefault(vm.editor().removedDefaults.lastIndex)) }
        assertEquals(opened, titles(vm))
        assertTrue(vm.editor().removedDefaults.isEmpty())
    }

    @Test
    fun `the thread adds, edits and deletes, and a read-only thread never sends`() = runTest(dispatcher) {
        val sheet = Samples.row("r1", "Day 1")
        repository.thread += SheetComment("c1", authorId = "me", authorName = "Author", text = "First")
        harness.badges.leaves.value = listOf(BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "comment", level3 = "r1"))
        val vm = harness.start(this)
        settle()

        vm.onEvent(ListEvent.OpenComments(sheet, readOnly = false))
        settle()
        assertEquals(
            listOf(BadgeRead(BadgeSurface.Drafts, BadgeKind.Comment, "r1")),
            harness.badges.reads,
            "opening an unread thread reads its comment badge, on its surface",
        )
        assertEquals(listOf("First"), vm.thread().comments.map { it.text })

        vm.onEvent(DialogEvent.EditCommentDraft("  Second  "))
        vm.onEvent(DialogEvent.SendComment)
        settle()
        assertEquals(listOf("r1" to "Second"), repository.commentAdds)
        assertEquals("2nd AD", repository.commentAuthors.single()?.designation)
        assertEquals(listOf("First", "Second"), vm.thread().comments.map { it.text })
        assertEquals("", vm.thread().draft)

        vm.onEvent(DialogEvent.StartCommentEdit("c1"))
        vm.onEvent(DialogEvent.EditCommentText("First, edited"))
        vm.onEvent(DialogEvent.SaveCommentEdit)
        settle()
        assertEquals(listOf(Triple("r1", "c1", "First, edited")), repository.commentEdits)
        assertEquals("First, edited", vm.thread().comments.first().text)
        assertTrue("c1" in vm.thread().editedIds)

        vm.onEvent(DialogEvent.AskDeleteComment("c2"))
        settle()
        assertTrue(repository.commentDeletes.isEmpty(), "the inline confirm comes first")
        vm.onEvent(DialogEvent.ConfirmDeleteComment)
        settle()
        assertEquals(listOf("r1" to "c2"), repository.commentDeletes)
        assertEquals(listOf("c1"), vm.thread().comments.map { it.id })

        vm.onEvent(DialogEvent.Dismiss)
        vm.onEvent(ListEvent.OpenComments(sheet, readOnly = true))
        vm.onEvent(DialogEvent.EditCommentDraft("Not allowed"))
        vm.onEvent(DialogEvent.SendComment)
        settle()
        assertEquals(1, repository.commentAdds.size)
    }
}
