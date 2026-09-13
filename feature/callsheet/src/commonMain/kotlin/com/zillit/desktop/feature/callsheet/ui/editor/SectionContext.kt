package com.zillit.desktop.feature.callsheet.ui.editor

import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.SheetMember

/** A section's bar shows when it has a title the web does not hide (system sections always show theirs). */
internal val PageCell.showsTitle: Boolean get() = title.isNotBlank() && !effectiveHideTitle

/** What every section renderer needs besides its cell. */
internal class SectionContext(
    val selected: Boolean,
    val onSelect: () -> Unit,
    val members: List<SheetMember>,
    /** The pane's focused line / column, only for the selected cell. */
    val focusedLine: Int?,
    val focusedColumn: Int?,
    /** Stable per cell, so a crew table stays collapsed while it is edited. */
    val key: String,
    /** False in the read-only preview beside the comments: no badges, no selection. */
    val interactive: Boolean = true,
) {
    /** "Row 2 → Name" — positional on purpose, so an id in a users column never leaks into the badge. */
    fun detail(cell: PageCell): String? {
        val line = focusedLine?.takeIf { selected } ?: return null
        val column = focusedColumn ?: return "Row ${line + 1}"
        val label = cell.columns.getOrNull(column)?.label?.trim().orEmpty()
        return "Row ${line + 1} → ${label.ifEmpty { "Col ${column + 1}" }}"
    }
}
