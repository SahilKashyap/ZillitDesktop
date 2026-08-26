package com.zillit.desktop.feature.transportation

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.transportation.data.TRANSPORT_SYNC_KINDS
import com.zillit.desktop.feature.transportation.data.matchesProject
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.TransportRepository
import com.zillit.desktop.feature.transportation.domain.TransportSyncKind
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import com.zillit.desktop.feature.transportation.ui.TransportViewModel
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
 * A transport wire frame re-runs only the load it touches — Fleet reloads
 * crew and vehicles, Trips the visible request list, Permanent the
 * allocations plus the crew (ZL-13708) — and a frame naming another
 * production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Suppress("TooManyFunctions") // One override per repository seam.
    private class FakeRepository(
        override val refreshes: Flow<TransportSyncKind>,
    ) : TransportRepository {
        var crewCalls = 0
        var vehicleCalls = 0
        var tripCalls = 0
        var permanentCalls = 0

        override suspend fun vehicles(): ZillitResult<List<Vehicle>> {
            vehicleCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun vehicleTypes() = ZillitResult.Success(emptyList<String>())
        override suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?> =
            ZillitResult.Success(null)
        override suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?> =
            ZillitResult.Success(null)
        override suspend fun deleteVehicles(ids: List<String>) = ZillitResult.Success(Unit)
        override suspend fun driverDesignations() = ZillitResult.Success(emptyList<String>())
        override suspend fun crew(): ZillitResult<List<TransportUser>> {
            crewCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun postingRightUsers() = ZillitResult.Success(emptyList<String>())
        override suspend fun updateDriver(
            userId: String, vehicleId: String?, isTempDriver: Boolean?, isTripAssigned: Boolean?,
        ) = ZillitResult.Success(Unit)
        override suspend fun pendingDocumentReminder(userId: String, type: String, message: String?) =
            ZillitResult.Success(Unit)
        override suspend fun trips(
            status: TripStatus, asDriver: Boolean, userId: String, coordinator: Boolean,
        ): ZillitResult<List<TripRequest>> {
            tripCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun raiseTrip(draft: TripDraft, coordinator: Boolean) =
            ZillitResult.Success(Unit)
        override suspend fun updateTrip(update: TripUpdate, nowMs: Long) = ZillitResult.Success(Unit)
        override suspend fun sendReminder(tripId: String) = ZillitResult.Success(Unit)
        override suspend fun permanentTrips(
            asDriver: Boolean, userId: String, coordinator: Boolean, status: PermanentStatus?,
        ): ZillitResult<List<PermanentTrip>> {
            permanentCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun myPermanentTrips(asDriver: Boolean, userId: String):
            ZillitResult<List<PermanentTrip>> {
            permanentCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createPermanent(draft: PermanentDraft) = ZillitResult.Success(Unit)
        override suspend fun updatePermanent(id: String, draft: PermanentDraft, status: PermanentStatus) =
            ZillitResult.Success(Unit)
        override suspend fun unassignPermanent(trip: PermanentTrip) = ZillitResult.Success(Unit)
        override suspend fun deletePermanent(id: String) = ZillitResult.Success(Unit)
        override suspend fun setSelfManage(id: String, selfManage: Boolean) = ZillitResult.Success(Unit)
    }

    private fun model(repository: FakeRepository) = TransportViewModel(
        repository = repository,
        resolveViewer = { TransportViewer(userId = "u1", canPost = true, ready = true) },
        nowMillis = { 0L },
    )

    @Test
    fun `each kind re-runs only its load, once per event`() = runTest(dispatcher) {
        val events = MutableSharedFlow<TransportSyncKind>()
        val repository = FakeRepository(events)
        val model = model(repository)

        model.start() // refresh: crew=1, vehicles=1; section Requests: trips=1
        runCurrent()
        assertEquals(1, repository.crewCalls)
        assertEquals(1, repository.vehicleCalls)
        assertEquals(1, repository.tripCalls)

        events.emit(TransportSyncKind.Fleet)
        runCurrent()
        assertEquals(2, repository.crewCalls, "Fleet reloads the crew")
        assertEquals(2, repository.vehicleCalls, "Fleet reloads the vehicles")
        assertEquals(1, repository.tripCalls, "Fleet leaves the trips alone")

        events.emit(TransportSyncKind.Trips)
        runCurrent()
        assertEquals(2, repository.tripCalls, "Trips reloads the visible request list")
        assertEquals(2, repository.crewCalls, "Trips leaves the crew alone")

        events.emit(TransportSyncKind.Permanent)
        runCurrent()
        assertEquals(3, repository.crewCalls, "Permanent refetches the users too (ZL-13708)")
        assertEquals(0, repository.permanentCalls, "the allocations are not on screen")

        model.start() // the window reopening must not add a second collector
        runCurrent()
        events.emit(TransportSyncKind.Trips)
        runCurrent()
        assertEquals(4, repository.tripCalls, "start reloads, the event reloads ONCE")
    }

    @Test
    fun `every shipped wire name maps to a kind, and only another production is dropped`() {
        assertEquals(
            TransportSyncKind.Fleet,
            TRANSPORT_SYNC_KINDS[SocketEventName("transportation:vehicle:created")],
        )
        assertEquals(
            TransportSyncKind.Trips,
            TRANSPORT_SYNC_KINDS[SocketEventName("transportation:trip:request:driver:assigned")],
        )
        assertEquals(
            TransportSyncKind.Permanent,
            TRANSPORT_SYNC_KINDS[SocketEventName("transportation:permanent:trip:request:updated")],
        )

        assertTrue(Json.parseToJsonElement("""{"project_id":"p1"}""").matchesProject("p1"))
        assertFalse(Json.parseToJsonElement("""{"project_id":"p2"}""").matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
    }
}
