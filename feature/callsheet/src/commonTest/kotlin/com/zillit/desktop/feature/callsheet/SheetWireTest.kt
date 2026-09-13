package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.data.PayloadWire
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The call sheet's payload codec: epoch numbers in `date` / `time` columns
 * and `shared.date`, the crew "In" column as a prefixed STRING, both key
 * spellings on the way in, snake_case on the way out, extras preserved.
 */
class SheetWireTest {

    private val wire = """
        {
          "shared": {"shootDayNumber": "5", "totalDays": "35", "dayType": "SWD", "date": 1755129600000,
                     "approverIds": ["a1"], "internalReceiverIds": [], "headerPosition": 0, "approversPosition": 3,
                     "currentScript": "v3"},
          "page_rows": [
            {"order": 0, "break": false, "page_row_cells": [
              {"order": 0, "system_default": true, "type": "section", "render_as": "generic",
               "section_title": "Call Times", "hide_title": false,
               "row_header": [{"type": "text", "value": "Field", "required": false},
                              {"type": "time", "value": "Value", "required": false}],
               "rows": [{"order": 0, "row": [{"value": "Shooting Call", "css_value": "", "attachment": ""},
                                             {"value": 1755155400000, "css_value": "", "attachment": ""}]}]},
              {"order": 1, "type": "table", "render_as": "employee", "section_title": "Camera",
               "row_header": [{"type": "text", "value": "Name"}, {"type": "text", "value": "In"}],
               "rows": [{"order": 0, "row": [{"value": "Ada"}, {"value": "Time:1755155400000"}]}],
               "colour": "teal"}
            ]}
          ],
          "version": 2
        }
    """.trimIndent()

    @Test
    fun `epochs read as digits and go back out as numbers, In stays a string`() {
        val payload = PayloadWire.parse(Json.parseToJsonElement(wire))
        assertEquals(1755129600000L, payload.shared.dateMs)
        assertEquals("1755155400000", payload.rows[0].cells[0].rows[0].values[1].value)
        assertEquals("Time:1755155400000", payload.rows[0].cells[1].rows[0].values[1].value)
        assertEquals(RenderKind.Employee, payload.rows[0].cells[1].renderAs)
        assertEquals(CellKind.Table, payload.rows[0].cells[1].kind)

        val out = PayloadWire.emit(payload)
        val shared = out["shared"]!!.jsonObject
        assertFalse((shared["date"] as JsonPrimitive).isString, "shared.date must be an epoch number")
        assertEquals("v3", shared["currentScript"]!!.jsonPrimitive.content, "unknown shared keys survive")
        val cells = out["page_rows"]!!.jsonArray[0].jsonObject["page_row_cells"]!!.jsonArray
        val time = cells[0].jsonObject["rows"]!!.jsonArray[0].jsonObject["row"]!!.jsonArray[1].jsonObject["value"]
        assertFalse((time as JsonPrimitive).isString, "a time column's epoch goes out as a number")
        val inValue = cells[1].jsonObject["rows"]!!.jsonArray[0].jsonObject["row"]!!.jsonArray[1].jsonObject["value"]
        assertTrue((inValue as JsonPrimitive).isString, "the In column is a prefixed string, never a number")
        assertEquals("teal", cells[1].jsonObject["colour"]!!.jsonPrimitive.content, "unknown cell keys survive")
        assertEquals("2", out["version"]!!.jsonPrimitive.content, "unknown root keys survive")
    }

    @Test
    fun `a camelCased answer reads the same as the stored spelling`() {
        val camel = wire
            .replace("page_rows", "pageRows")
            .replace("page_row_cells", "pageRowCells")
            .replace("row_header", "rowHeader")
            .replace("section_title", "sectionTitle")
            .replace("render_as", "renderAs")
            .replace("system_default", "systemDefault")
            .replace("css_value", "cssValue")
        val stored = PayloadWire.parse(Json.parseToJsonElement(wire))
        val normalised = PayloadWire.parse(Json.parseToJsonElement(camel))
        assertEquals(stored.rows, normalised.rows)
        assertEquals(stored.shared, normalised.shared)
    }

    @Test
    fun `an absent approver list is not an empty one`() {
        val stated = PayloadWire.parse(Json.parseToJsonElement(wire))
        assertTrue(stated.shared.approverIdsStated)
        val absent = PayloadWire.parse(Json.parseToJsonElement(wire.replace("\"approverIds\": [\"a1\"],", "")))
        assertFalse(absent.shared.approverIdsStated)
        assertFalse(PayloadWire.emit(absent)["shared"]!!.jsonObject.containsKey("approverIds"))
    }

    @Test
    fun `a listing row without cells is told apart from one with them`() {
        assertTrue(PayloadWire.hasCells(Json.parseToJsonElement(wire)))
        val slim = """{"page_rows": [{"order": 0, "page_row_cells": []}]}"""
        assertFalse(PayloadWire.hasCells(Json.parseToJsonElement(slim)))
    }
}
