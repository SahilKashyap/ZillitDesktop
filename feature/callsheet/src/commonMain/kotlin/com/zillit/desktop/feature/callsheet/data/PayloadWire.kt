package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.CellRow
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The payload codec.
 *
 * ## Why this is hand-written
 *
 * The wire is snake_case (`page_rows`, `row_header`, `css_value`) with one
 * camelCase island (`shared`), but the platform's response pipeline camelCases
 * **everything** — a payload loaded back arrives as `pageRows`/`rowHeader`,
 * and a sheet the web saved may carry BOTH (a stale camel `pageRows` beside
 * the current `page_rows`). So parsing accepts both spellings, preferring the
 * snake one, and emission always writes the canonical snake_case shape the
 * backend's PDF renderer and the web read.
 *
 * Keys this client does not model are kept per level and written back, so a
 * field another client adds is not lost by a desktop save.
 *
 * Values are typed by column: `date`/`time` columns carry epoch-ms NUMBERS,
 * everything else strings — the crew "In" column included, whose `Time:<ms>`
 * values must stay strings.
 */
@Suppress("TooManyFunctions") // One reader and one writer per level of the document.
object PayloadWire {

    private val ROOT_KEYS = setOf("shared", "page_rows", "pageRows")

    /** Presentation keys a stock template carries that must never enter a sheet. */
    private val TEMPLATE_ROOT_KEYS = setOf("id", "_id", "name", "identifier", "key", "displayName", "isCreateYourOwn")

    private val SHARED_KEYS = setOf(
        "shootDayNumber", "shoot_day_number", "totalDays", "total_days", "dayType", "day_type", "date",
        "approverIds", "approver_ids", "internalReceiverIds", "internal_receiver_ids",
        "headerPosition", "header_position", "approversPosition", "approvers_position",
    )
    private val ROW_KEYS = setOf("order", "break", "page_row_cells", "pageRowCells")
    private val CELL_KEYS = setOf(
        "order", "system_default", "systemDefault", "type", "render_as", "renderAs", "section_title",
        "sectionTitle", "hide_title", "hideTitle", "header_orientation", "headerOrientation", "view_type",
        "viewType", "row_header", "rowHeader", "rows",
    )
    private val COLUMN_KEYS = setOf("type", "value", "required", "width")
    private val LINE_KEYS = setOf("order", "row", "height")
    private val ATOM_KEYS = setOf("value", "css_value", "cssValue", "attachment")

    fun parse(element: JsonElement?): SheetPayload {
        val obj = element as? JsonObject ?: return SheetPayload()
        return SheetPayload(
            shared = parseShared(obj.field("shared") as? JsonObject),
            rows = (obj.field("page_rows", "pageRows") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { index, row -> parseRow(row as? JsonObject, index) },
            extras = obj.extras(ROOT_KEYS + TEMPLATE_ROOT_KEYS),
        )
    }

    /** Whether any row of [element] carries cells — a listing may send rows stripped of them. */
    fun hasCells(element: JsonElement?): Boolean {
        val rows = ((element as? JsonObject)?.field("page_rows", "pageRows") as? JsonArray).orEmpty()
        return rows.any { row ->
            ((row as? JsonObject)?.field("page_row_cells", "pageRowCells") as? JsonArray)?.isNotEmpty() == true
        }
    }

    fun parseShared(obj: JsonObject?): SharedHeader {
        if (obj == null) return SharedHeader(approverIdsStated = false)
        return SharedHeader(
            shootDayNumber = obj.text("shootDayNumber", "shoot_day_number"),
            totalDays = obj.text("totalDays", "total_days"),
            dayType = obj.text("dayType", "day_type"),
            dateMs = obj.epoch("date"),
            approverIds = obj.textList("approverIds", "approver_ids"),
            approverIdsStated = obj.field("approverIds", "approver_ids") is JsonArray,
            internalReceiverIds = obj.textList("internalReceiverIds", "internal_receiver_ids"),
            headerPosition = obj.int("headerPosition", "header_position"),
            approversPosition = obj.int("approversPosition", "approvers_position"),
            extras = obj.extras(SHARED_KEYS),
        )
    }

    private fun parseRow(obj: JsonObject?, index: Int): PageRow? {
        if (obj == null) return null
        return PageRow(
            order = obj.int("order") ?: index,
            isBreak = obj.bool("break"),
            cells = (obj.field("page_row_cells", "pageRowCells") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { cellIndex, cell -> parseCell(cell as? JsonObject, cellIndex) },
            extras = obj.extras(ROW_KEYS),
        )
    }

    private fun parseCell(obj: JsonObject?, index: Int): PageCell? {
        if (obj == null) return null
        val rawKind = obj.text("type")
        val rawRender = obj.text("render_as", "renderAs")
        return PageCell(
            order = obj.int("order") ?: index,
            systemDefault = obj.bool("system_default", "systemDefault"),
            kind = CellKind.fromWire(rawKind),
            renderAs = RenderKind.fromWire(rawRender),
            rawKind = rawKind,
            rawRenderAs = rawRender,
            title = obj.text("section_title", "sectionTitle"),
            hideTitle = obj.bool("hide_title", "hideTitle"),
            headerOrientation = obj.text("header_orientation", "headerOrientation"),
            viewType = obj.text("view_type", "viewType"),
            columns = (obj.field("row_header", "rowHeader") as? JsonArray)
                .orEmpty()
                .mapNotNull { column -> parseColumn(column as? JsonObject) },
            rows = (obj.field("rows") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { rowIndex, row -> parseCellRow(row as? JsonObject, rowIndex) },
            extras = obj.extras(CELL_KEYS),
        )
    }

    private fun parseColumn(obj: JsonObject?): ColumnSpec? {
        if (obj == null) return null
        return ColumnSpec(
            type = obj.text("type").ifBlank { "text" },
            label = obj.text("value"),
            required = obj.bool("required"),
            width = (obj.field("width") as? JsonPrimitive)?.doubleOrNull,
            extras = obj.extras(COLUMN_KEYS),
        )
    }

    private fun parseCellRow(obj: JsonObject?, index: Int): CellRow? {
        if (obj == null) return null
        return CellRow(
            order = obj.int("order") ?: index,
            values = (obj.field("row") as? JsonArray).orEmpty().map { atom ->
                val cell = atom as? JsonObject
                CellValue(
                    value = cell?.numberAwareText("value").orEmpty(),
                    cssValue = cell?.text("css_value", "cssValue").orEmpty(),
                    attachment = cell?.text("attachment").orEmpty(),
                    extras = cell?.extras(ATOM_KEYS).orEmpty(),
                )
            },
            height = obj.int("height"),
            extras = obj.extras(LINE_KEYS),
        )
    }

    fun emit(payload: SheetPayload): JsonObject = buildJsonObject {
        putExtras(payload.extras)
        put("shared", emitShared(payload.shared))
        put("page_rows", buildJsonArray { payload.rows.forEach { row -> add(emitRow(row)) } })
    }

    private fun emitShared(shared: SharedHeader): JsonObject = buildJsonObject {
        putExtras(shared.extras)
        put("shootDayNumber", shared.shootDayNumber)
        put("totalDays", shared.totalDays)
        put("dayType", shared.dayType)
        // `put` returns the PREVIOUS mapping (null here), so an elvis chain
        // would run both branches and leave "" — explicit if.
        val date = shared.dateMs
        if (date != null) put("date", date) else put("date", "")
        if (shared.approverIdsStated || shared.approverIds.isNotEmpty()) {
            put("approverIds", shared.approverIds.toJsonArray())
        }
        put("internalReceiverIds", shared.internalReceiverIds.toJsonArray())
        shared.headerPosition?.let { put("headerPosition", it) }
        shared.approversPosition?.let { put("approversPosition", it) }
    }

    private fun emitRow(row: PageRow): JsonObject = buildJsonObject {
        putExtras(row.extras)
        put("order", row.order)
        put("break", row.isBreak)
        put("page_row_cells", buildJsonArray { row.cells.forEach { cell -> add(emitCell(cell)) } })
    }

    private fun emitCell(cell: PageCell): JsonObject = buildJsonObject {
        putExtras(cell.extras)
        put("order", cell.order)
        put("system_default", cell.systemDefault)
        put("type", cell.rawKind.ifBlank { cell.kind.wire })
        put("render_as", cell.rawRenderAs.ifBlank { cell.renderAs.wire.ifBlank { RenderKind.Generic.wire } })
        put("section_title", cell.title)
        put("hide_title", cell.hideTitle)
        if (cell.headerOrientation.isNotBlank()) {
            // The web editor writes both spellings; its preview reads either.
            put("header_orientation", cell.headerOrientation)
            put("headerOrientation", cell.headerOrientation)
        }
        if (cell.viewType.isNotBlank()) put("view_type", cell.viewType)
        put(
            "row_header",
            buildJsonArray {
                cell.columns.forEach { column ->
                    add(
                        buildJsonObject {
                            putExtras(column.extras)
                            put("type", column.type)
                            put("value", column.label)
                            put("required", column.required)
                            column.width?.let { put("width", it) }
                        },
                    )
                }
            },
        )
        put(
            "rows",
            buildJsonArray {
                cell.rows.forEach { line ->
                    add(
                        buildJsonObject {
                            putExtras(line.extras)
                            put("order", line.order)
                            line.height?.let { put("height", it) }
                            put(
                                "row",
                                buildJsonArray {
                                    line.values.forEachIndexed { column, atom ->
                                        add(emitAtom(atom, cell.columns.getOrNull(column)))
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    /** Epoch-valued columns go back out as numbers; everything else as text. */
    private fun emitAtom(atom: CellValue, column: ColumnSpec?): JsonObject = buildJsonObject {
        putExtras(atom.extras)
        val epoch = atom.value.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        if (column?.isEpochValued == true && epoch != null) {
            put("value", epoch)
        } else {
            put("value", atom.value)
        }
        put("css_value", atom.cssValue)
        put("attachment", atom.attachment)
    }

    // Lenient readers ------------------------------------------------------

    private fun JsonObject.field(vararg names: String): JsonElement? =
        names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

    private fun JsonObject.text(vararg names: String): String =
        (field(*names) as? JsonPrimitive)?.content.orEmpty()

    /** A value that may be a whole number written as a double (`1.7577E12`) reads as its digits. */
    private fun JsonObject.numberAwareText(name: String): String {
        val primitive = field(name) as? JsonPrimitive ?: return ""
        if (primitive.isString) return primitive.content
        primitive.longOrNull?.let { return it.toString() }
        val double = primitive.doubleOrNull
        return if (double != null && double == kotlin.math.floor(double) && !double.isInfinite()) {
            double.toLong().toString()
        } else {
            primitive.content
        }
    }

    private fun JsonObject.int(vararg names: String): Int? {
        val primitive = field(*names) as? JsonPrimitive ?: return null
        return primitive.longOrNull?.toInt() ?: primitive.doubleOrNull?.toInt() ?: primitive.content.toIntOrNull()
    }

    private fun JsonObject.epoch(vararg names: String): Long? {
        val primitive = field(*names) as? JsonPrimitive ?: return null
        return (primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: primitive.content.trim().toLongOrNull())
            ?.takeIf { it > 0 }
    }

    private fun JsonObject.bool(vararg names: String): Boolean =
        (field(*names) as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

    private fun JsonObject.textList(vararg names: String): List<String> =
        (field(*names) as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { id -> id.isNotBlank() } }

    private fun JsonObject.extras(known: Set<String>): Map<String, JsonElement> =
        filterKeys { it !in known }

    private fun JsonObjectBuilder.putExtras(extras: Map<String, JsonElement>) {
        extras.forEach { (key, value) -> put(key, value) }
    }

    private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
