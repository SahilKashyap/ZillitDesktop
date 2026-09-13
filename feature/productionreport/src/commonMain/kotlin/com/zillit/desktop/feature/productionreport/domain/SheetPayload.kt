package com.zillit.desktop.feature.productionreport.domain

import kotlinx.serialization.json.JsonElement

/**
 * The editable document inside a production report.
 *
 * The wire stores this verbatim under `payload` — the backend never interprets
 * it beyond rendering, so whatever shape is saved is the shape every client
 * must be able to read back. The model mirrors the web's structure: a `shared`
 * header block plus ordered `page_rows`, each carrying cells that are
 * label/value sections, tables, or free-note boxes.
 *
 * Every level keeps the keys this client does not model in `extras`, so a
 * field another client adds survives a desktop save instead of being dropped.
 */
data class SheetPayload(
    val shared: SharedHeader = SharedHeader(),
    val rows: List<PageRow> = emptyList(),
    val extras: Map<String, JsonElement> = emptyMap(),
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
    /** Where the header block sits: "before page row N". Absent means 0. */
    val headerPosition: Int? = null,
    /** Where the approvers block sits: "before page row N". Absent means after the last row. */
    val approversPosition: Int? = null,
    /** `ad` | `wrap` | empty — see [ReportKind]. Never key-normalised on the wire. */
    val reportType: String = "",
    /** The AD report's own header field; empty on the other kinds. */
    val secondAdName: String = "",
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** One horizontal band of the page. A page break is a row with no cells. */
data class PageRow(
    val order: Int,
    val isBreak: Boolean = false,
    val cells: List<PageCell> = emptyList(),
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    /** A dedicated break row — what `normalizePageBreaks` leaves behind. */
    val isPageBreak: Boolean get() = isBreak && cells.isEmpty()
}

/** How a cell lays out its rows. Unknown values survive round-trips untouched. */
enum class CellKind(val wire: String) {
    Section("section"), Table("table"), Notes("notes"), Unknown("");

    companion object {
        fun fromWire(value: String?): CellKind =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: Unknown
    }
}

/** What a cell renders as, beyond its layout. */
enum class RenderKind(val wire: String) {
    Generic("generic"), Employee("employee"), Weather("weather"), Unknown("");

    companion object {
        fun fromWire(value: String?): RenderKind =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: Generic
    }
}

/**
 * One titled block: a section, table or notes box.
 *
 * [systemDefault] cells always show their title in the preview and warn when
 * it is emptied; the web never blocks a save on it.
 */
data class PageCell(
    val order: Int,
    val systemDefault: Boolean = false,
    val kind: CellKind = CellKind.Section,
    val renderAs: RenderKind = RenderKind.Generic,
    val title: String = "",
    val hideTitle: Boolean = false,
    /** `horizontal` (default) or `vertical` — rotated column names. */
    val headerOrientation: String = "",
    /** Notes only: `vertical` (default) or `horizontal`. */
    val viewType: String = "",
    val columns: List<ColumnSpec> = emptyList(),
    val rows: List<CellRow> = emptyList(),
    /** The wire's raw kind string, kept so an unknown kind round-trips. */
    val rawKind: String = kind.wire,
    val rawRenderAs: String = renderAs.wire,
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    val isVerticalHeader: Boolean get() = headerOrientation.equals("vertical", ignoreCase = true)

    /** Whether the preview draws the title bar: system sections always do. */
    val showsTitle: Boolean get() = title.isNotBlank() && (systemDefault || !hideTitle)

    /** The column widths as proportions; a missing width counts as 1. */
    fun columnWeights(): List<Double> = columns.map { (it.width ?: 1.0).coerceAtLeast(MIN_WIDTH) }

    companion object {
        const val MIN_WIDTH = 0.25
    }
}

/**
 * A column definition. [type] is one of `CELL_TYPES`:
 * `text|number|phone|email|url|date|time|users|attachment|location`.
 * Width is a unitless proportion (default 1).
 */
data class ColumnSpec(
    val type: String = "text",
    val label: String = "",
    val required: Boolean = false,
    val width: Double? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    val isEpochValued: Boolean get() = type == "date" || type == "time"
}

/** One line of a cell. [height] is a dragged row height in px (table editor only). */
data class CellRow(
    val order: Int,
    val values: List<CellValue> = emptyList(),
    val height: Int? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** The atom: always this triple, whatever the column type. */
data class CellValue(
    val value: String = "",
    val cssValue: String = "",
    val attachment: String = "",
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** The column types a new column may take — `attachment` is legacy-only (client ask, Sep 2026). */
val AUTHORABLE_COLUMN_TYPES = listOf("text", "number", "phone", "email", "url", "date", "time", "users", "location")

/** A system cell whose title was emptied — the editor's non-blocking warning. */
fun SheetPayload.firstUntitledSystemCell(): PageCell? =
    rows.asSequence()
        .flatMap { it.cells.asSequence() }
        .firstOrNull { it.systemDefault && it.title.isBlank() }

/**
 * Break rows carrying content are split into a bare break plus a content row,
 * and every `order` is rewritten to the array index — the web's
 * `normalizePageBreaks`, without which renderers disagree about pagination.
 */
fun SheetPayload.normalised(): SheetPayload {
    val split = rows.flatMap { row ->
        if (row.isBreak && row.cells.isNotEmpty()) {
            listOf(PageRow(order = 0, isBreak = true), row.copy(isBreak = false))
        } else {
            listOf(row)
        }
    }
    return copy(rows = split.mapIndexed { index, row -> row.copy(order = index) })
}
