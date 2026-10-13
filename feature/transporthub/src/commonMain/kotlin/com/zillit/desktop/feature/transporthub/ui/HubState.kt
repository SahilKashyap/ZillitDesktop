package com.zillit.desktop.feature.transporthub.ui

import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.StopAction
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripEvent
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleFilter
import com.zillit.desktop.feature.transporthub.domain.VehicleTypeOption

/**
 * A section in the hub's rail.
 *
 * The web gives each role its own route set and its own sidebar; one enum
 * covers all three here, and [visibleTo] is the single gate. The order is the
 * web's sidebar order, which is not the order the enum would fall into
 * alphabetically — a coordinator reads Dashboard, Trips, Ride Requests, Fleet
 * before the rest.
 *
 * The sections phase 1 does not port keep their place in the rail and say so
 * when opened, rather than being hidden: a coordinator who can see Shuttles on
 * the web should not have to wonder whether the desktop lost the data.
 */
enum class HubSection(
    val slug: String,
    val badgeBucket: HubBadgeBucket?,
    private val roles: Set<HubRole>,
    val isPorted: Boolean = true,
) {
    Trips("trips", HubBadgeBucket.Trips, setOf(HubRole.Coordinator)),
    RideRequests("ride-requests", HubBadgeBucket.Rides, setOf(HubRole.Coordinator)),
    Fleet("fleet", HubBadgeBucket.Fleet, setOf(HubRole.Coordinator)),
    Drivers("drivers", HubBadgeBucket.Drivers, setOf(HubRole.Coordinator)),
    Allocations("permanent-allocations", HubBadgeBucket.Allocations, setOf(HubRole.Coordinator)),

    /** A driver's or a passenger's own trips — the whole trip unit's count. */
    MyTrips("", HubBadgeBucket.MyTrips, setOf(HubRole.Driver, HubRole.Passenger)),

    /** A driver's assigned rides; a passenger's own requests. Same bucket. */
    MyRides("my-rides", HubBadgeBucket.Rides, setOf(HubRole.Driver, HubRole.Passenger)),

    // Not in phase 1. Present so the rail matches the web's.
    Dashboard("dashboard", null, setOf(HubRole.Coordinator), isPorted = false),
    ActionVehicles(
        "action-vehicles",
        HubBadgeBucket.ActionVehicles,
        setOf(HubRole.Coordinator),
        isPorted = false,
    ),
    Shuttles("shuttles", HubBadgeBucket.Shuttles, setOf(HubRole.Coordinator), isPorted = false),
    LiveTracking("live-map", null, setOf(HubRole.Coordinator), isPorted = false),
    Maintenance(
        "maintenance",
        HubBadgeBucket.Maintenance,
        setOf(HubRole.Coordinator),
        isPorted = false,
    ),
    DriverMap("driver-map", null, setOf(HubRole.Driver), isPorted = false),
    ;

    fun visibleTo(viewer: HubViewer): Boolean = viewer.canView && viewer.role in roles

    companion object {
        /** The rail for [viewer], in the web's order. */
        fun railFor(viewer: HubViewer): List<HubSection> = entries.filter { it.visibleTo(viewer) }

        /** Where a role lands with no section chosen. */
        fun homeFor(role: HubRole): HubSection = when (role) {
            HubRole.Coordinator -> Trips
            HubRole.Driver, HubRole.Passenger -> MyTrips
        }
    }
}

/**
 * The Trips list's tabs.
 *
 * A tab carries the statuses it accepts, not its own label: a label read at
 * construction would be fixed in whatever language the app started in, and
 * the switch would leave the strip in the old one.
 */
enum class TripTab(val statuses: List<TripStatus>) {
    All(emptyList()),

    /**
     * Pending holds three server statuses: a trip the coordinator has not
     * acted on yet is `pending`, a half-saved one `draft`, and one approved
     * but unassigned `approved`. All three read as "needs a car".
     */
    Pending(listOf(TripStatus.Pending, TripStatus.Draft, TripStatus.Approved)),
    Assigned(listOf(TripStatus.Assigned)),

    /** Every in-flight status at once — the coordinator sees one bucket. */
    InTransit(
        listOf(
            TripStatus.EnRoutePickup,
            TripStatus.AtPickup,
            TripStatus.InTransit,
            TripStatus.AtDropoff,
        ),
    ),
    Completed(listOf(TripStatus.Completed)),
    Cancelled(listOf(TripStatus.Cancelled)),
    Rejected(listOf(TripStatus.Rejected)),
    ;

    fun accepts(trip: Trip): Boolean = statuses.isEmpty() || trip.status in statuses
}

/** The Ride Requests list's tabs. See [TripTab] on why no label is stored. */
enum class RideTab {
    Pending,
    Active,
    Completed,
    Rejected,
    ;

    fun accepts(ride: RideRequest): Boolean = when (this) {
        Pending -> ride.status == RideStatus.Pending
        // Approved but not yet moving still counts as active: a car is owed.
        Active -> ride.status == RideStatus.Approved || ride.status.isActive
        Completed -> ride.status == RideStatus.Completed || ride.status == RideStatus.Cancelled
        Rejected -> ride.status == RideStatus.Rejected
    }
}

/** The Drivers page's stat cards, which double as its filter. */
enum class DriverTab {
    All,
    Available,
    Unavailable,

    /** The chase-list. Its own server query, not a filter over the list. */
    Incomplete,
}

/** The Allocations page's tabs. See [TripTab] on why no label is stored. */
enum class AllocationTab {
    All,
    Active,
    Drafts,
    Paused,
    ;

    fun accepts(trip: PermanentTrip): Boolean = when (this) {
        All -> true
        Active -> trip.state == PermanentState.Active
        Drafts -> trip.state == PermanentState.Draft
        Paused -> trip.state == PermanentState.Paused
    }
}

/** A vehicle being created or edited, as the form holds it. */
data class VehicleEditor(
    val id: String? = null,
    val name: String = "",
    val licensePlate: String = "",
    val vehicleType: String = "",
    val capacity: String = "",
    val fuelType: String = "",
    val isPrivate: Boolean = true,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val color: String = "",
    val photos: List<PhotoSlot> = emptyList(),
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = id != null

    companion object {
        /** At most four photographs; the form refuses the fifth. */
        const val MAX_PHOTOS = 4

        fun from(vehicle: Vehicle): VehicleEditor = VehicleEditor(
            id = vehicle.id,
            name = vehicle.name,
            licensePlate = vehicle.licensePlate,
            vehicleType = vehicle.vehicleType,
            capacity = vehicle.capacity?.toString().orEmpty(),
            fuelType = vehicle.fuelType,
            isPrivate = vehicle.isPrivate,
            ownerName = vehicle.ownerName,
            ownerContactNumber = vehicle.ownerContactNumber,
            make = vehicle.make,
            model = vehicle.model,
            year = vehicle.year?.toString().orEmpty(),
            color = vehicle.color,
            photos = vehicle.attachments.map { PhotoSlot(attachment = it) },
        )
    }
}

/** One picture on a vehicle: already stored, or just picked. */
data class PhotoSlot(
    val attachment: com.zillit.desktop.feature.transporthub.domain.HubAttachment,
    /** Present only for a picture picked in this session, for the thumbnail. */
    val bytes: ByteArray? = null,
) {
    // A data class holding a ByteArray needs these by hand: the generated
    // ones compare the array by identity, so two slots over the same bytes
    // would never be equal and the grid would redraw on every recomposition.
    override fun equals(other: Any?): Boolean =
        other is PhotoSlot && other.attachment == attachment

    override fun hashCode(): Int = attachment.hashCode()
}

/** A driver being created. */
data class DriverEditor(
    val userId: String = "",
    val fullName: String = "",
    val phone: String = "",
    val licenseNumber: String = "",
    val designation: String = "",
    val isTempDriver: Boolean = false,
    /**
     * True once a non-driver was picked.
     *
     * Such a person can only be added through the temporary-driver path, so
     * the checkbox is ticked for the coordinator and must then stay ticked.
     */
    val tempDriverLocked: Boolean = false,
    val licenseFront: PhotoSlot? = null,
    val licenseBack: PhotoSlot? = null,
    val saving: Boolean = false,
) {
    fun toDraft(pendingDetails: Boolean): DriverDraft = DriverDraft(
        userId = userId,
        fullName = fullName.trim(),
        phone = phone.trim(),
        licenseNumber = licenseNumber.trim(),
        designation = designation,
        isTempDriver = isTempDriver,
        pendingDetails = pendingDetails,
        licenseFrontUrl = licenseFront?.attachment?.media.orEmpty(),
        licenseBackUrl = licenseBack?.attachment?.media.orEmpty(),
    )
}

/** The chase-list's "fill in the rest" form. */
data class DriverDetailsEditor(
    val driverId: String,
    val driverName: String = "",
    val phone: String = "",
    val licenseNumber: String = "",
    val licenseFront: PhotoSlot? = null,
    val licenseBack: PhotoSlot? = null,
    val saving: Boolean = false,
)

/** A ride request being raised or edited. */
data class RideEditor(
    val id: String? = null,
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val passengers: List<HubPassenger> = emptyList(),
    val stops: List<HubStop> = emptyList(),
    val priority: RidePriority = RidePriority.Normal,
    val notes: String = "",
    /** True leaves the time unset: the ride is wanted now. */
    val asSoonAsPossible: Boolean = true,
    val scheduledPickupTimeMs: Long? = null,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = id != null
}

/** A trip being created. */
data class TripEditor(
    val title: String = "",
    val tripType: String = "one_time",
    val priority: RidePriority = RidePriority.Normal,
    val dateMs: Long = 0,
    val passengers: List<HubPassenger> = emptyList(),
    val notes: String = "",
    val driverId: String? = null,
    val vehicleId: String? = null,
    val saving: Boolean = false,
)

/** A permanent allocation being created or edited. */
data class AllocationEditor(
    val id: String? = null,
    val title: String = "",
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val passengers: List<HubPassenger> = emptyList(),
    val startDateMs: Long = 0,
    val endDateMs: Long = 0,
    val scheduledTimeMs: Long = 0,
    val activeDays: List<HubDay> = HubDay.weekdays,
    val isFullDayTrip: Boolean = false,
    val selfManage: Boolean = false,
    val isExclusive: Boolean = false,
    val notes: String = "",
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = id != null

    /** True when there is anything worth offering to keep as a draft. */
    val hasContent: Boolean
        get() = title.isNotBlank() || !pickup.isEmpty || !dropoff.isEmpty ||
            driverId != null || vehicleId != null || notes.isNotBlank() ||
            startDateMs > 0 || endDateMs > 0 || scheduledTimeMs > 0 ||
            passengers.any { it.name.isNotBlank() || !it.pickup.isEmpty || !it.dropoff.isEmpty }

    companion object {
        fun from(trip: PermanentTrip): AllocationEditor = AllocationEditor(
            id = trip.id,
            title = trip.title,
            pickup = trip.pickup,
            dropoff = trip.dropoff,
            driverId = trip.driverId,
            vehicleId = trip.vehicleId,
            passengers = trip.passengers,
            startDateMs = trip.startDateMs,
            endDateMs = trip.endDateMs,
            scheduledTimeMs = trip.scheduledTimeMs,
            activeDays = trip.activeDays.ifEmpty { HubDay.weekdays },
            isFullDayTrip = trip.isFullDayTrip,
            selfManage = trip.selfManage,
            isExclusive = trip.isExclusive,
            notes = trip.notes,
        )
    }
}

/** Swapping the driver or the vehicle out of a permanent allocation. */
data class SwapEditor(
    val tripId: String,
    val isDriver: Boolean,
    val pickedId: String? = null,
    val reason: String = "",
    val saving: Boolean = false,
)

/** Adding a stop to a ride in flight. */
data class StopEditor(
    val rideId: String,
    val location: HubLocation = HubLocation.Empty,
    val action: StopAction = StopAction.Pickup,
    val passengerName: String = "",
    val saving: Boolean = false,
)

/** Adding a passenger to a ride in flight. */
data class RidePassengerEditor(
    val rideId: String,
    val userId: String? = null,
    val name: String = "",
    val contactNumber: String = "",
    /** Blank means "the ride's own pickup" — the server is sent the ride's. */
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val saving: Boolean = false,
)

/** A reason being typed before a refusal or a cancellation. */
data class ReasonEditor(
    val rideId: String,
    val isCancel: Boolean,
    val reason: String = "",
    val saving: Boolean = false,
)

/** Putting a driver and a vehicle on a ride. */
data class AssignEditor(
    val rideId: String,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val saving: Boolean = false,
)

/** A confirmation the person must answer before something irreversible. */
data class HubConfirm(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val isDestructive: Boolean = true,
    val action: HubEvent,
)

/** Everything the hub renders. */
data class HubUiState(
    val viewer: HubViewer = HubViewer.Empty,
    val section: HubSection = HubSection.Trips,
    val loading: Boolean = false,
    /** Set while one row is mid-mutation, so only that row shows a spinner. */
    val busyRowId: String = "",
    val error: String? = null,

    // Shared directories, loaded once and read by every section.
    val vehicles: List<Vehicle> = emptyList(),
    val availableVehicles: List<Vehicle> = emptyList(),
    val vehicleTypes: List<VehicleTypeOption> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val availableDrivers: List<Driver> = emptyList(),
    val incompleteDrivers: List<Driver> = emptyList(),
    val people: List<ProjectPerson> = emptyList(),

    // Per-section data.
    val trips: List<Trip> = emptyList(),
    val rides: List<RideRequest> = emptyList(),
    val allocations: List<PermanentTrip> = emptyList(),
    val activeCars: List<ActiveCar> = emptyList(),
    val openTrip: Trip? = null,
    val openTripEvents: List<TripEvent> = emptyList(),

    // Tabs and searches.
    val tripTab: TripTab = TripTab.All,
    val tripSearch: String = "",
    val tripDateMs: Long = 0,
    val rideTab: RideTab = RideTab.Pending,
    val rideSearch: String = "",
    val onlyMyRides: Boolean = false,
    val driverTab: DriverTab = DriverTab.All,
    val driverSearch: String = "",
    val allocationTab: AllocationTab = AllocationTab.All,
    val allocationSearch: String = "",
    val vehicleFilter: VehicleFilter = VehicleFilter(),
    val vehicleSearch: String = "",

    // Open editors. At most one is non-null at a time.
    val vehicleEditor: VehicleEditor? = null,
    val driverEditor: DriverEditor? = null,
    val driverDetailsEditor: DriverDetailsEditor? = null,
    val rideEditor: RideEditor? = null,
    val tripEditor: TripEditor? = null,
    val allocationEditor: AllocationEditor? = null,
    val swapEditor: SwapEditor? = null,
    val stopEditor: StopEditor? = null,
    val ridePassengerEditor: RidePassengerEditor? = null,
    val reasonEditor: ReasonEditor? = null,
    val assignEditor: AssignEditor? = null,
    val confirm: HubConfirm? = null,
    /** Offered when a half-filled allocation form is being closed. */
    val draftPrompt: AllocationEditor? = null,
    val showActiveCars: Boolean = false,

    val badges: Map<String, Int> = emptyMap(),
) {
    val role: HubRole get() = viewer.role

    val rail: List<HubSection> get() = HubSection.railFor(viewer)

    fun badge(section: HubSection): Int = section.badgeBucket?.let { badges[it.name] } ?: 0

    // Derived lists. Each mirrors the web's filter for that page, and each is
    // the one place that page's rules live.

    /**
     * The trips the chosen tab and search leave.
     *
     * The whole list is fetched and filtered here because the server's own
     * `status` parameter does not group the in-flight statuses and silently
     * ignores some values — see [HubRepository.trips].
     */
    val visibleTrips: List<Trip>
        get() = trips
            .filter { tripTab.accepts(it) }
            .filter { trip -> tripDateMs == 0L || trip.sameDayAs(tripDateMs) }
            .filter { trip -> trip.matches(tripSearch) }

    val visibleRides: List<RideRequest>
        get() = scopedRides.filter { rideTab.accepts(it) }.filter { it.matches(rideSearch) }

    /**
     * The rides every count on the page is taken from.
     *
     * With "my requests" on, the stat cards and the tab counts narrow too —
     * otherwise they would disagree with the list under them.
     */
    val scopedRides: List<RideRequest>
        get() = if (onlyMyRides) rides.filter { it.isOwnedBy(viewer.userId) } else rides

    val visibleDrivers: List<Driver>
        get() = when (driverTab) {
            DriverTab.Incomplete -> incompleteDrivers
            DriverTab.Available -> drivers.filter { it.isOnDuty }
            DriverTab.Unavailable -> drivers.filterNot { it.isOnDuty }
            DriverTab.All -> drivers
        }.filter { it.matches(driverSearch) }

    val visibleAllocations: List<PermanentTrip>
        get() = allocations
            // A completed allocation is end-of-life: it would only bloat the
            // counts and the All tab.
            .filterNot { it.state == PermanentState.Completed }
            .filter { allocationTab.accepts(it) }
            .filter { it.matches(allocationSearch, drivers, vehicles) }

    val driverCounts: Map<DriverTab, Int>
        get() = mapOf(
            DriverTab.All to drivers.size,
            DriverTab.Available to drivers.count { it.isOnDuty },
            DriverTab.Unavailable to drivers.count { !it.isOnDuty },
            DriverTab.Incomplete to incompleteDrivers.size,
        )

    val allocationCounts: Map<AllocationTab, Int>
        get() {
            val live = allocations.filterNot { it.state == PermanentState.Completed }
            return AllocationTab.entries.associateWith { tab -> live.count { tab.accepts(it) } }
        }

    val rideCounts: Map<RideTab, Int>
        get() = RideTab.entries.associateWith { tab -> scopedRides.count { tab.accepts(it) } }

    /** Pending rides marked urgent — the card that asks to be looked at first. */
    val urgentPendingRides: Int
        get() = scopedRides.count {
            it.status == RideStatus.Pending && it.priority == RidePriority.Urgent
        }

    fun driverName(id: String?): String {
        if (id.isNullOrBlank()) return ""
        // Indexed by both ids: a reference points at the driver record on one
        // route and at the user behind it on another.
        return drivers.firstOrNull { it.id == id || it.userId == id }?.displayName.orEmpty()
    }

    fun vehicleLabel(id: String?): String {
        if (id.isNullOrBlank()) return ""
        return vehicles.firstOrNull { it.id == id }?.displayName.orEmpty()
    }
}

/** One day's worth of milliseconds, for the Trips date filter. */
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun Trip.sameDayAs(dayStartMs: Long): Boolean {
    val time = scheduledPickupTimeMs ?: createdMs
    if (time <= 0) return false
    return time >= dayStartMs && time < dayStartMs + DAY_MS
}

private fun Trip.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val haystack = listOf(
        title, driverName, vehicleLabel, pickup.address, dropoff.address, id,
    ) + passengers.map { it.name }
    return haystack.any { it.lowercase().contains(q) }
}

private fun RideRequest.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val haystack = listOf(
        requesterName, assignedDriverName, assignedVehicleLabel,
        pickup.address, dropoff.address, notes, id,
    ) + passengers.map { it.name }
    return haystack.any { it.lowercase().contains(q) }
}

private fun Driver.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOf(fullName, phone, licenseNumber, id).any { it.lowercase().contains(q) }
}

private fun PermanentTrip.matches(
    query: String,
    drivers: List<Driver>,
    vehicles: List<Vehicle>,
): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val driver = drivers.firstOrNull { it.id == driverId || it.userId == driverId }?.displayName
    val vehicle = vehicles.firstOrNull { it.id == vehicleId }?.displayName
    val haystack = listOf(
        title, driver.orEmpty(), vehicle.orEmpty(), pickup.address, dropoff.address, id,
    ) + passengers.map { it.name }
    return haystack.any { it.lowercase().contains(q) }
}

/**
 * Everything a person can do in the hub.
 *
 * Grouped by the section that owns it, so the ViewModel can split its
 * dispatch five ways instead of running one `when` over ninety members.
 * The groups are markers only — the members stay flat under [HubEvent], so a
 * call site reads `HubEvent.ApproveRide`, not `HubEvent.Rides.ApproveRide`.
 */
sealed interface HubEvent {

    /** The shell: the rail, a reload, and the dialogs the shell owns. */
    sealed interface Shell : HubEvent

    sealed interface FleetEvent : HubEvent
    sealed interface DriverEvent : HubEvent
    sealed interface TripEvent : HubEvent
    sealed interface RideEvent : HubEvent
    sealed interface AllocationEvent : HubEvent

    data class SelectSection(val section: HubSection) : Shell
    data object Refresh : Shell
    data object DismissError : Shell
    data object DismissConfirm : Shell

    /** Carries out the action a [HubConfirm] was asked about. */
    data class Confirmed(val action: HubEvent) : Shell

    // Fleet
    data class VehicleSearchChanged(val query: String) : FleetEvent
    data class VehicleFilterChanged(val filter: VehicleFilter) : FleetEvent
    data object NewVehicle : FleetEvent
    data class EditVehicle(val vehicle: Vehicle) : FleetEvent
    data class VehicleEditorChanged(val editor: VehicleEditor) : FleetEvent
    data object PickVehiclePhotos : FleetEvent
    data class RemoveVehiclePhoto(val slot: PhotoSlot) : FleetEvent
    data object SaveVehicle : FleetEvent
    data object CloseVehicleEditor : FleetEvent
    data class SetVehicleAllocation(val id: String, val allocation: VehicleAllocation) : FleetEvent
    data class DeleteVehicle(val vehicle: Vehicle) : FleetEvent
    data class DeleteVehicleConfirmed(val id: String) : FleetEvent

    // Drivers
    data class DriverTabChanged(val tab: DriverTab) : DriverEvent
    data class DriverSearchChanged(val query: String) : DriverEvent
    data object NewDriver : DriverEvent
    data class DriverEditorChanged(val editor: DriverEditor) : DriverEvent
    data class PickDriverUser(val userId: String) : DriverEvent
    data class PickLicencePhoto(val isFront: Boolean) : DriverEvent
    data object SaveDriver : DriverEvent

    /** Saves the driver with the phone and licence left blank. */
    data object SaveDriverIgnoringDetails : DriverEvent
    data object CloseDriverEditor : DriverEvent
    data class CompleteDriverDetails(val driver: Driver) : DriverEvent
    data class DriverDetailsEditorChanged(val editor: DriverDetailsEditor) : DriverEvent
    data class PickDetailsLicencePhoto(val isFront: Boolean) : DriverEvent
    data object SaveDriverDetails : DriverEvent
    data object CloseDriverDetailsEditor : DriverEvent
    data class ToggleDriverDuty(val driver: Driver) : DriverEvent
    data class DeleteDriver(val driver: Driver) : DriverEvent
    data class DeleteDriverConfirmed(val id: String) : DriverEvent

    // Trips
    data class TripTabChanged(val tab: TripTab) : TripEvent
    data class TripSearchChanged(val query: String) : TripEvent
    data class TripDateChanged(val dayStartMs: Long) : TripEvent
    data object NewTrip : TripEvent
    data class TripEditorChanged(val editor: TripEditor) : TripEvent
    data object SaveTrip : TripEvent
    data object CloseTripEditor : TripEvent
    data class OpenTrip(val id: String) : TripEvent
    data object CloseTrip : TripEvent
    data class SetTripStatus(val id: String, val status: TripStatus) : TripEvent
    data class RemindTrip(val id: String) : TripEvent
    data class DeleteTrip(val trip: Trip) : TripEvent

    /** Carries the kind because the two are deleted by different routes. */
    data class DeleteTripConfirmed(val id: String, val kind: TripResourceKind) : TripEvent

    // Ride requests
    data class RideTabChanged(val tab: RideTab) : RideEvent
    data class RideSearchChanged(val query: String) : RideEvent
    data class OnlyMyRidesChanged(val onlyMine: Boolean) : RideEvent
    data object NewRide : RideEvent
    data class EditRide(val ride: RideRequest) : RideEvent
    data class RideEditorChanged(val editor: RideEditor) : RideEvent
    data object SaveRide : RideEvent
    data object CloseRideEditor : RideEvent
    data class ApproveRide(val ride: RideRequest) : RideEvent
    data class RejectRide(val ride: RideRequest) : RideEvent
    data class CancelRide(val ride: RideRequest) : RideEvent
    data class ReasonEditorChanged(val editor: ReasonEditor) : RideEvent
    data object SubmitReason : RideEvent
    data object CloseReasonEditor : RideEvent
    data class AssignRide(val ride: RideRequest) : RideEvent
    data class AssignEditorChanged(val editor: AssignEditor) : RideEvent
    data object SubmitAssign : RideEvent
    data object CloseAssignEditor : RideEvent
    data class StartRide(val ride: RideRequest) : RideEvent
    data class CompleteRide(val ride: RideRequest) : RideEvent
    data class AddRideStop(val ride: RideRequest) : RideEvent
    data class StopEditorChanged(val editor: StopEditor) : RideEvent
    data object SubmitStop : RideEvent
    data object CloseStopEditor : RideEvent
    data class CompleteStop(val rideId: String, val index: Int) : RideEvent
    data class AddRidePassenger(val ride: RideRequest) : RideEvent
    data class RidePassengerEditorChanged(val editor: RidePassengerEditor) : RideEvent
    data object SubmitRidePassenger : RideEvent
    data object CloseRidePassengerEditor : RideEvent
    data object ShowActiveCars : RideEvent
    data object HideActiveCars : RideEvent

    // Allocations
    data class AllocationTabChanged(val tab: AllocationTab) : AllocationEvent
    data class AllocationSearchChanged(val query: String) : AllocationEvent
    data object NewAllocation : AllocationEvent
    data class EditAllocation(val trip: PermanentTrip) : AllocationEvent
    data class AllocationEditorChanged(val editor: AllocationEditor) : AllocationEvent
    data object SaveAllocation : AllocationEvent

    /** Closes the form, offering to keep a half-filled one as a draft. */
    data object RequestCloseAllocation : AllocationEvent
    data object SaveAllocationAsDraft : AllocationEvent
    data object DiscardAllocation : AllocationEvent
    data class ToggleAllocationState(val trip: PermanentTrip) : AllocationEvent
    data class DeleteAllocation(val trip: PermanentTrip) : AllocationEvent
    data class DeleteAllocationConfirmed(val id: String) : AllocationEvent
    data class SwapAllocationDriver(val trip: PermanentTrip) : AllocationEvent
    data class SwapAllocationVehicle(val trip: PermanentTrip) : AllocationEvent
    data class SwapEditorChanged(val editor: SwapEditor) : AllocationEvent
    data object SubmitSwap : AllocationEvent
    data object CloseSwapEditor : AllocationEvent
}

/** One-shot things the hub asks the window around it to do. */
sealed interface HubEffect {
    data class Notice(val text: String, val success: Boolean = true) : HubEffect
    data class OpenLink(val url: String) : HubEffect
}
