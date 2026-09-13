package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.InsertKind
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.ui.ConfirmAction
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import com.zillit.desktop.feature.productionreport.ui.SaveIntent
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
class ReportEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = ReportHarness(dispatcher)
    private val repository = harness.repository
    private val toasts = harness.toasts

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun ReportViewModel.thread() = assertIs<ReportDialog.Comments>(currentState.dialog)

    private fun ReportViewModel.editor() = assertNotNull(currentState.editor)

    private fun titles(vm: ReportViewModel) = vm.editor().document.rows.map { row -> row.cells.map { it.title } }

    @Test
    fun `create picks a layout, and a new draft saves only once it has a name`() = runTest(dispatcher) {
        repository.stock = listOf(
            Samples.stock("standard", "Create your own", createYourOwn = true),
            Samples.stock("feature", "Feature Film"),
        )
        repository.metadata = SheetMetadata(currentShootDay = 4)
        val vm = harness.start(this)

        vm.onEvent(DialogEvent.CreateTemplate)
        assertEquals(1, assertIs<ReportDialog.TemplatePicker>(vm.currentState.dialog).selected)
        vm.onEvent(DialogEvent.UseTemplate(1))
        settle()
        assertTrue(vm.editor().isNew)
        assertEquals("5", vm.editor().document.shared.shootDayNumber)
        assertFalse(vm.editor().dirty, "the merged call sheet is the clean baseline")

        vm.onEvent(EditorEvent.SaveAs)
        vm.onEvent(DialogEvent.EditDraftName("   "))
        vm.onEvent(DialogEvent.ConfirmDraftName)
        settle()
        assertIs<ReportDialog.DraftName>(vm.currentState.dialog, "a draft never saves nameless")
        assertEquals("Please enter a draft name.", toasts.last().message)

        vm.onEvent(DialogEvent.EditDraftName("Day 5"))
        vm.onEvent(DialogEvent.ConfirmDraftName)
        settle()
        assertEquals("Day 5", repository.created.single().first)
        assertEquals(MetadataUpdate(currentShootDay = 5), repository.metadataWrites.single(), "the counter advances")
        assertNull(vm.currentState.editor)
        assertEquals(ManageTab.Drafts, vm.currentState.tab)
        assertEquals("new1", vm.currentState.lists.drafts.rows.first().id, "the saved row shows before the reload")
        assertEquals("Draft created!", toasts.last().message)
    }

    @Test
    fun `saving a report out for review asks before restarting it, then saves a revision`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1", ReportStatus.PendingInternalApproval)
        repository.details["r1"] = ReportDetail(report, Samples.document())
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Edit(report))
        settle()
        assertEquals("r1", vm.editor().reportId)
        vm.onEvent(EditorEvent.Save)
        assertEquals(
            ConfirmAction.RestartReview(SaveIntent.Save),
            assertIs<ReportDialog.Confirm>(vm.currentState.dialog).action,
        )
        settle()
        assertTrue(repository.revisions.isEmpty())

        vm.onEvent(DialogEvent.Confirm)
        settle()
        assertEquals(listOf("r1"), repository.revisions, "an edit is a revision, never a create")
        assertTrue(repository.created.isEmpty())
        assertNull(vm.currentState.editor)
        assertEquals("Revision saved!", toasts.last().message)
    }

    @Test
    fun `a locked report never opens in the editor`() = runTest(dispatcher) {
        val vm = harness.start(this)
        repository.detailRequests.clear()
        vm.onEvent(ListEvent.Edit(Samples.row("r1", "Day 1", ReportStatus.ApprovedForPublish)))
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
        assertEquals(ConfirmAction.LeaveEditor, (vm.currentState.dialog as? ReportDialog.Confirm)?.action)
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
        assertEquals(listOf("Address", "Key Personnel", "Schedule Info"), opened.first())

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
        val report = Samples.row("r1", "Day 1")
        repository.thread += ReportComment("c1", authorId = "me", authorName = "Author", text = "First")
        val vm = harness.start(this)

        vm.onEvent(ListEvent.OpenComments(report, readOnly = false))
        settle()
        assertEquals(listOf("r1"), harness.badges.threadsRead, "opening a thread reads its badge")
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
        vm.onEvent(ListEvent.OpenComments(report, readOnly = true))
        vm.onEvent(DialogEvent.EditCommentDraft("Not allowed"))
        vm.onEvent(DialogEvent.SendComment)
        settle()
        assertEquals(1, repository.commentAdds.size)
    }
}
