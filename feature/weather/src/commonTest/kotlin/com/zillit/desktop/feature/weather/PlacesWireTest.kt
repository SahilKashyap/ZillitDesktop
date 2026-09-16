package com.zillit.desktop.feature.weather

import com.zillit.desktop.feature.weather.data.GooglePlacesGateway
import com.zillit.desktop.feature.weather.data.PlacesWire
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Google says, and what the search box, the locator and the city name are given. */
class PlacesWireTest {

    /** The dropdown rows are Google's main and secondary text, in Google's order. */
    @Test
    fun `suggestions read the structured formatting`() {
        val rows = PlacesWire.parseSuggestions(
            """
            {"predictions":[
              {"place_id":"p1","description":"Paris, France",
               "structured_formatting":{"main_text":"Paris","secondary_text":"France"}},
              {"place_id":"p2","description":"Paris, TX, USA",
               "structured_formatting":{"main_text":"Paris","secondary_text":"TX, USA"}},
              {"description":"no id — dropped"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(PlaceSuggestion("p1", "Paris", "France"), PlaceSuggestion("p2", "Paris", "TX, USA")), rows)
    }

    /** Google's refusal envelope has no predictions — an empty list, not a crash. */
    @Test
    fun `a refused autocomplete is no rows`() {
        assertTrue(PlacesWire.parseSuggestions("""{"status":"REQUEST_DENIED","error_message":"x"}""").isEmpty())
        assertTrue(PlacesWire.parseSuggestions("not json").isEmpty())
    }

    @Test
    fun `place details give the point`() {
        val point = PlacesWire.parseDetailsLocation(
            """{"result":{"geometry":{"location":{"lat":48.8566,"lng":2.3522}}},"status":"OK"}""",
        )

        assertEquals(48.8566, point?.lat)
        assertEquals(2.3522, point?.lng)
        assertNull(PlacesWire.parseDetailsLocation("""{"status":"NOT_FOUND"}"""))
    }

    /**
     * `getCityName` takes the first component across every result whose types
     * hold one of the three it accepts — so a street-level first result
     * still yields the city further down its components.
     */
    @Test
    fun `the city is the first locality-like component`() {
        val city = PlacesWire.parseCity(
            """
            {"results":[
              {"address_components":[
                 {"long_name":"5","types":["street_number"]},
                 {"long_name":"Avenue Anatole France","types":["route"]},
                 {"long_name":"Paris","types":["locality","political"]},
                 {"long_name":"Île-de-France","types":["administrative_area_level_1","political"]}
              ]}
            ]}
            """.trimIndent(),
        )

        assertEquals("Paris", city)
    }

    /** No locality at all (open country): the region stands in, as it does on the web. */
    @Test
    fun `a region names a point with no town`() {
        val city = PlacesWire.parseCity(
            """{"results":[{"address_components":[
                 {"long_name":"Highlands","types":["administrative_area_level_2","political"]},
                 {"long_name":"Scotland","types":["administrative_area_level_1","political"]}]}]}""",
        )

        assertEquals("Highlands", city)
        assertNull(PlacesWire.parseCity("""{"results":[],"status":"ZERO_RESULTS"}"""))
    }

    @Test
    fun `geolocation gives the point`() {
        val point = PlacesWire.parseGeolocation("""{"location":{"lat":51.5,"lng":-0.12},"accuracy":1500}""")

        assertEquals(51.5, point?.lat)
        assertNull(PlacesWire.parseGeolocation("""{"error":{"code":403}}"""))
    }

    /** The whole path: where am I, then what is that place called. */
    @Test
    fun `locate names the point it finds`() = runTest {
        val gateway = GooglePlacesGateway(
            httpClient = HttpClient(
                MockEngine { request ->
                    val body = when {
                        "geolocate" in request.url.host + request.url.encodedPath ->
                            """{"location":{"lat":51.5,"lng":-0.12}}"""
                        "geocode" in request.url.encodedPath ->
                            """{"results":[{"address_components":[{"long_name":"London","types":["locality"]}]}]}"""
                        else -> "{}"
                    }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                },
            ),
            placesKey = { "places" },
            mapsKey = { "maps" },
        )

        val here = gateway.locate()

        assertEquals("London", here?.name)
        assertEquals(51.5, here?.lat)
    }

    /** A Places key Google refuses is retried with the Maps key, and the search still answers. */
    @Test
    fun `a refused places key falls back to the maps key`() = runTest {
        val keysUsed = mutableListOf<String>()
        val gateway = GooglePlacesGateway(
            httpClient = HttpClient(
                MockEngine { request ->
                    val key = request.url.parameters["key"].orEmpty()
                    keysUsed += key
                    val body = if (key == "places") {
                        """{"status":"REQUEST_DENIED","error_message":"referer"}"""
                    } else {
                        """{"predictions":[{"place_id":"p1","structured_formatting":{"main_text":"Paris"}}]}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                },
            ),
            placesKey = { "places" },
            mapsKey = { "maps" },
        )

        val rows = gateway.suggest("Par")

        assertEquals(listOf("places", "maps"), keysUsed)
        assertEquals("Paris", rows.single().primary)
    }

    /** No keys at all: nothing is asked, nothing is answered. */
    @Test
    fun `without keys the gateway answers nothing`() = runTest {
        var calls = 0
        val gateway = GooglePlacesGateway(
            httpClient = HttpClient(MockEngine { calls++; respond("{}") }),
            placesKey = { null },
            mapsKey = { "" },
        )

        assertTrue(gateway.suggest("Paris").isEmpty())
        assertNull(gateway.locate())
        assertNull(gateway.cityName(1.0, 2.0))
        assertEquals(0, calls)
    }
}
