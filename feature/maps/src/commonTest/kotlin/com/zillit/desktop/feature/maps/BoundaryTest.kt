package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.BoundaryMode
import com.zillit.desktop.feature.maps.domain.BoundaryStatus
import com.zillit.desktop.feature.maps.domain.Geo
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.buildBoundaryPrompt
import com.zillit.desktop.feature.maps.domain.evaluatePinBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The boundary rules, transcribed from the web's `boundaryGeometry.test.js`
 * and `boundaryPrompt.test.js` — the copy is exact-match across platforms, so
 * the tests are too.
 */
class BoundaryTest {

    private val city = MapCity(id = "c", name = "Gurugram", coordinates = LatLng(28.4745, 77.0268), radiusMiles = 15.0)

    /** ~degrees of latitude per mile — good enough for fixtures. */
    private val mile = 1.0 / 69

    private val centre = LatLng(28.4745, 77.0268)

    private fun at(milesNorth: Double) = LatLng(centre.lat + milesNorth * mile, centre.lng)

    private fun zone(milesNorth: Double = 0.0, miles: Double = 2.0, name: String = "Zone A") = MapLocation(
        id = "z",
        cityId = "c",
        name = name,
        point = at(milesNorth),
        isStudioZone = true,
        miles = miles,
    )

    @Test
    fun `haversine is zero against itself and measures a degree`() {
        assertEquals(0.0, Geo.distanceMeters(LatLng(28.47, 77.02), LatLng(28.47, 77.02)), 1e-6)
        val degree = Geo.distanceMeters(LatLng(0.0, 0.0), LatLng(1.0, 0.0))
        assertTrue(degree > 110_000 && degree < 112_000)
    }

    @Test
    fun `nothing to enforce is inside`() {
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(0.0), null, null).status)
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(0.0), MapCity("n", "Nowhere"), null).status)
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(null, city, null).status)
    }

    @Test
    fun `the city radius decides inside and outside`() {
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(5.0), city, null).status)
        val outside = evaluatePinBoundary(at(40.0), city, null)
        assertEquals(BoundaryStatus.OutsideCity, outside.status)
        assertEquals("Gurugram", outside.cityName)
    }

    @Test
    fun `radius 0 is the 30-mile default, not no boundary`() {
        val zero = city.copy(radiusMiles = 0.0)
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(20.0), zero, null).status)
        assertEquals(BoundaryStatus.OutsideCity, evaluatePinBoundary(at(40.0), zero, null).status)
    }

    @Test
    fun `center point wins over coordinates`() {
        val moved = city.copy(centerPoint = at(30.0), radiusMiles = 5.0)
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(30.0), moved, null).status)
        assertEquals(BoundaryStatus.OutsideCity, evaluatePinBoundary(at(0.0), moved, null).status)
    }

    @Test
    fun `the active zone decides inside the city`() {
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(1.0), city, zone()).status)
        val outside = evaluatePinBoundary(at(6.0), city, zone())
        assertEquals(BoundaryStatus.OutsideZone, outside.status)
        assertEquals("Zone A", outside.zoneName)
    }

    @Test
    fun `a zone reaching beyond the city radius short-circuits to inside`() {
        assertEquals(
            BoundaryStatus.Inside,
            evaluatePinBoundary(at(20.0), city, zone(milesNorth = 14.0, miles = 10.0)).status,
        )
    }

    @Test
    fun `a zone without coordinates falls through to the city`() {
        val broken = zone().copy(point = null, miles = 5.0)
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(5.0), city, broken).status)
        assertEquals(BoundaryStatus.OutsideCity, evaluatePinBoundary(at(40.0), city, broken).status)
    }

    @Test
    fun `zone miles 0 is the 30-mile default`() {
        assertEquals(BoundaryStatus.Inside, evaluatePinBoundary(at(25.0), city, zone(miles = 0.0)).status)
    }

    @Test
    fun `adding inside asks nothing`() {
        assertNull(buildBoundaryPrompt(BoundaryStatus.Inside, BoundaryMode.Add))
    }

    @Test
    fun `outside the zone uses the J1 copy`() {
        val prompt = assertNotNull(
            buildBoundaryPrompt(
                BoundaryStatus.OutsideZone,
                BoundaryMode.Add,
                zoneName = "Burbank Zone",
                address = "123 Main St",
            ),
        )
        assertEquals("Outside Studio Zone", prompt.title)
        assertEquals("Location is outside the Burbank Zone. Do you want to pin the location?", prompt.message)
        assertEquals("123 Main St", prompt.address)
        assertEquals(listOf("No", "Yes"), prompt.actions.map { it.label })
        assertFalse(prompt.stacked)
    }

    @Test
    fun `outside the city offers three stacked choices`() {
        val prompt = assertNotNull(
            buildBoundaryPrompt(BoundaryStatus.OutsideCity, BoundaryMode.Add, cityName = "Mumbai"),
        )
        assertEquals("Outside Mumbai", prompt.title)
        assertEquals(
            "This location is outside the selected city and studio zone. What would you like to do?",
            prompt.message,
        )
        assertEquals(
            listOf("Create Another City & Pin Location", "Pin Location in the Same City", "Cancel"),
            prompt.actions.map { it.label },
        )
        assertEquals(
            listOf(BoundaryChoice.CreateCity, BoundaryChoice.Confirm, BoundaryChoice.Cancel),
            prompt.actions.map { it.choice },
        )
        assertTrue(prompt.stacked)
    }

    @Test
    fun `a move inside still confirms`() {
        val prompt = assertNotNull(buildBoundaryPrompt(BoundaryStatus.Inside, BoundaryMode.Move, name = "Base Camp"))
        assertEquals("Move Location", prompt.title)
        assertEquals("Move 'Base Camp' to the new position?", prompt.message)
        assertEquals(listOf("Cancel", "Move"), prompt.actions.map { it.label })
    }

    @Test
    fun `a move outside the zone swaps the verb`() {
        val prompt = assertNotNull(
            buildBoundaryPrompt(BoundaryStatus.OutsideZone, BoundaryMode.Move, zoneName = "Zone A"),
        )
        assertEquals("Location is outside the Zone A. Do you want to move the location?", prompt.message)
    }

    @Test
    fun `a move outside the city has its own copy and no create-city`() {
        val prompt = assertNotNull(
            buildBoundaryPrompt(BoundaryStatus.OutsideCity, BoundaryMode.Move, cityName = "Mumbai"),
        )
        assertEquals("The new position is outside Mumbai and any studio zone. Move the pin anyway?", prompt.message)
        assertEquals(listOf("Cancel", "Move"), prompt.actions.map { it.label })
    }

    @Test
    fun `fallback names never print null`() {
        listOf(
            buildBoundaryPrompt(BoundaryStatus.OutsideZone, BoundaryMode.Add),
            buildBoundaryPrompt(BoundaryStatus.OutsideCity, BoundaryMode.Add),
            buildBoundaryPrompt(BoundaryStatus.Inside, BoundaryMode.Move),
        ).forEach { prompt ->
            val text = listOf(prompt!!.title, prompt.message) + prompt.actions.map { it.label }
            assertFalse(text.any { "null" in it }, text.toString())
        }
        assertEquals(
            "Location is outside the studio zone. Do you want to pin the location?",
            buildBoundaryPrompt(BoundaryStatus.OutsideZone, BoundaryMode.Add)!!.message,
        )
    }
}
