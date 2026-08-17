package com.zillit.desktop.feature.productionreport.domain

/**
 * Turning the server's template into today's report.
 *
 * A purely local composition with zero API calls: strip the approver blocks,
 * regenerate the crew-times sections from the live crew, and seed the header
 * from project metadata. Call-sheet population happens afterwards, and only
 * fills blanks — see ReportPopulate.
 */
object ComposeReport {

    /** Cell titles that belong to the review machinery, never to the editor. */
    private val APPROVER_TITLES = setOf("approvers", "internal distribution", "for comments")

    fun newReport(
        template: SheetPayload,
        metadata: SheetMetadata,
        members: List<SheetMember>,
        todayYmd: String,
    ): SheetPayload {
        val seeded = template.copy(
            shared = template.shared.copy(
                dateYmd = todayYmd,
                approverIds = metadata.finalApproverIds,
                totalDays = metadata.totalDays,
                shootDayNumber = (metadata.currentShootDay + 1).toString(),
                internalReceiverIds = metadata.internalReceiverIds,
            ),
        )

        return seeded
            .withoutApproverCells()
            .withRegeneratedEmployees(members)
            .normalised()
    }

    /**
     * Approver blocks are stripped on the way in and re-injected by the
     * backend at render time. A desktop that leaves them in duplicates the
     * block on every server-rendered PDF.
     */
    fun SheetPayload.withoutApproverCells(): SheetPayload = copy(
        rows = rows.mapNotNull { row ->
            val kept = row.cells.filterNot { it.title.trim().lowercase() in APPROVER_TITLES }
            when {
                kept.isNotEmpty() || row.isBreak -> row.copy(cells = kept)
                else -> null
            }
        },
    )

    /**
     * One page row per department, sorted A→Z, each a single `employee` table
     * of Role/Name/IN/OUT — inserted before the first notes row, where the
     * web puts crew times.
     */
    fun SheetPayload.withRegeneratedEmployees(members: List<SheetMember>): SheetPayload {
        val withoutOld = rows.mapNotNull { row ->
            val kept = row.cells.filterNot { it.renderAs == RenderKind.Employee }
            when {
                kept.isNotEmpty() || row.isBreak -> row.copy(cells = kept)
                else -> null
            }
        }
        if (members.isEmpty()) return copy(rows = withoutOld)

        val generated = members
            .groupBy { it.department.ifBlank { "General" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (department, crew) ->
                PageRow(
                    order = 0,
                    cells = listOf(
                        PageCell(
                            order = 0,
                            kind = CellKind.Table,
                            renderAs = RenderKind.Employee,
                            rawKind = CellKind.Table.wire,
                            rawRenderAs = RenderKind.Employee.wire,
                            title = department,
                            columns = listOf(
                                ColumnSpec(type = "text", label = "Role"),
                                ColumnSpec(type = "text", label = "Name"),
                                ColumnSpec(type = "time", label = "IN"),
                                ColumnSpec(type = "time", label = "OUT"),
                            ),
                            rows = crew.mapIndexed { index, member ->
                                CellRow(
                                    order = index,
                                    values = listOf(
                                        CellValue(member.designation),
                                        CellValue(member.fullName),
                                        CellValue(),
                                        CellValue(),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            }

        val anchor = withoutOld.indexOfFirst { row ->
            row.cells.any { it.kind == CellKind.Notes }
        }
        val merged = if (anchor >= 0) {
            withoutOld.take(anchor) + generated + withoutOld.drop(anchor)
        } else {
            withoutOld + generated
        }
        return copy(rows = merged)
    }
}
