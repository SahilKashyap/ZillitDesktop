package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.CellRow
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.firstUntitledSystemCell
import com.zillit.desktop.feature.callsheet.domain.normalised
import com.zillit.desktop.feature.callsheet.domain.withAddedLine
import com.zillit.desktop.feature.callsheet.domain.withValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SheetPayloadTest {

    private fun cell(
        title: String,
        renderAs: RenderKind = RenderKind.Generic,
        systemDefault: Boolean = false,
        rows: List<CellRow> = listOf(CellRow(0, listOf(CellValue("a"), CellValue("b")))),
    ) = PageCell(
        order = 0,
        systemDefault = systemDefault,
        kind = CellKind.Section,
        renderAs = renderAs,
        title = title,
        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
        rows = rows,
    )

    @Test
    fun `editing one value leaves everything else alone`() {
        val sheet = SheetPayload(rows = listOf(PageRow(0, cells = listOf(cell("Call Times")))))

        val edited = sheet.withValue(0, 0, 0, 1, "changed")

        assertEquals("changed", edited.rows[0].cells[0].rows[0].values[1].value)
        assertEquals("a", edited.rows[0].cells[0].rows[0].values[0].value)
    }

    @Test
    fun `adding a line matches the column count`() {
        val sheet = SheetPayload(rows = listOf(PageRow(0, cells = listOf(cell("Cast")))))

        val grown = sheet.withAddedLine(0, 0)

        assertEquals(2, grown.rows[0].cells[0].rows.size)
        assertEquals(2, grown.rows[0].cells[0].rows[1].values.size)
    }

    @Test
    fun `a break row with content splits and orders rewrite`() {
        val sheet = SheetPayload(
            rows = listOf(
                PageRow(order = 5, isBreak = true, cells = listOf(cell("Notes"))),
                PageRow(order = 9, cells = listOf(cell("Cast"))),
            ),
        )

        val normal = sheet.normalised()

        assertEquals(3, normal.rows.size)
        assertTrue(normal.rows[0].isBreak)
        assertTrue(normal.rows[0].cells.isEmpty())
        assertEquals(listOf(0, 1, 2), normal.rows.map { it.order })
    }

    @Test
    fun `an untitled system section blocks saving`() {
        val bad = SheetPayload(
            rows = listOf(PageRow(0, cells = listOf(cell("", systemDefault = true)))),
        )

        assertEquals("", bad.firstUntitledSystemCell()?.title)
        assertNull(
            SheetPayload(rows = listOf(PageRow(0, cells = listOf(cell("Titled", systemDefault = true)))))
                .firstUntitledSystemCell(),
        )
    }

    @Test
    fun `composition strips approver cells and seeds the header`() {
        val template = SheetPayload(
            shared = SharedHeader(),
            rows = listOf(
                PageRow(0, cells = listOf(cell("Company Details"), cell("Approvers"))),
                PageRow(1, cells = listOf(cell("For Comments"))),
                PageRow(2, cells = listOf(cell("Radio Channels"))),
            ),
        )

        val sheet = ComposeSheet.newSheet(
            template = template,
            metadata = SheetMetadata(
                currentShootDay = 4,
                totalDays = "35",
                finalApproverIds = listOf("appr-1"),
            ),
            members = emptyList(),
            company = CompanySeed(),
            todayMs = 1_755_129_600_000,
        )

        assertEquals("5", sheet.shared.shootDayNumber)
        assertEquals("35", sheet.shared.totalDays)
        assertEquals(listOf("appr-1"), sheet.shared.approverIds)
        assertEquals(1_755_129_600_000, sheet.shared.dateMs)
        val titles = sheet.rows.flatMap { row -> row.cells.map { it.title } }
        assertTrue("Approvers" !in titles && "For Comments" !in titles)
    }

    @Test
    fun `employee rows regenerate per department before radio channels`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(cell("Old crew", renderAs = RenderKind.Employee))),
                PageRow(1, cells = listOf(cell("Radio Channels"))),
            ),
        )

        val sheet = ComposeSheet.newSheet(
            template = template,
            metadata = SheetMetadata(),
            members = listOf(
                SheetMember("u2", "Zed Alpha", "Sound", "Mixer"),
                SheetMember("u1", "Amy Beta", "Camera", "Operator"),
            ),
            company = CompanySeed(),
            todayMs = 0,
        )

        val employeeTitles = sheet.rows
            .flatMap { it.cells }
            .filter { it.renderAs == RenderKind.Employee }
            .map { it.title }
        assertEquals(listOf("Camera", "Sound"), employeeTitles)
        val radioIndex = sheet.rows.indexOfFirst { row ->
            row.cells.any { it.title == "Radio Channels" }
        }
        assertEquals(sheet.rows.size - 1, radioIndex, "radio channels stayed last")
        assertTrue(sheet.rows.flatMap { it.cells }.none { it.title == "Old crew" })
    }

    @Test
    fun `company details prefill only fills the blanks`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        cell(
                            "Company Details",
                            rows = listOf(
                                CellRow(0, listOf(CellValue("Project Name"), CellValue())),
                                CellRow(1, listOf(CellValue("Company Name"), CellValue("Kept"))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val sheet = ComposeSheet.newSheet(
            template = template,
            metadata = SheetMetadata(),
            members = emptyList(),
            company = CompanySeed(projectName = "Night Shoot", companyName = "Studio X"),
            todayMs = 0,
        )

        val lines = sheet.rows[0].cells[0].rows
        assertEquals("Night Shoot", lines[0].values[1].value)
        assertEquals("Kept", lines[1].values[1].value)
    }
}
