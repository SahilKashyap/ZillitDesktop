package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.missingDefaultTitles
import com.zillit.desktop.feature.callsheet.domain.restoreCell
import com.zillit.desktop.feature.callsheet.domain.restoreCellFromRemovedRow
import com.zillit.desktop.feature.callsheet.domain.restoreRow
import com.zillit.desktop.feature.callsheet.domain.shouldWriteApproverMeta
import com.zillit.desktop.feature.callsheet.domain.updateCell
import com.zillit.desktop.feature.callsheet.domain.withColumnRestored
import com.zillit.desktop.feature.callsheet.domain.withLineRestored

/**
 * The editor view — `openNewEditor`, `editCallSheet`, the save family and the
 * pane rules of `CallSheetApp.jsx:1394-1469, 2244-2616, 3310-3752`.
 *
 * Divergences, each fixing a web bug: a revision save refreshes the status
 * and the clean baseline (B-5, B-6) and keeps the sheet's name (B-7); the
 * review-restart prompt no longer guards template saves, which never touch
 * the sheet (B-11); every save is guarded against a second click (B-15); a
 * reopened editor starts with the pane closed (B-23).
 */
@Suppress("TooManyFunctions") // One function per editor act the web offers.
internal class EditorController(private val ctx: SheetContext) {

    private var sessions = 0L

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per editor act.
    fun onEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.Back -> back()
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
                validate(SaveIntent.Save)
            }
            EditorEvent.SaveAs -> {
                edit { copy(saveMenuOpen = false) }
                validate(SaveIntent.SaveAsNew)
            }
            is EditorEvent.SetSectionSearch -> edit { copy(sectionSearch = event.query) }
            EditorEvent.Undo -> undo(quick = false)
            EditorEvent.QuickUndo -> undo(quick = true)
            is EditorEvent.ExpireUndo -> edit { if (undo?.serial == event.serial) copy(undo = null) else this }
            is EditorEvent.ExpireQuickUndo -> edit {
                if (quickUndo?.serial == event.serial) copy(quickUndo = null) else this
            }
            is EditorEvent.RestoreDefault -> restoreDefault(event.index)
            else -> Unit
        }
    }

    fun onDialog(event: DialogEvent) {
        when (event) {
            is DialogEvent.EditDraftName -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.DraftName)?.copy(name = event.name) ?: dialog)
            }
            DialogEvent.ConfirmDraftName -> confirmDraftName()
            is DialogEvent.FixMissingTitle -> {
                ctx.update { copy(dialog = null) }
                select(EditorSelection.Cell(event.item.row, event.item.cell))
            }
            else -> Unit
        }
    }

    /**
     * A new document from a template (or the local default): the header
     * seeded from the project, the crew sections regenerated, Company Details
     * pre-filled — then opened at once.
     */
    fun openNew(source: SheetPayload?, fromSavedTemplate: Boolean, template: TemplateRef? = null) {
        val state = ctx.state
        if (!state.isPoster) return
        val base = source ?: state.stockTemplates.firstOrNull()?.payload ?: ComposeSheet.defaultTemplate(ctx.todayMs())
        val members = ctx.members().ifEmpty { state.members }
        val (document, shootDay) = ComposeSheet.newSheet(
            template = base,
            metadata = state.metadata,
            members = members,
            company = ctx.services.company(),
            todayMs = ctx.todayMs(),
            fromSavedTemplate = fromSavedTemplate,
        )
        val session = ++sessions
        ctx.update {
            copy(
                dialog = null,
                members = members,
                editor = EditorState(
                    session = session,
                    document = document,
                    currentShootDay = shootDay,
                    template = template,
                    dayTypes = metadata.dayTypes,
                ),
            )
        }
    }

    /** An existing sheet: blanks back-filled from the project, approver blocks stripped; no crew or company. */
    fun openExisting(sheet: CallSheetSummary) {
        if (!ctx.state.isPoster || sheet.status.locked) return
        ctx.launchWork {
            when (val result = ctx.repository.sheet(sheet.id)) {
                is ZillitResult.Success -> {
                    val detail = result.data
                    val source = detail.payload.takeIf { detail.hasPayload }
                        ?: ComposeSheet.defaultTemplate(ctx.todayMs())
                    val document = ComposeSheet.forEditing(source, ctx.state.metadata)
                    val session = ++sessions
                    ctx.update {
                        copy(
                            members = ctx.members().ifEmpty { members },
                            editor = EditorState(
                                session = session,
                                sheetId = detail.summary.id,
                                serialNo = detail.summary.serialNo.ifBlank { sheet.serialNo },
                                status = detail.summary.status
                                    .takeIf { it != CallSheetStatus.Unknown } ?: CallSheetStatus.Draft,
                                name = detail.summary.name,
                                document = document,
                                dayTypes = metadata.dayTypes,
                            ),
                        )
                    }
                }
                is ZillitResult.Failure -> ctx.toast(
                    "Failed to load call sheet: ${result.error.localised()}",
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

    /** Back: unsaved changes ask first. */
    private fun back() {
        val editor = ctx.state.editor ?: return
        if (editor.saving) return
        if (editor.dirty) {
            ctx.update {
                copy(
                    dialog = SheetDialog.Confirm(
                        action = ConfirmAction.LeaveEditor,
                        title = "Unsaved Changes",
                        message = "You have unsaved changes that will be lost if you leave. " +
                            "Would you like to save before leaving?",
                        confirmLabel = "Leave Without Saving",
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
        ctx.lists.refreshCurrent()
    }

    /** Save & Leave: a saved sheet saves its revision and leaves; a new one asks its name first. */
    fun saveAndLeave() = validate(SaveIntent.SaveThenLeave)

    /**
     * `validateBeforeSave`: nameless default sections block the save; saving
     * a sheet already out for review moves it back to Draft, so ask first.
     */
    private fun validate(intent: SaveIntent) {
        val editor = ctx.state.editor ?: return
        if (blockedByMissingTitles(editor)) return
        // Save As writes a new draft and leaves the sheet under review alone — no prompt (B-11).
        val touchesSheet = intent != SaveIntent.SaveAsNew
        if (touchesSheet && editor.sheetId != null && editor.status?.reviewInFlight == true) {
            ctx.update {
                copy(
                    dialog = SheetDialog.Confirm(
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

    /** Template saves only need the section names — they never touch the sheet under review. */
    fun guardTemplateSave(action: () -> Unit) {
        val editor = ctx.state.editor ?: return
        if (!blockedByMissingTitles(editor)) action()
    }

    private fun blockedByMissingTitles(editor: EditorState): Boolean {
        val missing = editor.document.missingDefaultTitles()
        if (missing.isEmpty()) return false
        ctx.update { copy(dialog = SheetDialog.MissingTitles(missing)) }
        return true
    }

    fun runSave(intent: SaveIntent) {
        val editor = ctx.state.editor ?: return
        when (intent) {
            SaveIntent.Save -> if (editor.isNew) askName() else save(onSaved = {})
            SaveIntent.SaveAsNew -> askName()
            SaveIntent.SaveThenLeave -> if (editor.isNew) {
                askName()
            } else {
                save(onSaved = { discardAndLeave() })
            }
        }
    }

    private fun askName() = ctx.update { copy(dialog = SheetDialog.DraftName("")) }

    /** The editor's sends save first, without the prompts — as the web calls `saveDraft` directly. */
    fun saveThen(action: (String) -> Unit) = save(onSaved = action)

    /** ZL-20654: a draft never saves nameless. Save As on a saved sheet creates a new one. */
    private fun confirmDraftName() {
        val dialog = ctx.state.dialog as? SheetDialog.DraftName ?: return
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
        if (editor.isNew) save(onSaved = {}) else saveAsNew()
    }

    /**
     * `saveDraft`: the metadata counters first (a failure never blocks the
     * sheet), then a revision — which stays in the editor — or the create,
     * which closes onto Drafts.
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
            val sheetId = editor.sheetId
            if (sheetId != null) {
                when (
                    val revision = ctx.repository.saveRevision(
                        sheetId,
                        name,
                        editor.document,
                        viewer.displayName,
                        viewer.userId,
                    )
                ) {
                    is ZillitResult.Success -> {
                        ctx.update {
                            val current = this.editor?.takeIf { it.session == editor.session } ?: return@update this
                            copy(
                                editor = current.copy(
                                    saving = false,
                                    baseline = editor.document,
                                    status = CallSheetStatus.Draft,
                                    draftName = "",
                                ),
                            )
                        }
                        ctx.toast("Revision saved!")
                        onSaved(sheetId)
                    }
                    is ZillitResult.Failure -> failed(revision.error.localised())
                }
            } else {
                val created = ctx.repository.create(project, name, editor.document, viewer.displayName, viewer.userId)
                when (created) {
                    is ZillitResult.Success -> {
                        ctx.toast("Draft created!")
                        closeOntoDrafts()
                        onSaved(created.data.id)
                    }
                    is ZillitResult.Failure -> failed(created.error.localised())
                }
            }
        }
    }

    private fun failed(message: String) {
        edit { copy(saving = false) }
        ctx.toast("Save failed: $message", isError = true)
    }

    /**
     * Save As on a saved sheet: a brand-new draft the editor now holds, clean
     * and in Draft; no counters written.
     */
    private fun saveAsNew() {
        val editor = ctx.state.editor ?: return
        val project = ctx.projectId() ?: return
        if (editor.saving || !ctx.state.isPoster) return
        edit { copy(saving = true) }
        ctx.launchWork {
            val viewer = ctx.viewer()
            when (
                val created = ctx.repository.create(
                    project,
                    editor.draftName,
                    editor.document,
                    viewer.displayName,
                    viewer.userId,
                )
            ) {
                is ZillitResult.Success -> {
                    ctx.update {
                        val current = this.editor?.takeIf { it.session == editor.session } ?: return@update this
                        copy(
                            editor = current.copy(
                                saving = false,
                                sheetId = created.data.id,
                                serialNo = created.data.serialNo,
                                status = CallSheetStatus.Draft,
                                name = editor.draftName,
                                draftName = "",
                                baseline = editor.document,
                                template = null,
                            ),
                        )
                    }
                    ctx.toast("Saved as new draft!")
                }
                is ZillitResult.Failure -> failed(created.error.localised())
            }
        }
    }

    /**
     * Total days as typed; the shoot-day counter only when a NEW sheet still
     * shows the number it was handed; the approvers, which become the project
     * default. ZL-21468: an EMPTIED list is written too — the PUT merges, so
     * omitting the key left the removed approver in the default and reopening
     * seeded them straight back — but only when the editor opened with
     * approvers (`shouldWriteApproverMeta`).
     */
    private suspend fun writeCounters(project: String, editor: EditorState) {
        val shared = editor.document.shared
        val update = MetadataUpdate(
            totalDays = shared.totalDays.ifBlank { null },
            currentShootDay = editor.currentShootDay
                .takeIf { it > 0 && shared.shootDayNumber.trim().toIntOrNull() == it },
            finalApproverIds = shared.approverIds
                .takeIf { shouldWriteApproverMeta(it, editor.initialApproverIds) },
        )
        if (update.isEmpty) return
        if (ctx.repository.saveMetadata(project, update) is ZillitResult.Success) {
            ctx.update {
                copy(
                    metadata = metadata.copy(
                        finalApproverIds = update.finalApproverIds ?: metadata.finalApproverIds,
                        currentShootDay = update.currentShootDay ?: metadata.currentShootDay,
                        totalDays = update.totalDays ?: metadata.totalDays,
                    ),
                )
            }
        }
    }

    /** `goToDraftsTab`: the editor closes and Drafts reloads. */
    fun closeOntoDrafts() {
        ctx.update { copy(editor = null, tab = SheetTab.Drafts) }
        ctx.lists.refreshCurrent()
    }

    private fun undo(quick: Boolean) {
        val editor = ctx.state.editor ?: return
        val record = (if (quick) editor.quickUndo else editor.undo) ?: return
        val doc = editor.document
        val restored = when (val action = record.action) {
            is UndoAction.Row -> doc.restoreRow(action.row, action.index, action.headerBefore, action.approversBefore)
            is UndoAction.Cell -> doc.restoreCell(action.cell, action.rowIndex, action.cellIndex)
            is UndoAction.Line -> doc.updateCell(action.rowIndex, action.cellIndex) {
                it.withLineRestored(action.index, action.line)
            }
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
                undo = if (quick) undo else null,
                quickUndo = if (quick) null else quickUndo,
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
