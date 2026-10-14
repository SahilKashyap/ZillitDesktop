package com.zillit.desktop.feature.transporthub.domain

/**
 * The Transportation Hub's entities, as the v2 transport service speaks them.
 *
 * The hub is a second, newer surface on the same host as the legacy
 * Transportation tool: `${TRANSPORTATION_BASE_URL}/api/v2/transportation`.
 * Where the legacy tool talks to `/request` and `/vehicle`, the hub talks to
 * `/ride-request`, `/trip`, `/permanent-trip`, `/driver` and `/vehicle` — a
 * wider vocabulary with its own statuses. Nothing here is shared with
 * `feature:transportation` on purpose: the two tools are allowed to drift,
 * and the legacy one is frozen.
 *
 * Two wire conventions run through all of it, and both are easy to get wrong:
 *
 *  - a location is `{ address, lat, long }` — **`long`, not `lng`**. The web
 *    funnels every write through one converter for exactly this reason; here
 *    that is [HubLocation.toWire] in the data layer.
 *  - a status arrives from a vocabulary wider than any one screen shows, so
 *    every status enum keeps an [Unknown] member and parses leniently. A
 *    status the server adds tomorrow must not blank a list today.
 */

/** A point on the map: what the server stores for a pickup, a drop-off, a stop. */
data class HubLocation(
    val address: String = "",
    val lat: Double = 0.0,
    /** The server's spelling. `lng` appears only in map-picker code. */
    val long: Double = 0.0,
) {
    val hasCoordinates: Boolean get() = lat != 0.0 || long != 0.0
    val isEmpty: Boolean get() = address.isBlank() && !hasCoordinates

    companion object {
        val Empty = HubLocation()
    }
}

/** How urgently a ride wants a car. The server's default is [Normal]. */
enum class RidePriority(val wire: String) {
    Urgent("urgent"),
    High("high"),
    Normal("normal"),
    Low("low"),
    ;

    companion object {
        fun from(value: String?): RidePriority =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: Normal
    }
}

/**
 * A ride request's status.
 *
 * The server's vocabulary is wider than the five tabs a coordinator sees; the
 * extra in-flight states ([EnRoutePickup] … [AtDropoff]) all read as "in
 * progress" on screen. [InTransit] also arrives spelled `in_progress` and
 * `in-progress` from older handlers, so parsing accepts all three.
 */
enum class RideStatus(val wire: String, private val aliases: List<String> = emptyList()) {
    Pending("pending"),
    Approved("approved"),
    Assigned("assigned"),
    EnRoutePickup("en_route_pickup"),
    AtPickup("at_pickup"),
    InTransit("in_transit", listOf("in_progress", "in-progress")),
    AtDropoff("at_dropoff"),
    Completed("completed"),
    Rejected("rejected"),
    Cancelled("cancelled"),
    Unknown(""),
    ;

    /** True while a car is committed to the ride — the web's `ACTIVE_STATES`. */
    val isActive: Boolean
        get() = this in setOf(Assigned, EnRoutePickup, AtPickup, InTransit, AtDropoff)

    /** True once the ride can no longer move: nothing left to act on. */
    val isTerminal: Boolean get() = this in setOf(Completed, Rejected, Cancelled)

    companion object {
        fun from(value: String?): RideStatus {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return Pending
            return entries.firstOrNull { it.wire == raw || raw in it.aliases } ?: Unknown
        }
    }
}

/**
 * A trip's status — the same vocabulary as a ride plus `draft`.
 *
 * `/trip` returns both real trips and ride requests in one list (see
 * [TripResourceKind]), so the two status sets overlap by design.
 */
enum class TripStatus(val wire: String, private val aliases: List<String> = emptyList()) {
    Draft("draft"),
    Pending("pending"),
    Approved("approved"),
    Assigned("assigned"),
    EnRoutePickup("en_route_pickup"),
    AtPickup("at_pickup"),
    InTransit("in_transit", listOf("in_progress", "in-progress")),
    AtDropoff("at_dropoff"),
    Completed("completed"),
    Cancelled("cancelled"),
    Rejected("rejected"),
    Unknown(""),
    ;

    val isTerminal: Boolean get() = this in setOf(Completed, Cancelled, Rejected)

    companion object {
        fun from(value: String?): TripStatus {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return Draft
            return entries.firstOrNull { it.wire == raw || raw in it.aliases } ?: Unknown
        }
    }
}

/**
 * Which of the two things a `/trip` row actually is.
 *
 * The list mixes them, and it decides how a row is deleted: a ride request is
 * cancelled through `DELETE /ride-request/:id`, a trip through
 * `DELETE /trip/:id`. Getting it wrong answers `trip_not_found`.
 */
enum class TripResourceKind(val wire: String) {
    Trip("trip"),
    RideRequest("ride_request"),
    ShuttleRun("shuttle_run"),
    ;

    companion object {
        fun from(value: String?): TripResourceKind =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: Trip
    }
}

/** What a vehicle is currently committed to — the server's allocation enum. */
enum class VehicleAllocation(val wire: String) {
    Available("available"),
    Allocated("allocated"),
    Assigned("assigned"),
    Permanent("permanent"),
    Maintenance("maintenance"),
    Unknown(""),
    ;

    /** True when the vehicle is spoken for, whichever way. */
    val isInUse: Boolean get() = this in setOf(Allocated, Assigned, Permanent)

    companion object {
        fun from(value: String?): VehicleAllocation {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return Available
            return entries.firstOrNull { it.wire == raw } ?: Unknown
        }
    }
}

/**
 * A permanent allocation's lifecycle.
 *
 * Two fields decide it and they disagree: a freshly saved draft carries
 * `status: "draft"` while its `trip_status` already says `"permanent"`. The
 * web resolves that in one place and so does [PermanentTrip.state]: the
 * authoring `status` wins when it says draft, otherwise the runtime
 * `trip_status` does, and the legacy value `permanent` means [Active].
 */
enum class PermanentState(val wire: String) {
    Draft("draft"),
    Active("active"),
    Paused("paused"),
    Completed("completed"),
    Archived("archived"),
    Unknown(""),
    ;

    companion object {
        fun from(value: String?): PermanentState {
            val raw = value?.trim()?.lowercase().orEmpty()
            return when {
                raw.isEmpty() -> Draft
                // Older records spell an active allocation "permanent"/"in_use".
                raw == "permanent" || raw == "in_use" -> Active
                else -> entries.firstOrNull { it.wire == raw } ?: Unknown
            }
        }
    }
}

/** What a stop on a ride is for. The UI's "wait" is the server's `waypoint`. */
enum class StopAction(val wire: String) {
    Pickup("pickup"),
    Dropoff("dropoff"),
    Waypoint("waypoint"),
    ;

    companion object {
        fun from(value: String?): StopAction {
            val raw = value?.trim()?.lowercase().orEmpty()
            // The ride form calls a waypoint a "wait"; the server never does.
            if (raw == "wait") return Waypoint
            return entries.firstOrNull { it.wire == raw } ?: Pickup
        }
    }
}

/**
 * A day of the week, as `schedule[].day` spells it.
 *
 * [index] is the server's own number for the day, not an ordinal: `/trip`
 * ships `active_days` as integers with Monday at 1 and Sunday at **0 or 7**,
 * both of which are valid. They are literals here because they are the wire's
 * values, and naming each one would only put a second spelling in the way.
 */
@Suppress("MagicNumber") // The wire's own day numbers.
enum class HubDay(val wire: String, val index: Int, val shortLabel: String) {
    Monday("monday", 1, "Mon"),
    Tuesday("tuesday", 2, "Tue"),
    Wednesday("wednesday", 3, "Wed"),
    Thursday("thursday", 4, "Thu"),
    Friday("friday", 5, "Fri"),
    Saturday("saturday", 6, "Sat"),
    Sunday("sunday", 0, "Sun"),
    ;

    companion object {
        /** Monday-first, the order a coordinator reads a shoot week in. */
        val weekOrder: List<HubDay> = listOf(
            Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, Sunday,
        )

        val weekdays: List<HubDay> = listOf(Monday, Tuesday, Wednesday, Thursday, Friday)

        /**
         * Parses either spelling the server uses. `/trip` ships `active_days`
         * as integers (Mon=1 … Sun=0 **or** 7, both valid), while the
         * permanent-allocation payload uses lowercase day names.
         */
        fun from(value: String?): HubDay? {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return null
            entries.firstOrNull { it.wire == raw }?.let { return it }
            return raw.toIntOrNull()?.let { fromIndex(it) }
        }

        fun fromIndex(index: Int): HubDay? = when (index) {
            SUNDAY_AS_SEVEN -> Sunday
            else -> entries.firstOrNull { it.index == index }
        }

        /** Some rows number Sunday 7 instead of 0. Both mean Sunday. */
        private const val SUNDAY_AS_SEVEN = 7
    }
}

/** One day's slot in a permanent allocation's weekly schedule. */
data class HubScheduleEntry(
    val day: HubDay,
    /** Epoch ms. The server's convention for "unset" is 0, not null. */
    val startTimeMs: Long = 0,
    val endTime: String = "",
    val isActive: Boolean = true,
)

/** A person riding: a project member, or a guest the coordinator typed in. */
data class HubPassenger(
    /** The server's id for this row. Absent on a row not yet saved. */
    val id: String? = null,
    /** Set only for a project member — a guest row must not carry one. */
    val userId: String? = null,
    val name: String = "",
    val contactNumber: String = "",
    val pickupName: String = "",
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val scheduledPickupTimeMs: Long? = null,
    val keepNamePrivate: Boolean = false,
    val status: String = "",
    val profilePicture: StoredPicture? = null,
)

/** A stop between the ends of a ride. */
data class HubStop(
    val id: String? = null,
    val location: HubLocation = HubLocation.Empty,
    val action: StopAction = StopAction.Pickup,
    /** Position in the list; the server persists it as `order`. */
    val order: Int = 0,
    val passengerName: String = "",
    val passengers: List<HubPassenger> = emptyList(),
    val isCompleted: Boolean = false,
)

/** A picture the store holds — its key plus where to read it from. */
data class StoredPicture(
    val media: String,
    val bucket: String? = null,
    val region: String? = null,
    val thumbnail: String? = null,
)

/** A file on a vehicle or a driver's licence: the pointer, not the bytes. */
data class HubAttachment(
    val media: String,
    val name: String = "",
    val thumbnail: String? = null,
    val contentType: String = "image",
    val contentSubtype: String = "",
    val bucket: String? = null,
    val region: String? = null,
    val caption: String = "",
    val fileSize: String = "0",
    val width: Int? = null,
    val height: Int? = null,
    val uniqueId: String? = null,
)

/** A vehicle in the fleet. */
data class Vehicle(
    val id: String,
    val name: String = "",
    /** The plate. Required on create; the server also calls it `vehicle_number`. */
    val licensePlate: String = "",
    val vehicleType: String = "",
    val allocation: VehicleAllocation = VehicleAllocation.Available,
    val capacity: Int? = null,
    val fuelType: String = "",
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val color: String = "",
    /** False means the production hired it: [ownerName] is then required. */
    val isPrivate: Boolean = true,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val attachments: List<HubAttachment> = emptyList(),
    val deleted: Boolean = false,
) {
    /** "Make · Model · Year · Colour", skipping whatever is blank. */
    val specLine: String
        get() = listOf(make, model, year?.toString().orEmpty(), color)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    val displayName: String get() = name.ifBlank { licensePlate }
}

/** The six types the server ships before a production adds its own. */
val builtInVehicleTypes: List<String> =
    listOf("Car", "Minivan", "Vanity Van", "Truck", "Minibus", "Bus")

/** A vehicle type the picker offers, and whether this production added it. */
data class VehicleTypeOption(val label: String, val isCustom: Boolean = false)

/** A driver on the project. */
data class Driver(
    val id: String,
    /** The project user behind the record. A virtual driver has none. */
    val userId: String? = null,
    val fullName: String = "",
    val phone: String = "",
    val licenseNumber: String = "",
    val designation: String = "",
    val isOnDuty: Boolean = false,
    val isTempDriver: Boolean = false,
    val isAvailable: Boolean = true,
    /** Set when the record was saved through "Ignore & create". */
    val pendingDetails: Boolean = false,
    /** What "Ignore & create" left blank, as the server names the fields. */
    val missingFields: List<String> = emptyList(),
    val addedBy: String = "",
    val licenseFrontUrl: String = "",
    val licenseBackUrl: String = "",
    val profilePicture: StoredPicture? = null,
    val assignedVehicleId: String? = null,
    val rating: Double? = null,
    val lastLocation: HubLocation? = null,
    val createdMs: Long = 0,
) {
    val displayName: String get() = fullName.ifBlank { phone }

    /** True when the chase-list should carry them: no phone, or no licence. */
    val detailsIncomplete: Boolean
        get() = pendingDetails || phone.isBlank() || licenseNumber.isBlank()
}

/** A ride request: someone asking for a car. */
data class RideRequest(
    val id: String,
    val status: RideStatus = RideStatus.Pending,
    val priority: RidePriority = RidePriority.Normal,
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val passengers: List<HubPassenger> = emptyList(),
    val stops: List<HubStop> = emptyList(),
    val notes: String = "",
    /** Who raised it. The server answers a snapshot, not a live join. */
    val requesterUserId: String? = null,
    val requesterName: String = "",
    val assignedDriverId: String? = null,
    val assignedDriverName: String = "",
    val assignedVehicleId: String? = null,
    val assignedVehicleLabel: String = "",
    val scheduledPickupTimeMs: Long? = null,
    val createdMs: Long = 0,
    val rejectionReason: String = "",
    val cancellationReason: String = "",
    val etaMinutes: Int? = null,
) {
    val passengerCount: Int get() = passengers.size

    /** True when [userId] raised the ride or rides in it — the web's `isOwnedBy`. */
    fun isOwnedBy(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        if (requesterUserId == userId) return true
        return passengers.any { it.userId == userId }
    }
}

/** A trip, or a ride request wearing a trip's clothes. See [TripResourceKind]. */
data class Trip(
    val id: String,
    val title: String = "",
    val kind: TripResourceKind = TripResourceKind.Trip,
    val status: TripStatus = TripStatus.Draft,
    val priority: RidePriority = RidePriority.Normal,
    val tripType: String = "one_time",
    val passengers: List<HubPassenger> = emptyList(),
    val stops: List<HubStop> = emptyList(),
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val driverId: String? = null,
    val driverName: String = "",
    val vehicleId: String? = null,
    val vehicleLabel: String = "",
    val scheduledPickupTimeMs: Long? = null,
    val notes: String = "",
    val activeDays: List<HubDay> = emptyList(),
    val createdMs: Long = 0,
) {
    /**
     * True when the row cannot be deleted through `/trip` at all — a ride
     * request already past acting on. The list hides Delete for these.
     */
    val deleteIsPointless: Boolean
        get() = kind == TripResourceKind.RideRequest && status.isTerminal
}

/** One entry in a trip's lifecycle log. */
data class TripEvent(
    val id: String = "",
    val type: String = "",
    val description: String = "",
    val atMs: Long = 0,
    val actorName: String = "",
)

/** A driver and a vehicle kept for one group of passengers, every shoot day. */
data class PermanentTrip(
    val id: String,
    val title: String = "",
    /** The authoring field. [state] reads this and [tripStatus] together. */
    val status: String = "",
    val tripStatus: String = "",
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val passengers: List<HubPassenger> = emptyList(),
    val startDateMs: Long = 0,
    val endDateMs: Long = 0,
    val scheduledTimeMs: Long = 0,
    val schedule: List<HubScheduleEntry> = emptyList(),
    val isFullDayTrip: Boolean = false,
    val selfManage: Boolean = false,
    val isExclusive: Boolean = false,
    val notes: String = "",
    val swapHistory: List<SwapEntry> = emptyList(),
    /** Every key the server sent, needed to promote a draft. See below. */
    val rawFields: Map<String, String> = emptyMap(),
) {
    /**
     * The one status the tabs, the pill and Pause/Resume all read.
     *
     * A draft says so in [status] while [tripStatus] still says `permanent`,
     * so authoring wins when it says draft and runtime wins otherwise.
     */
    val state: PermanentState
        get() = if (status.trim().lowercase() == "draft") {
            PermanentState.Draft
        } else {
            PermanentState.from(tripStatus.ifBlank { status })
        }

    val activeDays: List<HubDay>
        get() = schedule.filter { it.isActive }.map { it.day }
            .sortedBy { HubDay.weekOrder.indexOf(it) }
}

/** A driver or a vehicle being swapped out of a permanent allocation. */
data class SwapEntry(
    val atMs: Long = 0,
    val reason: String = "",
    val fromId: String = "",
    val toId: String = "",
    val isDriver: Boolean = true,
    val byName: String = "",
)

/** A car out on the road right now — the Active Cars panel. */
data class ActiveCar(
    val rideId: String = "",
    val driverId: String? = null,
    val driverName: String = "",
    val vehicleId: String? = null,
    val vehicleLabel: String = "",
    val status: RideStatus = RideStatus.Assigned,
    val passengers: List<HubPassenger> = emptyList(),
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    val etaMinutes: Int? = null,
)

/** A project member, for the passenger and driver pickers. */
data class ProjectPerson(
    val userId: String,
    val name: String,
    val email: String = "",
    val phone: String = "",
    val designation: String = "",
    val profilePicture: StoredPicture? = null,
)

/**
 * Which hub a viewer gets.
 *
 * One rule, shared with the web's `resolveHubRole`, and the order matters.
 * Posting rights on `transportation_tool` are the **only** door to
 * [Coordinator] — a designation door was tried and reverted, because User
 * Management's rights grid has to be able to revoke coordinator access from
 * anyone, including an admin, and a designation would survive the revoke.
 */
enum class HubRole { Coordinator, Driver, Passenger }

/** Who is looking, and what the hub lets them do. */
data class HubViewer(
    val userId: String = "",
    val designation: String = "",
    /** For the rail's profile card, which the web's sidebar carries too. */
    val fullName: String = "",
    val isAdmin: Boolean = false,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val isTempDriver: Boolean = false,
    /** Every designation the server counts as a driver's. */
    val driverDesignations: List<String> = emptyList(),
) {
    val role: HubRole
        get() = when {
            canPost -> HubRole.Coordinator
            isTempDriver || driverDesignations.any { it.equals(designation, ignoreCase = true) } ->
                HubRole.Driver
            else -> HubRole.Passenger
        }

    companion object {
        const val TOOL_IDENTIFIER = "transportation_tool"
        val Empty = HubViewer()
    }
}

/**
 * Which ride requests a read asks for.
 *
 * Three scopes, and the web uses a different one on each of the three pages
 * that show rides: a coordinator sees the production's (`/ride-request`), a
 * driver's Assigned Rides are the ones given to their driver record
 * (`?driver_id=`), and anyone's own requests are `?me=true`. Sending the wrong
 * one is not cosmetic — `me=true` on a driver's Assigned Rides answers the
 * rides they *asked for*, never the ones they are to drive.
 */
sealed interface RideScope {
    data object All : RideScope
    data object Mine : RideScope
    data class ForDriver(val driverId: String) : RideScope
}

/**
 * The Dashboard's numbers, as `/insights/dashboard` answers them.
 *
 * [byStatus] is the response's `trip_counts_by_status`: mode → status → count,
 * where a mode is `trips`, `ride_requests`, `shuttle_runs` or
 * `permanent_trips`. The Active card must sum **every** mode — reading only
 * `trips` under-counts, which is the bug ZL-19718 fixed on the web — and the
 * flat `active_trips` is only a fallback for a deployment that ships no
 * breakdown at all.
 *
 * [yesterday] holds the `*_yesterday` keys. A deployment that does not ship
 * them leaves the card's trend line reading "no change" rather than inventing
 * a direction.
 */
data class HubInsights(
    val byStatus: Map<String, Map<String, Int>> = emptyMap(),
    val flatActiveTrips: Int? = null,
    val completedToday: Int? = null,
    val fleetTotal: Int? = null,
    val fleetInUse: Int? = null,
    val rideRequestTotal: Int? = null,
    val yesterday: Map<String, Int> = emptyMap(),
) {
    private fun mode(name: String): Map<String, Int> = byStatus[name].orEmpty()

    private fun sum(name: String, vararg statuses: String): Int =
        mode(name).let { counts -> statuses.sumOf { counts[it] ?: 0 } }

    /**
     * Everything still in flight, across all four modes.
     *
     * Pending ride requests are left out on purpose: they are the Pending
     * Requests card's number, and counting them here would double them.
     */
    val activeTrips: Int
        get() = if (byStatus.isEmpty()) {
            flatActiveTrips ?: 0
        } else {
            sum(
                "trips",
                "pending", "assigned", "en_route_pickup", "at_pickup", "in_transit", "at_dropoff",
            ) +
                sum("ride_requests", "approved", "assigned", "in_progress") +
                sum("shuttle_runs", "scheduled", "boarding", "in_transit") +
                sum("permanent_trips", "active")
        }

    val inTransitTotal: Int
        get() = sum("trips", "in_transit") +
            sum("ride_requests", "in_progress") +
            sum("shuttle_runs", "in_transit")

    val assignedTotal: Int
        get() = sum("trips", "assigned") + sum("ride_requests", "assigned")

    val completedTotal: Int get() = completedToday ?: mode("trips")["completed"] ?: 0

    /** Free vehicles from the fleet block, when the server ships one. */
    val availableFromFleet: Int?
        get() = fleetTotal?.let { total -> (total - (fleetInUse ?: 0)).coerceAtLeast(0) }

    companion object {
        val Empty = HubInsights()
    }
}

/** What a maintenance booking is for — the server's `maintenance_type` enum. */
enum class MaintenanceType(val wire: String) {
    OilChange("oil_change"),
    TireRotation("tire_rotation"),
    BrakeCheck("brake_check"),
    Inspection("inspection"),
    Repair("repair"),
    Cleaning("cleaning"),
    Other("other"),
    ;

    companion object {
        fun from(value: String?): MaintenanceType {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Other
        }
    }
}

/** Where a maintenance booking has got to. */
enum class MaintenanceStatus(val wire: String) {
    Scheduled("scheduled"),
    InProgress("in_progress"),
    Completed("completed"),
    ;

    companion object {
        fun from(value: String?): MaintenanceStatus {
            val raw = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == raw } ?: Scheduled
        }
    }
}

/** A vehicle booked in for work. */
data class MaintenanceRecord(
    val id: String,
    val vehicleId: String? = null,
    val vehicleLabel: String = "",
    val type: MaintenanceType = MaintenanceType.Other,
    val status: MaintenanceStatus = MaintenanceStatus.Scheduled,
    /** Epoch ms at the start of the booked day. 0 when the server sent none. */
    val scheduledDateMs: Long = 0,
    val description: String = "",
    val estimatedCost: Double? = null,
    val odometerReading: Long? = null,
) {
    val isDone: Boolean get() = status == MaintenanceStatus.Completed
}

/** A maintenance booking being made. The server takes the date as YYYY-MM-DD. */
data class MaintenanceDraft(
    val vehicleId: String,
    val type: MaintenanceType,
    val scheduledDateMs: Long,
    val description: String = "",
    val estimatedCost: Double? = null,
    val odometerReading: Long? = null,
)

/** Where a driver last reported being — one pin on the web's live map. */
data class DriverLocation(
    val driverId: String,
    val driverName: String = "",
    val vehicleLabel: String = "",
    val location: HubLocation = HubLocation.Empty,
    /** When the position was reported. 0 when the server sent no stamp. */
    val atMs: Long = 0,
    val isOnDuty: Boolean = false,
    val speedKph: Double? = null,
) {
    /** True when there is a point worth putting on a map. */
    val hasFix: Boolean get() = location.hasCoordinates
}
