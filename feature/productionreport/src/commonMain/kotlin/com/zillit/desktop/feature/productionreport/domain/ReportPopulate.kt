package com.zillit.desktop.feature.productionreport.domain

/**
 * Seeding a fresh report from the last published call sheet.
 *
 * Empty-only writes, both passes: a value someone already typed always wins
 * over anything the call sheet says. A missing or unreadable call sheet
 * yields the report unchanged — the web behaves identically, down to the
 * silent catch.
 */
object ReportPopulate {

    private val IN_HEADER = Regex("""^in$""", RegexOption.IGNORE_CASE)
    private val NAME_HEADER = Regex("""^name$""", RegexOption.IGNORE_CASE)
    private val PERSONNEL_TITLE = Regex(
        """executives?\s*names?|key\s*personnel""",
        RegexOption.IGNORE_CASE,
    )
    private val DESIGNATION_HEADER = Regex("""^(designation|role|field)$""", RegexOption.IGNORE_CASE)
    private val PERSONNEL_NAME_HEADER = Regex("""^(name|value)$""", RegexOption.IGNORE_CASE)

    fun fromCallSheet(report: SheetPayload, callSheet: SheetPayload): SheetPayload =
        report
            .withCrewInTimes(callSheet.crewInTimesByName())
            .withKeyPersonnel(callSheet.personnelByDesignation())

    /** `lowercased name → IN value` over the call sheet's employee cells. */
    private fun SheetPayload.crewInTimesByName(): Map<String, String> {
        val times = mutableMapOf<String, String>()
        rows.flatMap { it.cells }
            .filter { it.renderAs == RenderKind.Employee }
            .forEach { cell ->
                val nameAt = cell.columns.indexOfFirst { NAME_HEADER.matches(it.label.trim()) }
                val inAt = cell.columns.indexOfFirst { IN_HEADER.matches(it.label.trim()) }
                if (nameAt < 0 || inAt < 0) return@forEach
                cell.rows.forEach { line ->
                    val name = line.values.getOrNull(nameAt)?.value?.trim().orEmpty()
                    val time = line.values.getOrNull(inAt)?.value?.trim().orEmpty()
                    if (name.isNotEmpty() && time.isNotEmpty()) {
                        times.putIfAbsent(name.lowercase(), time)
                    }
                }
            }
        return times
    }

    private fun SheetPayload.withCrewInTimes(times: Map<String, String>): SheetPayload {
        if (times.isEmpty()) return this
        return mapCells { cell ->
            if (cell.renderAs != RenderKind.Employee) return@mapCells cell
            val nameAt = cell.columns.indexOfFirst { NAME_HEADER.matches(it.label.trim()) }
            val inAt = cell.columns.indexOfFirst { IN_HEADER.matches(it.label.trim()) }
            if (nameAt < 0 || inAt < 0) return@mapCells cell
            cell.copy(
                rows = cell.rows.map { line ->
                    val name = line.values.getOrNull(nameAt)?.value?.trim().orEmpty()
                    val current = line.values.getOrNull(inAt)?.value?.trim().orEmpty()
                    val seed = times[name.lowercase()]
                    if (name.isEmpty() || current.isNotEmpty() || seed == null) {
                        line
                    } else {
                        line.copy(
                            values = line.values.mapIndexed { index, atom ->
                                if (index == inAt) {
                                    // Wall-clock wire: a call sheet carries
                                    // epoch times, a report carries HH:mm.
                                    atom.copy(value = ReportTime.toWireTime(seed))
                                } else {
                                    atom
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    /** `lowercased designation → name` from the call sheet's personnel cell. */
    private fun SheetPayload.personnelByDesignation(): Map<String, String> {
        val names = mutableMapOf<String, String>()
        rows.flatMap { it.cells }
            .filter { PERSONNEL_TITLE.containsMatchIn(it.title) }
            .forEach { cell ->
                val (designationAt, nameAt) = cell.personnelColumns() ?: return@forEach
                cell.rows.forEach { line ->
                    val designation = line.values.getOrNull(designationAt)?.value?.trim().orEmpty()
                    val name = line.values.getOrNull(nameAt)?.value?.trim().orEmpty()
                    if (designation.isNotEmpty() && name.isNotEmpty()) {
                        names.putIfAbsent(designation.lowercase(), name)
                    }
                }
            }
        return names
    }

    private fun SheetPayload.withKeyPersonnel(names: Map<String, String>): SheetPayload {
        if (names.isEmpty()) return this
        return mapCells { cell ->
            if (!PERSONNEL_TITLE.containsMatchIn(cell.title)) return@mapCells cell
            val (designationAt, nameAt) = cell.personnelColumns() ?: return@mapCells cell
            cell.copy(
                rows = cell.rows.map { line ->
                    val designation = line.values.getOrNull(designationAt)?.value?.trim().orEmpty()
                    val current = line.values.getOrNull(nameAt)?.value?.trim().orEmpty()
                    val seed = names[designation.lowercase()]
                    if (designation.isEmpty() || current.isNotEmpty() || seed == null) {
                        line
                    } else {
                        line.copy(
                            values = line.values.mapIndexed { index, atom ->
                                if (index == nameAt) atom.copy(value = seed) else atom
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * Designation and name column indexes, with the web's fallback: when no
     * header matches and there are at least two columns, the name is column 1.
     * A cell where both resolve to the same column is skipped.
     */
    private fun PageCell.personnelColumns(): Pair<Int, Int>? {
        val designationAt = columns.indexOfFirst { DESIGNATION_HEADER.matches(it.label.trim()) }
            .takeIf { it >= 0 } ?: 0
        var nameAt = columns.indexOfFirst { PERSONNEL_NAME_HEADER.matches(it.label.trim()) }
        if (nameAt < 0 && columns.size >= 2) nameAt = 1
        if (nameAt < 0 || nameAt == designationAt) return null
        return designationAt to nameAt
    }

    private fun SheetPayload.mapCells(change: (PageCell) -> PageCell): SheetPayload =
        copy(rows = rows.map { row -> row.copy(cells = row.cells.map(change)) })
}
