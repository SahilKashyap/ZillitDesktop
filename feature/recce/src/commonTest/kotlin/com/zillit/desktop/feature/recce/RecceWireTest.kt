package com.zillit.desktop.feature.recce

import com.zillit.desktop.feature.recce.data.parseRecce
import com.zillit.desktop.feature.recce.data.recceWire
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceDraft
import com.zillit.desktop.feature.recce.domain.ReccePerson
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.domain.Weather
import com.zillit.desktop.feature.recce.ui.pages.parseLatLngFromUrl
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecceWireTest {

    private val draft = RecceDraft(
        uniqueId = "u-1",
        timezone = "Europe/London",
        title = " Studio recce ",
        unit = "unit-1",
        dateMs = 1_700_000_000_000L,
        station = "Waterloo",
        weather = "12°C – 18°C, light cloud",
        crewNote = "Bring boots",
        rdv = RecceStop(timeMs = 1L, place = "Gate 4", lat = 51.5, long = -0.12),
        itinerary = listOf(
            RecceStop(kind = StopKind.Lunch, place = "Cafe"),
            RecceStop(kind = StopKind.End, lat = 0.0, long = 0.0),
        ),
        personnel = listOf(
            ReccePerson(userId = null, name = "Guest"),
            ReccePerson(userId = "u9", name = "Sam", role = "1st AD"),
        ),
        status = RecceStatus.Draft,
    )

    @Test
    fun `body follows the web's buildPayload key for key`() {
        val body = recceWire(draft, id = null)
        assertEquals(
            listOf(
                "unique_id",
                "timezone",
                "title",
                "unit",
                "recce_date",
                "station",
                "weather",
                "crew_note",
                "rdv",
                "itinerary",
                "personnel",
                "status",
            ),
            body.keys.toList(),
        )
        assertEquals("Studio recce", body["title"]!!.jsonPrimitive.content)
        assertEquals("draft", body["status"]!!.jsonPrimitive.content)
        val rdv = body["rdv"]!!.jsonObject
        assertEquals("-0.12", rdv["long"]!!.jsonPrimitive.content)
        val stops = body["itinerary"]!!.jsonArray
        assertEquals("Lunch", stops[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, stops[0].jsonObject["lat"])
        assertEquals("0.0", stops[1].jsonObject["lat"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, stops[0].jsonObject["photo"])
        assertEquals("0", stops[0].jsonObject["end_time"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["personnel"]!!.jsonArray[0].jsonObject["user_id"])
    }

    @Test
    fun `update appends recce_id last`() {
        val body = recceWire(draft, id = "r-7")
        assertEquals("recce_id", body.keys.last())
        assertEquals("r-7", body["recce_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `record parses with lenient longitude and version`() {
        val json = Json.parseToJsonElement(
            """{"_id":"r1","title":"T","unit":"u","recce_date":5,"rdv":{"time":9,"place":"P","lat":1.5,"long":2.5},
               "itinerary":[{"time":0,"kind":"Start","lng":3.5,"lat":4.5}],"personnel":[{"user_id":null,"name":"A"}],
               "status":"published","version":3}""",
        ) as JsonObject
        val recce = parseRecce(json)!!
        assertEquals(RecceStatus.Published, recce.status)
        assertEquals(3, recce.version)
        assertEquals(2.5, recce.rdv.long)
        assertEquals(3.5, recce.itinerary[0].long)
        assertNull(recce.personnel[0].userId)
    }

    @Test
    fun `weather round-trips the web's composed string`() {
        val w = Weather.parse("12°C – 18°C, light cloud")
        assertEquals(Weather("12", "18", "C", "light cloud"), w)
        assertEquals("12°C – 18°C, light cloud", w.compose())
        assertEquals(Weather(conditions = "sunny"), Weather.parse("sunny"))
        assertEquals("20°F", Weather(low = "20", unit = "F").compose())
    }

    @Test
    fun `clock is local midnight and local wall clock`() {
        val zone = TimeZone.of("Europe/London")
        val day = RecceClock.dayMillis("2026-03-06", zone)
        assertEquals("2026-03-06", RecceClock.ymd(day, zone))
        val at = RecceClock.clockMillis("2026-03-06", "08:30", zone)
        assertEquals("08:30", RecceClock.hm(at, zone))
        assertEquals(0L, RecceClock.clockMillis("2026-03-06", "", zone))
        // A clock without a day pins to 1970-01-01, the web's fallback.
        assertTrue(RecceClock.clockMillis("", "08:30", zone) < 86_400_000L)
        assertEquals("Fri, 6 Mar 2026", RecceClock.dateLabel(day, zone))
        assertEquals("Friday, 6th March 2026", RecceClock.longDateLabel(day, zone))
    }

    @Test
    fun `maps links yield coordinates`() {
        assertEquals(51.5 to -0.12, parseLatLngFromUrl("https://www.google.com/maps/@51.5,-0.12,15z"))
        assertEquals(1.0 to 2.0, parseLatLngFromUrl("https://maps.google.com/?q=1.0,2.0"))
        assertEquals(3.25 to 4.5, parseLatLngFromUrl("https://www.google.com/maps/place/x/!3d3.25!4d4.5"))
        assertNull(parseLatLngFromUrl("https://goo.gl/maps/abc"))
    }
}
