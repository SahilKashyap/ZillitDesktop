package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
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
 * camelCase island (`shared`), but a payload loaded back can arrive
 * camelCased (`pageRows`/`rowHeader`). So parsing accepts both spellings for
 * every key, and emission always writes the canonical snake_case shape the
 * backend's PDF renderer and the web read.
 *
 * Keys this client does not model are kept per level and written back, so a
 * field another client adds is not lost by a desktop save.
 *
 * Times and dates are wall-clock strings (`HH:mm`, `YYYY-MM-DD`) with no
 * timezone; legacy epoch values in time/date columns are folded to the local
 * wall clock on the way out, and every other value is sent exactly as typed —
 * the web sends the document verbatim.
 */
@Suppress("TooManyFunctions") // One reader and one writer per level of the document.
object PayloadWire {

    private val ROOT_KEYS = setOf("shared", "page_rows", "pageRows")

    /** Presentation keys a stock template carries that must never enter a report. */
    private val TEMPLATE_ROOT_KEYS = setOf("id", "_id", "name", "identifier", "key", "displayName", "isCreateYourOwn")

    private val SHARED_KEYS = setOf(
        "shootDayNumber", "shoot_day_number", "totalDays", "total_days", "dayType", "day_type", "date",
        "approverIds", "approver_ids", "internalReceiverIds", "internal_receiver_ids",
        "headerPosition", "header_position", "approversPosition", "approvers_position",
        "reportType", "report_type", "secondADName", "second_ad_name",
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

    private fun parseShared(obj: JsonObject?): SharedHeader {
        if (obj == null) return SharedHeader()
        return SharedHeader(
            shootDayNumber = obj.text("shootDayNumber", "shoot_day_number"),
            totalDays = obj.text("totalDays", "total_days"),
            dayType = obj.text("dayType", "day_type"),
            dateYmd = ReportTime.toWireDate(obj.text("date")),
            approverIds = obj.textList("approverIds", "approver_ids"),
            internalReceiverIds = obj.textList("internalReceiverIds", "internal_receiver_ids"),
            headerPosition = obj.int("headerPosition", "header_position"),
            approversPosition = obj.int("approversPosition", "approvers_position"),
            reportType = obj.text("reportType", "report_type").trim().lowercase(),
            secondAdName = obj.text("secondADName", "second_ad_name"),
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
                    value = cell?.text("value").orEmpty(),
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
        put("date", shared.dateYmd)
        put("approverIds", shared.approverIds.toJsonArray())
        put("internalReceiverIds", shared.internalReceiverIds.toJsonArray())
        shared.headerPosition?.let { put("headerPosition", it) }
        shared.approversPosition?.let { put("approversPosition", it) }
        // Only the AD / Wrap kinds carry these; a production report's `shared`
        // stays byte-for-byte what the web writes.
        if (shared.reportType.isNotBlank()) put("reportType", shared.reportType)
        if (shared.secondAdName.isNotBlank()) put("secondADName", shared.secondAdName)
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
        if (cell.headerOrientation.isNotBlank()) put("header_orientation", cell.headerOrientation)
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

    /** A legacy epoch in a time or date column folds to wall clock; everything else goes out as typed. */
    private fun emitAtom(atom: CellValue, column: ColumnSpec?): JsonObject = buildJsonObject {
        putExtras(atom.extras)
        val value = when {
            !ReportTime.isLegacyEpoch(atom.value) -> atom.value
            column?.type == "time" -> ReportTime.toWireTime(atom.value)
            column?.type == "date" -> ReportTime.toWireDate(atom.value)
            else -> atom.value
        }
        put("value", value)
        put("css_value", atom.cssValue)
        put("attachment", atom.attachment)
    }

    // Lenient readers ------------------------------------------------------

    private fun JsonObject.field(vararg names: String): JsonElement? =
        names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

    private fun JsonObject.text(vararg names: String): String =
        (field(*names) as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.int(vararg names: String): Int? {
        val primitive = field(*names) as? JsonPrimitive ?: return null
        return primitive.longOrNull?.toInt() ?: primitive.doubleOrNull?.toInt() ?: primitive.content.toIntOrNull()
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
