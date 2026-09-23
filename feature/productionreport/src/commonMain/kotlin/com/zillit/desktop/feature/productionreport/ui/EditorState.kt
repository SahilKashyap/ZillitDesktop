package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.SendActions
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.sendActions

/**
 * The report editor: the document, the selection shared by preview and
 * pane, and the chrome around them.
 */
data class EditorState(
    /** Distinguishes one opening from the next, so a late call-sheet merge lands only where it started. */
    val session: Long = 0,
    /** Null for a new document not yet saved. */
    val reportId: String? = null,
    val status: ReportStatus? = null,
    val name: String = "",
    /** The name typed into "Save As", held while the review-restart prompt is up. */
    val draftName: String = "",
    val document: SheetPayload,
    /** The last saved or opened document — the unsaved-changes baseline. */
    val baseline: SheetPayload = document,
    /**
     * The shoot day metadata handed out for a NEW report (0 = none); saving advances the
     * counter only if still shown.
     */
    val currentShootDay: Int = 0,
    /** The approvers as opened — the approvers panel's dirty check. */
    val initialApproverIds: List<String> = document.shared.approverIds,
    /** Set when the editor holds a saved project template — enables Update Template. */
    val template: TemplateRef? = null,
    val selection: EditorSelection? = null,
    val sidebarVisible: Boolean = false,
    /** Keeps the pane open with nothing selected, so an undo toast survives a removal. */
    val paneOpen: Boolean = false,
    /** The inner line / column the pane's focus is in, for the preview highlight. */
    val focusedLine: Int? = null,
    val focusedColumn: Int? = null,
    val zoom: Int = DEFAULT_ZOOM,
    /** The preview's share of the split, 25–80. */
    val splitPercent: Float = DEFAULT_SPLIT,
    val focusMode: Boolean = false,
    /** The last published call sheet is being merged in; the preview is dimmed. */
    val populating: Boolean = false,
    val saving: Boolean = false,
    val savingTemplate: Boolean = false,
    val savingApprovers: Boolean = false,
    val dayTypes: List<String> = emptyList(),
    val saveMenuOpen: Boolean = false,
    val sectionSearch: String = "",
    val undo: UndoRecord? = null,
    /** System sections removed this session — "Restore Fields". */
    val removedDefaults: List<RemovedDefault> = emptyList(),
    val weather: WeatherPanel? = null,
) {
    val dirty: Boolean get() = document != baseline

    val approversDirty: Boolean
        get() = document.shared.approverIds.sorted() != initialApproverIds.sorted()

    /** The pane shows whenever something is selected, the list is open, or it was kept open. */
    val showsPane: Boolean get() = selection != null || sidebarVisible || paneOpen

    val isNew: Boolean get() = reportId == null

    /**
     * The header's sends — `sheetSendActions` on the held status, the same
     * rule as the Drafts row menu: every unlocked report may go for
     * signature, a draft may go for comments. (The old single "Send for
     * Approval" showed only on NON-draft reports: the inverse.)
     */
    val sendActions: SendActions get() = sendActions(status ?: ReportStatus.Draft)

    fun selectedCell(): PageCell? = (selection as? EditorSelection.Cell)?.let { cell ->
        document.rows.getOrNull(cell.row)?.cells?.getOrNull(cell.cell)
    }

    companion object {
        const val DEFAULT_ZOOM = 100
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 150
        const val ZOOM_STEP = 10
        const val DEFAULT_SPLIT = 60f
        const val MIN_SPLIT = 25f
        const val MAX_SPLIT = 80f
    }
}

data class TemplateRef(val id: String, val name: String)

/**
 * The weather editor's live fetch: the raw forecast stays in memory only
 * (the web keeps it in component state), so the day strip can re-pick.
 */
data class WeatherPanel(
    val row: Int,
    val cell: Int,
    val location: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val response: kotlinx.serialization.json.JsonObject? = null,
    val fetching: Boolean = false,
    val error: String? = null,
)

/** A system section removed through the pane, for "Restore Fields". */
data class RemovedDefault(
    val cell: PageCell,
    val rowIndex: Int,
    val cellIndex: Int,
    /** The whole row as it was when the removal took the row too; empty when the row stayed. */
    val rowCells: List<PageCell> = emptyList(),
)

/**
 * The 3-second undo after a removal. Removals apply at once; undo restores
 * the removed piece into the document as it is NOW.
 */
data class UndoRecord(
    val label: String,
    val action: UndoAction,
    /** Bumped per record so the countdown restarts even for identical labels. */
    val serial: Long,
)

sealed interface UndoAction {
    data class Row(val row: PageRow, val index: Int, val headerBefore: Int?, val approversBefore: Int?) : UndoAction
    data class Cell(val cell: PageCell, val rowIndex: Int, val cellIndex: Int) : UndoAction
    data class Line(val rowIndex: Int, val cellIndex: Int, val line: CellRow, val index: Int) : UndoAction
    data class Column(
        val rowIndex: Int,
        val cellIndex: Int,
        val column: ColumnSpec,
        val values: List<CellValue>,
        val index: Int,
    ) : UndoAction
}
