package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.CellRow
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet.withRegeneratedEmployees
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opening a document: a stock template, a saved one, and a stored sheet (`openNewEditor` / `editCallSheet`). */
class ComposeSheetTest {

    private val today = 1_789_257_600_000L

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

    private fun companyDetails() = PageCell(
        order = 0,
        systemDefault = true,
        title = "Company Details",
        columns = listOf(ColumnSpec(label = "Field"), ColumnSpec(label = "Value")),
        rows = listOf(
            CellRow(0, listOf(CellValue("Project Name"), CellValue())),
            CellRow(1, listOf(CellValue("Company Name"), CellValue("Kept Ltd"))),
        ),
    )

    @Test
    fun `a stock template gets today, the next shoot day, the crew before Radio Channels, and the company`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(companyDetails())),
                PageRow(1, cells = listOf(PageCell(0, title = "Scenes", kind = CellKind.Table))),
                PageRow(2, cells = listOf(PageCell(0, title = "Radio Channels", kind = CellKind.Table))),
                PageRow(3, cells = listOf(PageCell(0, title = "Notes", kind = CellKind.Notes))),
            ),
        )
        val (document, shootDay) = ComposeSheet.newSheet(
            template = template,
            metadata = SheetMetadata(currentShootDay = 4, totalDays = "35", finalApproverIds = listOf("m1")),
            members = crew,
            company = CompanySeed(projectName = "Film", companyName = "Studio"),
            todayMs = today,
        )
        assertEquals(5, shootDay)
        assertEquals(today, document.shared.dateMs)
        assertEquals("5", document.shared.shootDayNumber)
        assertEquals("35", document.shared.totalDays)
        assertEquals(listOf("m1"), document.shared.approverIds)
        assertEquals(
            listOf("Company Details", "Scenes", "Camera", "Sound", "Radio Channels", "Notes"),
            document.rows.map { it.cells.single().title },
        )

        val camera = document.rows[2].cells.single()
        assertEquals(RenderKind.Employee, camera.renderAs)
        assertEquals(listOf("Name", "In"), camera.columns.map { it.label })
        // Accepted or unknown standing only; a blank department is skipped.
        assertEquals(listOf("Amy", "Cat"), camera.rows.map { it.values[0].value })
        assertEquals(listOf("Ben"), document.rows[3].cells.single().rows.map { it.values[0].value })
        assertEquals(document.rows.indices.toList(), document.rows.map { it.order })

        val company = document.rows[0].cells.single()
        assertEquals("Film", company.rows[0].values[1].value, "an empty value is pre-filled")
        assertEquals("Kept Ltd", company.rows[1].values[1].value, "a template's own value wins")
    }

    @Test
    fun `regenerating the crew replaces every old crew row and keeps the blocks in place`() {
        val old = PageCell(0, renderAs = RenderKind.Employee, title = "Old", kind = CellKind.Table)
        val start = SheetPayload(
            shared = SharedHeader(headerPosition = 0, approversPosition = 3),
            rows = listOf(
                PageRow(0, cells = listOf(old)),
                PageRow(1, cells = listOf(PageCell(0, title = "Scenes"))),
                PageRow(2, cells = listOf(old)),
            ),
        )
        val next = start.withRegeneratedEmployees(crew.take(2))
        assertEquals(listOf("Scenes", "Camera", "Sound"), next.rows.map { it.cells.single().title })
        assertEquals(0, next.shared.headerPosition)
        assertEquals(3, next.shared.approversPosition, "the approvers stay below the regenerated crew")
    }

    @Test
    fun `a saved template opens as saved and never hands out a shoot day`() {
        val saved = SheetPayload(
            shared = SharedHeader(shootDayNumber = "9", dateMs = 1L, approverIds = listOf("s1")),
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(PageCell(0, renderAs = RenderKind.Employee, title = "Grip", kind = CellKind.Table)),
                ),
            ),
        )
        val (document, shootDay) = ComposeSheet.newSheet(
            template = saved,
            metadata = SheetMetadata(currentShootDay = 4, totalDays = "35", finalApproverIds = listOf("m1")),
            members = crew,
            company = CompanySeed(),
            todayMs = today,
            fromSavedTemplate = true,
        )
        assertEquals(0, shootDay)
        assertEquals("9", document.shared.shootDayNumber)
        assertEquals(1L, document.shared.dateMs)
        assertEquals(listOf("s1"), document.shared.approverIds)
        assertEquals("35", document.shared.totalDays, "metadata fills only blanks")
        assertEquals(listOf("Grip"), document.rows.map { it.cells.single().title }, "saved crew is kept")
    }

    @Test
    fun `a stored sheet keeps its own approvers, else the project's, else its Approvers block, and drops the block`() {
        val payload = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(approversBlock(), PageCell(1, title = "Scenes"))),
                PageRow(1, isBreak = true, cells = listOf(PageCell(0, title = "Notes"))),
            ),
        )
        val fromBlock = ComposeSheet.forEditing(payload, SheetMetadata())
        assertEquals(listOf("a1", "a2"), fromBlock.shared.approverIds)
        assertEquals(listOf("Scenes"), fromBlock.rows[0].cells.map { it.title })
        assertTrue(fromBlock.rows[1].isPageBreak, "a break row carrying content is split")
        assertEquals("Notes", fromBlock.rows[2].cells.single().title)

        val fromProject = ComposeSheet.forEditing(payload, SheetMetadata(finalApproverIds = listOf("p1")))
        assertEquals(listOf("p1"), fromProject.shared.approverIds)

        val own = payload.copy(shared = SharedHeader(approverIds = listOf("o1")))
        val ownKept = ComposeSheet.forEditing(own, SheetMetadata(finalApproverIds = listOf("p1")))
        assertEquals(listOf("o1"), ownKept.shared.approverIds)
    }

    @Test
    fun `the local default template is the web's fourteen rows`() {
        val template = ComposeSheet.defaultTemplate(today)
        assertEquals(today, template.shared.dateMs)
        val titles = template.rows.flatMap { row -> row.cells.map { it.title } }
        assertTrue("Executives Names" in titles && "Call Times" in titles && "Radio Channels" in titles)
        assertTrue(template.rows.any { row -> row.cells.any { it.renderAs == RenderKind.Weather } })
        assertEquals(template.rows.indices.toList(), template.rows.map { it.order })
    }
}
