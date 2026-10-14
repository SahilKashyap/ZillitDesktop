package com.zillit.desktop.feature.transporthub.ui

import com.zillit.desktop.feature.transporthub.data.toEpochMillisOrNull
import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.AvDraft
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvCapabilities
import com.zillit.desktop.feature.transporthub.domain.AvInsuranceStatus
import com.zillit.desktop.feature.transporthub.domain.AvRatePeriod
import com.zillit.desktop.feature.transporthub.domain.AvSourceType
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.AvUseType
import com.zillit.desktop.feature.transporthub.domain.AvVariant
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteType
import com.zillit.desktop.feature.transporthub.domain.ShuttleRun
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.DriverLocation
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.MaintenanceType
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideScope
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
 * The web gives each role its own route set and its own sidebar
 * (`NAV_BY_ROLE` in `common/Layout.jsx`); one enum covers all three here, and
 * [railFor] is the single gate. Order is not the enum's own — each role's rail
 * is an explicit list, because the three orders disagree: a coordinator reads
 * Dashboard, Trips, Ride Requests, Fleet, Action Vehicles, Drivers … while a
 * driver reads My Trips, My Map, Assigned Rides, Ride Requests.
 *
 * Every section the web's sidebar carries is here. The two that were stubs —
 * Shuttles and Action Vehicles — are ported, so nothing in the rail opens onto
 * an apology any more.
 */
enum class HubSection(
    val slug: String,
    val badgeBucket: HubBadgeBucket?,
) {
    // Coordinator.
    Dashboard("", null),
    Trips("trips", HubBadgeBucket.Trips),
    RideRequests("ride-requests", HubBadgeBucket.Rides),
    Fleet("fleet", HubBadgeBucket.Fleet),

    /** Picture vehicles — what appears on camera, not what carries crew. */
    ActionVehicles("action-vehicles", HubBadgeBucket.ActionVehicles),
    Drivers("drivers", HubBadgeBucket.Drivers),
    Allocations("permanent-allocations", HubBadgeBucket.Allocations),
    Shuttles("shuttles", HubBadgeBucket.Shuttles),
    LiveTracking("live-map", null),
    Maintenance("maintenance", HubBadgeBucket.Maintenance),

    // Driver.
    /** A driver's or a passenger's own trips — the whole trip unit's count. */
    MyTrips("", HubBadgeBucket.MyTrips),
    DriverMap("driver-map", null),

    /** Rides a driver has been given to drive: `/ride-request?driver_id=…`. */
    AssignedRides("rides", HubBadgeBucket.Rides),

    /**
     * A driver raising a ride of their own (ZL-18744).
     *
     * The same page a passenger gets, on the same `me=true` scope — and
     * deliberately badge-less, as the web's own nav entry is: the ride unit's
     * count already sits on Assigned Rides beside it.
     */
    MyRequests("my-requests", null),

    // Passenger.
    /** A passenger's own requests. Same page as [MyRequests]. */
    MyRides("my-rides", HubBadgeBucket.Rides),
    ;

    fun visibleTo(viewer: HubViewer): Boolean = this in railFor(viewer)

    /** True when this section draws driver positions and wants the live stream. */
    val showsLocations: Boolean get() = this == LiveTracking || this == DriverMap

    companion object {
        /** The web's coordinator sidebar, in its order. */
        private val COORDINATOR_RAIL = listOf(
            Dashboard, Trips, RideRequests, Fleet, ActionVehicles,
            Drivers, Allocations, Shuttles, LiveTracking, Maintenance,
        )

        /** The web's driver sidebar: both ride lists, and they are not one. */
        private val DRIVER_RAIL = listOf(MyTrips, DriverMap, AssignedRides, MyRequests)

        private val PASSENGER_RAIL = listOf(MyTrips, MyRides)

        /** The rail for [viewer], in the web's order. */
        fun railFor(viewer: HubViewer): List<HubSection> {
            if (!viewer.canView) return emptyList()
            return when (viewer.role) {
                HubRole.Coordinator -> COORDINATOR_RAIL
                HubRole.Driver -> DRIVER_RAIL
                HubRole.Passenger -> PASSENGER_RAIL
            }
        }

        /**
         * Where a role lands with no section chosen — the web's index route
         * for that role: Dashboard for a coordinator, My Trips for the rest.
         */
        fun homeFor(role: HubRole): HubSection = when (role) {
            HubRole.Coordinator -> Dashboard
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

/**
 * The Dashboard's Recent Trips chips.
 *
 * The web's own five, and they are **not** the Trips page's seven: Cancelled
 * here also carries the rejected, and there is no Draft chip.
 */
enum class DashboardFilter {
    All,
    Active,
    Pending,
    Completed,
    Cancelled,
    ;

    fun accepts(trip: Trip): Boolean = when (this) {
        All -> true
        Active -> trip.status in setOf(
            TripStatus.Assigned,
            TripStatus.EnRoutePickup,
            TripStatus.AtPickup,
            TripStatus.InTransit,
            TripStatus.AtDropoff,
        )

        Pending -> trip.status in setOf(TripStatus.Pending, TripStatus.Draft, TripStatus.Approved)
        Completed -> trip.status == TripStatus.Completed
        Cancelled -> trip.status == TripStatus.Cancelled || trip.status == TripStatus.Rejected
    }
}

/** The Maintenance page's filter. */
enum class MaintenanceTab {
    All,
    Scheduled,
    Completed,
    ;

    fun accepts(record: MaintenanceRecord): Boolean = when (this) {
        All -> true
        Scheduled -> !record.isDone
        Completed -> record.isDone
    }
}

/** The Live Tracking page's two lists — the web's own tabs. */
enum class TrackingTab { Drivers, Rides }

/** A maintenance booking being made, as the form holds it. */
data class MaintenanceEditor(
    val vehicleId: String = "",
    val type: MaintenanceType = MaintenanceType.OilChange,
    /** `YYYY-MM-DD`, as the field and the validator both spell it. */
    val scheduledDate: String = "",
    val description: String = "",
    val estimatedCost: String = "",
    val odometerReading: String = "",
    val saving: Boolean = false,
)

/** The Shuttles page's two tabs — the web's own segmented control. */
enum class ShuttleTab { Routes, Runs }

/**
 * The Action Vehicles tabs a viewer gets.
 *
 * A union, not a choice: somebody who can both raise a request and source one
 * sees Identify next to their own Requested vehicles. Which tabs exist is
 * [HubUiState.actionVehicleTabs]; this enum is only their identity and the
 * statuses each accepts.
 */
enum class AvTab(val statuses: List<AvStatus>) {
    /** The requester's own composer and their requests. */
    Request(emptyList()),

    /** The sourcer's queue: what has been asked for and not yet found. */
    Identify(listOf(AvStatus.Requested)),

    /** The sourcer's live queue, bounced-back records included. */
    InProgress(listOf(AvStatus.Sourced, AvStatus.PendingApproval, AvStatus.Rejected)),

    /** Only the records this viewer is an approver on. */
    Pending(listOf(AvStatus.PendingApproval, AvStatus.ReturnPending)),

    /** Approved onward — the register everyone with access can see. */
    Finalized(AvStatus.FINALIZED),

    /** A thread this viewer is in the audience for, with no other tab to show it. */
    Comments(emptyList()),
    ;

    /** True when the tab is narrowed to records the viewer decides on. */
    val mineAsApprover: Boolean get() = this == Pending
}

/** Which form the Action Vehicles dialog is: a request, a sourcing, or an edit. */
enum class AvFormMode { Request, Source, Edit }

/** Which of a record's two approval rounds a picker or tracker is for. */
enum class AvFlow { Approval, Return }

/** A shuttle route being created or edited, as the form holds it. */
data class ShuttleRouteEditor(
    val id: String? = null,
    /**
     * The route's name, collected as two halves.
     *
     * The server stores one string; the web asks for From and To and joins
     * them with an arrow so nobody has to type it.
     */
    val from: String = "",
    val to: String = "",
    val type: ShuttleRouteType = ShuttleRouteType.Crew,
    val frequencyMinutes: String = "30",
    val capacity: String = "15",
    val startTime: String = "06:00",
    val endTime: String = "22:00",
    val daysActive: List<HubDay> = HubDay.weekdays,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val stops: List<ShuttleStop> = listOf(ShuttleStop(), ShuttleStop()),
    val notes: String = "",
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = id != null

    /** "Base Camp → Unit Base", or whichever half was filled in. */
    val composedName: String
        get() = listOf(from.trim(), to.trim()).filter { it.isNotBlank() }.joinToString(" → ")

    companion object {
        fun from(route: ShuttleRoute): ShuttleRouteEditor {
            // The stored name is one string; split it back on the arrow the
            // form joined it with so editing reads as two fields again.
            val halves = route.name.split("→", limit = 2)
            return ShuttleRouteEditor(
                id = route.id,
                from = halves.getOrNull(0)?.trim().orEmpty(),
                to = halves.getOrNull(1)?.trim().orEmpty(),
                type = route.type,
                frequencyMinutes = route.frequencyMinutes?.toString().orEmpty(),
                capacity = route.capacity?.toString().orEmpty(),
                startTime = route.startTime,
                endTime = route.endTime,
                daysActive = route.daysActive.ifEmpty { HubDay.weekdays },
                driverId = route.driverId,
                vehicleId = route.vehicleId,
                stops = route.stops.ifEmpty { listOf(ShuttleStop(), ShuttleStop()) },
                notes = route.notes,
            )
        }
    }
}

/** A picture vehicle being requested, sourced or edited. */
@Suppress("LongParameterList") // It mirrors the record it writes.
data class AvEditor(
    val id: String? = null,
    val mode: AvFormMode = AvFormMode.Request,
    val displayName: String = "",
    val categories: List<String> = emptyList(),
    val vehicleTypes: List<String> = emptyList(),
    val variants: List<AvVariant> = listOf(AvVariant()),
    val make: String = "",
    val model: String = "",
    val color: String = "",
    val year: String = "",
    val description: String = "",
    val condition: String = "",
    val modifications: List<String> = emptyList(),
    val useType: AvUseType = AvUseType.Both,
    val scenes: List<String> = emptyList(),
    val location: HubLocation = HubLocation.Empty,
    val needRehearsalDate: String = "",
    val needShootingDate: String = "",
    val reminderDays: String = "",
    val approvedDrivers: List<String> = emptyList(),
    val notes: String = "",
    val sourceType: AvSourceType = AvSourceType.Rented,
    val ownerName: String = "",
    val ownerContactNumber: String = "",
    val rate: String = "",
    val ratePeriod: AvRatePeriod = AvRatePeriod.Daily,
    val agreementStartDate: String = "",
    val agreementEndDate: String = "",
    val insuranceStatus: AvInsuranceStatus = AvInsuranceStatus.Pending,
    val policyRef: String = "",
    val replacementValue: String = "",
    val licensePlate: String = "",
    val permittedUses: List<String> = emptyList(),
    /** The typed detail behind "other", which never leaves as its own field. */
    val permittedUsesOther: String = "",
    val propPlates: List<String> = emptyList(),
    val images: List<PhotoSlot> = emptyList(),
    /**
     * True when the car itself is frozen.
     *
     * A finalized record keeps only its two shoot dates editable: everything
     * about the vehicle and the money has been agreed, and the server ignores
     * the rest — so the form greys it rather than letting somebody type into
     * fields that will not be saved.
     */
    val datesOnly: Boolean = false,
    /**
     * True when this form's save is the **requester's**.
     *
     * Set from the record's status, not from the mode: editing one still at
     * `requested` or `rejected` is still the AD's own submit, and sending the
     * sourced shape there would drop `variants[]` — which the server then
     * refuses for having none. Tied to the form, not to what the viewer is
     * allowed to do: somebody who can both request and source hits exactly
     * this path.
     */
    val requesterSubmit: Boolean = mode == AvFormMode.Request,
    val saving: Boolean = false,
) {
    /** The sourcing half of the form — the captain's, never the requester's. */
    val showsSourcing: Boolean get() = !requesterSubmit

    /** This editor with everything but the two shoot dates locked. */
    fun frozen(): AvEditor = copy(datesOnly = true)

    /** The form as the write routes want it. See [requesterSubmit]. */
    fun toDraft(): AvDraft = AvDraft(
        displayName = displayName,
        categories = categories,
        vehicleTypes = vehicleTypes,
        variants = variants.filterNot { it.isEmpty },
        make = make,
        model = model,
        color = color,
        year = year.trim().toIntOrNull(),
        description = description,
        condition = condition,
        modifications = modifications,
        useType = useType,
        scenes = scenes,
        location = location,
        needRehearsalMs = needRehearsalDate.toEpochMillisOrNull() ?: 0,
        needShootingMs = needShootingDate.toEpochMillisOrNull() ?: 0,
        reminderDays = reminderDays.trim().toIntOrNull(),
        approvedDrivers = approvedDrivers,
        notes = notes,
        sourceType = sourceType,
        ownerName = ownerName,
        ownerContactNumber = ownerContactNumber,
        rate = rate.trim().toDoubleOrNull(),
        ratePeriod = ratePeriod,
        agreementStartMs = agreementStartDate.toEpochMillisOrNull() ?: 0,
        agreementEndMs = agreementEndDate.toEpochMillisOrNull() ?: 0,
        insuranceStatus = insuranceStatus,
        policyRef = policyRef,
        replacementValue = replacementValue.trim().toDoubleOrNull(),
        licensePlate = licensePlate,
        // "Other" never leaves as its own field: the typed detail rides the
        // permitted-uses array, so no new server field is needed for it.
        permittedUses = permittedUses.filterNot { it == OTHER_USE } +
            listOfNotNull(permittedUsesOther.trim().takeIf { it.isNotBlank() && OTHER_USE in permittedUses }),
        propPlates = propPlates,
        images = images.map { it.attachment },
        isRequesterSubmit = requesterSubmit,
    )

    companion object {
        /** The picker's placeholder for a use the production words itself. */
        const val OTHER_USE = "other"
    }
}

/** Picking who decides on a record, for either of its two rounds. */
data class AvApproverPicker(
    val vehicleId: String,
    val flow: AvFlow,
    /** True when adding to a round already in flight rather than starting one. */
    val addingToExisting: Boolean = false,
    val picked: List<String> = emptyList(),
    val search: String = "",
    val note: String = "",
    val saving: Boolean = false,
)

/** Opening a record's private thread to more people. */
data class AvCommentPicker(
    val vehicleId: String,
    val picked: List<String> = emptyList(),
    val search: String = "",
    val message: String = "",
    val saving: Boolean = false,
)

/** A record's thread, open. */
data class AvThread(
    val vehicleId: String,
    val draft: String = "",
    val sending: Boolean = false,
)

/** An approver deciding — approve outright, or decline with a reason. */
data class AvDecision(
    val vehicleId: String,
    val flow: AvFlow,
    val isReject: Boolean = false,
    val reason: String = "",
    val saving: Boolean = false,
)

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

/**
 * A trip being created.
 *
 * [date] and each passenger's [TripPassengerRow.time] are held as the text the
 * fields show, because that is what the web collects: a day and an `HH:mm`,
 * joined into one instant at save. A trip with no time at all is valid — the
 * server then schedules it for the day.
 */
data class TripEditor(
    val title: String = "",
    val tripType: String = "one_time",
    val priority: RidePriority = RidePriority.Normal,
    /** `YYYY-MM-DD`. The day every passenger's time is read against. */
    val date: String = "",
    val passengers: List<TripPassengerRow> = listOf(TripPassengerRow()),
    val notes: String = "",
    val driverId: String? = null,
    val vehicleId: String? = null,
    val saving: Boolean = false,
) {
    companion object {
        /** The three the server's `trip_type` accepts. */
        val TRIP_TYPES: List<String> = listOf("one_time", "permanent", "recurring")
    }
}

/**
 * One passenger on the New Trip form.
 *
 * A row is either a project member — picked, which fills the name and the
 * number — or a guest whose details are typed. Only a member's row carries a
 * [userId]: sending a blank one makes the server's required-field validator
 * refuse the whole trip.
 */
data class TripPassengerRow(
    val userId: String? = null,
    val name: String = "",
    val contactNumber: String = "",
    val pickup: HubLocation = HubLocation.Empty,
    val dropoff: HubLocation = HubLocation.Empty,
    /** `HH:mm`, read against the trip's own day. Blank leaves it unset. */
    val time: String = "",
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

/**
 * Putting a driver and a vehicle on a ride, or on a trip.
 *
 * One form for both because the fields are the same; [isTrip] is what decides
 * which route the submit goes to — `PUT /trip/:id/assign` takes the driver
 * record's id, `PUT /ride-request/:id/assign` the same, and neither accepts
 * the other's path.
 */
data class AssignEditor(
    val rideId: String,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val isTrip: Boolean = false,
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
    val section: HubSection = HubSection.Dashboard,
    val loading: Boolean = false,
    /** Set while one row is mid-mutation, so only that row shows a spinner. */
    val busyRowId: String = "",
    /**
     * The clock, as the ViewModel reads it.
     *
     * Held in state rather than read in a composable so the Dashboard's date
     * line and the "overdue" chips are the same instant the loads used, and so
     * a test can fix it.
     */
    val nowMs: Long = 0,
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
    val maintenance: List<MaintenanceRecord> = emptyList(),
    val driverLocations: List<DriverLocation> = emptyList(),
    val shuttleRoutes: List<ShuttleRoute> = emptyList(),
    val shuttleRuns: List<ShuttleRun> = emptyList(),
    val actionVehicles: List<ActionVehicle> = emptyList(),

    // Dashboard. Each of the three loads is allowed to fail on its own, as
    // the web's `Promise.allSettled` lets them: a missing counter leaves its
    // card blank rather than replacing the page with a banner.
    val insights: HubInsights = HubInsights.Empty,
    val pendingRideCount: Int = 0,

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
    val dashboardFilter: DashboardFilter = DashboardFilter.All,
    val dashboardSearch: String = "",
    val maintenanceTab: MaintenanceTab = MaintenanceTab.All,
    val maintenanceSearch: String = "",
    val trackingTab: TrackingTab = TrackingTab.Drivers,
    val shuttleTab: ShuttleTab = ShuttleTab.Routes,
    val shuttleSearch: String = "",
    /** Blank shows every route's runs — the web's "All routes". */
    val runRouteFilter: String = "",
    val avTab: AvTab = AvTab.Finalized,
    /**
     * True once somebody chose a tab.
     *
     * Until then the register follows the first tab the viewer has, which
     * moves as their records do — an approver who is given something to
     * decide should land on it.
     */
    val avTabPicked: Boolean = false,
    val avSearch: String = "",
    /** Blank is every category — the web's own "all". */
    val avCategory: String = "",
    /** When the tracking page last had an answer — the web's "updated" line. */
    val trackingUpdatedMs: Long = 0,

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
    val maintenanceEditor: MaintenanceEditor? = null,
    val routeEditor: ShuttleRouteEditor? = null,
    val avEditor: AvEditor? = null,
    val avApproverPicker: AvApproverPicker? = null,
    val avCommentPicker: AvCommentPicker? = null,
    val avThread: AvThread? = null,
    val avDecision: AvDecision? = null,
    /** The record whose detail panel is open. */
    val openActionVehicle: String? = null,
    /** Which of a record's two rounds the open approver tracker shows. */
    val avTrackerFlow: AvFlow = AvFlow.Approval,
    val confirm: HubConfirm? = null,
    /** Offered when a half-filled allocation form is being closed. */
    val draftPrompt: AllocationEditor? = null,
    val showActiveCars: Boolean = false,

    val badges: Map<String, Int> = emptyMap(),
) {
    val role: HubRole get() = viewer.role

    val rail: List<HubSection> get() = HubSection.railFor(viewer)

    fun badge(section: HubSection): Int = section.badgeBucket?.let { badges[it.name] } ?: 0

    /**
     * The viewer's own driver record.
     *
     * Both of a driver's lists are scoped by it — `/trip?driver_id=` and
     * `/ride-request?driver_id=` — and it is the driver record's id, which is
     * not the user id the profile carries. Null until the driver list lands,
     * or for anyone with no driver record at all.
     */
    val myDriverId: String?
        get() = viewer.userId.takeIf { it.isNotBlank() }?.let { me ->
            drivers.firstOrNull { it.userId == me }?.id
        }

    /**
     * The scope the open section's ride list is read on.
     *
     * Assigned Rides falls back to the production's list while the driver
     * record is still resolving, which is what the web does — `me=true` would
     * answer the wrong list rather than a short one.
     */
    val rideScope: RideScope
        get() = when (section) {
            HubSection.AssignedRides ->
                myDriverId?.let { RideScope.ForDriver(it) } ?: RideScope.All

            HubSection.MyRides, HubSection.MyRequests -> RideScope.Mine
            else -> if (role == HubRole.Coordinator) RideScope.All else RideScope.Mine
        }

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

    /**
     * The Dashboard's Recent Trips.
     *
     * The web asks `/trip?limit=15` for this and the Trips page asks for the
     * whole list; here one load serves both, newest first — which is the order
     * `limit=15` would have given anyway.
     */
    val recentTrips: List<Trip>
        get() = trips
            .sortedByDescending { it.scheduledPickupTimeMs ?: it.createdMs }
            .take(RECENT_TRIP_COUNT)
            .filter { dashboardFilter.accepts(it) }
            .filter { it.matches(dashboardSearch) }

    val visibleMaintenance: List<MaintenanceRecord>
        get() = maintenance
            .filter { maintenanceTab.accepts(it) }
            .filter { it.matches(maintenanceSearch, vehicles) }

    val maintenanceCounts: Map<MaintenanceTab, Int>
        get() = MaintenanceTab.entries.associateWith { tab -> maintenance.count { tab.accepts(it) } }

    /** The cars the Live Tracking page's Rides tab lists. */
    val trackedRides: List<RideRequest>
        get() = rides.filter { it.status.isActive }

    /**
     * The routes, with the disabled ones at the bottom.
     *
     * Within each group the server's own order is kept, so a coordinator can
     * triage the live routes without the retired ones in the way.
     */
    val visibleShuttleRoutes: List<ShuttleRoute>
        get() = shuttleRoutes
            .filter { it.matches(shuttleSearch) }
            .withIndex()
            .sortedWith(compareBy({ !it.value.isActive }, { it.index }))
            .map { it.value }

    /** The runs the chosen route leaves. Blank is every route. */
    val visibleShuttleRuns: List<ShuttleRun>
        get() = shuttleRuns
            .filter { runRouteFilter.isBlank() || it.routeId == runRouteFilter }
            .filter { it.matches(shuttleSearch) }

    /** The routes present in the loaded runs, for the filter — id to label. */
    val runRouteOptions: List<Pair<String, String>>
        get() = shuttleRuns
            .mapNotNull { run -> run.routeId?.takeIf { it.isNotBlank() }?.let { it to run.title } }
            .distinctBy { it.first }

    /**
     * What this viewer may do in Action Vehicles.
     *
     * Posting access on `transportation_tool` is the only door to sourcing —
     * the same rule as the coordinator's hub — while requesting is a union of
     * an AD designation, that posting right, and being a project admin.
     */
    val avCapabilities: AvCapabilities
        get() = AvCapabilities.of(
            designationIdentifier = viewer.designation,
            hasPostingRights = viewer.canPost,
            isAdmin = viewer.isAdmin,
        )

    /** True when this viewer decides on at least one record in flight. */
    val avCanApprove: Boolean
        get() = actionVehicles.any { it.my.isApprover || it.my.isReturnApprover }

    /** True when they are in some live record's comment audience. */
    val avHasComments: Boolean
        get() = actionVehicles.any { !it.status.isTerminal && it.my.inComments }

    /**
     * The tabs this viewer gets, as the web's `tabsFor` builds them.
     *
     * A *pure* requester — somebody who may ask but not source — is left
     * without "Sent for approval": their own request tab and its status pills
     * already carry the same queue, and two copies of it only confuse.
     */
    val actionVehicleTabs: List<AvTab>
        get() = buildList {
            val caps = avCapabilities
            val pureRequester = caps.canRequest && !caps.canSource
            if (caps.canRequest) add(AvTab.Request)
            if (caps.canSource) add(AvTab.Identify)
            if ((caps.canSource || avCanApprove) && !pureRequester) add(AvTab.InProgress)
            if (avCanApprove) add(AvTab.Pending)
            add(AvTab.Finalized)
            if (avHasComments) add(AvTab.Comments)
        }

    /** The records the open Action Vehicles tab leaves. */
    val visibleActionVehicles: List<ActionVehicle>
        get() = actionVehicles
            .filter { vehicle ->
                when (avTab) {
                    // The requester's tab is their own requests, whatever
                    // stage each has reached.
                    AvTab.Request -> vehicle.my.isRequester
                    AvTab.Comments -> vehicle.my.inComments
                    else -> vehicle.status in avTab.statuses
                }
            }
            .filter { !avTab.mineAsApprover || it.my.isApprover || it.my.isReturnApprover }
            .filter { avCategory.isBlank() || avCategory in it.categories }
            .filter { it.matches(avSearch) }

    val actionVehicleCounts: Map<AvTab, Int>
        get() = actionVehicleTabs.associateWith { tab ->
            actionVehicles.count { vehicle ->
                when (tab) {
                    AvTab.Request -> vehicle.my.isRequester
                    AvTab.Comments -> vehicle.my.inComments
                    else -> vehicle.status in tab.statuses &&
                        (!tab.mineAsApprover || vehicle.my.isApprover || vehicle.my.isReturnApprover)
                }
            }
        }

    /** Every category in use, built-ins first, for the filter. */
    val avCategoryOptions: List<String>
        get() = actionVehicles.flatMap { it.categories }.distinct().sorted()

    fun actionVehicle(id: String?): ActionVehicle? =
        id?.let { wanted -> actionVehicles.firstOrNull { it.id == wanted } }

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

/** How many rows the Dashboard's Recent Trips card holds — the web's `limit`. */
private const val RECENT_TRIP_COUNT = 15

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

private fun ShuttleRoute.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val haystack = listOf(name, type.wire, driverName, vehicleLabel, notes) +
        stops.map { it.name } + stops.map { it.location.address }
    return haystack.any { it.lowercase().contains(q) }
}

private fun ShuttleRun.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOf(routeName, driverName, vehicleLabel, status.wire)
        .any { it.lowercase().contains(q) }
}

private fun ActionVehicle.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val haystack = listOf(
        displayName, make, model, color, ownerName, licensePlate,
        location.address, requesterName, description,
    ) + categories + propPlates + scenes + variants.map { it.summary }
    return haystack.any { it.lowercase().contains(q) }
}

private fun MaintenanceRecord.matches(query: String, vehicles: List<Vehicle>): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val vehicle = vehicleLabel.ifBlank {
        vehicles.firstOrNull { it.id == vehicleId }?.displayName.orEmpty()
    }
    return listOf(vehicle, description, type.wire, status.wire, id)
        .any { it.lowercase().contains(q) }
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
    sealed interface DashboardEvent : HubEvent
    sealed interface MaintenanceEvent : HubEvent
    sealed interface TrackingEvent : HubEvent
    sealed interface ShuttleEvent : HubEvent
    sealed interface ActionVehicleEvent : HubEvent
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

    // Dashboard
    data class DashboardFilterChanged(val filter: DashboardFilter) : DashboardEvent
    data class DashboardSearchChanged(val query: String) : DashboardEvent

    /** The "View all trips" button — the rail's Trips, from the Dashboard. */
    data object OpenAllTrips : DashboardEvent

    // Maintenance
    data class MaintenanceTabChanged(val tab: MaintenanceTab) : MaintenanceEvent
    data class MaintenanceSearchChanged(val query: String) : MaintenanceEvent
    data object NewMaintenance : MaintenanceEvent
    data class MaintenanceEditorChanged(val editor: MaintenanceEditor) : MaintenanceEvent
    data object SaveMaintenance : MaintenanceEvent
    data object CloseMaintenanceEditor : MaintenanceEvent
    data class CompleteMaintenance(val record: MaintenanceRecord) : MaintenanceEvent
    data class DeleteMaintenance(val record: MaintenanceRecord) : MaintenanceEvent
    data class DeleteMaintenanceConfirmed(val id: String) : MaintenanceEvent

    // Live tracking
    data class TrackingTabChanged(val tab: TrackingTab) : TrackingEvent
    data object RefreshTracking : TrackingEvent

    /** Opens a driver's last reported position in the browser's map. */
    data class TrackDriver(val driverId: String) : TrackingEvent

    // Shuttles
    data class ShuttleTabChanged(val tab: ShuttleTab) : ShuttleEvent
    data class ShuttleSearchChanged(val query: String) : ShuttleEvent
    data class RunRouteFilterChanged(val routeId: String) : ShuttleEvent
    data object NewShuttleRoute : ShuttleEvent
    data class EditShuttleRoute(val route: ShuttleRoute) : ShuttleEvent
    data class RouteEditorChanged(val editor: ShuttleRouteEditor) : ShuttleEvent
    data object SaveShuttleRoute : ShuttleEvent
    data object CloseRouteEditor : ShuttleEvent
    data class ToggleShuttleRoute(val route: ShuttleRoute) : ShuttleEvent

    /** Refused for a route with no driver or no vehicle. See the handler. */
    data class GenerateShuttleRuns(val route: ShuttleRoute) : ShuttleEvent
    data class DeleteShuttleRoute(val route: ShuttleRoute) : ShuttleEvent
    data class DeleteShuttleRouteConfirmed(val id: String) : ShuttleEvent
    data class SetShuttleRunStatus(val run: ShuttleRun, val status: ShuttleRunStatus) :
        ShuttleEvent

    // Action vehicles
    data class AvTabChanged(val tab: AvTab) : ActionVehicleEvent
    data class AvSearchChanged(val query: String) : ActionVehicleEvent
    data class AvCategoryChanged(val category: String) : ActionVehicleEvent
    data object NewActionVehicleRequest : ActionVehicleEvent

    /** Opens the sourcing form over a requested record. */
    data class SourceActionVehicle(val vehicle: ActionVehicle) : ActionVehicleEvent
    data class EditActionVehicle(val vehicle: ActionVehicle) : ActionVehicleEvent
    data class AvEditorChanged(val editor: AvEditor) : ActionVehicleEvent
    data object SaveActionVehicle : ActionVehicleEvent
    data object CloseAvEditor : ActionVehicleEvent
    data class OpenActionVehicle(val id: String) : ActionVehicleEvent
    data object CloseActionVehicle : ActionVehicleEvent
    data class SetActionVehicleStatus(val id: String, val status: AvStatus) : ActionVehicleEvent
    data class DeleteActionVehicle(val vehicle: ActionVehicle) : ActionVehicleEvent
    data class DeleteActionVehicleConfirmed(val id: String) : ActionVehicleEvent

    /** Opens the approver picker — for approval, or for a return. */
    data class PickAvApprovers(
        val vehicle: ActionVehicle,
        val flow: AvFlow,
        val addingToExisting: Boolean = false,
    ) : ActionVehicleEvent

    data class AvApproverPickerChanged(val picker: AvApproverPicker) : ActionVehicleEvent
    data object SubmitAvApprovers : ActionVehicleEvent
    data object CloseAvApproverPicker : ActionVehicleEvent
    data class RemoveAvApprover(
        val vehicleId: String,
        val userId: String,
        val flow: AvFlow,
    ) : ActionVehicleEvent

    data class RemindAvApprover(val vehicleId: String, val userId: String) : ActionVehicleEvent
    data class AvTrackerFlowChanged(val flow: AvFlow) : ActionVehicleEvent

    /** Opens the approve/decline confirmation an approver answers. */
    data class DecideActionVehicle(
        val vehicle: ActionVehicle,
        val flow: AvFlow,
        val isReject: Boolean,
    ) : ActionVehicleEvent

    data class AvDecisionChanged(val decision: AvDecision) : ActionVehicleEvent
    data object SubmitAvDecision : ActionVehicleEvent
    data object CloseAvDecision : ActionVehicleEvent
    data class OpenAvThread(val vehicleId: String) : ActionVehicleEvent
    data class AvThreadChanged(val thread: AvThread) : ActionVehicleEvent
    data object SendAvComment : ActionVehicleEvent
    data object CloseAvThread : ActionVehicleEvent
    data class PickAvCommenters(val vehicle: ActionVehicle) : ActionVehicleEvent
    data class AvCommentPickerChanged(val picker: AvCommentPicker) : ActionVehicleEvent
    data object SubmitAvCommenters : ActionVehicleEvent
    data object CloseAvCommentPicker : ActionVehicleEvent
    data object PickAvPhotos : ActionVehicleEvent
    data class RemoveAvPhoto(val slot: PhotoSlot) : ActionVehicleEvent

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

    /** Opens the assign form for a trip — the detail view's own button. */
    data class AssignTrip(val id: String) : TripEvent
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
