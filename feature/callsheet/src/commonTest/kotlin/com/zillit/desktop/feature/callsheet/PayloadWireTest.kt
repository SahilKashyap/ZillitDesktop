package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.data.PayloadWire
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The payload's wire shape.
 *
 * Two spellings are read — the backend stores snake_case but the platform's
 * response normaliser camelCases everything — and exactly one is written.
 * Date/time cells are epoch-ms NUMBERS on the wire; a string there renders
 * as raw digits on the server's PDF.
 */
class PayloadWireTest {

    private val snake = """
        {
          "shared": {"shootDayNumber":"5","totalDays":"35","dayType":"SWD","date":1755129600000,
                     "approverIds":["a1"],"internalReceiverIds":[]},
          "page_rows": [
            {"order":0,"break":false,"page_row_cells":[
              {"order":0,"system_default":true,"type":"section","render_as":"generic",
               "section_title":"Call Times","hide_title":false,
               "row_header":[{"type":"text","value":"Field","required":false},
                             {"type":"time","value":"Value","required":false,"width":1.5}],
               "rows":[{"order":0,"row":[
                 {"value":"Shooting Call","css_value":"","attachment":""},
                 {"value":1755162000000,"css_value":"","attachment":""}]}]}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `snake case parses and emits byte-compatible structure`() {
        val payload = PayloadWire.parse(Json.parseToJsonElement(snake))

        assertEquals("5", payload.shared.shootDayNumber)
        assertEquals(1_755_129_600_000, payload.shared.dateMs)
        val cell = payload.rows[0].cells[0]
        assertEquals(CellKind.Section, cell.kind)
        assertEquals("Call Times", cell.title)
        assertEquals(1.5, cell.columns[1].width)
        assertEquals("1755162000000", cell.rows[0].values[1].value)

        val out = PayloadWire.emit(payload)
        val rows = out["page_rows"]!!.jsonArray
        val outCell = rows[0].jsonObject["page_row_cells"]!!.jsonArray[0].jsonObject
        assertEquals("Call Times", (outCell["section_title"] as JsonPrimitive).content)
        val atom = outCell["rows"]!!.jsonArray[0].jsonObject["row"]!!.jsonArray[1].jsonObject
        val value = atom["value"] as JsonPrimitive
        assertTrue(!value.isString, "a time cell went out as a string")
        assertEquals("1755162000000", value.content)
    }

    @Test
    fun `camelCased responses parse identically`() {
        val camel = """
            {"shared":{"shootDayNumber":"5"},
             "pageRows":[{"order":0,"pageRowCells":[
               {"order":0,"systemDefault":true,"type":"table","renderAs":"employee",
                "sectionTitle":"Camera","hideTitle":true,
                "rowHeader":[{"type":"text","value":"Name"}],
                "rows":[{"order":0,"row":[{"value":"Amy","cssValue":"x","attachment":""}]}]}]}]}
        """.trimIndent()

        val payload = PayloadWire.parse(Json.parseToJsonElement(camel))

        val cell = payload.rows[0].cells[0]
        assertEquals(RenderKind.Employee, cell.renderAs)
        assertEquals("Camera", cell.title)
        assertTrue(cell.systemDefault && cell.hideTitle)
        assertEquals("x", cell.rows[0].values[0].cssValue)
    }

    @Test
    fun `text cells never become numbers`() {
        val payload = PayloadWire.parse(Json.parseToJsonElement(snake))
        val out = PayloadWire.emit(payload)

        val atom = out["page_rows"]!!.jsonArray[0].jsonObject["page_row_cells"]!!
            .jsonArray[0].jsonObject["rows"]!!.jsonArray[0].jsonObject["row"]!!
            .jsonArray[0].jsonObject
        assertTrue((atom["value"] as JsonPrimitive).isString)
    }

    @Test
    fun `shared header emits camelCase with a numeric date`() {
        val payload = PayloadWire.parse(Json.parseToJsonElement(snake))
        val shared = PayloadWire.emit(payload)["shared"]!!.jsonObject

        assertEquals("5", (shared["shootDayNumber"] as JsonPrimitive).content)
        assertTrue(!(shared["date"] as JsonPrimitive).isString)
    }

    @Test
    fun `sheet time codec round-trips against the sheet date`() {
        val zone = TimeZone.of("Asia/Kolkata")
        val date = 1_755_129_600_000 // 2025-08-14 00:00 IST

        val encoded = SheetTime.encode("06:30", date, zone)
        assertEquals("06:30", SheetTime.display(encoded, zone))
        assertEquals("O/C", SheetTime.encode("O/C", date, zone), "legacy text must pass through")
        assertEquals("", SheetTime.encode("  ", date, zone))
    }
}
