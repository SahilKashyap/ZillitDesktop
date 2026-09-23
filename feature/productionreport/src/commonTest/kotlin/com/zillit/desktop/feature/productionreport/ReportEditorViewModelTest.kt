package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.CallSheetForDay
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.InsertKind
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
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

        harness.badges.live.emit(listOf(BadgeLeaf(ReportBadges.UNIT_DRAFTS, level2 = "comment", level3 = "r1")))
        settle()
        vm.onEvent(ListEvent.OpenComments(report, readOnly = false))
        settle()
        assertEquals(
            listOf(BadgeRead(BadgeSurface.Drafts, BadgeKind.Comment, "r1")),
            harness.badges.reads,
            "opening a thread reads its comment badge on this list",
        )
        assertFalse(vm.thread().readOnly, "the creator may post")
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

    @Test
    fun `everyone reads a thread, only the creator or a comment recipient writes in it`() = runTest(dispatcher) {
        val theirs = Samples.row("r1", "Day 1", ReportStatus.PendingApproval, createdById = "u2")
        val vm = harness.start(this, Samples.viewer)

        vm.onEvent(ListEvent.OpenComments(theirs, readOnly = false))
        settle()
        assertTrue(vm.thread().readOnly, "an approver reads the thread; the screen's answer is not trusted")
        assertEquals("Only the report's creator and its comment recipients can post here.", vm.thread().closedNote)
        vm.onEvent(DialogEvent.EditCommentDraft("Not mine"))
        vm.onEvent(DialogEvent.SendComment)
        settle()
        assertTrue(repository.commentAdds.isEmpty())

        vm.onEvent(DialogEvent.Dismiss)
        repository.metadata = SheetMetadata(internalReceiverIds = listOf("me"))
        vm.start()
        settle()
        vm.onEvent(ListEvent.OpenComments(theirs, readOnly = false))
        settle()
        assertFalse(vm.thread().readOnly, "a comment recipient may post on every report")
    }

    @Test
    fun `an emptied approver list reaches the metadata write, a fresh template's empty list does not`() =
        runTest(dispatcher) {
            val report = Samples.row("r1", "Day 1")
            repository.details["r1"] = ReportDetail(
                report,
                Samples.document().copy(shared = SharedHeader(approverIds = listOf("u2"))),
            )
            val vm = harness.start(this)

            vm.onEvent(ListEvent.Edit(report))
            settle()
            assertEquals(listOf("u2"), vm.editor().initialApproverIds)
            vm.onEvent(DocumentEvent.ToggleApprover("u2"))
            vm.onEvent(EditorEvent.Save)
            settle()
            assertEquals(
                MetadataUpdate(finalApproverIds = emptyList()),
                repository.metadataWrites.single(),
                "ZL-21468: the removal is written, not omitted",
            )

            repository.metadataWrites.clear()
            vm.onEvent(DialogEvent.CreateTemplate)
            settle()
            assertTrue(vm.editor().document.shared.approverIds.isEmpty())
            vm.onEvent(EditorEvent.SaveAs)
            vm.onEvent(DialogEvent.EditDraftName("Day 2"))
            vm.onEvent(DialogEvent.ConfirmDraftName)
            settle()
            assertTrue(
                repository.metadataWrites.none { it.finalApproverIds != null },
                "a fresh document's [] must not clear the project default",
            )
        }

    @Test
    fun `Save as Template is create-only, and editing a report is never a template session`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        repository.details["r1"] = ReportDetail(report, Samples.document())
        val vm = harness.start(this)

        vm.onEvent(ListEvent.Edit(report))
        settle()
        assertNull(vm.editor().template, "no Update Template on an existing report")
        vm.onEvent(EditorEvent.SaveAsTemplate)
        settle()
        assertEquals(0, repository.templateCreates, "the handler refuses, whatever the screen sent")
        assertEquals("r1", vm.editor().reportId, "the editor stays open, its edits kept")

        vm.onEvent(EditorEvent.Back)
        vm.onEvent(DialogEvent.CreateTemplate)
        settle()
        vm.onEvent(EditorEvent.SaveAsTemplate)
        settle()
        assertEquals(1, repository.templateCreates)
        assertNull(vm.currentState.editor)
    }

    private fun withCrewCall(value: String) = SheetPayload(
        rows = listOf(
            PageRow(
                0,
                cells = listOf(
                    PageCell(
                        order = 0,
                        title = "Call Times",
                        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
                        rows = listOf(
                            CellRow(0, listOf(CellValue("Crew Call"), CellValue(value))),
                            CellRow(1, listOf(CellValue("Unit Wrap"), CellValue(""))),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun ReportViewModel.crewCall(): String =
        editor().document.rows.flatMap { it.cells }.flatMap { it.rows }
            .first { it.values.firstOrNull()?.value == "Crew Call" }.values[1].value

    @Test
    fun `a new report takes the day's published call sheet's crew call, or says none was published`() =
        runTest(dispatcher) {
            repository.stock = listOf(
                com.zillit.desktop.feature.productionreport.domain.StockTemplate("daily", "Daily", withCrewCall("")),
            )
            harness.callSheetForDay = CallSheetForDay.Found(
                SheetPayload(
                    rows = listOf(
                        PageRow(
                            0,
                            cells = listOf(
                                PageCell(
                                    order = 0,
                                    title = "Times",
                                    columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
                                    rows = listOf(CellRow(0, listOf(CellValue("Unit Call"), CellValue("7:30")))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val vm = harness.start(this)
            vm.onEvent(DialogEvent.CreateTemplate)
            settle()
            assertEquals(
                listOf(ReportTime.todayYmd(Samples.NOW)),
                harness.callSheetDays,
                "the report's shoot date, not the newest published sheet",
            )
            assertEquals("07:30", vm.crewCall())
            assertFalse(vm.editor().dirty, "the merged document is the clean baseline")
            assertNull(vm.currentState.dialog)

            vm.onEvent(EditorEvent.Back)
            harness.callSheetForDay = CallSheetForDay.None
            vm.onEvent(DialogEvent.CreateTemplate)
            settle()
            assertEquals("", vm.crewCall())
            val prompt = assertIs<ReportDialog.Confirm>(vm.currentState.dialog)
            assertEquals(ConfirmAction.NoPublishedCallSheet, prompt.action)
            assertEquals("No Published Call Sheet", prompt.title)

            vm.onEvent(DialogEvent.Confirm)
            vm.onEvent(EditorEvent.Back)
            harness.callSheetForDay = CallSheetForDay.Unavailable
            vm.onEvent(DialogEvent.CreateTemplate)
            settle()
            assertNull(vm.currentState.dialog, "a failed request says nothing about what is published")
        }

    @Test
    fun `the header's sends follow the shared rule, check call times, then save and send`() = runTest(dispatcher) {
        val report = Samples.row("r1", "Day 1")
        repository.details["r1"] = ReportDetail(
            report,
            withCrewCall("").copy(shared = SharedHeader(approverIds = listOf("u2"))),
        )
        val vm = harness.start(this)
        vm.onEvent(ListEvent.Edit(report))
        settle()
        assertTrue(vm.editor().sendActions.sendForSignature)
        assertTrue(vm.editor().sendActions.sendForComments, "a draft may go for comments")

        vm.onEvent(EditorEvent.SendForSignature)
        settle()
        assertEquals(ConfirmAction.MissingCallTimes, assertIs<ReportDialog.Confirm>(vm.currentState.dialog).action)
        assertTrue(repository.revisions.isEmpty(), "checked before the save")
        vm.onEvent(DialogEvent.Confirm)

        vm.onEvent(EditorEvent.SendForComments)
        assertTrue(assertIs<ReportDialog.SendPicker>(vm.currentState.dialog).fromEditor)
        vm.onEvent(DialogEvent.Dismiss)

        val filled = withCrewCall("06:30").let { doc ->
            doc.copy(
                shared = SharedHeader(approverIds = listOf("u2")),
                rows = doc.rows.map { row ->
                    row.copy(
                        cells = row.cells.map { cell ->
                            cell.copy(
                                rows = cell.rows.map { line ->
                                    line.copy(
                                        values = line.values.map { v -> v.copy(value = v.value.ifBlank { "19:00" }) },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        repository.details["r1"] = ReportDetail(report, filled)
        vm.onEvent(EditorEvent.Back)
        vm.onEvent(ListEvent.Edit(report))
        settle()
        vm.onEvent(EditorEvent.SendForSignature)
        settle()
        assertEquals(listOf("r1"), repository.revisions, "the send saves first")
        assertEquals("r1", repository.signatureSends.single().first)
        assertNull(vm.currentState.editor)
        assertEquals(ManageTab.Approvals, vm.currentState.tab)
    }
}
