package com.zillit.desktop.feature.callsheet.ui.editor

import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.approversSlot
import com.zillit.desktop.feature.callsheet.domain.headerSlot

/** One thing the preview draws, in order. */
internal sealed interface PreviewBlock {
    data class Insert(
        val key: String,
        val afterIndex: Int,
        val lockApprovers: Boolean = false,
        val aboveHeader: Boolean = false,
    ) : PreviewBlock
    data object Title : PreviewBlock
    data object Approvers : PreviewBlock
    data class Row(val index: Int) : PreviewBlock
}

/**
 * The web's render order (`PageRowsPreview.jsx:2064-2362`): the title bar and
 * the approvers block at their slots, an insert strip after each, and one
 * after every row — except between two multi-department crew rows.
 */
internal fun previewBlocks(document: SheetPayload): List<PreviewBlock> {
    val rows = document.rows
    val header = document.headerSlot()
    val approvers = document.approversSlot()
    val blocks = mutableListOf<PreviewBlock>()
    var headerDone = false
    fun maybeHeader(before: Int) {
        if (headerDone || before < header) return
        headerDone = true
        if (header == 0) blocks += PreviewBlock.Insert("insert-above-header", afterIndex = -1, aboveHeader = true)
        blocks += PreviewBlock.Title
        if (rows.isNotEmpty()) blocks += PreviewBlock.Insert("insert-below-header", afterIndex = header - 1)
    }
    if (header > 0 && rows.isNotEmpty()) blocks += PreviewBlock.Insert(
        "insert-top",
        afterIndex = -1,
        aboveHeader = true,
    )
    rows.forEachIndexed { index, row ->
        maybeHeader(index)
        if (index == approvers) {
            blocks += PreviewBlock.Approvers
            blocks += PreviewBlock.Insert("insert-below-approvers-$index", afterIndex = index - 1, lockApprovers = true)
        }
        blocks += PreviewBlock.Row(index)
        val next = rows.getOrNull(index + 1)
        if (!(row.isMultiCrew() && next?.isMultiCrew() == true)) {
            blocks += PreviewBlock.Insert("after-$index", afterIndex = index, aboveHeader = index < header)
        }
    }
    maybeHeader(rows.size)
    if (approvers >= rows.size) {
        blocks += PreviewBlock.Approvers
        blocks += PreviewBlock.Insert("insert-below-approvers-end", afterIndex = rows.size - 1, lockApprovers = true)
    }
    return blocks
}

internal fun PageRow.isMultiCrew(): Boolean = cells.size > 1 && cells.first().renderAs == RenderKind.Employee

internal fun PageRow.isTopSections(): Boolean = cells.size > 1 && cells.all { it.kind == CellKind.Section }
