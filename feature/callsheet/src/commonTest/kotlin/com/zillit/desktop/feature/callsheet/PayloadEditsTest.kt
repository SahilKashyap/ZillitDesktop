package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.CellRow
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.InsertKind
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SectionBlock
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.approversSlot
import com.zillit.desktop.feature.callsheet.domain.convertedTo
import com.zillit.desktop.feature.callsheet.domain.headerSlot
import com.zillit.desktop.feature.callsheet.domain.insertCell
import com.zillit.desktop.feature.callsheet.domain.insertRow
import com.zillit.desktop.feature.callsheet.domain.moveBlock
import com.zillit.desktop.feature.callsheet.domain.removeCell
import com.zillit.desktop.feature.callsheet.domain.removeRow
import com.zillit.desktop.feature.callsheet.domain.restoreCell
import com.zillit.desktop.feature.callsheet.domain.restoreCellFromRemovedRow
import com.zillit.desktop.feature.callsheet.domain.restoreRow
import com.zillit.desktop.feature.callsheet.domain.sectionBlocks
import com.zillit.desktop.feature.callsheet.domain.toggledApprover
import com.zillit.desktop.feature.callsheet.domain.withAtom
import com.zillit.desktop.feature.callsheet.domain.withColumnAdded
import com.zillit.desktop.feature.callsheet.domain.withColumnRemoved
import com.zillit.desktop.feature.callsheet.domain.withDate
import com.zillit.desktop.feature.callsheet.domain.withLineHeight
import com.zillit.desktop.feature.callsheet.ui.editor.PreviewBlock
import com.zillit.desktop.feature.callsheet.ui.editor.previewBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Structural edits: the preview's inserts, the pane's removals and drags, and the blocks that ride along. */
class PayloadEditsTest {

    private fun cell(title: String, kind: CellKind = CellKind.Section, renderAs: RenderKind = RenderKind.Generic) =
        PageCell(order = 0, kind = kind, renderAs = renderAs, title = title)

    private fun doc(vararg titles: String, header: Int? = null, approvers: Int? = null) = SheetPayload(
        shared = SharedHeader(headerPosition = header, approversPosition = approvers),
        rows = titles.mapIndexed { index, title -> PageRow(order = index, cells = listOf(cell(title))) },
    )

    private fun SheetPayload.titles() = rows.map { row -> row.cells.joinToString("+") { it.title }.ifEmpty { "|" } }

    @Test
    fun `inserting below the header shifts the approvers but not the header`() {
        val (next, selection) = doc("A", "B").insertRow(InsertKind.Section, afterIndex = -1)
        assertEquals(listOf("New Section", "A", "B"), next.titles())
        assertEquals(0, next.headerSlot())
        assertEquals(3, next.approversSlot())
        assertEquals(EditorSelection.Cell(0, 0), selection)
    }

    @Test
    fun `inserting above the header moves the header down with its row`() {
        val (next, _) = doc("A", "B").insertRow(InsertKind.Table, afterIndex = -1, aboveHeader = true)
        assertEquals(1, next.headerSlot())
        assertEquals("New Table", next.rows[0].cells[0].title)
    }

    @Test
    fun `inserting right under the approvers keeps them where they are`() {
        val (next, _) = doc("A", "B", approvers = 1).insertRow(InsertKind.Notes, afterIndex = 0, lockApprovers = true)
        assertEquals(listOf("A", "New Notes", "B"), next.titles())
        assertEquals(1, next.approversSlot())
    }

    @Test
    fun `a page break selects nothing and a cell insert cannot be a break`() {
        val (withBreak, selection) = doc("A").insertRow(InsertKind.PageBreak, afterIndex = 0)
        assertTrue(withBreak.rows[1].isPageBreak)
        assertEquals(null, selection)
        val (unchanged, none) = doc("A").insertCell(0, 0, InsertKind.PageBreak)
        assertEquals(doc("A"), unchanged)
        assertEquals(null, none)
    }

    @Test
    fun `side-by-side cells insert after the one clicked and are renumbered`() {
        val (next, selection) = doc("A").insertCell(0, afterCell = 0, InsertKind.Section)
        assertEquals(listOf("A", "New Section"), next.rows[0].cells.map { it.title })
        assertEquals(listOf(0, 1), next.rows[0].cells.map { it.order })
        assertEquals(EditorSelection.Cell(0, 1), selection)
    }

    @Test
    fun `removing a row keeps the header and approvers where the reader sees them, and undo restores both`() {
        val start = doc("A", "B", "C", header = 1, approvers = 3)
        val removed = start.removeRow(0)
        assertEquals(listOf("B", "C"), removed.titles())
        assertEquals(0, removed.headerSlot())
        assertEquals(2, removed.approversSlot())

        val restored = removed.restoreRow(start.rows[0], 0, start.shared.headerPosition, start.shared.approversPosition)
        assertEquals(start.titles(), restored.titles())
        assertEquals(1, restored.headerSlot())
        assertEquals(3, restored.approversSlot())
    }

    @Test
    fun `the last cell of a row takes the row with it, and restoring re-creates the row`() {
        val start = doc("A", "B")
        val removed = start.removeCell(0, 0)
        assertEquals(listOf("B"), removed.titles())
        val back = removed.restoreCellFromRemovedRow(start.rows[0].cells[0], 0, start.rows[0].cells)
        assertEquals(listOf("A", "B"), back.titles(), "never squeezed in beside B, as the web did")
    }

    @Test
    fun `a removed row's sections restore side by side again, in their old order`() {
        val row = PageRow(0, cells = listOf(cell("Address"), cell("Key Personnel"), cell("Schedule Info")))
        val start = SheetPayload(rows = listOf(row, PageRow(1, cells = listOf(cell("Scenes")))))
        val removed = start.removeRow(0)
        val restored = removed
            .restoreCellFromRemovedRow(row.cells[2], 0, row.cells)
            .restoreCellFromRemovedRow(row.cells[0], 0, row.cells)
            .restoreCellFromRemovedRow(row.cells[1], 0, row.cells)
        assertEquals(listOf("Address+Key Personnel+Schedule Info", "Scenes"), restored.titles())
    }

    @Test
    fun `a section whose row stayed goes back beside its neighbours`() {
        val start = SheetPayload(rows = listOf(PageRow(0, cells = listOf(cell("A"), cell("C")))))
        val removed = start.removeCell(0, 1)
        assertEquals(listOf("A"), removed.titles())
        assertEquals(listOf("A+C"), removed.restoreCell(start.rows[0].cells[1], 0, 1).titles())
    }

    @Test
    fun `sections list blocks interleave the header and approvers at their slots`() {
        val blocks = doc("A", "B", header = 1, approvers = 1).sectionBlocks()
        assertEquals(listOf("row-0", "header", "approvers", "row-1"), blocks.map { it.key })
    }

    @Test
    fun `dragging a block recomputes both slots from the rows above them`() {
        val start = doc("A", "B", "C")
        val headerDown = start.moveBlock(SectionBlock.Header.key, "row-1")
        assertEquals(listOf("A", "B", "C"), headerDown.titles())
        assertEquals(2, headerDown.headerSlot(), "the header now sits under A and B")

        val rowUp = start.moveBlock("row-2", "row-0")
        assertEquals(listOf("C", "A", "B"), rowUp.titles())
        assertEquals(listOf(0, 1, 2), rowUp.rows.map { it.order })
    }

    @Test
    fun `type switches keep what they can and crew sections never switch`() {
        val table = PageCell(
            order = 0,
            kind = CellKind.Table,
            title = "Cast",
            columns = listOf(ColumnSpec(label = "#"), ColumnSpec(label = "Character"), ColumnSpec(label = "Call")),
            rows = listOf(CellRow(0, listOf(CellValue("1"), CellValue("Lead"), CellValue("06:00")))),
        )
        // The web's `handleTypeChange`: any switch but notes keeps every column and value.
        val section = table.convertedTo(CellKind.Section)
        assertEquals(CellKind.Section, section.kind)
        assertEquals(listOf("#", "Character", "Call"), section.columns.map { it.label })
        assertEquals(listOf("1", "Lead", "06:00"), section.rows.single().values.map { it.value })

        val notes = table.convertedTo(CellKind.Notes)
        assertEquals(1, notes.columns.size)
        assertEquals(1, notes.rows.size)
        assertEquals("", notes.rows.single().values.single().value, "notes start blank")

        val crew = table.copy(renderAs = RenderKind.Employee)
        assertEquals(crew, crew.convertedTo(CellKind.Notes))
    }

    @Test
    fun `columns add and remove with their values, and short lines are padded when edited`() {
        val base = PageCell(
            order = 0,
            kind = CellKind.Table,
            columns = listOf(ColumnSpec(label = "A"), ColumnSpec(label = "B")),
            rows = listOf(CellRow(0, listOf(CellValue("a")))),
        )
        val added = base.withColumnAdded()
        assertEquals(3, added.columns.size)
        val removed = added.withColumnRemoved(0)
        assertEquals(listOf("B", ""), removed.columns.map { it.label })

        val edited = base.withAtom(0, 1) { it.copy(value = "b") }
        assertEquals(listOf("a", "b"), edited.rows[0].values.map { it.value })
        assertEquals(28, base.withLineHeight(0, 5).rows[0].height, "a line is never shorter than 28")
    }

    @Test
    fun `a new date clears fetched weather, and approvers toggle in order`() {
        val weather = PageCell(
            order = 0,
            renderAs = RenderKind.Weather,
            title = "Weather",
            columns = listOf(ColumnSpec(label = "Weather")),
            rows = listOf(CellRow(0, listOf(CellValue("{\"_type\":\"weather\"}")))),
        )
        val start = SheetPayload(
            shared = SharedHeader(dateMs = DAY),
            rows = listOf(PageRow(0, cells = listOf(weather))),
        )
        val moved = start.withDate(DAY + DAY_MS)
        assertEquals(DAY + DAY_MS, moved.shared.dateMs)
        assertEquals("", moved.rows[0].cells[0].rows[0].values[0].value)

        val toggled = start.toggledApprover("a").toggledApprover("b").toggledApprover("a")
        assertEquals(listOf("b"), toggled.shared.approverIds)
    }

    @Test
    fun `the preview draws the header, rows and approvers in the web's order with insert strips`() {
        val blocks = previewBlocks(doc("A", "B", approvers = 1))
        val shape = blocks.map {
            when (it) {
                is PreviewBlock.Insert -> "+"
                PreviewBlock.Title -> "T"
                PreviewBlock.Approvers -> "AP"
                is PreviewBlock.Row -> "R${it.index}"
            }
        }
        assertEquals(listOf("+", "T", "+", "R0", "+", "AP", "+", "R1", "+"), shape)
    }

    @Test
    fun `no insert strip sits between two multi-department crew rows`() {
        val crewRow = PageRow(
            order = 0,
            cells = listOf(
                cell("Camera", CellKind.Table, RenderKind.Employee),
                cell("Sound", CellKind.Table, RenderKind.Employee),
            ),
        )
        val blocks = previewBlocks(SheetPayload(rows = listOf(crewRow, crewRow.copy(order = 1))))
        val afterFirst = blocks.indexOfFirst { it is PreviewBlock.Row && it.index == 0 } + 1
        assertTrue(blocks[afterFirst] is PreviewBlock.Row, "row 1 follows row 0 directly")
    }
}

/** 2026-09-13T00:00Z. */
private const val DAY = 1_789_257_600_000L
private const val DAY_MS = 86_400_000L
