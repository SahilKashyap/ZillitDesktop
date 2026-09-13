package com.zillit.desktop.feature.callsheet.domain

/**
 * Turning a template into today's sheet, and a stored sheet into an editable
 * document — purely local, zero API calls.
 *
 * Mirrors `openNewEditor` / `editCallSheet` (`CallSheetApp.jsx`): seed the
 * header from project metadata, regenerate the crew sections from the live
 * crew when the template allows it, strip the approver blocks the backend
 * re-injects, normalise page breaks, and pre-fill Company Details.
 */
object ComposeSheet {

    /** Cell titles that belong to the review machinery, never to the editor. */
    private val APPROVER_TITLES = setOf("approvers", "internal distribution", "for comments")

    private val RADIO = Regex("radio", RegexOption.IGNORE_CASE)

    /**
     * A new document from [template]. Returns the document and the shoot day
     * it was handed out (0 for a saved template, which never ratchets the
     * project counter).
     */
    fun newSheet(
        template: SheetPayload,
        metadata: SheetMetadata,
        members: List<SheetMember>,
        company: CompanySeed,
        todayMs: Long,
        fromSavedTemplate: Boolean = false,
    ): Pair<SheetPayload, Int> {
        val (shared, shootDay) = resolveSharedForTemplate(template.shared, metadata, fromSavedTemplate, todayMs)
        val seeded = template.copy(shared = shared)
        val crew = members.filter { it.isAccepted }
        val withCrew = if (shouldRegenerateEmployeeRows(seeded, crew.size, fromSavedTemplate)) {
            seeded.withRegeneratedEmployees(crew)
        } else {
            seeded
        }
        return withCrew.withoutApproverCells().normalised().withCompanyDetails(company) to shootDay
    }

    /**
     * A stored sheet opened for editing: blanks in the header back-filled from
     * the project metadata, approvers seeded from an "Approvers" block when
     * still none, approver blocks stripped, page breaks normalised. No crew
     * regeneration and no company prefill — the draft keeps what it saved.
     */
    fun forEditing(payload: SheetPayload, metadata: SheetMetadata): SheetPayload {
        val fromBlock = payload.rows.asSequence()
            .flatMap { it.cells.asSequence() }
            .firstOrNull { it.title.trim().equals("approvers", ignoreCase = true) }
            ?.rows
            ?.mapNotNull { line -> line.values.firstOrNull()?.value?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val shared = payload.shared
        val approvers = shared.approverIds.ifEmpty { metadata.finalApproverIds }.ifEmpty { fromBlock }
        return payload.copy(
            shared = shared.copy(
                totalDays = shared.totalDays.ifBlank { metadata.totalDays },
                approverIds = approvers,
                approverIdsStated = true,
                internalReceiverIds = shared.internalReceiverIds.ifEmpty { metadata.internalReceiverIds },
            ),
        ).withoutApproverCells().normalised()
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
     * `generateEmployeeDetails`: one page row per department (members with no
     * department are skipped), departments sorted A→Z ignoring case, each a
     * single `employee` table of Name / In inserted before the first row
     * holding a Radio section — or at the end. Every row that held a crew
     * section before is replaced, and the title bar and approvers keep their
     * place relative to the rows around them.
     *
     * "In" is a TEXT column: its values are the mode-prefixed strings of
     * [SheetTime.InMode], never bare epochs — the web's editor and preview
     * call string methods on them.
     */
    fun SheetPayload.withRegeneratedEmployees(members: List<SheetMember>): SheetPayload {
        val isCrew = { row: PageRow -> row.cells.any { it.renderAs == RenderKind.Employee } }
        val withoutOld = rows.filterNot(isCrew)
        val removedAbove = { slot: Int -> rows.take(slot).count(isCrew) }
        val header = shared.headerPosition?.let { it - removedAbove(it) }
        val approvers = shared.approversPosition?.let { it - removedAbove(it) }
        val generated = employeeRows(members)
        if (generated.isEmpty()) return copy(rows = withoutOld).renumbered()
        val anchor = withoutOld.indexOfFirst { row -> row.cells.any { RADIO.containsMatchIn(it.title) } }
        val at = if (anchor >= 0) anchor else withoutOld.size
        val merged = withoutOld.take(at) + generated + withoutOld.drop(at)
        return copy(
            rows = merged,
            shared = shared.copy(
                approversPosition = approvers?.let { if (it >= at) it + generated.size else it },
                headerPosition = header?.let { if (it > at) it + generated.size else it },
            ),
        ).renumbered()
    }

    private fun employeeRows(members: List<SheetMember>): List<PageRow> = members
        .filter { it.departmentKey.isNotBlank() || it.department.isNotBlank() }
        .groupBy { it.departmentKey.ifBlank { it.department } }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (key, crew) ->
            PageRow(
                order = 0,
                cells = listOf(
                    PageCell(
                        order = 0,
                        kind = CellKind.Table,
                        renderAs = RenderKind.Employee,
                        title = crew.first().department.ifBlank { key },
                        columns = listOf(ColumnSpec(label = EMPLOYEE_NAME), ColumnSpec(label = EMPLOYEE_IN)),
                        rows = crew.mapIndexed { index, member ->
                            CellRow(order = index, values = listOf(CellValue(member.fullName), CellValue()))
                        },
                    ),
                ),
            )
        }

    const val EMPLOYEE_NAME = "Name"
    const val EMPLOYEE_IN = "In"

    /**
     * Company Details rows whose label matches are pre-filled from the open
     * project — only where the value is still empty, so a template that
     * already carries one wins.
     */
    fun SheetPayload.withCompanyDetails(company: CompanySeed): SheetPayload {
        val seeds = mapOf(
            "project name" to company.projectName,
            "company name" to company.companyName,
            "company address" to company.companyAddress,
        )
        return copy(
            rows = rows.map { row ->
                row.copy(
                    cells = row.cells.map { cell ->
                        if (!cell.title.trim().equals("company details", ignoreCase = true)) {
                            cell
                        } else {
                            cell.copy(
                                rows = cell.rows.map { line ->
                                    val label = line.values.firstOrNull()?.value?.trim()?.lowercase()
                                    val seed = seeds[label].orEmpty()
                                    val current = line.values.getOrNull(1)?.value.orEmpty()
                                    if (seed.isBlank() || current.isNotBlank() || line.values.size < 2) {
                                        line
                                    } else {
                                        line.copy(values = line.values.replaced(1, line.values[1].copy(value = seed)))
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * The local fallback document (`defaultTemplate()`, `CallSheetApp.jsx:161-841`)
     * — used only when the server offers no stock template.
     */
    @Suppress("LongMethod") // The web's local default document, transcribed row by row.
    fun defaultTemplate(todayMs: Long): SheetPayload = SheetPayload(
        shared = SharedHeader(dayType = "SWD", dateMs = todayMs),
        rows = listOf(
            row(
                section(
                    "Executives Names",
                    columns = listOf("Designation" to "text", "Name" to "users"),
                    labels = listOf("Director", "Producer", "Executive Producer", "Writer", "1st AD"),
                ),
                section(
                    "Company Details",
                    columns = listOf("Field" to "text", "Value" to "text"),
                    labels = listOf("Project Name", "Company Name", "Company Address", "Onset"),
                ),
                section(
                    "Call Times",
                    columns = listOf("Field" to "text", "Value" to "time"),
                    labels = listOf(
                        "Current Script",
                        "Current Schedule",
                        "Shooting Call",
                        "Crew Parking",
                        "Holding Catering",
                    ),
                ),
            ),
            row(
                PageCell(
                    order = 0,
                    kind = CellKind.Section,
                    renderAs = RenderKind.Weather,
                    title = "Weather",
                    hideTitle = true,
                    columns = listOf(ColumnSpec(label = "Weather")),
                    rows = listOf(CellRow(0, listOf(CellValue()))),
                ),
            ),
            row(notes("Production Notes")),
            row(
                section(
                    "Shoot Location",
                    columns = listOf("Field" to "text", "Value" to "text"),
                    labels = listOf("Unit Base", "Location", "Address"),
                ),
                section(
                    "Contact Details",
                    columns = listOf("Designation" to "text", "User/Crew" to "text", "Contact" to "phone"),
                    labels = listOf(
                        "Production Coordinator", "Assistant Director", "Transport", "Unit Manager",
                        "Location Manager", "Production Secretary",
                    ),
                ),
            ),
            row(table("SC. / Set / Synopsis", listOf("SC.", "SET / SYNOPSIS", "D/N", "PAGE", "CAST#", "LOC"))),
            row(table("Cast", listOf("ID", "Cast", "Character", "SWF", "P/Up", "Arr", "Hair / MU", "Costume", "Set"))),
            row(
                table(
                    "Stunts",
                    listOf(
                        "ID",
                        "Stunts",
                        "Character",
                        "SWF",
                        "D/R",
                        "P/Up",
                        "Arr",
                        "Hair / MU",
                        "Costume",
                        "Trvl",
                        "Reh",
                        "Set",
                    ),
                ),
            ),
            row(
                table(
                    "Stand Ins / Utilities",
                    listOf(
                        "ID", "Stand-Ins / Utilities", "Character", "Agent", "P/Up", "Arr", "Hair / MU", "Costume",
                        "Trvl", "Reh", "Set",
                    ),
                ),
            ),
            row(
                table(
                    "Supporting Artists",
                    listOf(
                        "ID",
                        "SA Character",
                        "Supporting Artists",
                        "Scene",
                        "Agent",
                        "Call",
                        "M/Up",
                        "Cos",
                        "Reh",
                        "Set",
                    ),
                ),
            ),
            row(
                PageCell(
                    order = 0,
                    kind = CellKind.Table,
                    title = "Radio Channels",
                    columns = listOf(ColumnSpec(type = "number", label = "Number"), ColumnSpec(label = "Channel")),
                    rows = listOf("AD / Medic", "Locations", "Camera").mapIndexed { index, channel ->
                        CellRow(index, listOf(CellValue((index + 1).toString()), CellValue(channel)))
                    },
                ),
            ),
            row(table("Unit Transport", listOf("Driver", "Time", "Passengers", "From", "Destination", "Arrival Time"))),
            row(notes("Transport Notes")),
            row(notes("Department Notes")),
            row(notes("Additional Notes")),
        ),
    ).renumbered()

    private fun row(vararg cells: PageCell) = PageRow(order = 0, cells = cells.toList())

    private fun section(title: String, columns: List<Pair<String, String>>, labels: List<String>) = PageCell(
        order = 0,
        systemDefault = true,
        kind = CellKind.Section,
        title = title,
        columns = columns.map { (label, type) -> ColumnSpec(type = type, label = label) },
        rows = labels.mapIndexed { index, label ->
            CellRow(index, listOf(CellValue(label)) + List(columns.size - 1) { CellValue() })
        },
    )

    private fun table(title: String, columns: List<String>) = PageCell(
        order = 0,
        kind = CellKind.Table,
        title = title,
        columns = columns.map { ColumnSpec(label = it) },
        rows = List(2) { index -> CellRow(index, List(columns.size) { CellValue() }) },
    )

    private fun notes(title: String) = PageCell(
        order = 0,
        kind = CellKind.Notes,
        title = title,
        columns = listOf(ColumnSpec(), ColumnSpec()),
        rows = listOf(CellRow(0, listOf(CellValue(), CellValue()))),
    )
}
