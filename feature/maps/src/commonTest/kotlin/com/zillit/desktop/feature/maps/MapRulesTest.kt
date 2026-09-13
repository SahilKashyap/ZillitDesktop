package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.domain.GeoComponent
import com.zillit.desktop.feature.maps.domain.GeocodedPlace
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationRules
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.ZoneRules
import com.zillit.desktop.feature.maps.domain.buildDirectionsShare
import com.zillit.desktop.feature.maps.domain.buildLocationShare
import com.zillit.desktop.feature.maps.domain.isWithinSelectedCity
import com.zillit.desktop.feature.maps.domain.jsNumber
import com.zillit.desktop.feature.maps.domain.pickAutoCity
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.domain.typeStyle
import com.zillit.desktop.feature.maps.domain.zoneLocationCounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** City selection, share text, type styles and the form rules — the web's pure helpers. */
class MapRulesTest {

    private fun city(id: String) = MapCity(id = id, name = "City $id")

    @Test
    fun `the auto-selection ladder`() {
        assertNull(pickAutoCity(emptyList(), "x"))
        val one = listOf(city("solo"))
        assertEquals(one[0], pickAutoCity(one, "someone-else"))
        val three = listOf(city("a"), city("b"), city("c"))
        assertEquals(three[1], pickAutoCity(three, "b"))
        assertEquals(three[0], pickAutoCity(three, "deleted-city"))
        assertEquals(three[0], pickAutoCity(three, null))
    }

    private val base = MapLocation(
        id = "l",
        cityId = "c",
        name = "Base Camp",
        address = "12 Marine Drive, Mumbai",
        point = LatLng(19.0, 72.8),
    )

    @Test
    fun `the location share matches the spec template exactly`() {
        val share = buildLocationShare(base)!!
        assertEquals(
            listOf(
                "📍 Base Camp",
                "12 Marine Drive, Mumbai",
                "19, 72.8",
                "View: https://www.google.com/maps/search/?api=1&query=19,72.8",
            ).joinToString("\n"),
            share.text,
        )
        assertFalse(share.url.contains("key="))
    }

    @Test
    fun `a share omits the empty address line and needs coordinates`() {
        val text = buildLocationShare(base.copy(address = ""))!!.text
        assertFalse(text.contains("\n\n"))
        assertEquals(3, text.split("\n").size)
        assertNull(buildLocationShare(base.copy(point = null)))
    }

    @Test
    fun `the directions share matches the spec template`() {
        val share = buildDirectionsShare("Hotel", LatLng(1.0, 2.0), "Set", LatLng(3.0, 4.0), "5 km (3.1 mi)", "~12 min")!!
        assertEquals(
            listOf(
                "🚗 Directions to Set",
                "From: Hotel",
                "Distance: 5 km (3.1 mi)",
                "ETA: ~12 min",
                "View: https://www.google.com/maps/dir/?api=1&origin=1,2&destination=3,4",
            ).joinToString("\n"),
            share.text,
        )
        val bare = buildDirectionsShare(null, LatLng(1.0, 2.0), "Set", LatLng(3.0, 4.0), null, null)!!.text
        assertFalse(bare.contains("From:") || bare.contains("Distance:") || bare.contains("\n\n"))
        assertNull(buildDirectionsShare(null, LatLng(1.0, 2.0), null, null, null, null))
    }

    @Test
    fun `numbers print the way JavaScript prints them`() {
        assertEquals("19", jsNumber(19.0))
        assertEquals("72.8", jsNumber(72.8))
        assertEquals("0.00001", jsNumber(0.00001))
        assertEquals("-0.0005", jsNumber(-0.0005))
        assertEquals("28.123456", toFixed(28.1234564, 6))
        assertEquals("-77.100000", toFixed(-77.1, 6))
        // 12.35 is 12.3499… in binary, as JavaScript's own toFixed sees it;
        // an exact half rounds up.
        assertEquals("12.3", toFixed(12.35, 1))
        assertEquals("12.3", toFixed(12.25, 1))
        assertEquals("1.00", toFixed(1.005, 2))
        assertEquals("-0.00", toFixed(-0.0001, 2))
        assertEquals("0.00", toFixed(-0.0, 2))
        assertEquals("35", toFixed(34.5, 0))
    }

    @Test
    fun `a type wears its own icon on the built-in colour`() {
        assertEquals("H", typeStyle("Hotel", emptyList()).icon)
        assertEquals("#8E44AD", typeStyle("Hotel", emptyList()).colorHex)
        val custom = listOf(LocationType("t", "Hotel", icon = "🏨"))
        assertEquals("🏨", typeStyle("Hotel", custom).icon)
        assertEquals("#8E44AD", typeStyle("Hotel", custom).colorHex)
        assertEquals("Z", typeStyle("Unheard of", emptyList()).icon)
        assertEquals("#1B4F72", typeStyle("Unheard of", emptyList()).colorHex)
    }

    @Test
    fun `the location form refuses in the web's order`() {
        assertEquals("Please fill in required fields", LocationRules.saveError("", "", "", "", null))
        assertEquals("Please enter an address or search for a location", LocationRules.saveError("A", "", "", "", null))
        assertEquals("Please specify the custom location type", LocationRules.saveError("A", "B", "Other", "", LatLng(1.0, 1.0)))
        assertEquals(
            "Please search and select an address to set the coordinates",
            LocationRules.saveError("A", "B", "Hotel", "", null),
        )
        assertNull(LocationRules.saveError("A", "B", "Hotel", "", LatLng(1.0, 1.0)))
        assertEquals("Warehouse", LocationRules.savedType("other", " Warehouse "))
        assertTrue(LocationRules.isShootingType("Night Shoot"))
    }

    @Test
    fun `the custom radius is checked raw, and previewed leniently`() {
        assertEquals("Zone radius is required when Custom is selected.", ZoneRules.customRadiusError(true, ""))
        assertEquals("Enter a radius greater than 0 miles.", ZoneRules.customRadiusError(true, "0"))
        assertNull(ZoneRules.customRadiusError(true, "12.5"))
        assertNull(ZoneRules.customRadiusError(false, ""))
        assertEquals(30.0, ZoneRules.effectiveRadius(true, 40, ""))
        assertEquals(40.0, ZoneRules.effectiveRadius(false, 40, "12"))
    }

    @Test
    fun `a city outside its own zone is refused with the web's sentence`() {
        val message = ZoneRules.cityOutsideZone("Mumbai", LatLng(19.0, 72.8), LatLng(19.5, 72.8), 30.0)
        assertEquals(
            "Mumbai is 34.5 miles from the center point, but zone radius is 30 miles. " +
                "Please adjust the center or increase the radius.",
            message,
        )
        assertNull(ZoneRules.cityOutsideZone("Mumbai", LatLng(19.0, 72.8), LatLng(19.1, 72.8), 30.0))
        assertEquals("MG Road & Ring Road Zone", ZoneRules.intersectionZoneName(" MG Road ", "Ring Road"))
        assertEquals("MG Road, Pune", ZoneRules.intersectionQuery("MG Road", "", "Pune"))
        assertEquals("Please select Street 1 from the dropdown suggestions", ZoneRules.intersectionSearchError("MG", false, "", false))
    }

    @Test
    fun `zone counts and the form's area check`() {
        val zone = MapLocation(id = "z", cityId = "c", name = "Z", point = LatLng(19.0, 72.8), isStudioZone = true, miles = 5.0)
        val near = base.copy(id = "near", point = LatLng(19.01, 72.8))
        val far = base.copy(id = "far", point = LatLng(21.0, 72.8))
        assertEquals(mapOf("z" to 1), zoneLocationCounts(listOf(zone), listOf(near, far)))
        val mumbai = MapCity(id = "c", name = "Mumbai", coordinates = LatLng(19.0, 72.8))
        assertTrue(isWithinSelectedCity(LatLng(19.01, 72.8), mumbai, listOf(zone), null))
        assertFalse(isWithinSelectedCity(LatLng(19.3, 72.8), mumbai, listOf(zone), null))
        assertTrue(isWithinSelectedCity(LatLng(19.5, 72.8), mumbai, emptyList(), null))
        assertFalse(isWithinSelectedCity(LatLng(21.0, 72.8), mumbai, emptyList(), null))
    }

    @Test
    fun `place names follow the two reverse-geocode walks`() {
        val place = GeocodedPlace(
            address = "Lokhandwala, Andheri West, Mumbai, Maharashtra",
            components = listOf(
                GeoComponent("Lokhandwala", listOf("neighborhood")),
                GeoComponent("Andheri West", listOf("sublocality_level_1", "sublocality")),
                GeoComponent("Mumbai", listOf("locality")),
            ),
        )
        assertEquals("Andheri West", place.pinName)
        assertEquals("Andheri West", place.suggestedCityName)
        assertEquals("Mumbai", GeocodedPlace("Mumbai, India", listOf(GeoComponent("Mumbai", listOf("locality")))).suggestedCityName)
        assertEquals("221B Baker St", GeocodedPlace("221B Baker St, London").pinName)
    }
}
