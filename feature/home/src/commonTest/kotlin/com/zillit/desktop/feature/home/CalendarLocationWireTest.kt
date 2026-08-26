package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.eventBody
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * A picked place keeps its coordinates all the way to the server.
 *
 * The web sends `location: {lat, long}` beside `location_description`
 * (`pages/home/newCalendar/AddCalendarEvent.jsx:433-438`) — note the wire's
 * `long`, not `lng`. The desktop used to send the words alone, so an event
 * whose location was picked on a map arrived without a point on the earth.
 */
class CalendarLocationWireTest {

    private val zone = TimeZone.of("Asia/Kolkata")
    private val times = EventTimes(1_787_000_000_000, 1_787_003_600_000)

    private fun draft(lat: Double? = null, lng: Double? = null) = EventDraft(
        title = "Camera test",
        location = "Mehboob Studios, Bandra West, Mumbai",
        locationLat = lat,
        locationLng = lng,
    )

    @Test
    fun `a picked place travels as the web's lat-long object`() {
        val body = eventBody(draft(lat = 19.0607, lng = 72.8302), times, zone)

        assertEquals(
            "Mehboob Studios, Bandra West, Mumbai",
            body["location_description"]?.jsonPrimitive?.content,
        )
        val point = body["location"] as? JsonObject
        assertNotNull(point, "a picked place must send its coordinates")
        assertEquals(19.0607, point["lat"]?.jsonPrimitive?.content?.toDouble())
        // `long`, the wire's spelling — `lng` would be silently dropped.
        assertEquals(72.8302, point["long"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `a typed place sends no point at all`() {
        val body = eventBody(draft(), times, zone)

        // Omitted rather than null-or-zero: half a point is worse than none,
        // and (0, 0) is a real place in the Atlantic.
        assertFalse(body.containsKey("location"))
        assertEquals(
            "Mehboob Studios, Bandra West, Mumbai",
            body["location_description"]?.jsonPrimitive?.content,
        )
    }
}
