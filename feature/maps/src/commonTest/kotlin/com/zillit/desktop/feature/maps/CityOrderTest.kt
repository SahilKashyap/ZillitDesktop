package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.data.MapRefresh
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapRepository
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The order cities are listed in.
 *
 * The production's own arrangement — the city it works in most belongs at the
 * top — and the service takes the whole list rather than a move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CityOrderTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Moving down sends the whole arrangement, in its new order. */
    @Test
    fun `moving a city down sends the new arrangement`() = runTest(dispatcher) {
        val repository = FakeRepo()
        val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true, canPost = true) })
        model.start()
        advanceUntilIdle()

        model.onEvent(MapEvent.MoveCity("c1", up = false))
        advanceUntilIdle()

        assertEquals(listOf("c2", "c1", "c3"), repository.ordered)
        assertEquals(listOf("c2", "c1", "c3"), model.state.value.cities.map { it.id })
    }

    /** Moving up does the reverse. */
    @Test
    fun `moving a city up sends the new arrangement`() = runTest(dispatcher) {
        val repository = FakeRepo()
        val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true, canPost = true) })
        model.start()
        advanceUntilIdle()

        model.onEvent(MapEvent.MoveCity("c3", up = true))
        advanceUntilIdle()

        assertEquals(listOf("c1", "c3", "c2"), repository.ordered)
    }

    /** The ends do not wrap: the first cannot go up, the last cannot go down. */
    @Test
    fun `the ends of the list hold`() = runTest(dispatcher) {
        val repository = FakeRepo()
        val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true, canPost = true) })
        model.start()
        advanceUntilIdle()

        model.onEvent(MapEvent.MoveCity("c1", up = true))
        model.onEvent(MapEvent.MoveCity("c3", up = false))
        advanceUntilIdle()

        assertNull(repository.ordered)
    }

    /** A city that is not on the list cannot be moved. */
    @Test
    fun `an unknown city is ignored`() = runTest(dispatcher) {
        val repository = FakeRepo()
        val model = MapViewModel(repository, resolveViewer = { MapViewer(ready = true, canPost = true) })
        model.start()
        advanceUntilIdle()

        model.onEvent(MapEvent.MoveCity("nope", up = true))
        advanceUntilIdle()

        assertNull(repository.ordered)
    }

    private class FakeRepo(
        override val refreshes: Flow<MapRefresh> = MutableSharedFlow(),
    ) : MapRepository {

        var ordered: List<String>? = null

        override suspend fun cities() = ZillitResult.Success(
            listOf(
                MapCity("c1", "Goa", "", 0.0, 0.0, 0.0, 0),
                MapCity("c2", "Mumbai", "", 0.0, 0.0, 0.0, 0),
                MapCity("c3", "Delhi", "", 0.0, 0.0, 0.0, 0),
            ),
        )

        override suspend fun reorderCities(cityIds: List<String>): ZillitResult<Unit> {
            ordered = cityIds
            return ZillitResult.Success(Unit)
        }

        override suspend fun createCity(
            name: String,
            description: String,
            lat: Double,
            lng: Double,
            radiusMiles: Double,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteCity(id: String) = ZillitResult.Success(Unit)
        override suspend fun types() = ZillitResult.Success(emptyList<LocationType>())
        override suspend fun createType(name: String, icon: String, subTypes: List<String>) =
            ZillitResult.Success(Unit)
        override suspend fun deleteType(id: String) = ZillitResult.Success(Unit)
        override suspend fun locations(cityId: String?) = ZillitResult.Success(emptyList<MapLocation>())
        override suspend fun createLocation(draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun updateLocation(id: String, draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun createZone(draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun updateZone(id: String, draft: ZoneDraft) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
    }
}
