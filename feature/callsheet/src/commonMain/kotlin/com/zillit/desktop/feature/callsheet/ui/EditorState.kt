package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CellRow
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.sendActions
import kotlinx.serialization.json.JsonObject

/**
 * The call sheet editor: the document, the selection shared by preview and
 * pane, and the chrome around them.
 */
data class EditorState(
    /** Distinguishes one opening from the next, so a late answer lands only where it started. */
    val session: Long = 0,
    /** Null for a new document not yet saved. */
    val sheetId: String? = null,
    val serialNo: String = "",
    val status: CallSheetStatus? = null,
    val name: String = "",
    /** The name typed into "Save As", held while the review-restart prompt is up. */
    val draftName: String = "",
    val document: SheetPayload,
    /** The last saved or opened document — the unsaved-changes baseline. */
    val baseline: SheetPayload = document,
    /**
     * The shoot day metadata handed out for a NEW sheet (0 = none); saving
     * advances the project counter only if the sheet still shows it.
     */
    val currentShootDay: Int = 0,
    /** The approvers as opened or last saved for the project — the approvers panel's dirty check. */
    val initialApproverIds: List<String> = document.shared.approverIds,
    /** Set when the editor holds a saved project template — enables Update Template. */
    val template: TemplateRef? = null,
    val selection: EditorSelection? = null,
    val sidebarVisible: Boolean = false,
    /** Keeps the pane open with nothing selected ("No section selected"). */
    val paneOpen: Boolean = false,
    /** The inner line / column the pane's focus is in, for the preview highlight. */
    val focusedLine: Int? = null,
    val focusedColumn: Int? = null,
    /** The preview's share of the split, 25–80. */
    val splitPercent: Float = DEFAULT_SPLIT,
    val saving: Boolean = false,
    val savingTemplate: Boolean = false,
    val savingApprovers: Boolean = false,
    val dayTypes: List<String> = emptyList(),
    val saveMenuOpen: Boolean = false,
    val sectionSearch: String = "",
    /** The Sections list's "Removing …" bar with its 3-second undo. */
    val undo: UndoRecord? = null,
    /** The cell editor's "Row removed" / "Column removed" toast. */
    val quickUndo: UndoRecord? = null,
    /** System sections removed this session — "Restore Fields". */
    val removedDefaults: List<RemovedDefault> = emptyList(),
    val weather: WeatherPanel? = null,
) {
    val dirty: Boolean get() = document != baseline

    val approversDirty: Boolean
        get() = document.shared.approverIds.sorted() != initialApproverIds.sorted()

    /** The pane shows whenever something is selected, the list is open, or it was kept open. */
    val showsPane: Boolean get() = selection != null || sidebarVisible || paneOpen

    val isNew: Boolean get() = sheetId == null

    /**
     * ZL-21539: "Save as Template" only while CREATING a document (a saved
     * template opened for editing included) — never on an existing sheet,
     * where it would force-close the editor over unsaved edits.
     */
    val offersSaveAsTemplate: Boolean get() = isNew

    /** The header's Send for Signature: a saved, unlocked sheet. */
    val offersSignature: Boolean
        get() = sheetId != null && sendActions(status ?: CallSheetStatus.Draft).sendForSignature

    /** The header's Send for Comments: a saved sheet still a draft. */
    val offersComments: Boolean get() = sheetId != null && sendActions(status ?: CallSheetStatus.Draft).sendForComments

    fun selectedCell(): PageCell? = (selection as? EditorSelection.Cell)?.let { cell ->
        document.rows.getOrNull(cell.row)?.cells?.getOrNull(cell.cell)
    }

    companion object {
        const val DEFAULT_SPLIT = 60f
        const val MIN_SPLIT = 25f
        const val MAX_SPLIT = 80f
        const val UNDO_MILLIS = 3_000L
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
    val response: JsonObject? = null,
    /** The forecast strip's highlighted day; null when current conditions were written. */
    val selectedDay: Int? = null,
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
 * A removal's undo. Removals apply at once — against the document as it is
 * now, never a snapshot taken seconds earlier — and undo restores the removed
 * piece into the document as it is NOW.
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
