package com.zillit.desktop.feature.productionreport.ui.editor

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.SheetMember

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
) {
    /** "Row 2 → Name" — positional on purpose, so an id in a users column never leaks into the badge. */
    fun detail(cell: PageCell): String? {
        val line = focusedLine?.takeIf { selected } ?: return null
        val column = focusedColumn ?: return str(S.desktop_row_n, line + 1)
        val label = cell.columns.getOrNull(column)?.label?.trim().orEmpty()
        return str(S.desktop_row_col_path, line + 1, label.ifEmpty { str(S.desktop_col_n, column + 1) })
    }
}
