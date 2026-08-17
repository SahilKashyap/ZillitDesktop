package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.ReportPopulate
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportPopulateTest {

    private fun employees(title: String, columns: List<String>, rows: List<List<String>>) = PageCell(
        order = 0,
        kind = CellKind.Table,
        renderAs = RenderKind.Employee,
        title = title,
        columns = columns.map { ColumnSpec(type = "text", label = it) },
        rows = rows.mapIndexed { index, values ->
            CellRow(index, values.map { CellValue(it) })
        },
    )

    private fun personnel(title: String, rows: List<List<String>>) = PageCell(
        order = 0,
        kind = CellKind.Section,
        renderAs = RenderKind.Generic,
        title = title,
        columns = listOf(ColumnSpec(label = "Designation"), ColumnSpec(label = "Name")),
        rows = rows.mapIndexed { index, values ->
            CellRow(index, values.map { CellValue(it) })
        },
    )

    @Test
    fun `crew IN times seed from the call sheet by name, blanks only`() {
        val report = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        employees(
                            "Camera",
                            listOf("Role", "Name", "IN", "OUT"),
                            listOf(
                                listOf("Operator", "Amy Beta", "", ""),
                                listOf("Focus", "Bo Cee", "09:00", ""),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val callSheet = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        employees(
                            "Camera",
                            listOf("Name", "In"),
                            listOf(
                                listOf("amy beta", "1755133200000"),
                                listOf("Bo Cee", "1755126000000"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val seeded = ReportPopulate.fromCallSheet(report, callSheet)

        val lines = seeded.rows[0].cells[0].rows
        assertTrue(lines[0].values[2].value.matches(Regex("""\d{2}:\d{2}""")), "epoch was folded")
        assertEquals("09:00", lines[1].values[2].value, "an existing IN was overwritten")
    }

    @Test
    fun `key personnel seed by designation, blanks only`() {
        val report = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        personnel(
                            "Key Personnel",
                            listOf(listOf("Director", ""), listOf("Producer", "Kept Name")),
                        ),
                    ),
                ),
            ),
        )
        val callSheet = SheetPayload(
            rows = listOf(
                PageRow(
                    0,
                    cells = listOf(
                        personnel(
                            "Executives Names",
                            listOf(listOf("Director", "Dana Dee"), listOf("Producer", "Pat Q")),
                        ),
                    ),
                ),
            ),
        )

        val seeded = ReportPopulate.fromCallSheet(report, callSheet)

        val lines = seeded.rows[0].cells[0].rows
        assertEquals("Dana Dee", lines[0].values[1].value)
        assertEquals("Kept Name", lines[1].values[1].value)
    }

    @Test
    fun `crew sections regenerate with role name in out before notes`() {
        val template = SheetPayload(
            rows = listOf(
                PageRow(0, cells = listOf(personnel("Schedule Info", emptyList()))),
                PageRow(
                    1,
                    cells = listOf(
                        PageCell(order = 0, kind = CellKind.Notes, title = "Notes"),
                    ),
                ),
            ),
        )

        val report = ComposeReport.newReport(
            template = template,
            metadata = SheetMetadata(currentShootDay = 2, totalDays = "20"),
            members = listOf(SheetMember("u1", "Amy Beta", "Camera", "Operator")),
            todayYmd = "2026-08-14",
        )

        assertEquals("3", report.shared.shootDayNumber)
        assertEquals("2026-08-14", report.shared.dateYmd)
        val cameraIndex = report.rows.indexOfFirst { row ->
            row.cells.any { it.renderAs == RenderKind.Employee }
        }
        val notesIndex = report.rows.indexOfFirst { row ->
            row.cells.any { it.kind == CellKind.Notes }
        }
        assertTrue(cameraIndex in 0 until notesIndex, "crew must sit before notes")
        val crew = report.rows[cameraIndex].cells[0]
        assertEquals(listOf("Role", "Name", "IN", "OUT"), crew.columns.map { it.label })
        assertEquals(listOf("Operator", "Amy Beta", "", ""), crew.rows[0].values.map { it.value })
    }
}
