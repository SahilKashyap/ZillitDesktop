package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.data.eventWire
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A picked place travels with its coordinates.
 *
 * The web sends `locationLat`/`locationLng` beside `location` on this exact
 * endpoint (`boxScheduleV2/components/CreateEventModal.jsx:540`, filled by
 * `PlacePicker.jsx:107-115`); the desktop used to send the text alone, so a
 * place picked on the map arrived at the server as words with no point on
 * the earth.
 */
class DiaryLocationWireTest {

    private fun draft(lat: Double? = null, lng: Double? = null) = DiaryDraft(
        kind = DiaryKind.Event,
        title = "Camera test",
        body = "",
        date = 1_787_000_000_000,
        startDateTime = 1_787_000_000_000,
        endDateTime = 1_787_003_600_000,
        fullDay = false,
        location = "Mehboob Studios, Mumbai",
        locationLat = lat,
        locationLng = lng,
    )

    @Test
    fun `a picked place sends its coordinates beside the text`() {
        val body = eventWire(draft(lat = 19.0607, lng = 72.8302), create = true)

        assertEquals("Mehboob Studios, Mumbai", body["location"]?.jsonPrimitive?.content)
        assertEquals(19.0607, body["locationLat"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(72.8302, body["locationLng"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `a typed place sends null coordinates, never a silent zero`() {
        val body = eventWire(draft(), create = true)

        // Null, not absent and not 0.0 — the web sends the keys as null, and
        // a zero would pin every typed location off the coast of Africa.
        assertTrue(body.containsKey("locationLat"))
        assertEquals(JsonNull, body["locationLat"])
        assertEquals(JsonNull, body["locationLng"])
    }
}
