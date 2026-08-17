package com.zillit.desktop.feature.callsheet.domain

/**
 * Turning the server's template into today's sheet.
 *
 * This is the web's `openNewEditor()` — a purely local composition with zero
 * API calls. The order of operations matters and is preserved: strip the
 * approver blocks, regenerate the employee sections from the live crew, seed
 * the header from project metadata, then pre-fill company details.
 */
object ComposeSheet {

    /** Cell titles that belong to the review machinery, never to the editor. */
    private val APPROVER_TITLES = setOf("approvers", "internal distribution", "for comments")

    fun newSheet(
        template: SheetPayload,
        metadata: SheetMetadata,
        members: List<SheetMember>,
        company: CompanySeed,
        todayMs: Long,
    ): SheetPayload {
        val seeded = template.copy(
            shared = template.shared.copy(
                dateMs = todayMs,
                approverIds = metadata.finalApproverIds,
                totalDays = metadata.totalDays,
                shootDayNumber = (metadata.currentShootDay + 1).toString(),
                internalReceiverIds = metadata.internalReceiverIds,
            ),
        )

        return seeded
            .withoutApproverCells()
            .withRegeneratedEmployees(members)
            .withCompanyDetails(company)
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
     * of Name/In — inserted before the Radio Channels row so crew calls sit
     * where every call sheet reader expects them.
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
                                ColumnSpec(type = "text", label = "Name"),
                                ColumnSpec(type = "time", label = "In"),
                            ),
                            rows = crew.mapIndexed { index, member ->
                                CellRow(
                                    order = index,
                                    values = listOf(CellValue(member.fullName), CellValue()),
                                )
                            },
                        ),
                    ),
                )
            }

        val anchor = withoutOld.indexOfFirst { row ->
            row.cells.any { it.title.contains("radio", ignoreCase = true) }
        }
        val merged = if (anchor >= 0) {
            withoutOld.take(anchor) + generated + withoutOld.drop(anchor)
        } else {
            withoutOld + generated
        }
        return copy(rows = merged)
    }

    /**
     * Company-details rows whose label matches are pre-filled from the open
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
                        if (!cell.title.contains("company", ignoreCase = true)) {
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
                                        line.copy(
                                            values = line.values.mapIndexed { index, atom ->
                                                if (index == 1) atom.copy(value = seed) else atom
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}
