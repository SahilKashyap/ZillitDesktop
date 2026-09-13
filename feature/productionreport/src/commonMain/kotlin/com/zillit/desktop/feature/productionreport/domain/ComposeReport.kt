package com.zillit.desktop.feature.productionreport.domain

/**
 * Turning a template into today's report, and a stored report into an
 * editable document — purely local, zero API calls.
 *
 * Mirrors `openNewEditor` / `editProductionReport` (`ProductionReportApp.jsx`):
 * seed the header from project metadata, regenerate the crew sections from
 * the live crew when the template allows it, strip the approver blocks the
 * backend re-injects, and normalise page breaks. Call-sheet population runs
 * afterwards and only fills blanks — see [ReportPopulate].
 */
object ComposeReport {

    /** Cell titles that belong to the review machinery, never to the editor. */
    private val APPROVER_TITLES = setOf("approvers", "internal distribution", "for comments")

    /** Crew standings that never belong in a crew section (`memberAdapter` excludes them). */
    private val INACTIVE_STANDINGS = setOf("left", "removed", "pending", "rejected")

    /**
     * A new document from [template]. Returns the document and the shoot day
     * it was handed (0 for a saved template, which never ratchets the counter).
     */
    fun newReport(
        template: SheetPayload,
        metadata: SheetMetadata,
        members: List<SheetMember>,
        todayYmd: String,
        fromSavedTemplate: Boolean = false,
        regenerateCrew: Boolean = true,
    ): Pair<SheetPayload, Int> {
        val (shared, shootDay) = resolveSharedForTemplate(template.shared, metadata, fromSavedTemplate, todayYmd)
        val seeded = template.copy(shared = shared)
        val withCrew = if (regenerateCrew && shouldRegenerateEmployeeRows(seeded, members.size, fromSavedTemplate)) {
            seeded.withRegeneratedEmployees(members)
        } else {
            seeded
        }
        return withCrew.withoutApproverCells().normalised() to shootDay
    }

    /**
     * A stored report opened for editing: approvers seeded from its Approvers
     * block when the payload names none, then the project default; approver
     * blocks stripped; page breaks normalised. No metadata, no crew, no call sheet.
     */
    fun forEditing(payload: SheetPayload, projectApproverIds: List<String>): SheetPayload {
        val fromBlock = payload.rows.asSequence()
            .flatMap { it.cells.asSequence() }
            .firstOrNull { it.title.trim().equals("approvers", ignoreCase = true) }
            ?.rows
            ?.mapNotNull { line -> line.values.firstOrNull()?.value?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val approvers = payload.shared.approverIds.ifEmpty { fromBlock }.ifEmpty { projectApproverIds }
        return payload.copy(shared = payload.shared.copy(approverIds = approvers))
            .withoutApproverCells()
            .normalised()
    }

    /**
     * Approver blocks are stripped on the way in and re-injected by the
     * backend at render time. A client that leaves them in duplicates the
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
     * `generateEmployeeDetails`: one page row per department in first-seen
     * order, each an `employee` table of Role / Name / IN / OUT, inserted
     * before the first row holding a notes box (or at the end). Every row that
     * held a crew section before is replaced.
     */
    fun SheetPayload.withRegeneratedEmployees(members: List<SheetMember>): SheetPayload {
        val isCrew = { row: PageRow -> row.cells.any { it.renderAs == RenderKind.Employee } }
        val withoutOld = rows.filterNot(isCrew)
        // A block's slot counts the rows above it; the removed crew rows no longer do.
        val removedAbove = { slot: Int -> rows.take(slot).count(isCrew) }
        val header = shared.headerPosition?.let { it - removedAbove(it) }
        val approvers = shared.approversPosition?.let { it - removedAbove(it) }
        val generated = employeeRows(members)
        val anchor = withoutOld.indexOfFirst { row -> row.cells.any { it.kind == CellKind.Notes } }
        val at = if (anchor >= 0) anchor else withoutOld.size
        val merged = withoutOld.take(at) + generated + withoutOld.drop(at)
        return copy(
            rows = merged,
            shared = shared.copy(
                // The approvers close the report: at the insertion point they stay below the new crew.
                approversPosition = approvers?.let { if (it >= at) it + generated.size else it },
                headerPosition = header?.let { if (it > at) it + generated.size else it },
            ),
        ).renumbered()
    }

    private fun employeeRows(members: List<SheetMember>): List<PageRow> = members
        .filter { it.department.isNotBlank() && it.status?.trim()?.lowercase() !in INACTIVE_STANDINGS }
        .groupBy { it.department }
        .map { (department, crew) ->
            PageRow(
                order = 0,
                cells = listOf(
                    PageCell(
                        order = 0,
                        kind = CellKind.Table,
                        renderAs = RenderKind.Employee,
                        title = department,
                        columns = EMPLOYEE_COLUMNS.map { ColumnSpec(label = it) },
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

    val EMPLOYEE_COLUMNS = listOf("Role", "Name", "IN", "OUT")

    /**
     * The local fallback document (`defaultTemplate()`, `ProductionReportApp.jsx:143-522`) —
     * used only when the server has no stock template.
     */
    @Suppress("LongMethod") // The web's local default document, transcribed row by row.
    fun defaultTemplate(todayYmd: String): SheetPayload = SheetPayload(
        shared = SharedHeader(dateYmd = todayYmd),
        rows = listOf(
            row(
                section("Address", system = true, columns = listOf("Field", "Value"), labels = listOf("")),
                section(
                    "Key Personnel",
                    system = true,
                    columns = listOf("Designation", "Name"),
                    labels = listOf("Director", "Producer", "Executive Producer", "Writer", "1st AD"),
                    secondColumnType = "users",
                ),
                section(
                    "Schedule Info",
                    system = true,
                    columns = listOf("Field", "Value"),
                    labels = listOf(
                        "Current Script", "Current Schedule", "Start Date", "Schedule Finishing Date",
                        "Estimate Finish Date",
                    ),
                ),
            ),
            row(
                table(
                    "Schedule Summary",
                    listOf("Field", "Set Est.", "Prev.", "Today", "Total", "To Date", "+/-"),
                    listOf("Shoot", "Idle", "Rehearse / Test / Travel", "Holidays", "Total"),
                ),
            ),
            row(
                table("Scenes", listOf("Scene #", "D/N", "Set", "Location", "Pages"), List(SCENE_LINES) { "" }),
                section(
                    "Call Times",
                    system = false,
                    columns = listOf("Field", "Value"),
                    labels = listOf("Shooting Call", "Camera Wrap", "1st Meal", "2nd Meal", "Last Out"),
                ),
            ),
            row(
                table(
                    "Script Summary",
                    listOf("Field", "Minutes", "Scenes", "Added", "Retakes"),
                    listOf("Script", "Taken Prev.", "Taken Today", "Taken Total", "To Be Taken"),
                ),
            ),
            row(
                table(
                    "Camera Data",
                    listOf("Category", "Value"),
                    listOf("Camera", "Negative", "ISO / ASA"),
                    hideTitle = false,
                ),
                table(
                    "Data Capture Minutes",
                    listOf("Category", "Value"),
                    listOf("Today", "Previous", "To Date"),
                    hideTitle = false,
                ),
                table(
                    "Mag Inventory",
                    listOf("Category", "Value"),
                    listOf("Next Today", "Previous", "Remaining"),
                    hideTitle = false,
                ),
            ),
            row(table("Description", listOf("Description", "Roll", "Mag", "Load"), listOf(""))),
            row(
                table(
                    "Cast",
                    listOf("#", "Character", "W", "H", "SW", "SWF", "Work", "MDB", "Meal", "Out"),
                    List(CAST_ROWS) { (it + 1).toString() },
                ),
            ),
            row(table("Extras", listOf("Description", "#", "Rate", "In", "Out"), listOf(""))),
            row(table("Footer", listOf("Production Report", "Crew Call", "Lunch", "Camera Wrap", "Date"), listOf(""))),
            row(
                PageCell(
                    order = 0,
                    kind = CellKind.Notes,
                    title = "Notes",
                    columns = listOf(ColumnSpec(), ColumnSpec()),
                    rows = listOf(CellRow(0, listOf(CellValue(), CellValue()))),
                ),
            ),
        ),
    ).renumbered()

    private const val CAST_ROWS = 10

    private fun row(vararg cells: PageCell) = PageRow(order = 0, cells = cells.toList())

    private fun section(
        title: String,
        system: Boolean,
        columns: List<String>,
        labels: List<String>,
        secondColumnType: String = "text",
    ) = PageCell(
        order = 0,
        systemDefault = system,
        kind = CellKind.Section,
        title = title,
        hideTitle = true,
        columns = columns.mapIndexed { index, label ->
            ColumnSpec(type = if (index == 1) secondColumnType else "text", label = label)
        },
        rows = labels.mapIndexed { index, label -> CellRow(index, listOf(CellValue(label), CellValue())) },
    )

    private fun table(title: String, columns: List<String>, labels: List<String>, hideTitle: Boolean = true) = PageCell(
        order = 0,
        kind = CellKind.Table,
        title = title,
        hideTitle = hideTitle,
        columns = columns.map { ColumnSpec(label = it) },
        rows = labels.mapIndexed { index, label ->
            CellRow(index, listOf(CellValue(label)) + List(columns.size - 1) { CellValue() })
        },
    )
}

private const val SCENE_LINES = 5
