package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.ReportsModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

class ReportsModelTest {
    @Test
    fun `quantity suffix marks more than one piece`() {
        assertEquals(" ×6", ReportsModel.qtySuffix(6))
        assertEquals("", ReportsModel.qtySuffix(1))
        assertEquals("", ReportsModel.qtySuffix(0))
    }

    @Test
    fun `scene cast lists each character with their change`() {
        val scene = rec("""{"characters":[{"name":"Meera","change":"#2 Wedding"},{"name":"Arjun","change":"—"}]}""")
        assertEquals("Meera: #2 Wedding · Arjun: —", ReportsModel.sceneCast(scene))
        assertEquals("", ReportsModel.sceneCast(rec("{}")))
    }

    @Test
    fun `wrap groups keep the service order`() {
        val groups = ReportsModel.wrapGroups(rec("""{"total":3,"groups":{"PURCHASED":[{"_id":"a"},{"_id":"b"}],"RENTED":[{"_id":"c"}]}}"""))
        assertEquals(listOf("PURCHASED" to 2, "RENTED" to 1), groups.map { it.first to it.second.size })
        assertEquals(emptyList(), ReportsModel.wrapGroups(null))
    }

    @Test
    fun `csv escapes commas quotes and newlines`() {
        val csv = ReportsModel.toCsv(listOf(mapOf("a" to "x, y", "b" to "say \"hi\"", "c" to "one\ntwo", "d" to "")), listOf("a", "b", "c", "d"))
        assertEquals("a,b,c,d\n\"x, y\",\"say \"\"hi\"\"\",\"one\ntwo\",", csv)
        assertEquals("", ReportsModel.toCsv(emptyList(), listOf("a")))
    }

    @Test
    fun `inventory csv hides the cost unless finance`() {
        val row = rec("""{"asset":"CST-000004","name":"Gold bangles","category":"JEWELLERY","quantity":6,"source":"PURCHASED","purchase_cost":4500,"status":"AVAILABLE"}""")
        val without = ReportsModel.inventoryCsv(listOf(row), withCost = false).lines()
        assertEquals(ReportsModel.INVENTORY_CSV_COLUMNS.joinToString(","), without[0])
        assertEquals("CST-000004,Gold bangles,JEWELLERY,,,,,6,,PURCHASED,,,AVAILABLE,", without[1])
        assertEquals("CST-000004,Gold bangles,JEWELLERY,,,,,6,,PURCHASED,,4500,AVAILABLE,", ReportsModel.inventoryCsv(listOf(row), withCost = true).lines()[1])
    }

    @Test
    fun `daily csv has a row per scene and ticket`() {
        val data = rec("""{"scenes":[{"number":"4","name":"Kitchen","status":"READY","characters":[{"name":"A","change":"#1"}]}],"missing":[{"costume":{"asset_number":"CST-1","name":"Hat"},"last_seen_location":"Set"}]}""")
        val lines = ReportsModel.dailyCsv(data).lines()
        assertEquals(3, lines.size)
        assertEquals("Scene,4,Kitchen,READY,A: #1", lines[1])
        assertEquals("Missing,CST-1,Hat,MISSING,Set", lines[2])
    }
}
