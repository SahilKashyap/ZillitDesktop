package com.zillit.desktop.core.locationpicker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker bridge, without an embedded browser in sight.
 *
 * The page cannot be run in a unit test, so what is testable is the discipline
 * either side of it: a `picked` payload decodes to the right place, a hostile
 * address or key cannot escape the JS string literal it is spliced into, and
 * anything malformed is dropped rather than turned into a pin at (0, 0).
 */
class LocationPickerWireTest {

    private val theme = PickerTheme(
        background = "#101828",
        surface = "#1D2939",
        text = "#F9FAFB",
        accent = "#F97316",
        border = "#344054",
        isDark = true,
    )

    /** The page's own `JSON.stringify`, from Kotlin's side of the glass. */
    private fun pagePicked(name: String, address: String, lat: Double, lng: Double): String =
        buildJsonObject {
            put("type", "picked")
            put("name", name)
            put("address", address)
            put("lat", lat)
            put("lng", lng)
        }.toString()

    private fun decodeObject(text: String): JsonObject =
        assertIs<JsonObject>(Json.parseToJsonElement(text))

    private fun JsonObject.content(key: String): String =
        assertIs<JsonPrimitive>(this[key]).content

    @Test
    fun `a picked payload decodes to the place it describes`() {
        val event = LocationPickerWire.parse(
            pagePicked("Aria Hotel", "1 Aria Road, Mumbai 400001, India", 19.075984, -72.877656),
        )

        val picked = assertIs<LocationPickerEvent.Picked>(event)
        assertEquals("Aria Hotel", picked.location.name)
        assertEquals("1 Aria Road, Mumbai 400001, India", picked.location.address)
        assertEquals(19.075984, picked.location.lat, TOLERANCE)
        assertEquals(-72.877656, picked.location.lng, TOLERANCE)
    }

    @Test
    fun `a nameless pick falls back to its address, as the web does`() {
        val event = LocationPickerWire.parse(pagePicked("", "12 Wharf Street", 1.5, -2.25))

        val picked = assertIs<LocationPickerEvent.Picked>(event)
        assertEquals("12 Wharf Street", picked.location.name)
    }

    @Test
    fun `a reverse-geocode that found nothing still yields coordinates`() {
        // What the page sends when the Geocoder fails: the coordinates as the
        // address, so the pick is usable rather than lost.
        val event = LocationPickerWire.parse(pagePicked("", "1.500000, -2.250000", 1.5, -2.25))

        val picked = assertIs<LocationPickerEvent.Picked>(event)
        assertEquals(1.5, picked.location.lat, TOLERANCE)
        assertEquals(-2.25, picked.location.lng, TOLERANCE)
        assertEquals("1.500000, -2.250000", picked.location.address)
    }

    @Test
    fun `an address full of quotes and newlines survives the round trip`() {
        val hostile = "\"Bill's\" Bar\\Grill\nUnit </script> 3 "
        val event = LocationPickerWire.parse(pagePicked(hostile, hostile, 0.5, 0.25))

        val picked = assertIs<LocationPickerEvent.Picked>(event)
        assertEquals(hostile, picked.location.address)
        assertEquals(hostile, picked.location.name)
    }

    @Test
    fun `the page's own ready announcement is not an event`() {
        assertTrue(LocationPickerWire.isPageReady("""{"type":"ready"}"""))
        assertNull(LocationPickerWire.parse("""{"type":"ready"}"""))
        assertFalse(LocationPickerWire.isPageReady("""{"type":"map-ready"}"""))
        assertEquals(LocationPickerEvent.MapReady, LocationPickerWire.parse("""{"type":"map-ready"}"""))
    }

    @Test
    fun `a refused key and a page error both read as failures`() {
        val refused = assertIs<LocationPickerEvent.Failed>(
            LocationPickerWire.parse("""{"type":"auth-failed"}"""),
        )
        assertTrue(refused.message.contains("Maps key"), refused.message)

        val broken = assertIs<LocationPickerEvent.Failed>(
            LocationPickerWire.parse("""{"type":"error","message":"script blocked"}"""),
        )
        assertEquals("script blocked", broken.message)
    }

    @Test
    fun `malformed lines are ignored rather than guessed at`() {
        listOf(
            "",
            "not json at all",
            "[]",
            "{}",
            """{"type":"who-knows"}""",
            // Half a coordinate is not a location.
            """{"type":"picked","address":"somewhere","lat":1.5}""",
            """{"type":"picked","address":"somewhere","lng":-2.25}""",
            """{"type":"picked","address":"somewhere","lat":"north","lng":"west"}""",
        ).forEach { line ->
            assertNull(LocationPickerWire.parse(line), "parsed <$line>")
            assertFalse(LocationPickerWire.isPageReady(line), "read <$line> as ready")
        }
    }

    @Test
    fun `boot splices the key and theme as JS string literals, not as code`() {
        val hostile = """AIza");window.stolen=1;zillitPicker.boot("x"""
        val script = LocationPickerWire.bootScript(hostile, LocationPickerWire.themeJson(theme))

        assertTrue(script.startsWith("zillitPicker.boot("), script)
        assertTrue(script.endsWith(")"), script)
        // Both arguments must be complete JSON string literals: wrapping them
        // in brackets is a parse only a well-escaped pair can survive.
        val args = script.removePrefix("zillitPicker.boot(").removeSuffix(")")
        val parsed = assertIs<JsonArray>(Json.parseToJsonElement("[$args]"))
        assertEquals(2, parsed.size)
        assertEquals(hostile, assertIs<JsonPrimitive>(parsed[0]).content)
        assertEquals("#F97316", decodeObject(assertIs<JsonPrimitive>(parsed[1]).content).content("accent"))
    }

    @Test
    fun `a key containing a newline cannot break the script onto a second line`() {
        val script = LocationPickerWire.bootScript("one\ntwo", LocationPickerWire.themeJson(theme))

        assertFalse(script.contains('\n'), script)
    }

    @Test
    fun `camera and pin scripts carry plain numbers`() {
        assertEquals("zillitPicker.center(1.5, -2.25, 15)", LocationPickerWire.centerScript(1.5, -2.25, 15))
        assertEquals("zillitPicker.setPin(1.5, -2.25)", LocationPickerWire.setPinScript(1.5, -2.25))
        assertEquals("zillitPicker.reset()", LocationPickerWire.resetScript)
    }

    @Test
    fun `a saved address cannot become code on its way back into the search box`() {
        val hostile = """Unit 3"); window.stolen = 1; ("""
        val script = LocationPickerWire.searchTextScript(hostile)

        val argument = script.removePrefix("zillitPicker.setSearchText(").removeSuffix(")")
        assertEquals(hostile, assertIs<JsonPrimitive>(Json.parseToJsonElement(argument)).content)
    }

    @Test
    fun `the theme carries every token the page paints with`() {
        val fields = decodeObject(LocationPickerWire.themeJson(theme))

        listOf("background", "surface", "text", "accent", "border", "dark").forEach { key ->
            assertTrue(fields.containsKey(key), "theme is missing $key")
        }
        assertEquals("true", fields.content("dark"))
        assertEquals("#101828", fields.content("background"))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
