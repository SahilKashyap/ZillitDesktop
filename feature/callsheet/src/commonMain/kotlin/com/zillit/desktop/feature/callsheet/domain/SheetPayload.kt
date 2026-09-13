package com.zillit.desktop.feature.callsheet.domain

import kotlinx.serialization.json.JsonElement

/**
 * The editable document inside a call sheet.
 *
 * The wire stores this verbatim under `payload` — the backend never interprets
 * it beyond rendering, so whatever shape is saved is the shape every client
 * must be able to read back. The model mirrors the web's structure: a `shared`
 * header block plus ordered `page_rows`, each carrying cells that are
 * label/value sections, tables, or notes boxes.
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
 * The sheet-wide header.
 *
 * CamelCase on the wire — the one part of the payload that is. Day counts are
 * STRING-encoded numbers ("5" of "35"), and [dateMs] is epoch milliseconds at
 * LOCAL midnight: the server's renderer prints whatever calendar day the epoch
 * lands on, so an afternoon instant drifts a day east of the dateline.
 */
data class SharedHeader(
    val shootDayNumber: String = "",
    val totalDays: String = "",
    /** One of the project's day types; blank is "Select" and stays blank. */
    val dayType: String = "",
    val dateMs: Long? = null,
    val approverIds: List<String> = emptyList(),
    /**
     * Whether the document states an approver list at all. An explicit empty
     * list means "nobody signs this sheet"; an absent one defers to the
     * project's default approvers (the web's `approverIdsFromSheet` null).
     */
    val approverIdsStated: Boolean = true,
    val internalReceiverIds: List<String> = emptyList(),
    /** Where the title bar sits: "before page row N". Absent means 0. */
    val headerPosition: Int? = null,
    /** Where the approvers block sits: "before page row N". Absent means after the last row. */
    val approversPosition: Int? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** One horizontal band of the sheet. A page break is a row with no cells. */
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
 * [systemDefault] cells always show their title in the preview, and a default
 * *section* may not be saved nameless — see [missingDefaultTitles].
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
    /** Notes only: `horizontal` joins the notes on one line; anything else lists them. */
    val viewType: String = "",
    val columns: List<ColumnSpec> = emptyList(),
    val rows: List<CellRow> = emptyList(),
    /** The wire's raw kind string, kept so an unknown kind round-trips. */
    val rawKind: String = kind.wire,
    val rawRenderAs: String = renderAs.wire,
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    val isVerticalHeader: Boolean get() = headerOrientation.equals("vertical", ignoreCase = true)

    /** The web forces `hide_title` off for system sections in every renderer. */
    val effectiveHideTitle: Boolean get() = !systemDefault && hideTitle

    /** The column widths as proportions; a missing width counts as 1. */
    fun columnWeights(): List<Double> = columns.map { (it.width ?: 1.0).coerceAtLeast(MIN_WIDTH) }

    companion object {
        const val MIN_WIDTH = 0.25
    }
}

/**
 * A column definition. [type] is one of `CELL_TYPES`:
 * `text|number|phone|email|url|date|time|users|attachment`. Width is a
 * unitless proportion (default 1). `date` and `time` columns hold epoch-ms
 * NUMBERS on the wire, everything else strings.
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

/** Every column type a cell may carry — `attachment` is legacy and never offered for a new column. */
val CELL_TYPES = listOf("text", "number", "phone", "email", "url", "date", "time", "users", "attachment")

/** A default section saved without a name — the web's `getMissingDefaultSectionTitles` entry. */
data class MissingTitle(val row: Int, val cell: Int, val hint: String)

/**
 * Every default *section* whose title was emptied, with the first value it
 * still holds as a hint. Tables and notes warn inline but never block a save.
 */
fun SheetPayload.missingDefaultTitles(): List<MissingTitle> =
    rows.flatMapIndexed { r, row ->
        row.cells.mapIndexedNotNull { c, cell ->
            if (cell.kind == CellKind.Section && cell.systemDefault && cell.title.isBlank()) {
                val hint = cell.rows.asSequence()
                    .flatMap { it.values.asSequence() }
                    .map { it.value.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                MissingTitle(r, c, hint)
            } else {
                null
            }
        }
    }

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
