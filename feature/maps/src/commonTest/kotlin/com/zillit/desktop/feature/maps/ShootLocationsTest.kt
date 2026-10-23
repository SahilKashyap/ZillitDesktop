package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapHost
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapShootLocations
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.SHOOT_LOCATION_FINAL
import com.zillit.desktop.feature.maps.ui.MapEffect
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapViewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Location tool's Shortlisted and Final places, drawn here (web PR #6517).
 *
 * They are the map's to show and nobody's to change from here: the map has
 * no call that could save one, so a drag snaps back and the card offers View
 * rather than Edit. They are still searched and counted with the map's own —
 * a pin a reader can see has to be findable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShootLocationsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val bridge = MapLocation(
        id = "location-tool-p1",
        cityId = "",
        name = "Millennium Bridge",
        type = SHOOT_LOCATION_FINAL,
        address = "Bankside",
        sceneNumber = "14",
        point = LatLng(51.5079, -0.0877),
        readOnly = true,
        shootStatus = "published",
    )

    private fun tool(pins: List<MapLocation> = listOf(bridge)): Pair<MapViewModel, MutableList<MapEffect>> {
        val effects = mutableListOf<MapEffect>()
        val vm = MapViewModel(
            FakeMapRepository(),
            { MapViewer(userId = "u1", canPost = true, ready = true) },
            FakeCanvasHost(),
            MapHost(shootLocations = object : MapShootLocations {
                override suspend fun pins(): List<MapLocation> = pins
            }),
        )
        CoroutineScope(dispatcher).launch { vm.effects.collect(effects::add) }
        return vm to effects
    }

    @Test
    fun `shoot locations are drawn, typed and counted with the map's own`() = runTest(dispatcher) {
        val (vm, _) = tool()
        vm.start()
        advanceUntilIdle()

        assertContains(vm.currentState.filteredLocations.map { it.id }, bridge.id)
        assertContains(vm.currentState.presentTypes, SHOOT_LOCATION_FINAL)
        assertEquals(1, vm.currentState.typeCounts[SHOOT_LOCATION_FINAL])
    }

    @Test
    fun `they stay out of the city's own list, which is what writes read`() = runTest(dispatcher) {
        val (vm, _) = tool()
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.currentState.locations.none { it.readOnly }, "a shoot pin is not one of the city's records")
        // The map still finds it by id, so its card can open.
        assertEquals(bridge.id, vm.currentState.location(bridge.id)?.id)
    }

    @Test
    fun `View hands a shoot location over to the Location tool`() = runTest(dispatcher) {
        val (vm, effects) = tool()
        vm.start()
        advanceUntilIdle()

        vm.onEvent(MapEvent.Locations.OpenInLocationTool(bridge.id))
        advanceUntilIdle()

        assertEquals(
            listOf("/film-tools/location"),
            effects.filterIsInstance<MapEffect.OpenTool>().map { it.path },
        )
    }

    @Test
    fun `a pin is drawn under the city whose circle holds it, and nowhere else`() {
        val delhi = MapCity(id = "delhi", name = "Delhi", coordinates = LatLng(28.6139, 77.2090), radiusMiles = 30.0)
        val noida = MapCity(id = "noida", name = "Noida", coordinates = LatLng(28.5355, 77.3910), radiusMiles = 30.0)
        val inNoida = bridge.copy(id = "p-noida", point = LatLng(28.5400, 77.3800))
        val state = MapUiState(cities = listOf(delhi, noida), shootLocations = listOf(inNoida))

        // The two circles overlap; the pin belongs to the nearer city only,
        // so Delhi neither draws it nor counts it (web PR #6543).
        assertEquals(
            listOf(inNoida.id),
            state.copy(selectedCityId = "noida").cityShootLocations.map { it.id },
        )
        assertTrue(state.copy(selectedCityId = "delhi").cityShootLocations.isEmpty())
        assertEquals(0, state.copy(selectedCityId = "delhi").typeCounts.size)
    }

    @Test
    fun `a pin inside no city shows under every city, so it never disappears`() {
        val delhi = MapCity(id = "delhi", name = "Delhi", coordinates = LatLng(28.6139, 77.2090), radiusMiles = 30.0)
        val faraway = bridge.copy(id = "p-far", point = LatLng(51.5079, -0.0877))
        val state = MapUiState(cities = listOf(delhi), shootLocations = listOf(faraway), selectedCityId = "delhi")

        assertEquals(listOf(faraway.id), state.cityShootLocations.map { it.id })
    }

    @Test
    fun `a host with no seam draws nothing extra`() = runTest(dispatcher) {
        val effects = mutableListOf<MapEffect>()
        val vm = MapViewModel(
            FakeMapRepository(),
            { MapViewer(userId = "u1", ready = true) },
            FakeCanvasHost(),
            MapHost(),
        )
        CoroutineScope(dispatcher).launch { vm.effects.collect(effects::add) }
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.currentState.shootLocations.isEmpty())
    }
}
