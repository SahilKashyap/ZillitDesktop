@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.crewdood

import com.zillit.desktop.feature.crewdood.data.CrewDoodWire
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the four reads say, and the two zones they say it in. */
class CrewDoodWireTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    private fun utc(stamp: String) = Instant.parse(stamp).toEpochMilliseconds()

    /** A local midnight, as the Production Calendar stores one — in this machine's zone. */
    private fun localMidnight(date: String): Long =
        LocalDate.parse(date).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    @Test
    fun `a deal's dates are UTC days and the last pay day is read from either key`() {
        val deals = CrewDoodWire.deals(
            parse(
                """[
                  {"_id":"d1","status":"active","user_id":"u1",
                   "deal":{"start_date":${utc("2026-04-01T00:00:00Z")},"end_date":${utc("2026-04-08T23:59:59Z")}}},
                  {"_id":"d2","status":"deactivated","user_id":"u2",
                   "deal":{"start_date":${utc("2026-04-01T00:00:00Z")}},"last_pay_date":${utc("2026-04-03T00:00:00Z")}},
                  {"_id":"d3","status":"active","user_id":"u3",
                   "deal":{"start_date":${utc("2026-04-01T00:00:00Z")}},"last_pay_day":${utc("2026-04-05T00:00:00Z")}}
                ]""",
            ),
        )
        assertEquals(LocalDate.parse("2026-04-01"), deals[0].start)
        assertEquals(LocalDate.parse("2026-04-08"), deals[0].end)
        // `last_pay_date` is what the deactivate endpoint stamps; the web's
        // model reads only `last_pay_day`, the notice template's name for it.
        assertEquals(LocalDate.parse("2026-04-03"), deals[1].lastPayDay)
        assertEquals(LocalDate.parse("2026-04-05"), deals[2].lastPayDay)
    }

    @Test
    fun `a deal names its person and its department by whichever key it carries`() {
        val deals = CrewDoodWire.deals(
            parse(
                """[{"_id":"d1","status":"active","deal_reference":"DM-0031","department_id":"dep-top",
                     "crew_details":{"full_legal_name":"Zed Someone","custom_designation":"Steadicam",
                       "department_identifier":"department_camera","designation_identifier":"designation_steadicam"},
                     "deal":{"start_date":1}}]""",
            ),
        )
        val deal = deals.single()
        assertEquals("Zed Someone", deal.crewName)
        assertEquals("Steadicam", deal.customDesignation)
        assertEquals(listOf("dep-top", "department_camera"), deal.departmentRefs)
        assertEquals(listOf("designation_steadicam"), deal.designationRefs)
        assertEquals("DM-0031", deal.reference)
    }

    @Test
    fun `the shoot days come from the production_dates entry, as local days`() {
        val days = CrewDoodWire.shootDays(
            parse(
                """[{"identifier":"pre_production_dates","production_dates":[{"activity_date":${localMidnight("2026-03-01")}}]},
                    {"identifier":"production_dates","production_dates":[
                      {"activity_date":${localMidnight("2026-04-07")}},
                      {"activity_date":${localMidnight("2026-04-06")}},
                      {"activity_date":${localMidnight("2026-04-06")}}]}]""",
            ),
        )
        assertEquals(listOf(LocalDate.parse("2026-04-06"), LocalDate.parse("2026-04-07")), days)
    }

    @Test
    fun `no production_dates entry means no shoot days`() {
        assertTrue(CrewDoodWire.shootDays(parse("""[{"identifier":"other"}]""")).isEmpty())
    }

    @Test
    fun `the crew list keeps its order and translates its label keys`() {
        val units = CrewDoodWire.units(
            parse(
                """[{"unit_name":"main_unit_label","departments":[
                     {"department_name":"camera_label","users":[
                       {"user_id":"u1","full_name":"Ann","designation_name":"dop_label",
                        "profile_picture":{"thumbnail":"thumb.jpg","media":"full.jpg"}}]}]}]""",
            ),
            labelOf = { key -> key.removeSuffix("_label").uppercase() },
        )
        val department = units.single().departments.single()
        assertEquals("CAMERA", department.name)
        assertEquals(listOf("camera_label"), department.aliases)
        assertEquals("DOP", department.users.single().role)
        assertEquals("thumb.jpg", department.users.single().picture)
    }

    @Test
    fun `the calendar reads weekly days off and plain-string holidays`() {
        val calendar = CrewDoodWire.calendar(
            parse("""{"weekly_off":[0,6],"holidays":[{"date":"2026-04-02","name":"Founders Day"},{"date":"nonsense"}]}"""),
        )
        assertEquals(setOf(0, 6), calendar?.weeklyOff)
        assertEquals(mapOf(LocalDate.parse("2026-04-02") to "Founders Day"), calendar?.holidays)
    }

    @Test
    fun `nothing saved reads as no calendar, which the model draws as Saturday and Sunday`() {
        assertNull(CrewDoodWire.calendar(parse("""{}""")))
        assertNull(CrewDoodWire.calendar(null))
        // A saved empty week means what it says, and is not the default.
        assertEquals(emptySet(), CrewDoodWire.calendar(parse("""{"weekly_off":[]}"""))?.weeklyOff)
    }

    @Test
    fun `a pushed calendar carries its stamp, and only a stamped older one is kept out`() {
        // The hub's envelope: the calendar sits in `data`, the project at the top.
        val pushed = CrewDoodWire.calendarPush(
            parse("""{"project_id":"p1","data":{"weekly_off":[0,5,6],"holidays":[],"updated_at":2000}}"""),
        )
        assertEquals(setOf(0, 5, 6), pushed?.weeklyOff)
        assertEquals(2000L, pushed?.updatedAt)
        // A read carries the stamp too, so the two can be ordered.
        assertEquals(1000L, CrewDoodWire.calendar(parse("""{"weekly_off":[0,6],"updated_at":1000}"""))?.updatedAt)

        // A bare frame is read too, in case a service sends one.
        val older = CrewDoodWire.calendarPush(parse("""{"weekly_off":[0,6],"updated_at":1000}"""))
        assertTrue(pushed!!.isNewerThan(older))
        assertFalse(older!!.isNewerThan(pushed))
        // An unstamped calendar is one nobody can order: it is never the older.
        assertTrue(DoodCalendar().isNewerThan(pushed))
        assertTrue(pushed.isNewerThan(DoodCalendar()))
    }

    @Test
    fun `a frame with no weekly days off in it is not a calendar`() {
        // Not "nobody has a day off": the screen reads the calendar instead.
        assertNull(CrewDoodWire.calendarPush(parse("""{"project_id":"p1"}""")))
        assertNull(CrewDoodWire.calendarPush(parse("""{"project_id":"p1","data":{}}""")))
        assertNull(CrewDoodWire.calendarPush(parse("""{"holidays":[{"date":"2026-04-02","name":"X"}]}""")))
        assertNull(CrewDoodWire.calendarPush(null))
        // A saved empty week IS a calendar, and says what it says.
        assertEquals(emptySet(), CrewDoodWire.calendarPush(parse("""{"weekly_off":[]}"""))?.weeklyOff)
    }

    @Test
    fun `a save sends weekly_off and one holiday entry per day`() {
        val body = CrewDoodWire.calendarBody(
            DoodCalendar(
                weeklyOff = setOf(6, 0),
                holidays = mapOf(
                    LocalDate.parse("2026-12-25") to "Christmas ",
                    LocalDate.parse("2026-04-02") to "Founders Day",
                ),
            ),
        )
        assertEquals(listOf(0, 6), (body["weekly_off"] as JsonArray).map { it.jsonPrimitive.content.toInt() })
        val holidays = body["holidays"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-04-02", "2026-12-25"), holidays.map { it["date"]!!.jsonPrimitive.content })
        assertEquals("Christmas", holidays[1]["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a body that is not a list reads as nothing rather than throwing`() {
        assertTrue(CrewDoodWire.deals(parse("""{"message":"no"}""")).isEmpty())
        assertTrue(CrewDoodWire.units(parse("null"), labelOf = { it }).isEmpty())
        assertTrue(CrewDoodWire.directory(parse("""{}"""), labelOf = { it }).isEmpty())
    }

    @Test
    fun `the department master carries its designations and their aliases`() {
        val directory = CrewDoodWire.directory(
            parse(
                """[{"_id":"dep-1","department_name":"camera_label","identifier":"department_camera",
                     "designations":[{"_id":"des-1","designation_name":"steadicam_label","identifier":"designation_steadicam"}]}]""",
            ),
            labelOf = { key -> key.removeSuffix("_label") },
        )
        val department = directory.single()
        assertEquals("camera", department.name)
        assertTrue("department_camera" in department.aliases)
        assertTrue("dep-1" in department.aliases)
        assertEquals("steadicam", department.designations.single().name)
        assertTrue("designation_steadicam" in department.designations.single().aliases)
    }
}
