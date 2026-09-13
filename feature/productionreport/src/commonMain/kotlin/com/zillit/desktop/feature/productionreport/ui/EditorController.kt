package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.ComposeReport.withoutApproverCells
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportPopulate
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportTemplates
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.normalised
import com.zillit.desktop.feature.productionreport.domain.restoreCell
import com.zillit.desktop.feature.productionreport.domain.restoreCellFromRemovedRow
import com.zillit.desktop.feature.productionreport.domain.restoreRow
import com.zillit.desktop.feature.productionreport.domain.updateCell
import com.zillit.desktop.feature.productionreport.domain.withColumnRestored
import com.zillit.desktop.feature.productionreport.domain.withLineRestored

/**
 * The editor view — `openNewEditor`, `editProductionReport`, the save
 * family, and the pane rules of `ProductionReportApp.jsx:1092-1119, 1692-2389, 2931-3486`.
 */
@Suppress("TooManyFunctions") // One function per editor act the web offers.
internal class EditorController(private val ctx: ReportContext) {

    private var sessions = 0L

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per editor act.
    fun onEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.Back -> back()
            EditorEvent.ToggleFocus -> edit { copy(focusMode = !focusMode) }
            is EditorEvent.Zoom -> edit {
                copy(zoom = (zoom + event.delta).coerceIn(EditorState.MIN_ZOOM, EditorState.MAX_ZOOM))
            }
            EditorEvent.ResetZoom -> edit { copy(zoom = EditorState.DEFAULT_ZOOM) }
            is EditorEvent.Split -> edit {
                copy(splitPercent = event.percent.coerceIn(EditorState.MIN_SPLIT, EditorState.MAX_SPLIT))
            }
            EditorEvent.ShowSections -> edit { copy(sidebarVisible = true, selection = null, paneOpen = false) }
            EditorEvent.HideSections -> edit { copy(sidebarVisible = false) }
            EditorEvent.ClosePane -> edit { copy(selection = null, paneOpen = false, sidebarVisible = false) }
            is EditorEvent.Select -> select(event.selection)
            is EditorEvent.Focus -> edit { copy(focusedLine = event.line, focusedColumn = event.column) }
            EditorEvent.ToggleSaveMenu -> edit { copy(saveMenuOpen = !saveMenuOpen) }
            EditorEvent.Save -> {
                edit { copy(saveMenuOpen = false) }
                guard(SaveIntent.Save)
            }
            EditorEvent.SaveAs -> {
                edit { copy(saveMenuOpen = false) }
                ctx.update { copy(dialog = ReportDialog.DraftName("")) }
            }
            is EditorEvent.SetSectionSearch -> edit { copy(sectionSearch = event.query) }
            EditorEvent.Undo -> undo()
            is EditorEvent.ExpireUndo -> edit { if (undo?.serial == event.serial) copy(undo = null) else this }
            is EditorEvent.RestoreDefault -> restoreDefault(event.index)
            else -> Unit
        }
    }

    fun onDialog(event: DialogEvent) {
        when (event) {
            is DialogEvent.EditDraftName -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.DraftName)?.copy(name = event.name) ?: dialog)
            }
            DialogEvent.ConfirmDraftName -> confirmDraftName()
            else -> Unit
        }
    }

    /**
     * A new document from a template (or the local default), opened at once;
     * the last published call sheet is merged into empty cells behind a
     * dimmed preview, and the merged document becomes the clean baseline.
     */
    fun openNew(source: SheetPayload?, fromSavedTemplate: Boolean, template: TemplateRef? = null) {
        val templateRef = template
        val state = ctx.state
        if (!state.isPoster) return
        val kind = ctx.kind
        val base = when {
            source != null -> source
            kind != ReportKind.Production ->
                ReportTemplates.forKind(kind) ?: ComposeReport.defaultTemplate(ctx.todayYmd())
            else -> ComposeReport.defaultTemplate(ctx.todayYmd())
        }
        val typed = base.copy(shared = base.shared.copy(reportType = kind.wire))
        val (document, shootDay) = ComposeReport.newReport(
            template = typed,
            metadata = state.metadata,
            members = state.members,
            todayYmd = ctx.todayYmd(),
            fromSavedTemplate = fromSavedTemplate,
            regenerateCrew = kind.generatesCrewSections,
        )
        val session = ++sessions
        ctx.update {
            copy(
                dialog = null,
                editor = EditorState(
                    session = session,
                    document = document,
                    currentShootDay = shootDay,
                    template = templateRef,
                    dayTypes = metadata.dayTypes,
                    populating = ctx.projectId() != null,
                ),
            )
        }
        ctx.projectId()?.let { populate(it, session) }
    }

    private fun populate(projectId: String, session: Long) {
        ctx.launchWork {
            val callSheet = ctx.services.callSheets.lastPublishedPayload(projectId)
            ctx.update {
                val current = editor?.takeIf { it.session == session } ?: return@update this
                if (callSheet == null) {
                    copy(editor = current.copy(populating = false))
                } else {
                    fun merged(payload: SheetPayload) = ReportPopulate.fromCallSheet(
                        payload,
                        callSheet,
                    ).withoutApproverCells().normalised()
                    copy(
                        editor = current.copy(
                            document = merged(current.document),
                            baseline = merged(current.baseline),
                            populating = false,
                        ),
                    )
                }
            }
        }
    }

    /** An existing report: approvers seeded, approver blocks stripped; no metadata, crew or call sheet. */
    fun openExisting(report: ReportSummary) {
        if (!ctx.state.isPoster || report.status.locked) return
        ctx.launchWork {
            when (val result = ctx.repository.report(report.id)) {
                is ZillitResult.Success -> {
                    val detail = result.data
                    val source = detail.payload.takeIf { detail.hasPayload }
                        ?: ComposeReport.defaultTemplate(ctx.todayYmd())
                    val document = ComposeReport.forEditing(source, ctx.state.metadata.finalApproverIds)
                    val session = ++sessions
                    ctx.update {
                        copy(
                            editor = EditorState(
                                session = session,
                                reportId = detail.summary.id,
                                status = detail.summary.status
                                    .takeIf { it != ReportStatus.Unknown } ?: ReportStatus.Draft,
                                name = detail.summary.name,
                                document = document,
                                dayTypes = metadata.dayTypes,
                            ),
                        )
                    }
                }
                is ZillitResult.Failure -> ctx.toast(
                    "Failed to load production report: ${result.error.localised()}",
                    isError = true,
                )
            }
        }
    }

    /** Selecting anything hides the list and keeps the pane open. */
    private fun select(selection: EditorSelection?) = edit {
        if (selection == null) {
            copy(selection = null)
        } else {
            copy(
                selection = selection,
                sidebarVisible = false,
                paneOpen = true,
                focusedLine = null,
                focusedColumn = null,
            )
        }
    }

    /** Back: unsaved changes ask first — for an existing report too, where the web discarded silently. */
    private fun back() {
        val editor = ctx.state.editor ?: return
        if (editor.saving) return
        if (editor.dirty) {
            ctx.update {
                copy(
                    dialog = ReportDialog.Confirm(
                        action = ConfirmAction.LeaveEditor,
                        title = "Unsaved Changes",
                        message = "You have unsaved changes. Would you like to save before leaving?",
                        confirmLabel = "Discard",
                        danger = true,
                        secondaryLabel = "Save & Leave",
                    ),
                )
            }
        } else {
            discardAndLeave()
        }
    }

    fun discardAndLeave() {
        ctx.update { copy(editor = null) }
        ctx.lists.loadDrafts()
    }

    fun saveAndLeave() {
        val editor = ctx.state.editor ?: return
        if (editor.isNew) {
            ctx.update { copy(dialog = ReportDialog.DraftName("")) }
        } else {
            guard(SaveIntent.Save)
        }
    }

    /** ZL-20654: a draft never saves nameless. Save As on an existing report creates a new one. */
    private fun confirmDraftName() {
        val dialog = ctx.state.dialog as? ReportDialog.DraftName ?: return
        val name = dialog.name.trim()
        if (name.isEmpty()) {
            ctx.toast("Please enter a draft name.", isError = true)
            return
        }
        val editor = ctx.state.editor ?: return
        ctx.update {
            copy(
                dialog = null,
                editor = editor.copy(draftName = name, name = if (editor.isNew) name else editor.name),
            )
        }
        guard(if (editor.isNew) SaveIntent.Save else SaveIntent.SaveAsNew)
    }

    /**
     * `validateBeforeSave`: saving a report already out for review moves it
     * back to Draft, so ask first.
     */
    private fun guard(intent: SaveIntent) {
        val editor = ctx.state.editor ?: return
        if (editor.reportId != null && editor.status?.reviewInFlight == true) {
            ctx.update {
                copy(
                    dialog = ReportDialog.Confirm(
                        action = ConfirmAction.RestartReview(intent),
                        title = "Restart review?",
                        message = "This file is already shared for review. Saving will move it back to Draft and " +
                            "restart the review process. Are you sure you want to continue?",
                        confirmLabel = "Yes, save",
                        danger = true,
                    ),
                )
            }
        } else {
            runSave(intent)
        }
    }

    fun runSave(intent: SaveIntent) {
        when (intent) {
            SaveIntent.Save, SaveIntent.SaveThenLeave -> save(onSaved = {})
            SaveIntent.SaveAsNew -> saveAsNew()
        }
    }

    /** The editor's sends save first, without the review prompt — as the web calls `saveDraft` directly. */
    fun saveThen(action: (String) -> Unit) = save(onSaved = action)

    /**
     * `saveDraft`: the metadata counters first (a failure never blocks the
     * report), then a revision or the create; the editor closes onto Drafts.
     */
    private fun save(onSaved: (String) -> Unit) {
        val editor = ctx.state.editor ?: return
        val project = ctx.projectId() ?: return
        if (editor.saving || !ctx.state.isPoster) return
        edit { copy(saving = true) }
        ctx.launchWork {
            writeCounters(project, editor)
            val viewer = ctx.viewer()
            val name = editor.name.ifBlank { editor.draftName }
            val saved: ZillitResult<ReportSummary> = if (editor.reportId != null) {
                when (val revision = ctx.repository.saveRevision(
                    editor.reportId,
                    name,
                    editor.document,
                    viewer.displayName,
                    viewer.userId,
                )) {
                    is ZillitResult.Success -> ZillitResult.Success(revision.data ?: placeholder(editor, name))
                    is ZillitResult.Failure -> revision
                }
            } else {
                ctx.repository.create(project, name, editor.document, viewer.displayName, viewer.userId)
            }
            when (saved) {
                is ZillitResult.Success -> {
                    val row = saved.data
                    val id = editor.reportId ?: row.id
                    ctx.toast(if (editor.reportId != null) "Revision saved!" else "Draft created!")
                    closeOntoDrafts(
                        row.copy(id = id, name = row.name.ifBlank { name }),
                        wasSent = editor.status?.let { it != ReportStatus.Draft } == true,
                    )
                    onSaved(id)
                }
                is ZillitResult.Failure -> {
                    edit { copy(saving = false) }
                    ctx.toast("Save failed: ${saved.error.localised()}", isError = true)
                }
            }
        }
    }

    /** Save As on an existing report: a brand-new draft; no counters written. */
    private fun saveAsNew() {
        val editor = ctx.state.editor ?: return
        val project = ctx.projectId() ?: return
        if (editor.saving || !ctx.state.isPoster) return
        edit { copy(saving = true) }
        ctx.launchWork {
            val viewer = ctx.viewer()
            when (val created = ctx.repository.create(
                project,
                editor.draftName,
                editor.document,
                viewer.displayName,
                viewer.userId,
            )) {
                is ZillitResult.Success -> {
                    ctx.toast("Saved as new draft!")
                    closeOntoDrafts(created.data, wasSent = false)
                }
                is ZillitResult.Failure -> {
                    edit { copy(saving = false) }
                    ctx.toast("Save failed: ${created.error.localised()}", isError = true)
                }
            }
        }
    }

    /**
     * Total days as typed; the shoot-day counter only when a NEW report still
     * shows the number it was handed; the approvers, which become the project
     * default (the web's behaviour, kept).
     */
    private suspend fun writeCounters(project: String, editor: EditorState) {
        val shared = editor.document.shared
        val update = MetadataUpdate(
            totalDays = shared.totalDays.ifBlank { null },
            currentShootDay = editor.currentShootDay
                .takeIf { it > 0 && shared.shootDayNumber.trim().toIntOrNull() == it },
            finalApproverIds = shared.approverIds.ifEmpty { null },
        )
        if (update.isEmpty) return
        if (ctx.repository.saveMetadata(project, update) is ZillitResult.Success) {
            update.finalApproverIds?.let { ids ->
                ctx.update { copy(metadata = metadata.copy(finalApproverIds = ids)) }
            }
            update.currentShootDay?.let { day -> ctx.update { copy(metadata = metadata.copy(currentShootDay = day)) } }
        }
    }

    private fun placeholder(editor: EditorState, name: String) = ReportSummary(
        id = editor.reportId.orEmpty(),
        serialNo = "",
        name = name,
        status = ReportStatus.Draft,
        createdBy = ctx.viewer().displayName,
        createdById = ctx.state.me,
    )

    /** Closes the editor onto Drafts with the saved row in place before the reload lands. */
    private fun closeOntoDrafts(row: ReportSummary, wasSent: Boolean) {
        val now = ctx.now()
        ctx.update {
            val existing = lists.drafts.rows
            val patched = if (existing.any { it.id == row.id }) {
                existing.map { saved ->
                    if (saved.id != row.id) {
                        saved
                    } else {
                        saved.copy(name = row.name.ifBlank { saved.name }, updatedOn = now, status = ReportStatus.Draft)
                    }
                }
            } else {
                listOf(
                    row.copy(
                        status = ReportStatus.Draft,
                        createdOn = row.createdOn ?: now,
                        updatedOn = row.updatedOn ?: now,
                    ),
                ) + existing
            }
            copy(
                editor = null,
                workspace = Workspace.Manage,
                tab = ManageTab.Drafts,
                lists = lists.copy(drafts = lists.drafts.copy(rows = patched, loaded = true)),
            )
        }
        ctx.lists.loadDrafts()
        if (wasSent) ctx.lists.loadSent()
    }

    private fun undo() {
        val editor = ctx.state.editor ?: return
        val record = editor.undo ?: return
        val doc = editor.document
        val restored = when (val action = record.action) {
            is UndoAction.Row -> doc.restoreRow(action.row, action.index, action.headerBefore, action.approversBefore)
            is UndoAction.Cell -> doc.restoreCell(action.cell, action.rowIndex, action.cellIndex)
            is UndoAction.Line -> doc.updateCell(
                action.rowIndex,
                action.cellIndex,
            ) { it.withLineRestored(action.index, action.line) }
            is UndoAction.Column -> doc.updateCell(action.rowIndex, action.cellIndex) {
                it.withColumnRestored(action.index, action.column, action.values)
            }
        }
        // Every section the undo put back leaves "Restore Fields" — all of a row's, not only a lone one.
        val backInPlace = when (val action = record.action) {
            is UndoAction.Cell -> listOf(action.cell)
            is UndoAction.Row -> action.row.cells
            else -> emptyList()
        }
        edit {
            copy(
                document = restored,
                undo = null,
                removedDefaults = removedDefaults.filterNot { it.cell in backInPlace },
            )
        }
    }

    private fun restoreDefault(index: Int) {
        val editor = ctx.state.editor ?: return
        val removed = editor.removedDefaults.getOrNull(index) ?: return
        edit {
            copy(
                document = if (removed.rowCells.isEmpty()) {
                    document.restoreCell(removed.cell, removed.rowIndex, removed.cellIndex)
                } else {
                    document.restoreCellFromRemovedRow(removed.cell, removed.rowIndex, removed.rowCells)
                },
                removedDefaults = removedDefaults.filterIndexed { i, _ -> i != index },
            )
        }
    }

    private inline fun edit(crossinline change: EditorState.() -> EditorState) = ctx.update {
        copy(editor = editor?.change())
    }
}
