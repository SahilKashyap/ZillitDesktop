package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.data.MAP_SYNC_EVENTS
import com.zillit.desktop.feature.maps.data.MapRefresh
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.data.matchesProject
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.ZoneDraft
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `map_location_*` frame re-lists the pins exactly once — the web page's
 * three handlers (`MapPage.jsx:73,100,126`) each touch the location lists —
 * and a frame naming another production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(override val refreshes: Flow<MapRefresh>) : MapRepository {
        var listCalls = 0
        var cityCalls = 0

        override suspend fun cities(): ZillitResult<List<MapCity>> {
            cityCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createCity(
            name: String, description: String, lat: Double, lng: Double, radiusMiles: Double,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteCity(id: String) = ZillitResult.Success(Unit)
        override suspend fun types() = ZillitResult.Success(emptyList<LocationType>())
        override suspend fun createType(name: String, icon: String, subTypes: List<String>) =
            ZillitResult.Success(Unit)
        override suspend fun deleteType(id: String) = ZillitResult.Success(Unit)
        override suspend fun locations(cityId: String?): ZillitResult<List<MapLocation>> {
            listCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createLocation(draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun updateLocation(id: String, draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun createZone(draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun updateZone(id: String, draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
    }

    @Test
    fun `an emitted event re-lists the pins once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<MapRefresh>()
            val repository = FakeRepository(events)
            val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true) })

            model.start()
            runCurrent()
            assertEquals(1, repository.listCalls, "start loads once")

            events.emit(MapRefresh.Pins)
            runCurrent()
            assertEquals(2, repository.listCalls, "the event re-lists")

            model.start() // the window reopening must not add a second collector
            runCurrent()
            events.emit(MapRefresh.Pins)
            runCurrent()
            assertEquals(4, repository.listCalls, "start reloads, the event re-lists ONCE")
        }

    /**
     * A city change re-reads the strip as well as the pins.
     *
     * The two are separate calls on the repository, so the naive wiring
     * (every frame re-lists pins) left a city renamed or reordered elsewhere
     * showing its old name until the window was reopened.
     */
    @Test
    fun `a city frame reloads the cities, not only the pins`() = runTest(dispatcher) {
        val events = MutableSharedFlow<MapRefresh>()
        val repository = FakeRepository(events)
        val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true) })

        model.start()
        runCurrent()
        val citiesAfterStart = repository.cityCalls

        events.emit(MapRefresh.Pins)
        runCurrent()
        assertEquals(citiesAfterStart, repository.cityCalls, "a pin frame leaves the strip alone")

        events.emit(MapRefresh.Cities)
        runCurrent()
        assertEquals(citiesAfterStart + 1, repository.cityCalls, "a city frame re-reads the strip")
    }

    /** The city names are the eight both phones carry, in both spellings. */
    @Test
    fun `both city spellings are subscribed`() {
        val names = MAP_SYNC_EVENTS.map { it.value }

        listOf("added", "updated", "deleted", "reordered").forEach { verb ->
            assertTrue("city:$verb" in names, "the original map's city:$verb")
            assertTrue("map:city:$verb" in names, "the new module's map:city:$verb")
        }
    }

    @Test
    fun `only a frame naming another production is dropped`() {
        val mine = Json.parseToJsonElement("""{"project_id":"p1","map_location":{}}""")
        val other = Json.parseToJsonElement("""{"project_id":"p2"}""")
        val silent = Json.parseToJsonElement("""{"map_location":{}}""")

        assertTrue(mine.matchesProject("p1"))
        assertFalse(other.matchesProject("p1"))
        assertTrue(silent.matchesProject("p1"), "a frame naming no project must pass")
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(other.matchesProject(null), "no known project, nothing to compare")
    }
}
