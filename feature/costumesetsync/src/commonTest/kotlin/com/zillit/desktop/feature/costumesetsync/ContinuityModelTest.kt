package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.ContinuityModel
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

class ContinuityModelTest {
    private fun character(vararg statuses: String): Rec {
        val items = statuses.joinToString(",") { """{"costume":{"name":"P-$it","status":"$it"}}""" }
        return rec("""{"change":{"items":[$items]}}""")
    }

    @Test
    fun `no change is not assigned`() {
        val r = ContinuityModel.readiness(rec("{}"))
        assertEquals("NOT_ASSIGNED", r.level)
        assertEquals("no_change", r.blockers.single().key)
    }

    @Test
    fun `a change with no pieces says so`() {
        assertEquals("no_pieces", ContinuityModel.readiness(rec("""{"change":{"items":[]}}""")).blockers.single().key)
    }

    @Test
    fun `the worst piece sets the level and lists blockers`() {
        val r = ContinuityModel.readiness(character("AVAILABLE", "CLEANING", "MISSING"))
        assertEquals("MISSING", r.level)
        assertEquals(1, r.ready)
        assertEquals(3, r.total)
        assertEquals(listOf("P-CLEANING", "P-MISSING"), r.blockers.map { it.name })
    }

    @Test
    fun `issued and on set count as ready`() {
        assertEquals("READY", ContinuityModel.readiness(character("ISSUED", "ON_SET")).level)
    }

    @Test
    fun `day key round trips local midnight`() {
        val ms = DayKeys.toMs("2026-09-29")
        assertEquals("2026-09-29", DayKeys.of(ms))
        assertEquals("", DayKeys.of(0))
        assertEquals(0L, DayKeys.toMs("29/09/2026"))
    }

    @Test
    fun `history lists past days and days with takes only, newest first`() {
        val past = DayKeys.toMs("2026-09-01")
        val today = DayKeys.toMs("2026-09-10")
        val future = DayKeys.toMs("2026-09-20")
        val scenes = listOf(
            rec("""{"_id":"a","shoot_date":$past}"""),
            rec("""{"_id":"b","shoot_date":$today}"""),
            rec("""{"_id":"c","shoot_date":$future}"""),
            rec("""{"_id":"d","shoot_date":$past,"status":"OMITTED"}"""),
        )
        val records = listOf(rec("""{"scene_id":"b","take_number":1}"""), rec("""{"scene_id":"a","take_number":1}"""))
        val days = ContinuityModel.shootDays(scenes, records, "2026-09-10")
        assertEquals(listOf("2026-09-10", "2026-09-01"), days.map { it.day })
        assertEquals(listOf(1, 1), days.map { it.takes })
    }

    @Test
    fun `prep day is today else the next scheduled day`() {
        val scenes = listOf(rec("""{"shoot_date":${DayKeys.toMs("2026-09-12")}}"""))
        assertEquals("2026-09-12", ContinuityModel.nextPrepDay(scenes, "2026-09-10"))
        assertEquals("2026-09-12", ContinuityModel.nextPrepDay(scenes, "2026-09-12"))
        assertEquals("2026-09-20", ContinuityModel.nextPrepDay(scenes, "2026-09-20"))
    }

    @Test
    fun `a new take starts from the last one`() {
        val last = rec(
            """{"take_number":3,"details":{"Shirt":"Blue"},"accessories":[{"name":"Watch","present":false}]}""",
        )
        val fill = ContinuityModel.fill(last, null)
        assertEquals("4", fill.takeNumber)
        assertEquals(listOf("Shirt" to "Blue"), fill.details)
        assertEquals(listOf("Watch" to false), fill.accessories)
    }

    @Test
    fun `a first take seeds the defaults and the accessory pieces`() {
        val sc = rec(
            """{"change":{"items":[{"costume":{"name":"Ring","category":"JEWELLERY"}},""" +
                """{"costume":{"name":"Coat","category":"OUTERWEAR"}}]}}""",
        )
        val fill = ContinuityModel.fill(null, sc)
        assertEquals("1", fill.takeNumber)
        assertEquals(ContinuityModel.DEFAULT_DETAILS, fill.details.map { it.first })
        assertEquals(listOf("Ring" to true), fill.accessories)
    }
}
