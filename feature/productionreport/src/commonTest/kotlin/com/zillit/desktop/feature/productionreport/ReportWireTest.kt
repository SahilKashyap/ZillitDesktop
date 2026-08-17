package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.data.PayloadWire
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report payload's wire shape: wall-clock strings out, both spellings in,
 * legacy epochs folded on read and never re-emitted.
 */
class ReportWireTest {

    private val wire = """
        {
          "shared": {"shootDayNumber":"5","totalDays":"35","dayType":"SWD","date":"2026-08-14",
                     "approverIds":["a1"],"internalReceiverIds":[]},
          "page_rows": [
            {"order":0,"break":false,"page_row_cells":[
              {"order":0,"system_default":true,"type":"table","render_as":"employee",
               "section_title":"Camera","hide_title":false,
               "row_header":[{"type":"text","value":"Role"},{"type":"text","value":"Name"},
                             {"type":"time","value":"IN"},{"type":"time","value":"OUT"}],
               "rows":[{"order":0,"row":[
                 {"value":"Operator","css_value":"","attachment":""},
                 {"value":"Amy","css_value":"","attachment":""},
                 {"value":"6:30","css_value":"","attachment":""},
                 {"value":"Per HOD","css_value":"","attachment":""}]}]}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `dates stay strings and clocks normalise on the way out`() {
        val payload = PayloadWire.parse(Json.parseToJsonElement(wire))
        assertEquals("2026-08-14", payload.shared.dateYmd)

        val out = PayloadWire.emit(payload)
        val shared = out["shared"]!!.jsonObject
        assertTrue((shared["date"] as JsonPrimitive).isString)
        assertEquals("2026-08-14", (shared["date"] as JsonPrimitive).content)

        val atoms = out["page_rows"]!!.jsonArray[0].jsonObject["page_row_cells"]!!
            .jsonArray[0].jsonObject["rows"]!!.jsonArray[0].jsonObject["row"]!!.jsonArray
        val inValue = atoms[2].jsonObject["value"] as JsonPrimitive
        val outValue = atoms[3].jsonObject["value"] as JsonPrimitive
        assertTrue(inValue.isString && outValue.isString)
        assertEquals("Time:06:30", inValue.content, "a clock in an IN column becomes a Time: entry")
        assertEquals("Per HOD", outValue.content)
    }

    @Test
    fun `a legacy epoch date folds on read instead of surviving`() {
        val zone = TimeZone.currentSystemDefault()
        val legacy = wire.replace("\"2026-08-14\"", "1755129600000")

        val payload = PayloadWire.parse(Json.parseToJsonElement(legacy))

        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}""").matches(payload.shared.dateYmd),
            "epoch date must fold to $zone wall clock, got '${payload.shared.dateYmd}'",
        )
    }

    @Test
    fun `camelCased payloads read identically`() {
        val camel = """
            {"shared":{"shootDayNumber":"5","date":"2026-08-14"},
             "pageRows":[{"order":0,"pageRowCells":[
               {"order":0,"systemDefault":false,"type":"notes","renderAs":"generic",
                "sectionTitle":"Notes","hideTitle":false,
                "rowHeader":[{"type":"text","value":""}],
                "rows":[{"order":0,"row":[{"value":"wrapped early","cssValue":"","attachment":""}]}]}]}]}
        """.trimIndent()

        val payload = PayloadWire.parse(Json.parseToJsonElement(camel))

        assertEquals("Notes", payload.rows[0].cells[0].title)
        assertEquals("wrapped early", payload.rows[0].cells[0].rows[0].values[0].value)
    }
}
