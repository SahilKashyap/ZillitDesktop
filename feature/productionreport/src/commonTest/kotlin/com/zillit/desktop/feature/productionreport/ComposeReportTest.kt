package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.ComposeReport.withRegeneratedEmployees
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opening a document: a stock template, a saved one, and a stored report. */
class ComposeReportTest {

    private val crew = listOf(
        SheetMember("u1", "Amy", department = "Camera", designation = "Operator", status = "accepted"),
        SheetMember("u2", "Ben", department = "Sound", designation = "Mixer", status = "accepted"),
        SheetMember("u3", "Cat", department = "Camera", designation = "1st AC", status = null),
        SheetMember("u4", "Dan", department = "Camera", designation = "Loader", status = "left"),
        SheetMember("u5", "Eve", department = "Sound", designation = "Boom", status = "pending"),
        SheetMember("u6", "Fay", department = "", designation = "Runner", status = "accepted"),
    )

    private fun approversBlock() = PageCell(
        order = 0,
        title = " Approvers ",
        columns = listOf(ColumnSpec(label = "Name")),
        rows = listOf(CellRow(0, listOf(CellValue("a1"))), CellRow(1, listOf(CellValue("a2")))),
    )

    @Test
    fun `a stock template gets today, the next shoot day, and today's crew by department`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(PageCell(0, title = "Scenes", kind = CellKind.Table))),
                PageRow(1, cells = listOf(PageCell(0, title = "Notes", kind = CellKind.Notes))),
            ),
        )
        val (document, shootDay) = ComposeReport.newReport(
            template = template,
            metadata = SheetMetadata(currentShootDay = 4, finalApproverIds = listOf("m1")),
            members = crew,
            todayYmd = "2026-09-13",
        )
        assertEquals(5, shootDay)
        assertEquals("2026-09-13", document.shared.dateYmd)
        assertEquals(listOf("Scenes", "Camera", "Sound", "Notes"), document.rows.map { it.cells.single().title })

        val camera = document.rows[1].cells.single()
        assertEquals(RenderKind.Employee, camera.renderAs)
        assertEquals(ComposeReport.EMPLOYEE_COLUMNS, camera.columns.map { it.label })
        assertEquals(
            listOf("Operator" to "Amy", "1st AC" to "Cat"),
            camera.rows.map { it.values[0].value to it.values[1].value },
        )
        assertEquals(listOf("Mixer"), document.rows[2].cells.single().rows.map { it.values[0].value })
        assertEquals(listOf(0, 1, 2, 3), document.rows.map { it.order })
    }

    @Test
    fun `approver blocks never enter the editor, and a row they emptied goes`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(approversBlock())),
                PageRow(1, cells = listOf(PageCell(0, title = "For Comments"), PageCell(1, title = "Cast"))),
            ),
        )
        val (document, _) = ComposeReport.newReport(template, SheetMetadata(), emptyList(), "2026-09-13")
        assertEquals(listOf(listOf("Cast")), document.rows.map { row -> row.cells.map { it.title } })
    }

    @Test
    fun `a stored report takes its approvers from the payload, then its block, then the project`() {
        val stored = SheetPayload(rows = listOf(PageRow(0, cells = listOf(approversBlock()))))
        assertEquals(listOf("a1", "a2"), ComposeReport.forEditing(stored, listOf("p1")).shared.approverIds)
        assertTrue(ComposeReport.forEditing(stored, listOf("p1")).rows.isEmpty())

        val own = stored.copy(shared = SharedHeader(approverIds = listOf("o1")))
        assertEquals(listOf("o1"), ComposeReport.forEditing(own, listOf("p1")).shared.approverIds)
        assertEquals(listOf("p1"), ComposeReport.forEditing(SheetPayload(), listOf("p1")).shared.approverIds)
    }

    @Test
    fun `a page break carrying cells splits into a break and its content`() {
        val stored = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(PageCell(0, title = "A"))),
                PageRow(1, isBreak = true, cells = listOf(PageCell(0, title = "B"))),
            ),
        )
        val document = ComposeReport.forEditing(stored, emptyList())
        assertEquals(3, document.rows.size)
        assertTrue(document.rows[1].isPageBreak)
        assertEquals("B", document.rows[2].cells.single().title)
    }

    @Test
    fun `regenerating crew replaces the old sections and keeps the blocks below them in place`() {
        val old = PageCell(0, kind = CellKind.Table, renderAs = RenderKind.Employee, title = "Old crew")
        val payload = SheetPayload(
            shared = SharedHeader(approversPosition = 2),
            rows = listOf(PageRow(0, cells = listOf(PageCell(0, title = "Top"))), PageRow(1, cells = listOf(old))),
        )
        val next = payload.withRegeneratedEmployees(crew)
        assertEquals(listOf("Top", "Camera", "Sound"), next.rows.map { it.cells.single().title })
        assertEquals(3, next.shared.approversPosition, "the approvers stay under every row they were under")
    }

    @Test
    fun `the local default template has the web's sections and today's date`() {
        val template = ComposeReport.defaultTemplate("2026-09-13")
        assertEquals("2026-09-13", template.shared.dateYmd)
        val titles = template.rows.flatMap { row -> row.cells.map { it.title } }
        assertTrue(titles.containsAll(listOf("Address", "Key Personnel", "Schedule Info", "Scenes", "Cast", "Notes")))
        assertEquals(
            setOf("Address", "Key Personnel", "Schedule Info"),
            template.rows.flatMap { it.cells }.filter { it.systemDefault }.map { it.title }.toSet(),
        )
    }
}
