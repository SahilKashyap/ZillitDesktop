package com.zillit.desktop.feature.transporthub.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What a hub socket frame touches.
 *
 * The web subscribes per page to named event groups; the groups collapse to
 * these five because that is how many independent loads the hub has. A frame
 * for another production is dropped before it gets here.
 */
enum class HubSyncKind { Fleet, Drivers, Trips, Rides, Permanent }

/** What a vehicle list is narrowed by. Every axis is optional. */
data class VehicleFilter(
    val allocation: VehicleAllocationFilter = VehicleAllocationFilter.All,
    /** Empty means every type. */
    val types: List<String> = emptyList(),
    val ownership: VehicleOwnership = VehicleOwnership.All,
    val search: String = "",
) {
    val isEmpty: Boolean
        get() = allocation == VehicleAllocationFilter.All &&
            types.isEmpty() &&
            ownership == VehicleOwnership.All &&
            search.isBlank()

    /** How many axes a coordinator has set — the badge on the filter button. */
    val activeAxisCount: Int
        get() = listOf(
            allocation != VehicleAllocationFilter.All,
            types.isNotEmpty(),
            ownership != VehicleOwnership.All,
        ).count { it }
}

/**
 * The four buckets the Fleet tabs offer.
 *
 * [InUse] is three server statuses at once, which is why this is its own type
 * rather than a [VehicleAllocation]: the query sends them comma-separated.
 */
enum class VehicleAllocationFilter(val query: String?) {
    All(null),
    Available("available"),
    InUse("assigned,allocated,permanent"),
    Maintenance("maintenance"),
}

enum class VehicleOwnership(val query: String?) {
    All(null),
    Private("private"),
    OwnerManaged("owner-managed"),
}

/** A vehicle being created or edited. */
data class VehicleDraft(
    val name: String = "",
    val licensePlate: String = "",
    val vehicleType: String = "",
    val capacity: Int? = null,
    val fuelType: String = "",
    val isPrivate: Boolean = true,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val color: String = "",
    /** At most four; the form refuses more. */
    val attachments: List<HubAttachment> = emptyList(),
)

/** A driver being created. */
data class DriverDraft(
    val userId: String = "",
    val fullName: String = "",
    val phone: String = "",
    val licenseNumber: String = "",
    val designation: String = "",
    val isTempDriver: Boolean = false,
    /**
     * True for "Ignore & create": the server then accepts a blank phone and
     * licence and files the record under the chase-list. Phone and licence
     * must still go out as strings — the route answers 406 on a non-string,
     * so a blank is `""`, never null.
     */
    val pendingDetails: Boolean = false,
    val licenseFrontUrl: String = "",
    val licenseBackUrl: String = "",
)

/** The two fields the chase-list exists to fill in. */
data class DriverDetailsPatch(
    val phone: String? = null,
    val licenseNumber: String? = null,
    val licenseFrontUrl: String? = null,
    val licenseBackUrl: String? = null,
)

/** A ride request being created or edited. */
data class RideDraft(
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val passengers: List<HubPassenger> = emptyList(),
    val stops: List<HubStop> = emptyList(),
    val priority: RidePriority = RidePriority.Normal,
    val notes: String = "",
    /** Null means "as soon as possible"; the server is sent "now". */
    val scheduledPickupTimeMs: Long? = null,
)

/** A trip being created. */
data class TripDraft(
    val title: String = "",
    val tripType: String = "one_time",
    val priority: RidePriority = RidePriority.Normal,
    val passengers: List<HubPassenger> = emptyList(),
    val notes: String = "",
    val driverId: String? = null,
    val vehicleId: String? = null,
)

/** A permanent allocation being created or edited. */
data class PermanentDraft(
    val title: String = "",
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val passengers: List<HubPassenger> = emptyList(),
    val startDateMs: Long = 0,
    val endDateMs: Long = 0,
    val scheduledTimeMs: Long = 0,
    val activeDays: List<HubDay> = emptyList(),
    val isFullDayTrip: Boolean = false,
    val selfManage: Boolean = false,
    val isExclusive: Boolean = false,
    val notes: String = "",
    val state: PermanentState = PermanentState.Draft,
)

/**
 * The hub half of the transport service.
 *
 * Every route lives under `${TRANSPORTATION_BASE_URL}/api/v2/transportation`,
 * the same host the legacy tool uses and a different set of route folders.
 * Three things are true of all of them and are handled in the data layer, not
 * here: HTTP 200 with `status: 0` is a failure whose `message` is a label key;
 * a longitude is `long`; and several PUT validators require the record's id in
 * the **body** as well as the path, so the body-building helpers add it.
 */
@Suppress("TooManyFunctions") // One seam per server route; the hub has many.
interface HubRepository {

    /** A kind per socket frame from another client. Empty without a bus. */
    val refreshes: Flow<HubSyncKind> get() = emptyFlow()

    // Who is looking ---------------------------------------------------------

    /** The designations the server counts as a driver's. */
    suspend fun driverDesignations(): ZillitResult<List<String>>

    /** Project members, for the passenger and driver pickers. */
    suspend fun projectPeople(): ZillitResult<List<ProjectPerson>>

    // Fleet ------------------------------------------------------------------

    /** The whole fleet, or the slice [filter] describes. */
    suspend fun vehicles(filter: VehicleFilter = VehicleFilter()): ZillitResult<List<Vehicle>>

    /** Only the vehicles free to be assigned right now. */
    suspend fun availableVehicles(): ZillitResult<List<Vehicle>>

    /**
     * The project's type list, built-ins included.
     *
     * Reads the top-level `/vehicle-types`, not `/vehicle/types` — the latter
     * only ever answers the six built-ins and never a production's own.
     */
    suspend fun vehicleTypes(): ZillitResult<List<VehicleTypeOption>>

    suspend fun addVehicleType(label: String): ZillitResult<Unit>
    suspend fun removeVehicleType(label: String): ZillitResult<Unit>

    suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?>
    suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?>

    /** Moves a vehicle between available and maintenance, and back. */
    suspend fun setVehicleAllocation(id: String, allocation: VehicleAllocation): ZillitResult<Vehicle?>

    /** `DELETE /vehicle` takes the ids in the body, as `vehicleIds`. */
    suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit>

    // Drivers ----------------------------------------------------------------

    suspend fun drivers(): ZillitResult<List<Driver>>

    suspend fun availableDrivers(): ZillitResult<List<Driver>>

    /**
     * The chase-list: drivers missing a phone or a licence.
     *
     * Its own query (`?details=incomplete`) rather than a filter over
     * [drivers], because the server resolves `missing_fields` and `added_by`
     * for it and leaves virtual drivers — designation-only users with no
     * record yet — out.
     */
    suspend fun incompleteDrivers(): ZillitResult<List<Driver>>

    suspend fun addDriver(draft: DriverDraft): ZillitResult<Driver?>

    suspend fun updateDriverDetails(id: String, patch: DriverDetailsPatch): ZillitResult<Driver?>

    suspend fun setDriverDuty(id: String, onDuty: Boolean): ZillitResult<Driver?>

    /** `DELETE /driver` takes the ids in the body, as `driverIds`. */
    suspend fun deleteDrivers(ids: List<String>): ZillitResult<Unit>

    // Trips ------------------------------------------------------------------

    /**
     * Every trip, unfiltered.
     *
     * The server's `status` parameter is unreliable — it does not group the
     * in-flight statuses under one bucket and silently ignores some values —
     * so the whole list comes back and the tabs filter it here.
     */
    suspend fun trips(): ZillitResult<List<Trip>>

    suspend fun trip(id: String): ZillitResult<Trip?>

    suspend fun tripEvents(id: String): ZillitResult<List<TripEvent>>

    suspend fun addTrip(draft: TripDraft): ZillitResult<String?>

    /**
     * Puts a driver and a vehicle on a trip.
     *
     * Needed straight after [addTrip] when the coordinator picked a driver:
     * `POST /trip` ignores `driver_id` and answers `pending`, which hides the
     * trip from the driver's own feed.
     */
    suspend fun assignTrip(id: String, driverId: String, vehicleId: String?): ZillitResult<Unit>

    suspend fun setTripStatus(id: String, status: TripStatus): ZillitResult<Unit>

    suspend fun sendTripReminder(id: String): ZillitResult<Unit>

    suspend fun deleteTrip(id: String): ZillitResult<Unit>

    // Ride requests ----------------------------------------------------------

    /**
     * The ride requests a viewer may see.
     *
     * [onlyMine] is required for a driver or a passenger — a coordinator who
     * sends no scope sees everything. Rejected rides are fetched separately
     * and merged, because the default list leaves them out.
     */
    suspend fun rideRequests(onlyMine: Boolean = false): ZillitResult<List<RideRequest>>

    suspend fun activeCars(): ZillitResult<List<ActiveCar>>

    suspend fun addRideRequest(draft: RideDraft): ZillitResult<String?>

    suspend fun updateRideRequest(id: String, draft: RideDraft): ZillitResult<Unit>

    suspend fun approveRide(id: String): ZillitResult<Unit>

    suspend fun rejectRide(id: String, reason: String): ZillitResult<Unit>

    /**
     * Puts a driver and a vehicle on a ride.
     *
     * Both naming conventions go out together (`driver_id` with
     * `assigned_driver`, `vehicle_id` with `assigned_vehicle`): the dev
     * backend flips the status but drops the ids, and sending both means the
     * desktop works the moment that is fixed, with no release here.
     */
    suspend fun assignRide(id: String, driverId: String?, vehicleId: String?): ZillitResult<Unit>

    suspend fun setRideStatus(id: String, status: RideStatus): ZillitResult<Unit>

    /** A soft cancel, by the requester or the coordinator. Takes a reason. */
    suspend fun cancelRide(id: String, reason: String): ZillitResult<Unit>

    suspend fun addRideStop(id: String, stop: HubStop): ZillitResult<Unit>

    /** Completes the stop at [index] — the server addresses it by position. */
    suspend fun completeRideStop(id: String, index: Int): ZillitResult<Unit>

    suspend fun addRidePassenger(id: String, passenger: HubPassenger): ZillitResult<Unit>

    // Permanent allocations --------------------------------------------------

    suspend fun permanentTrips(): ZillitResult<List<PermanentTrip>>

    suspend fun addPermanentTrip(draft: PermanentDraft): ZillitResult<PermanentTrip?>

    suspend fun updatePermanentTrip(id: String, draft: PermanentDraft): ZillitResult<PermanentTrip?>

    /**
     * Promotes a draft to active by re-sending the whole record.
     *
     * Not `/activate`: that shortcut skips the validator and would flip an
     * empty draft's runtime status while leaving it unfit to run. Sending the
     * record back with `status: "active"` makes the server check it.
     */
    suspend fun activateDraft(trip: PermanentTrip): ZillitResult<PermanentTrip?>

    /** Resumes a paused allocation. Safe here: the record already validated. */
    suspend fun resumePermanentTrip(id: String): ZillitResult<PermanentTrip?>

    suspend fun pausePermanentTrip(id: String): ZillitResult<PermanentTrip?>

    suspend fun swapPermanentDriver(id: String, driverId: String, reason: String): ZillitResult<PermanentTrip?>

    suspend fun swapPermanentVehicle(id: String, vehicleId: String, reason: String): ZillitResult<PermanentTrip?>

    suspend fun deletePermanentTrip(id: String): ZillitResult<Unit>
}
