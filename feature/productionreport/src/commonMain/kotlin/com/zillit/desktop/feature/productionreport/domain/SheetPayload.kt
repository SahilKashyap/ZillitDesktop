package com.zillit.desktop.feature.productionreport.domain

/**
 * The editable document inside a production report.
 *
 * The wire stores this verbatim under `payload` — the backend never interprets
 * it beyond rendering, so whatever shape is saved is the shape every client
 * must be able to read back. The model here mirrors the web's structure
 * exactly: a `shared` header block plus ordered `page_rows`, each carrying
 * cells that are label/value sections, tables, or free-note boxes.
 */
data class SheetPayload(
    val shared: SharedHeader = SharedHeader(),
    val rows: List<PageRow> = emptyList(),
)

/**
 * The report-wide header.
 *
 * CamelCase on the wire — the one part of the payload that is. Day counts are
 * STRING-encoded numbers ("5" of "35"), and [dateYmd] is a wall-clock
 * `YYYY-MM-DD` — never an epoch: a 06:30 crew call is 06:30 on set whoever
 * opens the report, which is the whole point of the wall-clock wire.
 */
data class SharedHeader(
    val shootDayNumber: String = "",
    val totalDays: String = "",
    val dayType: String = "SWD",
    val dateYmd: String = "",
    val approverIds: List<String> = emptyList(),
    val internalReceiverIds: List<String> = emptyList(),
    /** `ad` | `wrap` | empty — see [ReportKind]. Never key-normalised on the wire. */
    val reportType: String = "",
    /** The AD report's own header field; empty on the other kinds. */
    val secondAdName: String = "",
)

/** One horizontal band of the sheet. A page break is a row with no cells. */
data class PageRow(
    val order: Int,
    val isBreak: Boolean = false,
    val cells: List<PageCell> = emptyList(),
)

/** How a cell is rendered. Unknown values survive round-trips untouched. */
enum class CellKind(val wire: String) {
    Section("section"), Table("table"), Notes("notes"), Unknown("");

    companion object {
        fun fromWire(value: String?): CellKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it.wire.isNotEmpty() }
                ?: Unknown
    }
}

enum class RenderKind(val wire: String) {
    Generic("generic"), Employee("employee"), Weather("weather"), Unknown("");

    companion object {
        fun fromWire(value: String?): RenderKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it.wire.isNotEmpty() }
                ?: Generic
    }
}

/**
 * One titled block: a section, table or notes box.
 *
 * [systemDefault] cells must keep a non-empty [title] — the save validation
 * the web enforces, mirrored in [SheetPayload.firstUntitledSystemCell].
 */
data class PageCell(
    val order: Int,
    val systemDefault: Boolean = false,
    val kind: CellKind = CellKind.Section,
    val renderAs: RenderKind = RenderKind.Generic,
    val title: String = "",
    val hideTitle: Boolean = false,
    val headerOrientation: String = "",
    val columns: List<ColumnSpec> = emptyList(),
    val rows: List<CellRow> = emptyList(),
    /** The wire's raw kind string, kept so an unknown kind round-trips. */
    val rawKind: String = kind.wire,
    val rawRenderAs: String = renderAs.wire,
)

/**
 * A column definition. [type] is one of
 * `text|number|phone|email|url|date|time|users|attachment` — `date` and `time`
 * columns hold epoch-ms numbers on the wire, everything else strings.
 */
data class ColumnSpec(
    val type: String = "text",
    val label: String = "",
    val required: Boolean = false,
    val width: Double? = null,
) {
    val isEpochValued: Boolean get() = type == "date" || type == "time"
}

data class CellRow(
    val order: Int,
    val values: List<CellValue> = emptyList(),
)

/** The atom: always this triple, whatever the column type. */
data class CellValue(
    val value: String = "",
    val cssValue: String = "",
    val attachment: String = "",
)

/** The save-blocking validation: a system cell whose title was emptied. */
fun SheetPayload.firstUntitledSystemCell(): PageCell? =
    rows.asSequence()
        .flatMap { it.cells.asSequence() }
        .firstOrNull { it.systemDefault && it.kind == CellKind.Section && it.title.isBlank() }

/** A single cell value replaced, everything else untouched. */
fun SheetPayload.withValue(
    rowIndex: Int,
    cellIndex: Int,
    rowInCell: Int,
    column: Int,
    value: String,
): SheetPayload = copy(
    rows = rows.mapIndexed { r, row ->
        if (r != rowIndex) {
            row
        } else {
            row.copy(
                cells = row.cells.mapIndexed { c, cell ->
                    if (c != cellIndex) {
                        cell
                    } else {
                        cell.copy(
                            rows = cell.rows.mapIndexed { i, line ->
                                if (i != rowInCell) {
                                    line
                                } else {
                                    line.copy(
                                        values = line.values.mapIndexed { v, atom ->
                                            if (v == column) atom.copy(value = value) else atom
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    },
)

/** A blank line appended to one cell, matching its column count. */
fun SheetPayload.withAddedLine(rowIndex: Int, cellIndex: Int): SheetPayload = copy(
    rows = rows.mapIndexed { r, row ->
        if (r != rowIndex) {
            row
        } else {
            row.copy(
                cells = row.cells.mapIndexed { c, cell ->
                    if (c != cellIndex) {
                        cell
                    } else {
                        cell.copy(
                            rows = cell.rows + CellRow(
                                order = cell.rows.size,
                                values = List(maxOf(cell.columns.size, 1)) { CellValue() },
                            ),
                        )
                    }
                },
            )
        }
    },
)

/**
 * Break rows carrying content are split into a bare break plus a content row,
 * and every `order` is rewritten to the array index — the web's
 * `normalizePageBreaks`, without which renderers disagree about pagination.
 */
fun SheetPayload.normalised(): SheetPayload {
    val split = rows.flatMap { row ->
        if (row.isBreak && row.cells.isNotEmpty()) {
            listOf(row.copy(cells = emptyList()), row.copy(isBreak = false))
        } else {
            listOf(row)
        }
    }
    return copy(rows = split.mapIndexed { index, row -> row.copy(order = index) })
}
