package com.zillit.desktop.feature.transportation.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What a transport wire event touches — the web splits its handlers the
 * same three ways: vehicles-and-drivers, the trip-request lists, and the
 * permanent allocations (which also refetch the users, ZL-13708).
 */
enum class TransportSyncKind { Fleet, Trips, Permanent }

/** The transport service, `/api/v2/transportation`. */
@Suppress("TooManyFunctions") // One seam per server route: vehicles, trips, permanents, drivers, users.
interface TransportRepository {

    /**
     * A [TransportSyncKind] per socket frame from another client — the
     * page-and-modal handlers on the web's `Transportation.jsx` and its
     * `transportationComponents`. The ViewModel re-runs the matching load.
     * Empty by default: tests, and hosts without a socket.
     */
    val refreshes: Flow<TransportSyncKind> get() = emptyFlow()

    // Vehicles ---------------------------------------------------------------
    suspend fun vehicles(): ZillitResult<List<Vehicle>>
    suspend fun vehicleTypes(): ZillitResult<List<String>>
    suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?>
    suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?>
    suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit>

    // Drivers ----------------------------------------------------------------
    /** The designations that make a crew member a driver — loaded by the web's header, not the tool. */
    suspend fun driverDesignations(): ZillitResult<List<String>>

    /** `project/users` with the driver flags. */
    suspend fun crew(): ZillitResult<List<TransportUser>>

    /** The users holding the tool's posting right — the coordinators. */
    suspend fun postingRightUsers(): ZillitResult<List<String>>

    /** `driver/update-details` — any subset: vehicle, temp-driver flag, availability. */
    suspend fun updateDriver(
        userId: String,
        vehicleId: String? = null,
        isTempDriver: Boolean? = null,
        isTripAssigned: Boolean? = null,
    ): ZillitResult<Unit>

    suspend fun pendingDocumentReminder(userId: String, type: String, message: String?): ZillitResult<Unit>

    // Trip requests ----------------------------------------------------------
    suspend fun trips(status: TripStatus, asDriver: Boolean, userId: String,
        coordinator: Boolean): ZillitResult<List<TripRequest>>
    suspend fun raiseTrip(draft: TripDraft, coordinator: Boolean): ZillitResult<Unit>
    suspend fun updateTrip(update: TripUpdate, nowMs: Long): ZillitResult<Unit>
    suspend fun sendReminder(tripId: String): ZillitResult<Unit>

    // Permanent allocations --------------------------------------------------
    suspend fun permanentTrips(asDriver: Boolean, userId: String, coordinator: Boolean,
        status: PermanentStatus?): ZillitResult<List<PermanentTrip>>
    suspend fun myPermanentTrips(asDriver: Boolean, userId: String): ZillitResult<List<PermanentTrip>>
    suspend fun createPermanent(draft: PermanentDraft): ZillitResult<Unit>
    suspend fun updatePermanent(id: String, draft: PermanentDraft, status: PermanentStatus): ZillitResult<Unit>
    suspend fun unassignPermanent(trip: PermanentTrip): ZillitResult<Unit>
    suspend fun deletePermanent(id: String): ZillitResult<Unit>
    suspend fun setSelfManage(id: String, selfManage: Boolean): ZillitResult<Unit>
}
