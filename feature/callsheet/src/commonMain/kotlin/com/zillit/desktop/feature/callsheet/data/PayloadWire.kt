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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The payload codec.
 *
 * ## Why this is hand-written
 *
 * The wire is snake_case (`page_rows`, `row_header`, `css_value`) with one
 * camelCase island (`shared`), but the platform's response pipeline camelCases
 * **everything** — a payload loaded back arrives as `pageRows`/`rowHeader`.
 * So parsing accepts both spellings for every key, and emission always writes
 * the canonical snake_case shape the backend's PDF renderer expects.
 *
 * Values are typed by column: `date`/`time` columns carry epoch-ms NUMBERS,
 * everything else strings. The domain stores strings; the codec restores the
 * numeric encoding on the way out, or the server renders "1755129600000"
 * instead of a clock time.
 */
object PayloadWire {

    fun parse(element: JsonElement?): SheetPayload {
        val obj = element as? JsonObject ?: return SheetPayload()
        return SheetPayload(
            shared = parseShared(obj.field("shared") as? JsonObject),
            rows = (obj.field("page_rows", "pageRows") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { index, row -> parseRow(row as? JsonObject, index) },
        )
    }

    private fun parseShared(obj: JsonObject?): SharedHeader {
        if (obj == null) return SharedHeader()
        return SharedHeader(
            shootDayNumber = obj.text("shootDayNumber", "shoot_day_number"),
            totalDays = obj.text("totalDays", "total_days"),
            dayType = obj.text("dayType", "day_type").ifBlank { "SWD" },
            dateMs = obj.field("date")?.jsonPrimitiveOrNull?.longOrNull,
            approverIds = obj.textList("approverIds", "approver_ids"),
            internalReceiverIds = obj.textList("internalReceiverIds", "internal_receiver_ids"),
        )
    }

    private fun parseRow(obj: JsonObject?, index: Int): PageRow? {
        if (obj == null) return null
        return PageRow(
            order = obj.field("order")?.jsonPrimitiveOrNull?.longOrNull?.toInt() ?: index,
            isBreak = obj.bool("break"),
            cells = (obj.field("page_row_cells", "pageRowCells") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { cellIndex, cell -> parseCell(cell as? JsonObject, cellIndex) },
        )
    }

    private fun parseCell(obj: JsonObject?, index: Int): PageCell? {
        if (obj == null) return null
        val rawKind = obj.text("type")
        val rawRender = obj.text("render_as", "renderAs")
        return PageCell(
            order = obj.field("order")?.jsonPrimitiveOrNull?.longOrNull?.toInt() ?: index,
            systemDefault = obj.bool("system_default", "systemDefault"),
            kind = CellKind.fromWire(rawKind),
            renderAs = RenderKind.fromWire(rawRender),
            rawKind = rawKind,
            rawRenderAs = rawRender,
            title = obj.text("section_title", "sectionTitle"),
            hideTitle = obj.bool("hide_title", "hideTitle"),
            headerOrientation = obj.text("header_orientation", "headerOrientation"),
            columns = (obj.field("row_header", "rowHeader") as? JsonArray)
                .orEmpty()
                .mapNotNull { column -> parseColumn(column as? JsonObject) },
            rows = (obj.field("rows") as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { rowIndex, row -> parseCellRow(row as? JsonObject, rowIndex) },
        )
    }

    private fun parseColumn(obj: JsonObject?): ColumnSpec? {
        if (obj == null) return null
        return ColumnSpec(
            type = obj.text("type").ifBlank { "text" },
            label = obj.text("value"),
            required = obj.bool("required"),
            width = obj.field("width")?.jsonPrimitiveOrNull?.doubleValue,
        )
    }

    private fun parseCellRow(obj: JsonObject?, index: Int): CellRow? {
        if (obj == null) return null
        return CellRow(
            order = obj.field("order")?.jsonPrimitiveOrNull?.longOrNull?.toInt() ?: index,
            values = (obj.field("row") as? JsonArray).orEmpty().map { atom ->
                val cell = atom as? JsonObject
                CellValue(
                    value = cell?.text("value").orEmpty(),
                    cssValue = cell?.text("css_value", "cssValue").orEmpty(),
                    attachment = cell?.text("attachment").orEmpty(),
                )
            },
        )
    }

    fun emit(payload: SheetPayload): JsonObject = buildJsonObject {
        put(
            "shared",
            buildJsonObject {
                put("shootDayNumber", payload.shared.shootDayNumber)
                put("totalDays", payload.shared.totalDays)
                put("dayType", payload.shared.dayType)
                // `put` returns the PREVIOUS mapping (null here), so an elvis
                // chain would run both branches and leave "" — explicit if.
                val date = payload.shared.dateMs
                if (date != null) put("date", date) else put("date", "")
                put("approverIds", payload.shared.approverIds.toJsonArray())
                put("internalReceiverIds", payload.shared.internalReceiverIds.toJsonArray())
            },
        )
        put(
            "page_rows",
            buildJsonArray {
                payload.rows.forEach { row -> add(emitRow(row)) }
            },
        )
    }

    private fun emitRow(row: PageRow): JsonObject = buildJsonObject {
        put("order", row.order)
        put("break", row.isBreak)
        put(
            "page_row_cells",
            buildJsonArray { row.cells.forEach { cell -> add(emitCell(cell)) } },
        )
    }

    private fun emitCell(cell: PageCell): JsonObject = buildJsonObject {
        put("order", cell.order)
        put("system_default", cell.systemDefault)
        put("type", cell.rawKind.ifBlank { cell.kind.wire })
        put("render_as", cell.rawRenderAs.ifBlank { cell.renderAs.wire })
        put("section_title", cell.title)
        put("hide_title", cell.hideTitle)
        if (cell.headerOrientation.isNotBlank()) put("header_orientation", cell.headerOrientation)
        put(
            "row_header",
            buildJsonArray {
                cell.columns.forEach { column ->
                    add(
                        buildJsonObject {
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
                            put("order", line.order)
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
        val epoch = atom.value.toLongOrNull()
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
        field(*names)?.jsonPrimitiveOrNull?.content.orEmpty()

    private fun JsonObject.bool(vararg names: String): Boolean =
        field(*names)?.jsonPrimitiveOrNull?.content.equals("true", ignoreCase = true)

    private fun JsonObject.textList(vararg names: String): List<String> =
        (field(*names) as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitiveOrNull?.content }

    private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
        get() = this as? JsonPrimitive

    private val JsonPrimitive.doubleValue: Double?
        get() = content.toDoubleOrNull()

    private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
