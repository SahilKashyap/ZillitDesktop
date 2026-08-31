package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.data.locationWire
import com.zillit.desktop.feature.maps.data.zoneWire
import com.zillit.desktop.feature.maps.domain.Geo
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapWireTest {

    @Test
    fun `location body uses long not lng and names not ids`() {
        val body = locationWire(
            LocationDraft(
                cityId = "c1", name = " Unit Base ", type = "Base Camp", subTypes = listOf("Trucks"),
                address = " 1 Sound Stage Rd ", sceneNumber = "12", lat = 19.07, lng = 72.87,
            ),
            id = null,
        )

        assertEquals("Unit Base", (body["location_name"] as JsonPrimitive).content)
        assertEquals("Base Camp", (body["location_type"] as JsonPrimitive).content)
        val point = body["location"]!!.jsonObject
        assertEquals("72.87", (point["long"] as JsonPrimitive).content)
        assertNull(point["lng"], "the wire key is `long`")
        assertEquals("false", (body["is_studio_zone"] as JsonPrimitive).content)
        assertEquals("false", (body["distribute"] as JsonPrimitive).content)
        assertNull(body["location_id"])
        assertNull(body["attachments"], "create omits the attachments array")
    }

    @Test
    fun `edit carries the id and an attachments array`() {
        val body = locationWire(
            LocationDraft(cityId = "c1", name = "x", type = "Other", address = "a", lat = 1.0, lng = 2.0),
            id = "loc-9",
        )
        assertEquals("loc-9", (body["location_id"] as JsonPrimitive).content)
        assertTrue("attachments" in body)
    }

    @Test
    fun `zone body is a circle in miles`() {
        val body = zoneWire(ZoneDraft("c1", "Mumbai Zone", 19.0, 72.0, 30.0), id = null)
        assertEquals("true", (body["is_studio_zone"] as JsonPrimitive).content)
        assertEquals("30.0", (body["miles"] as JsonPrimitive).content)
        assertEquals("point", (body["center_point_type"] as JsonPrimitive).content)
    }

    @Test
    fun `haversine matches known distances and containment`() {
        // Mumbai CST → Pune station is roughly 120 km.
        val d = Geo.distanceMeters(18.9398, 72.8355, 18.5286, 73.8743)
        assertTrue(d in 115_000.0..125_000.0, "got $d")
        assertTrue(Geo.within(18.95, 72.84, 18.9398, 72.8355, 30.0))
        assertFalse(Geo.within(18.5286, 73.8743, 18.9398, 72.8355, 30.0))
        assertEquals(0.0, Geo.distanceMeters(1.0, 1.0, 1.0, 1.0))
    }
    /**
     * Rights as issued: owning the production does not put pins on its map.
     *
     * Android reads the tool's row and nothing else — `if (viewAccess ==
     * false) finish() else hasPermission = postingAccess` — the web's map
     * module never mentions `is_admin`, and `MAP_TOOL` is not among the tools
     * iOS admin-excepts for download. This port had `isAdmin || canPost`.
     */
    @Test
    fun `a project admin does not inherit map editing`() {
        val admin = MapViewer(canView = false, canPost = false, isAdmin = true, ready = true)

        assertFalse(admin.mayEdit, "an admin without posting rights could edit the map")
        assertTrue(admin.isBlocked, "an admin without view rights still opened the map")
    }

}
