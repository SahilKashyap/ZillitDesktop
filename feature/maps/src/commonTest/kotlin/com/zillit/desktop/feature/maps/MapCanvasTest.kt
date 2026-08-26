package com.zillit.desktop.feature.maps

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.data.MapCanvasWire
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapPinMarker
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas seam: the ViewModel pushes drawable pins on every (re)load, and
 * folds the canvas's clicks back into the same acts the list performs — the
 * behaviours the web's map page has (`GoogleMapComponent.jsx:532` map click,
 * `:1049` marker click, `:587-594` recentre on selection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapCanvasTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCanvas : MapCanvasHost {
        override val events = MutableSharedFlow<MapCanvasEvent>()
        val pinPushes = mutableListOf<List<MapPinMarker>>()
        val centers = mutableListOf<Triple<Double, Double, Int>>()

        override fun setPins(pins: List<MapPinMarker>) {
            pinPushes += pins
        }

        override fun center(lat: Double, lng: Double, zoom: Int) {
            centers += Triple(lat, lng, zoom)
        }
    }

    private class FakeRepository(
        private val pins: List<MapLocation>,
        override val refreshes: Flow<Unit> = MutableSharedFlow(),
    ) : MapRepository {
        override suspend fun cities() = ZillitResult.Success(
            listOf(MapCity("c1", "Goa", "", CITY_LAT, CITY_LNG, 0.0, pins.size)),
        )
        override suspend fun createCity(
            name: String, description: String, lat: Double, lng: Double, radiusMiles: Double,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteCity(id: String) = ZillitResult.Success(Unit)
        override suspend fun types() = ZillitResult.Success(
            listOf(LocationType("t1", "Hotel", "", emptyList(), true)),
        )
        override suspend fun createType(name: String, icon: String, subTypes: List<String>) =
            ZillitResult.Success(Unit)
        override suspend fun deleteType(id: String) = ZillitResult.Success(Unit)
        override suspend fun locations(cityId: String?) = ZillitResult.Success(pins)
        override suspend fun createLocation(draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun updateLocation(id: String, draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun createZone(draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun updateZone(id: String, draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
    }

    private fun pin(id: String, lat: Double? = PIN_LAT, lng: Double? = PIN_LNG, zone: Boolean = false) =
        MapLocation(
            id = id, cityId = "c1", name = "Pin $id", type = "Hotel", subTypes = emptyList(),
            description = "", address = "Somewhere", sceneNumber = "", lat = lat, lng = lng,
            isStudioZone = zone, radiusMiles = if (zone) 30.0 else 0.0,
            centerPointType = "", attachmentNames = emptyList(),
        )

    private fun model(
        canvas: FakeCanvas,
        pins: List<MapLocation>,
        refreshes: Flow<Unit> = MutableSharedFlow(),
        viewer: MapViewer = MapViewer(canPost = true, ready = true),
    ) = MapViewModel(FakeRepository(pins, refreshes), resolveViewer = { viewer }, canvas = canvas)

    @Test
    fun `pins are pushed to the canvas on load and on refresh, coordinate-less ones dropped`() =
        runTest(dispatcher) {
            val canvas = FakeCanvas()
            val refreshes = MutableSharedFlow<Unit>()
            val model = model(canvas, listOf(pin("p1"), pin("p2", lat = null), pin("z1", zone = true)), refreshes)

            model.start()
            runCurrent()

            assertTrue(canvas.pinPushes.isNotEmpty(), "load pushes the pins")
            val drawn = canvas.pinPushes.last()
            assertEquals(listOf("z1", "p1"), drawn.map { it.id }, "zones first, the coordinate-less pin dropped")
            assertTrue(drawn.first { it.id == "z1" }.isZone)
            assertEquals(30.0, drawn.first { it.id == "z1" }.radiusMiles)

            val before = canvas.pinPushes.size
            refreshes.emit(Unit)
            runCurrent()
            assertTrue(canvas.pinPushes.size > before, "a socket refresh re-pushes the pins")
        }

    @Test
    fun `a marker click opens that pin's editor and recentres on it`() = runTest(dispatcher) {
        val canvas = FakeCanvas()
        val model = model(canvas, listOf(pin("p1")))
        model.start()
        runCurrent()

        canvas.events.emit(MapCanvasEvent.MarkerClicked("p1"))
        runCurrent()

        assertEquals("p1", model.state.value.pinEditor?.locationId, "the marker's pin is open")
        assertEquals(Triple(PIN_LAT, PIN_LNG, PIN_ZOOM), canvas.centers.last(), "recentred on the pin")
    }

    @Test
    fun `selecting a pin from the list recentres the map the same way`() = runTest(dispatcher) {
        val canvas = FakeCanvas()
        val model = model(canvas, listOf(pin("p1")))
        model.start()
        runCurrent()

        model.onEvent(MapEvent.EditPin("p1"))
        runCurrent()

        assertEquals(Triple(PIN_LAT, PIN_LNG, PIN_ZOOM), canvas.centers.last())
    }

    @Test
    fun `a map click proposes a prefilled pin, once, and only for posters`() = runTest(dispatcher) {
        val canvas = FakeCanvas()
        val model = model(canvas, listOf(pin("p1")))
        model.start()
        runCurrent()

        canvas.events.emit(MapCanvasEvent.MapClicked(1.25, 2.5))
        runCurrent()
        val editor = model.state.value.pinEditor
        assertEquals("1.25", editor?.latText)
        assertEquals("2.5", editor?.lngText)
        assertNull(editor?.locationId, "a proposal is a new pin")
        assertEquals("c1", editor?.cityId, "prefilled with the selected city")

        // The web disables map clicks while its form is open (:536).
        canvas.events.emit(MapCanvasEvent.MapClicked(9.0, 9.0))
        runCurrent()
        assertEquals("1.25", model.state.value.pinEditor?.latText, "an open editor is not replaced")

        val viewerOnly = FakeCanvas()
        val watching = model(viewerOnly, listOf(pin("p1")), viewer = MapViewer(ready = true))
        watching.start()
        runCurrent()
        viewerOnly.events.emit(MapCanvasEvent.MapClicked(1.0, 1.0))
        runCurrent()
        assertNull(watching.state.value.pinEditor, "a viewer without posting rights proposes nothing")
    }

    @Test
    fun `canvas ready replays the pins and centres on the city, a failure reads as text`() =
        runTest(dispatcher) {
            val canvas = FakeCanvas()
            val model = model(canvas, listOf(pin("p1")))
            model.start()
            runCurrent()

            val before = canvas.pinPushes.size
            canvas.events.emit(MapCanvasEvent.Ready)
            runCurrent()
            assertTrue(canvas.pinPushes.size > before, "ready re-pushes what is loaded")
            assertEquals(Triple(CITY_LAT, CITY_LNG, CITY_ZOOM), canvas.centers.last())

            canvas.events.emit(MapCanvasEvent.Failed("Google rejected the key"))
            runCurrent()
            assertEquals("Google rejected the key", model.state.value.canvasError)
        }

    @Test
    fun `the wire decodes page events and refuses what it does not know`() {
        assertIs<MapCanvasEvent.Ready>(MapCanvasWire.parse("""{"type":"map-ready"}"""))
        assertEquals(
            MapCanvasEvent.MarkerClicked("abc"),
            MapCanvasWire.parse("""{"type":"marker-click","id":"abc"}"""),
        )
        assertEquals(
            MapCanvasEvent.MapClicked(1.5, -2.25),
            MapCanvasWire.parse("""{"type":"map-click","lat":1.5,"lng":-2.25}"""),
        )
        assertIs<MapCanvasEvent.Failed>(MapCanvasWire.parse("""{"type":"auth-failed"}"""))
        assertEquals(
            MapCanvasEvent.Failed("boom"),
            MapCanvasWire.parse("""{"type":"error","message":"boom"}"""),
        )
        assertTrue(MapCanvasWire.isPageReady("""{"type":"ready"}"""))
        assertNull(MapCanvasWire.parse("""{"type":"ready"}"""), "the shell announcement is not an event")
        assertNull(MapCanvasWire.parse("not json"))
        assertNull(MapCanvasWire.parse("""{"type":"mystery"}"""))
    }

    @Test
    fun `scripts quote their payloads so a hostile name cannot escape`() {
        val marker = MapPinMarker(
            id = "p1", name = """He said "hi", didn't he?""", label = "Hotel",
            lat = 1.0, lng = 2.0,
        )
        val script = MapCanvasWire.pinsScript(listOf(marker))
        assertTrue(script.startsWith("zillitMap.setPins(\""), "the payload rides as one JSON string")
        assertTrue(script.endsWith(")"))

        // Undo the page's side of the contract: the argument is one JS/JSON
        // string literal whose content is the pin array — the name must
        // round-trip intact through both layers of encoding.
        val literal = script.removePrefix("zillitMap.setPins(").removeSuffix(")")
        val payload = Json.decodeFromString(JsonPrimitive.serializer(), literal).content
        val name = (Json.parseToJsonElement(payload) as JsonArray)
            .let { it[0] as JsonObject }
            .let { (it["name"] as JsonPrimitive).content }
        assertEquals(marker.name, name)

        assertEquals("zillitMap.center(1.5, -2.0, 15)", MapCanvasWire.centerScript(1.5, -2.0, 15))
        val boot = MapCanvasWire.bootScript("""k"ey""")
        assertTrue(boot.startsWith("zillitMap.boot(\""), "the key rides as one JSON string")
    }

    private companion object {
        const val CITY_LAT = 15.29
        const val CITY_LNG = 74.12
        const val PIN_LAT = 15.3
        const val PIN_LNG = 74.15
        const val PIN_ZOOM = 15
        const val CITY_ZOOM = 11
    }
}
