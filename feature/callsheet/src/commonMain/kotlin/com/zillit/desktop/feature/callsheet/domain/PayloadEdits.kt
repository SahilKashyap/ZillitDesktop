@file:Suppress("TooManyFunctions") // One pure function per structural edit the editor and preview offer.

package com.zillit.desktop.feature.callsheet.domain

/**
 * Every structural edit of a call sheet document, as pure functions.
 *
 * Transcribed from the web's preview insert tooling (`PageRowsPreview.jsx`
 * `addPageRowAfter` / `addCellToRowAfter` / `newCellDefaults`) and the editor
 * pane (`PageRowsEditor.jsx`), with the web's latent bugs fixed rather than
 * copied: orders are renumbered on every change, positions are clamped, a
 * short legacy line is padded instead of silently refusing an edit, and a
 * removal resolves against the document as it is now — never a snapshot taken
 * seconds earlier.
 */

/** What the editor pane is showing. */
sealed interface EditorSelection {
    /** The sheet header ("Call Sheet Info"). */
    data object Shared : EditorSelection

    /** The virtual approvers block. */
    data object Approvers : EditorSelection

    data class Cell(val row: Int, val cell: Int) : EditorSelection
}

/** What the "+" menus insert. */
enum class InsertKind(val label: String) { Notes("Notes"), Section("Section"), Table("Table"), PageBreak("Page Break") }

/** One entry of the Sections list: the two virtual blocks interleave the real rows. */
sealed interface SectionBlock {
    val key: String

    data object Header : SectionBlock {
        override val key: String = "header"
    }

    data object Approvers : SectionBlock {
        override val key: String = "approvers"
    }

    data class Row(val index: Int) : SectionBlock {
        override val key: String = "row-$index"
    }
}

/** The title bar's slot: "before page row N", clamped into the document. */
fun SheetPayload.headerSlot(): Int = (shared.headerPosition ?: 0).coerceIn(0, rows.size)

/** The approvers block's slot: "before page row N", absent meaning the end. */
fun SheetPayload.approversSlot(): Int = (shared.approversPosition ?: rows.size).coerceIn(0, rows.size)

/**
 * The Sections list: header and approvers at their slots, header first when
 * they share one. The web's `combinedItems`.
 */
fun SheetPayload.sectionBlocks(): List<SectionBlock> {
    val header = headerSlot()
    val approvers = approversSlot()
    val blocks = mutableListOf<SectionBlock>()
    for (index in 0..rows.size) {
        if (index == header) blocks += SectionBlock.Header
        if (index == approvers) blocks += SectionBlock.Approvers
        if (index < rows.size) blocks += SectionBlock.Row(index)
    }
    return blocks
}

/** `newCellDefaults` — what a fresh section, table or notes box looks like. */
fun newCell(kind: InsertKind): PageCell = when (kind) {
    InsertKind.Section -> PageCell(
        order = 0,
        kind = CellKind.Section,
        title = "New Section",
        hideTitle = true,
        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
        rows = blankLines(count = 3, columns = 2),
    )
    InsertKind.Table -> PageCell(
        order = 0,
        kind = CellKind.Table,
        title = "New Table",
        columns = List(TABLE_COLUMNS) { ColumnSpec(label = "Col ${it + 1}") },
        rows = blankLines(count = 2, columns = TABLE_COLUMNS),
    )
    InsertKind.Notes, InsertKind.PageBreak -> PageCell(
        order = 0,
        kind = CellKind.Notes,
        title = "New Notes",
        columns = listOf(ColumnSpec(label = "Title")),
        rows = blankLines(count = 1, columns = 1),
    )
}

private const val TABLE_COLUMNS = 6

private fun blankLines(count: Int, columns: Int): List<CellRow> =
    List(count) { index -> CellRow(order = index, values = List(columns) { CellValue() }) }

/** Every page row and every cell renumbered to its index. */
fun SheetPayload.renumbered(): SheetPayload = copy(
    rows = rows.mapIndexed { r, row ->
        row.copy(order = r, cells = row.cells.mapIndexed { c, cell -> cell.copy(order = c) })
    },
)

/**
 * `addPageRowAfter`: a new row (or page break) at `afterIndex + 1`, the title
 * bar and approvers blocks shifted so they stay where the user sees them.
 * [lockApprovers] pins the approvers block when inserting right under it;
 * [aboveHeader] puts the row above the title bar. Returns the new selection.
 */
fun SheetPayload.insertRow(
    kind: InsertKind,
    afterIndex: Int,
    lockApprovers: Boolean = false,
    aboveHeader: Boolean = false,
): Pair<SheetPayload, EditorSelection?> {
    val insertAt = (afterIndex + 1).coerceIn(0, rows.size)
    val header = headerSlot()
    val approvers = approversSlot()
    val row = if (kind == InsertKind.PageBreak) {
        PageRow(order = 0, isBreak = true)
    } else {
        PageRow(order = 0, cells = listOf(newCell(kind)))
    }
    val nextRows = rows.toMutableList().apply { add(insertAt, row) }
    val nextShared = shared.copy(
        headerPosition = if (aboveHeader || insertAt < header) header + 1 else header,
        approversPosition = when {
            lockApprovers -> approvers
            insertAt <= approvers -> approvers + 1
            else -> approvers
        },
    )
    val next = copy(shared = nextShared, rows = nextRows).renumbered()
    val selection = if (kind == InsertKind.PageBreak) null else EditorSelection.Cell(insertAt, 0)
    return next to selection
}

/** `addCellToRowAfter`: a side-by-side cell; page breaks cannot be placed inside a row. */
fun SheetPayload.insertCell(rowIndex: Int, afterCell: Int, kind: InsertKind): Pair<SheetPayload, EditorSelection?> {
    if (kind == InsertKind.PageBreak || rowIndex !in rows.indices) return this to null
    val row = rows[rowIndex]
    val at = (afterCell + 1).coerceIn(0, row.cells.size)
    val cells = row.cells.toMutableList().apply { add(at, newCell(kind)) }
    val next = copy(rows = rows.replaced(rowIndex, row.copy(cells = cells))).renumbered()
    return next to EditorSelection.Cell(rowIndex, at)
}

/** A whole page row removed, with the title bar and approvers blocks kept in place. */
fun SheetPayload.removeRow(rowIndex: Int): SheetPayload {
    if (rowIndex !in rows.indices) return this
    val header = headerSlot()
    val approvers = approversSlot()
    return copy(
        shared = shared.copy(
            headerPosition = if (rowIndex < header) header - 1 else shared.headerPosition,
            approversPosition = if (rowIndex < approvers) approvers - 1 else shared.approversPosition,
        ),
        rows = rows.filterIndexed { index, _ -> index != rowIndex },
    ).renumbered()
}

/** One cell removed; a row left with no cells goes too. */
fun SheetPayload.removeCell(rowIndex: Int, cellIndex: Int): SheetPayload {
    val row = rows.getOrNull(rowIndex) ?: return this
    if (cellIndex !in row.cells.indices) return this
    if (row.cells.size <= 1) return removeRow(rowIndex)
    val cells = row.cells.filterIndexed { index, _ -> index != cellIndex }
    return copy(rows = rows.replaced(rowIndex, row.copy(cells = cells))).renumbered()
}

/**
 * Undoes [removeRow]: the row back at its index and the two blocks back where
 * they were — the positions are remembered, not recomputed, because "before
 * row N" cannot tell a block that sat above the removed row from one below it.
 */
fun SheetPayload.restoreRow(row: PageRow, rowIndex: Int, headerBefore: Int?, approversBefore: Int?): SheetPayload {
    val nextRows = rows.toMutableList().apply { add(rowIndex.coerceIn(0, size), row) }
    return copy(
        shared = shared.copy(
            headerPosition = headerBefore?.coerceIn(0, nextRows.size),
            approversPosition = approversBefore?.coerceIn(0, nextRows.size),
        ),
        rows = nextRows,
    ).renumbered()
}

/**
 * Restores a removed cell whose row stayed — undo, or "Restore Fields" for a
 * system section: back into that row at its old place; as a row of its own
 * when no row is left at that index.
 */
fun SheetPayload.restoreCell(cell: PageCell, rowIndex: Int, cellIndex: Int): SheetPayload {
    val row = rows.getOrNull(rowIndex)
    if (row != null && !row.isPageBreak) {
        val cells = row.cells.toMutableList().apply { add(cellIndex.coerceIn(0, size), cell) }
        return copy(rows = rows.replaced(rowIndex, row.copy(cells = cells))).renumbered()
    }
    return withOwnRow(cell, rowIndex)
}

/**
 * "Restore Fields" for a section whose row went with it ([rowCells] is that
 * row as it was): beside the sections of that row already restored at its
 * index, else as a row of its own there. The web's `restoreDefault` inserted
 * it into whichever row had taken the index.
 */
fun SheetPayload.restoreCellFromRemovedRow(cell: PageCell, rowIndex: Int, rowCells: List<PageCell>): SheetPayload {
    val original = rowCells.map { it.copy(order = 0) }
    val position = { candidate: PageCell -> original.indexOf(candidate.copy(order = 0)) }
    val row = rows.getOrNull(rowIndex)
    val rejoins = row != null && !row.isPageBreak && row.cells.isNotEmpty() && row.cells.all { position(it) >= 0 }
    if (!rejoins) return withOwnRow(cell, rowIndex)
    val mine = position(cell)
    return restoreCell(cell, rowIndex, row.cells.count { position(it) < mine })
}

/** [cell] as a one-cell row at [rowIndex], with the blocks below it shifted down. */
private fun SheetPayload.withOwnRow(cell: PageCell, rowIndex: Int): SheetPayload {
    val at = rowIndex.coerceIn(0, rows.size)
    val header = headerSlot()
    val approvers = approversSlot()
    return copy(
        shared = shared.copy(
            headerPosition = if (at < header) header + 1 else shared.headerPosition,
            approversPosition = if (at < approvers) approvers + 1 else shared.approversPosition,
        ),
        rows = rows.toMutableList().apply { add(at, PageRow(order = 0, cells = listOf(cell))) },
    ).renumbered()
}

/**
 * Drag in the Sections list: move [fromKey] to where [toKey] sits. Each
 * virtual block's position becomes the number of real rows above it.
 */
fun SheetPayload.moveBlock(fromKey: String, toKey: String): SheetPayload {
    if (fromKey == toKey) return this
    val blocks = sectionBlocks().toMutableList()
    val from = blocks.indexOfFirst { it.key == fromKey }
    val to = blocks.indexOfFirst { it.key == toKey }
    if (from < 0 || to < 0) return this
    blocks.add(to, blocks.removeAt(from))
    val nextRows = mutableListOf<PageRow>()
    var header = 0
    var approvers = 0
    blocks.forEach { block ->
        when (block) {
            SectionBlock.Header -> header = nextRows.size
            SectionBlock.Approvers -> approvers = nextRows.size
            is SectionBlock.Row -> nextRows += rows[block.index]
        }
    }
    return copy(
        shared = shared.copy(headerPosition = header, approversPosition = approvers),
        rows = nextRows,
    ).renumbered()
}

/** Replaces one cell through [change]; out-of-range indexes change nothing. */
fun SheetPayload.updateCell(rowIndex: Int, cellIndex: Int, change: (PageCell) -> PageCell): SheetPayload {
    val row = rows.getOrNull(rowIndex) ?: return this
    val cell = row.cells.getOrNull(cellIndex) ?: return this
    return copy(rows = rows.replaced(rowIndex, row.copy(cells = row.cells.replaced(cellIndex, change(cell)))))
}

/**
 * The Type select (`section` / `table` / `notes`), per the web: switching TO
 * notes resets the cell to one blank note under a single untitled column;
 * any other switch changes the type alone and keeps every column and value.
 * Weather and crew cells never change type — the pane offers them no Type select.
 */
fun PageCell.convertedTo(kind: CellKind): PageCell {
    val fixed = renderAs == RenderKind.Weather || renderAs == RenderKind.Employee
    if (kind == this.kind || fixed || kind == CellKind.Unknown) return this
    return if (kind == CellKind.Notes) {
        copy(
            kind = kind,
            rawKind = kind.wire,
            title = title.ifBlank { "Notes" },
            columns = listOf(ColumnSpec()),
            rows = listOf(CellRow(order = 0, values = listOf(CellValue()))),
        )
    } else {
        copy(kind = kind, rawKind = kind.wire)
    }
}

/** A blank text column appended, every line padded to match. */
fun PageCell.withColumnAdded(): PageCell = copy(
    columns = columns + ColumnSpec(),
    rows = rows.map { line -> line.copy(values = line.values.padded(columns.size) + CellValue()) },
)

/** One column and its values removed; the last column always stays. */
fun PageCell.withColumnRemoved(index: Int): PageCell {
    if (columns.size <= 1 || index !in columns.indices) return this
    return copy(
        columns = columns.filterIndexed { i, _ -> i != index },
        rows = rows.map { line -> line.copy(values = line.values.filterIndexed { i, _ -> i != index }) },
    )
}

/** Undoes [withColumnRemoved] with the values each line had. */
fun PageCell.withColumnRestored(index: Int, column: ColumnSpec, values: List<CellValue>): PageCell {
    val at = index.coerceIn(0, columns.size)
    return copy(
        columns = columns.toMutableList().apply { add(at, column) },
        rows = rows.mapIndexed { i, line ->
            val padded = line.values.padded(columns.size).toMutableList()
            padded.add(at.coerceAtMost(padded.size), values.getOrNull(i) ?: CellValue())
            line.copy(values = padded)
        },
    )
}

fun PageCell.withColumn(index: Int, change: (ColumnSpec) -> ColumnSpec): PageCell =
    if (index !in columns.indices) this else copy(columns = columns.replaced(index, change(columns[index])))

/**
 * `updateHeader(type)`: a new column type, with `time` / `date` values folded
 * to readable text when the column leaves those types.
 */
fun PageCell.withColumnType(index: Int, type: String): PageCell {
    val column = columns.getOrNull(index) ?: return this
    if (column.type == type) return this
    return copy(
        columns = columns.replaced(index, column.copy(type = type)),
        rows = rows.map { line ->
            val atom = line.values.getOrNull(index) ?: return@map line
            val converted = SheetTime.convertOnTypeChange(atom.value, column.type, type)
            if (converted == atom.value) {
                line
            } else {
                line.copy(values = line.values.replaced(index, atom.copy(value = converted)))
            }
        },
    )
}

/**
 * A column-edge drag: [deltaUnits] moves width from the right neighbour to the
 * left column (min 0.25 each, two decimals). The last column's edge grows it alone.
 */
fun PageCell.withColumnResized(index: Int, baseline: List<ColumnSpec>, deltaUnits: Double): PageCell {
    if (index !in baseline.indices) return this
    val left = ((baseline[index].width ?: 1.0) + deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH).roundTo2()
    val next = baseline.toMutableList()
    next[index] = next[index].copy(width = left)
    if (index + 1 < baseline.size) {
        val right = ((baseline[index + 1].width ?: 1.0) - deltaUnits).coerceAtLeast(PageCell.MIN_WIDTH).roundTo2()
        next[index + 1] = next[index + 1].copy(width = right)
    }
    return copy(columns = next)
}

private fun Double.roundTo2(): Double = kotlin.math.round(this * HUNDREDTHS) / HUNDREDTHS

private const val HUNDREDTHS = 100.0

/** A blank line appended with one value per column. */
fun PageCell.withLineAdded(): PageCell =
    copy(rows = rows + CellRow(order = rows.size, values = List(maxOf(columns.size, 1)) { CellValue() }))

/** One line removed; lines are renumbered. */
fun PageCell.withLineRemoved(index: Int): PageCell {
    if (index !in rows.indices) return this
    return copy(rows = rows.filterIndexed { i, _ -> i != index }.mapIndexed { i, line -> line.copy(order = i) })
}

/** Undoes [withLineRemoved]. */
fun PageCell.withLineRestored(index: Int, line: CellRow): PageCell {
    val next = rows.toMutableList().apply { add(index.coerceIn(0, size), line) }
    return copy(rows = next.mapIndexed { i, row -> row.copy(order = i) })
}

/** One atom replaced — a short legacy line is padded instead of silently ignored. */
fun PageCell.withAtom(line: Int, column: Int, change: (CellValue) -> CellValue): PageCell {
    if (line !in rows.indices || column < 0) return this
    val row = rows[line]
    val values = row.values.padded(column + 1)
    return copy(rows = rows.replaced(line, row.copy(values = values.replaced(column, change(values[column])))))
}

/** "Apply All": one value into every line of a column. */
fun PageCell.withColumnValue(column: Int, value: String): PageCell = copy(
    rows = rows.map { line ->
        val values = line.values.padded(column + 1)
        line.copy(values = values.replaced(column, values[column].copy(value = value)))
    },
)

/** A dragged row height, never under 28 px. */
fun PageCell.withLineHeight(line: Int, height: Int): PageCell {
    if (line !in rows.indices) return this
    return copy(rows = rows.replaced(line, rows[line].copy(height = height.coerceAtLeast(MIN_LINE_HEIGHT))))
}

private const val MIN_LINE_HEIGHT = 28

/**
 * A new shoot date wipes every weather value — the forecast belonged to the
 * old day. Silent, as on the web.
 */
fun SheetPayload.withDate(dateMs: Long): SheetPayload {
    if (dateMs == shared.dateMs) return this
    val cleared = rows.map { row ->
        row.copy(
            cells = row.cells.map { cell ->
                val first = cell.rows.firstOrNull()?.values?.firstOrNull()
                if (cell.renderAs == RenderKind.Weather && first != null && first.value.isNotEmpty()) {
                    cell.withAtom(0, 0) { it.copy(value = "") }
                } else {
                    cell
                }
            },
        )
    }
    return copy(shared = shared.copy(dateMs = dateMs), rows = cleared)
}

/** The weather value, creating the first line and atom when missing. */
fun PageCell.withWeatherValue(value: String): PageCell {
    val base = if (rows.isEmpty()) copy(rows = listOf(CellRow(0, listOf(CellValue())))) else this
    return base.withAtom(0, 0) { it.copy(value = value) }
}

/** Approver toggled in the sheet's own list — appended, or removed. */
fun SheetPayload.toggledApprover(userId: String): SheetPayload {
    val ids = shared.approverIds
    val next = if (userId in ids) ids - userId else ids + userId
    return copy(shared = shared.copy(approverIds = next, approverIdsStated = true))
}

private fun List<CellValue>.padded(size: Int): List<CellValue> =
    if (this.size >= size) this else this + List(size - this.size) { CellValue() }

internal fun <T> List<T>.replaced(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }
