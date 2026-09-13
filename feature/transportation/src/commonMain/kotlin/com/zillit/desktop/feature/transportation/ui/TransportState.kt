package com.zillit.desktop.feature.transportation.ui

import com.zillit.desktop.feature.transportation.domain.DialCountry
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.GeoPlace
import com.zillit.desktop.feature.transportation.domain.LicenceRequest
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripDraft
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripPriority
import com.zillit.desktop.feature.transportation.domain.TripRequest
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft

/**
 * The tool's sections — the web's tile grid, as a side nav. Its ten tiles
 * fold into six: *Raise pickup request* and *Track request details* are one
 * request list with a button; *Assign driver*, *Driver list* and
 * *Allocated drivers* are one driver list with filters; the two
 * *Permanent allocations* tiles (coordinator's, everyone else's) are one
 * section that branches on the role; *Fill in details* is a page of its own.
 */
enum class TransportSection(val label: String) {
    Requests("Pickup requests"),
    Vehicles("Vehicle list"),
    Drivers("Drivers"),
    Permanent("Allocations"),
    MyAssignments("My assignments"),
    MyDetails("Fill in details"),
}

/** The driver list's filters — the web's three tiles and their tabs. */
enum class DriverFilter(val label: String) {
    /** `full_driver_list`: every accepted driver. */
    All("All drivers"),

    /** Assign driver → *Assigned drivers*: has a vehicle. */
    WithVehicle("With vehicle"),

    /** Assign driver → *Unassigned drivers*: no vehicle. */
    WithoutVehicle("Without vehicle"),

    /** `allocated_driver_list`, split by [AllocationFilter]. */
    Allocated("Allocated"),
}

enum class AllocationFilter(val label: String) {
    PermanentFullDay("Permanent allocation"),
    PermanentForDay("Permanent allocation for the day"),
    ForJob("Allocation for the job"),
    Available("Available"),
    ;

    /** The web's `CustomDriverListModal` predicate for this tab. */
    fun admits(user: TransportUser): Boolean = when (this) {
        PermanentFullDay -> user.permanentTrip && user.fullDayTrip
        PermanentForDay -> user.permanentTrip && !user.fullDayTrip
        ForJob -> user.isTripAssigned && !user.permanentTrip && !user.fullDayTrip
        Available -> user.isAvailable && !user.fullDayTrip
    }
}

/** Where the temporary-driver candidates come from — the web's radio pair. */
enum class TempDriverSource(val label: String) {
    Crew("Crew members"),
    TransportDepartment("Transport department"),
}

/** One passenger under entry — the web's Add passenger modal. */
data class PassengerEditor(
    val userId: String = "",
    val dateYmd: String = "",
    val time: String = "",
    val pickupAddress: String = "",
    val pickupLat: String = "",
    val pickupLng: String = "",
    val dropAddress: String = "",
    val dropLat: String = "",
    val dropLng: String = "",
    /** Set while editing a passenger already on the list; the picker is locked to them. */
    val editingUserId: String? = null,
    /** Opened by the self-assign switch: the passenger is the viewer, fixed. */
    val selfAssign: Boolean = false,
    /** Whether this passenger is for the open trip (true) or the request being raised (false). */
    val forOpenTrip: Boolean = false,
    val driverStatus: String = "",
) {
    val isEdit: Boolean get() = editingUserId != null

    fun toPassenger(user: TransportUser): TripPassenger? {
        val at = TransportClock.clockMillis(dateYmd, time)
        if (at == 0L) return null
        return TripPassenger(
            userId = user.userId,
            name = user.fullName,
            designation = user.designation,
            pickupMs = at,
            pickupTimeText = TransportClock.clockText(at),
            pickup = GeoPlace(pickupAddress.trim(), pickupLat.trim().toDoubleOrNull(),
                pickupLng.trim().toDoubleOrNull()),
            dropOff = GeoPlace(dropAddress.trim(), dropLat.trim().toDoubleOrNull(), dropLng.trim().toDoubleOrNull()),
            driverStatus = driverStatus,
        )
    }

    companion object {
        /** The form pre-filled from a passenger already on the list — for edit, or as the next one's template. */
        fun from(p: TripPassenger, editing: Boolean, forOpenTrip: Boolean) = PassengerEditor(
            userId = if (editing) p.userId else "",
            dateYmd = TransportClock.ymd(p.pickupMs),
            time = TransportClock.hm(p.pickupMs),
            pickupAddress = p.pickup.address,
            pickupLat = p.pickup.lat?.toString().orEmpty(),
            pickupLng = p.pickup.long?.toString().orEmpty(),
            dropAddress = p.dropOff.address,
            dropLat = p.dropOff.lat?.toString().orEmpty(),
            dropLng = p.dropOff.long?.toString().orEmpty(),
            editingUserId = if (editing) p.userId else null,
            forOpenTrip = forOpenTrip,
            driverStatus = if (editing) p.driverStatus else "",
        )
    }
}

/** The raise-pickup-request dialog. */
data class RaiseEditor(
    /** The date every passenger of this request is picked up on; asked first, as on the web. */
    val pickupYmd: String = "",
    val priority: TripPriority = TripPriority.Medium,
    val passengers: List<TripPassenger> = emptyList(),
    val ccUsers: List<String> = emptyList(),
    val vehicleId: String? = null,
    val driverId: String? = null,
    val saving: Boolean = false,
) {
    fun toDraft(selfId: String) = TripDraft(
        priority = priority,
        selfAssign = passengers.any { it.userId == selfId },
        passengers = passengers,
        ccUsers = ccUsers,
        vehicleId = vehicleId,
        driverId = driverId,
    )
}

/** The open trip: its record plus the coordinator's edits before an Update. */
data class TripView(
    val trip: TripRequest,
    val passengers: List<TripPassenger>,
    val ccUsers: List<String>,
    val vehicleId: String?,
    val driverId: String?,
    val busy: Boolean = false,
    /** A reject or cancel waiting for its "are you sure". */
    val confirm: TripAction? = null,
) {
    companion object {
        fun of(trip: TripRequest) = TripView(trip, trip.passengers, trip.ccUsers, trip.vehicleId, trip.driverId)
    }
}

data class VehicleEditor(
    val id: String? = null,
    val name: String = "",
    val number: String = "",
    val type: String = "",
    val seats: String = "4",
    val ownerName: String = "",
    val ownerContact: String = "",
    val ownerAddress: String = "",
    val countryCode: String = "",
    val attachments: List<StoredMedia> = emptyList(),
    /** Set when this form creates a temporary driver's private car — the owner is that driver. */
    val forTempDriverId: String? = null,
    val saving: Boolean = false,
    val uploading: Boolean = false,
) {
    fun toDraft() = VehicleDraft(
        name = name,
        number = number,
        type = type,
        seats = seats.trim().toIntOrNull() ?: 0,
        ownerName = ownerName,
        ownerContact = ownerContact,
        ownerAddress = ownerAddress,
        countryCode = countryCode,
        attachments = attachments,
    )

    companion object {
        fun from(vehicle: Vehicle) = VehicleEditor(
            id = vehicle.id,
            name = vehicle.name,
            number = vehicle.number,
            type = vehicle.type,
            seats = vehicle.seats.toString(),
            ownerName = vehicle.ownerName,
            ownerContact = vehicle.ownerContact,
            ownerAddress = vehicle.ownerAddress,
            countryCode = vehicle.countryCode,
            attachments = vehicle.attachments,
        )
    }
}

/** A vehicle's detail drawer, with a driver picked but not yet submitted. */
data class VehicleDetails(
    val vehicleId: String,
    val pendingDriverId: String? = null,
    val busy: Boolean = false,
)

data class PermanentEditor(
    val id: String? = null,
    val status: PermanentStatus = PermanentStatus.Draft,
    val passengers: List<PermanentPassenger> = emptyList(),
    val ccUsers: List<String> = emptyList(),
    val vehicleId: String? = null,
    val driverId: String? = null,
    val startYmd: String = "",
    val endYmd: String = "",
    val fullDay: Boolean = false,
    val selfManage: Boolean = false,
    /** The web's "View details": everything read-only. */
    val viewOnly: Boolean = false,
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    /** Passengers, CC and the start date are fixed once an allocation is live; a draft stays open. */
    val peopleEditable: Boolean get() = !viewOnly && (isNew || status == PermanentStatus.Draft)

    fun toDraft(asDraft: Boolean) = PermanentDraft(
        passengers = passengers,
        ccUsers = ccUsers,
        vehicleId = vehicleId,
        driverId = driverId,
        startMs = TransportClock.dayMillis(startYmd),
        endMs = TransportClock.dayMillis(endYmd),
        fullDay = fullDay,
        asDraft = asDraft,
    )

    companion object {
        fun from(trip: PermanentTrip, viewOnly: Boolean) = PermanentEditor(
            id = trip.id,
            status = trip.status,
            passengers = trip.passengers,
            ccUsers = trip.ccUsers,
            vehicleId = trip.vehicleId,
            driverId = trip.driverId,
            startYmd = TransportClock.ymd(trip.startMs),
            endYmd = TransportClock.ymd(trip.endMs),
            fullDay = trip.fullDay,
            selfManage = trip.selfManage,
            viewOnly = viewOnly,
        )
    }
}

/**
 * Fill in details — the driver's own form, or a driver's record as the
 * coordinator sees it. One editor for both, since the web's modal is one
 * component branching on `isDriver`.
 */
data class DriverDetailsEditor(
    val userId: String,
    /** The viewer editing their own details: phone, address, licence and documents open up. */
    val self: Boolean,
    val phone: String = "",
    val countryCode: String = "",
    val address: String = "",
    /** The coordinator's availability select; null where the web hides it. */
    val available: Boolean? = null,
    val vehicleId: String? = null,
    val licenceFront: StoredMedia? = null,
    val licenceBack: StoredMedia? = null,
    val documents: List<StoredMedia> = emptyList(),
    val saving: Boolean = false,
    val uploading: Boolean = false,
) {
    val licencePictures: List<StoredMedia> get() = listOfNotNull(licenceFront, licenceBack)

    /** The web's `updateUserDetails` payload for this form, by who is filling it. */
    fun toUpdate(): DriverDetailsUpdate = if (self) {
        DriverDetailsUpdate(
            userId = userId,
            phone = phone,
            countryCode = countryCode,
            address = address,
            licencePictures = licencePictures,
            documents = documents,
        )
    } else {
        DriverDetailsUpdate(
            userId = userId,
            vehicleId = vehicleId,
            isTripAssigned = available?.let { !it },
            licencePictures = licencePictures,
            documents = documents,
        )
    }

    companion object {
        fun from(user: TransportUser, self: Boolean, coordinator: Boolean) = DriverDetailsEditor(
            userId = user.userId,
            self = self,
            phone = user.phone,
            countryCode = user.countryCode,
            address = user.address,
            // The web shows the status select only to a coordinator, for a
            // driver on a day-by-day permanent allocation.
            available = if (coordinator && user.permanentTrip && !user.fullDayTrip) !user.isTripAssigned else null,
            vehicleId = user.vehicleId,
            licenceFront = user.licencePictures.firstOrNull { it.caption.equals("front", ignoreCase = true) },
            licenceBack = user.licencePictures.firstOrNull { it.caption.equals("back", ignoreCase = true) },
            documents = user.documents,
        )
    }
}

/** What a people picker is choosing for. */
enum class PickPurpose { RaiseCc, TripCc, PermanentPassengers, PermanentCc }

/** The multi-select people dialog — the web's `PassengerListModal`. */
data class PeoplePicker(
    val purpose: PickPurpose,
    val chosen: Set<String> = emptySet(),
    val query: String = "",
)

/** Which form a picked vehicle or driver lands in. */
enum class AssignTarget { Raise, Trip, Permanent, DriverDetails, VehicleDetails }

/** A yes/no the tool asks before an irreversible act. */
sealed interface TransportConfirm {
    data class DeleteVehicles(val ids: List<String>) : TransportConfirm
    data class UnassignPermanent(val trip: PermanentTrip) : TransportConfirm
    data class DeletePermanent(val trip: PermanentTrip) : TransportConfirm

    /** The web's `private_vehicle_driver_change_message`: changing the driver of a personal car. */
    data class ChangePrivateDriver(val target: AssignTarget) : TransportConfirm

    /** A crew member is being made a temporary driver — do they bring their own car? */
    data class TempDriverVehicle(val user: TransportUser) : TransportConfirm

    /** A temporary driver is being unmade; their private car goes with them. */
    data class UnassignTempDriver(val user: TransportUser) : TransportConfirm
}

/** The document-reminder message dialog — which driver, and what to ask for. */
data class ReminderEditor(val userId: String, val message: String = "", val sending: Boolean = false)

/** The temporary-drivers dialog. */
data class TempDriverDialog(
    val source: TempDriverSource = TempDriverSource.Crew,
    val query: String = "",
    val busy: Boolean = false,
)

@Suppress("LongParameterList") // One field per open surface; the screen reads them all.
data class TransportUiState(
    val viewer: TransportViewer = TransportViewer(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val section: TransportSection = TransportSection.Requests,
    val crew: List<TransportUser> = emptyList(),
    /** Licence changes waiting on a decision — the coordinator's queue. */
    val licenceRequests: List<LicenceRequest> = emptyList(),
    val driverDesignations: List<String> = emptyList(),
    /**
     * The coordinators — everyone holding the tool's posting right.
     *
     * They are excluded from the temporary-driver picker: a coordinator
     * assigns trips, and making one a driver puts them on both sides of their
     * own allocations. The web applies the same exclusion.
     */
    val coordinatorIds: List<String> = emptyList(),
    /** Everyone allowed to open the tool; empty while unknown, which keeps the pickers open. */
    val viewingRightIds: Set<String> = emptySet(),
    val countries: List<DialCountry> = emptyList(),
    /** Unread by badge unit — the tab and nav chips. */
    val badges: Map<String, Int> = emptyMap(),
    /** Bytes of files picked this session, so a fresh upload previews without a round trip. */
    val localMedia: Map<String, ByteArray> = emptyMap(),
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleTypes: List<String> = emptyList(),
    val vehicleQuery: String = "",
    /** The vehicle grid's select-to-delete mode; null when off. */
    val vehicleSelection: Set<String>? = null,
    val tripStatus: TripStatus = TripStatus.Pending,
    val trips: List<TripRequest> = emptyList(),
    val openTrip: TripView? = null,
    val raise: RaiseEditor? = null,
    val passengerDialog: PassengerEditor? = null,
    val peoplePicker: PeoplePicker? = null,
    val vehiclePicker: AssignTarget? = null,
    val driverPicker: AssignTarget? = null,
    val driverPickerQuery: String = "",
    val vehicleEditor: VehicleEditor? = null,
    val vehicleDetails: VehicleDetails? = null,
    val confirm: TransportConfirm? = null,
    val permanentTab: PermanentStatus = PermanentStatus.Permanent,
    /** A driver's own allocations: as a passenger (false) or as the driver (true). */
    val permanentAsDriver: Boolean = false,
    val permanent: List<PermanentTrip> = emptyList(),
    val permanentEditor: PermanentEditor? = null,
    val myTab: TripStatus = TripStatus.Assigned,
    val myTrips: List<TripRequest> = emptyList(),
    val driverFilter: DriverFilter = DriverFilter.All,
    val allocationFilter: AllocationFilter = AllocationFilter.PermanentFullDay,
    val driverQuery: String = "",
    val driverDetails: DriverDetailsEditor? = null,
    val reminder: ReminderEditor? = null,
    val tempDrivers: TempDriverDialog? = null,
) {
    val me: TransportUser? get() = crew.firstOrNull { it.userId == viewer.userId }
    val isDriver: Boolean get() = me?.isDriver(driverDesignations) == true

    /** Everyone a coordinator can assign: accepted drivers, not themselves. */
    val drivers: List<TransportUser>
        get() = crew.filter { it.isAccepted && it.isDriver(driverDesignations) && it.userId != viewer.userId }

    /**
     * Who may be made a temporary driver.
     *
     * Accepted crew who are not already drivers and are not the viewer — and
     * not a coordinator: a coordinator allocates the trips, so driving them
     * puts one person on both sides of their own allocation. The web applies
     * the same exclusion (`AssignNewDriver.jsx`).
     */
    fun temporaryDriverCandidates(): List<TransportUser> = crew.filter { user ->
        user.isAccepted &&
            !user.isDriver(driverDesignations) &&
            user.userId != viewer.userId &&
            user.userId !in coordinatorIds
    }

    /** The temporary-drivers dialog: candidates of one [TempDriverSource], plus the temps already made. */
    fun tempDriverRows(dialog: TempDriverDialog): List<TransportUser> {
        val fromTransport = dialog.source == TempDriverSource.TransportDepartment
        return crew.filter { user ->
            user.isAccepted &&
                user.userId != viewer.userId &&
                user.userId !in coordinatorIds &&
                (user.isTempDriver || !user.isDriver(driverDesignations)) &&
                user.department.equals(TRANSPORT_DEPARTMENT, ignoreCase = true) == fromTransport
        }.matching(dialog.query)
    }

    /** Passengers and CC candidates: accepted crew, not themselves, allowed to open the tool. */
    val passengerCandidates: List<TransportUser>
        get() = crew.filter { it.isAccepted && it.userId != viewer.userId && canOpenTool(it.userId) }

    /** The web's `ViewingRightUsersList`: an unknown rights list keeps everyone. */
    fun canOpenTool(userId: String): Boolean = viewingRightIds.isEmpty() || userId in viewingRightIds

    /** The driver list, by the web's tile filter. */
    fun filteredDrivers(): List<TransportUser> {
        val rows = crew.filter { it.isDriver(driverDesignations) && it.userId != viewer.userId }
        val filtered = when (driverFilter) {
            DriverFilter.All -> rows.filter { it.isAccepted }
            DriverFilter.WithVehicle -> rows.filter { !it.isGone && it.vehicleId != null }
            DriverFilter.WithoutVehicle -> rows.filter { !it.isGone && it.vehicleId == null }
            DriverFilter.Allocated -> rows.filter { it.isAccepted && allocationFilter.admits(it) }
        }
        return filtered.matching(driverQuery)
    }

    /** The vehicles the grid shows: live, and matching the search on name or number. */
    val visibleVehicles: List<Vehicle>
        get() = vehicles.filter { it.isLive }.filter { vehicle ->
            vehicleQuery.isBlank() ||
                vehicle.name.contains(vehicleQuery, ignoreCase = true) ||
                vehicle.number.contains(vehicleQuery, ignoreCase = true)
        }

    /**
     * The people a picker may still add: candidates less everyone the form
     * already names — its passengers, CC list and driver — narrowed by the
     * search.
     */
    fun peopleChoices(picker: PeoplePicker): List<TransportUser> {
        val named: List<String> = when (picker.purpose) {
            PickPurpose.RaiseCc -> raise?.let { r ->
                r.passengers.map { it.userId } + r.ccUsers + listOfNotNull(r.driverId)
            }
            PickPurpose.TripCc -> openTrip?.let { t ->
                t.passengers.map { it.userId } + t.ccUsers + listOfNotNull(t.driverId)
            }
            PickPurpose.PermanentPassengers -> permanentEditor?.let { p ->
                p.passengers.map { it.userId } + listOfNotNull(p.driverId)
            }
            PickPurpose.PermanentCc -> permanentEditor?.let { p ->
                p.passengers.map { it.userId } + p.ccUsers + listOfNotNull(p.driverId)
            }
        }.orEmpty()
        return passengerCandidates.filter { it.userId !in named }.matching(picker.query)
    }

    /** The web's `ShowDriversModal` filters, by what the driver is being picked for. */
    fun driverChoices(target: AssignTarget): List<TransportUser> = when (target) {
        AssignTarget.Raise, AssignTarget.Trip -> drivers.filter { !it.isTripAssigned && !it.fullDayTrip }
        AssignTarget.Permanent -> drivers.filter { !it.fullDayTrip }
        AssignTarget.VehicleDetails -> drivers.filter { it.vehicleId == null }
        AssignTarget.DriverDetails -> drivers
    }.filter { canOpenTool(it.userId) }.matching(driverPickerQuery)

    /** The web's `VehicleListModal` filters: free vehicles for a trip, driver-less ones for a driver. */
    fun vehicleChoices(target: AssignTarget): List<Vehicle> = vehicles.filter { it.isLive }.filter { vehicle ->
        when (target) {
            AssignTarget.DriverDetails -> vehicle.driverId == null
            else -> vehicle.editable
        }
    }

    val sections: List<TransportSection>
        get() = buildList {
            add(TransportSection.Requests)
            if (viewer.isCoordinator) {
                add(TransportSection.Vehicles)
                add(TransportSection.Drivers)
            }
            add(TransportSection.Permanent)
            if (isDriver) {
                add(TransportSection.MyAssignments)
                add(TransportSection.MyDetails)
            }
        }

    /** The nav chip a section carries — the web's tile badges. */
    fun sectionBadge(section: TransportSection): Int = when (section) {
        TransportSection.Requests -> badge(TRIP_REQUEST_BADGE)
        TransportSection.Vehicles -> badge(VEHICLE_BADGE)
        TransportSection.Permanent -> badge(PERMANENT_BADGE)
        TransportSection.MyAssignments -> badge(DRIVER_ASSIGNMENT_BADGE)
        TransportSection.MyDetails -> badge(DRIVER_REMINDER_BADGE)
        TransportSection.Drivers -> 0
    }

    fun badge(unit: String): Int = badges[unit] ?: 0

    fun user(id: String?): TransportUser? = crew.firstOrNull { it.userId == id }
    fun vehicle(id: String?): Vehicle? = vehicles.firstOrNull { it.id == id }

    /** A user no longer on the production shows as "Unknown", never as a raw id. */
    fun userName(id: String?): String =
        user(id)?.fullName ?: if (id.isNullOrBlank()) "—" else "Unknown"
    fun vehicleLabel(id: String?): String = vehicle(id)?.label ?: "—"

    /** People whose name or designation matches the query — the film tools' `search`. */
    private fun List<TransportUser>.matching(query: String): List<TransportUser> {
        val q = query.trim()
        if (q.isEmpty()) return this
        return filter { it.fullName.contains(q, true) || it.designationLabel.contains(q, true) }
    }

    companion object {
        const val TRANSPORT_DEPARTMENT = "transportation_department_label"
        const val TRIP_REQUEST_BADGE = "transportation_trip_request_label"
        const val VEHICLE_BADGE = "transportation_vehical_request_label"
        const val PERMANENT_BADGE = "transportation_permanent_allocation_request_label"
        const val PERMANENT_AS_PASSENGER_BADGE =
            "transportation_permanent_allocation_request_assigned_as_passenger_label"
        const val PERMANENT_AS_DRIVER_BADGE = "transportation_permanent_allocation_request_assigned_as_driver_label"
        const val DRIVER_ASSIGNMENT_BADGE = "transportation_trip_request_driver_assignment_label"
        const val DRIVER_REMINDER_BADGE = "transportation_driver_pending_document_request_label"

        fun tripStatusBadge(status: TripStatus) = "transportation_trip_request_${status.wire}_label"
    }
}

@Suppress("TooManyFunctions") // One event per act on the screen.
sealed interface TransportEvent {
    data class SelectSection(val section: TransportSection) : TransportEvent
    data object Refresh : TransportEvent
    data object DismissError : TransportEvent
    data object CancelConfirm : TransportEvent
    data object AcceptConfirm : TransportEvent

    /** "No" to a question that has a third answer — the temp driver without a car. */
    data object DeclineConfirm : TransportEvent

    // Requests
    data class SelectTripStatus(val status: TripStatus) : TransportEvent
    data class OpenTrip(val trip: TripRequest) : TransportEvent
    data object CloseTrip : TransportEvent
    data class TripAct(val action: TripAction) : TransportEvent
    data object TripDismissConfirm : TransportEvent
    data class TripSelfAssign(val on: Boolean) : TransportEvent
    data class TripRemovePassenger(val userId: String) : TransportEvent
    data class TripRemoveCc(val userId: String) : TransportEvent
    data object SendReminder : TransportEvent
    data object TrackTrip : TransportEvent
    data object NewRequest : TransportEvent
    data class RaiseChanged(val editor: RaiseEditor) : TransportEvent
    data class RaiseSelfAssign(val on: Boolean) : TransportEvent
    data class RaiseRemovePassenger(val userId: String) : TransportEvent
    data class RaiseRemoveCc(val userId: String) : TransportEvent
    data object SubmitRaise : TransportEvent
    data object CancelRaise : TransportEvent

    /** Opens the passenger form: blank for a new passenger, pre-filled to edit one. */
    data class OpenPassenger(val forOpenTrip: Boolean, val edit: TripPassenger? = null) : TransportEvent
    data class PassengerChanged(val editor: PassengerEditor) : TransportEvent
    data object SubmitPassenger : TransportEvent
    data object CancelPassenger : TransportEvent

    // Pickers
    data class OpenPeoplePicker(val purpose: PickPurpose) : TransportEvent
    data class PeoplePickerChanged(val picker: PeoplePicker) : TransportEvent
    data object SubmitPeoplePicker : TransportEvent
    data object CancelPeoplePicker : TransportEvent
    data class OpenVehiclePicker(val target: AssignTarget) : TransportEvent
    data class PickVehicle(val vehicleId: String) : TransportEvent
    data object CancelVehiclePicker : TransportEvent
    data class OpenDriverPicker(val target: AssignTarget) : TransportEvent
    data class DriverPickerQuery(val query: String) : TransportEvent
    data class PickDriver(val userId: String) : TransportEvent
    data object CancelDriverPicker : TransportEvent

    // Vehicles
    data class VehicleQuery(val query: String) : TransportEvent
    data object ToggleVehicleSelection : TransportEvent
    data class ToggleVehicleSelected(val vehicleId: String) : TransportEvent
    data object DeleteSelectedVehicles : TransportEvent
    data object NewVehicle : TransportEvent
    data class EditVehicle(val vehicle: Vehicle) : TransportEvent
    data class VehicleChanged(val editor: VehicleEditor) : TransportEvent
    data object AddVehicleImages : TransportEvent
    data class RemoveVehicleImage(val media: StoredMedia) : TransportEvent
    data object SaveVehicle : TransportEvent
    data object CancelVehicle : TransportEvent
    data class DeleteVehicle(val vehicle: Vehicle) : TransportEvent
    data class OpenVehicle(val vehicle: Vehicle) : TransportEvent
    data object CloseVehicle : TransportEvent
    data object SubmitVehicleDriver : TransportEvent

    // Permanent
    data class SelectPermanentTab(val status: PermanentStatus) : TransportEvent
    data class SelectPermanentRole(val asDriver: Boolean) : TransportEvent
    data object NewPermanent : TransportEvent
    data class EditPermanent(val trip: PermanentTrip, val viewOnly: Boolean = false) : TransportEvent
    data class PermanentChanged(val editor: PermanentEditor) : TransportEvent
    data class PermanentSelfAssign(val on: Boolean) : TransportEvent
    data class PermanentRemovePassenger(val userId: String) : TransportEvent
    data class PermanentRemoveCc(val userId: String) : TransportEvent
    data class SavePermanent(val asDraft: Boolean) : TransportEvent
    data object CancelPermanent : TransportEvent
    data class UnassignPermanent(val trip: PermanentTrip) : TransportEvent
    data class DeletePermanent(val trip: PermanentTrip) : TransportEvent
    data class SelfManage(val on: Boolean) : TransportEvent

    // Drivers
    data class SelectDriverFilter(val filter: DriverFilter) : TransportEvent
    data class SelectAllocationFilter(val filter: AllocationFilter) : TransportEvent
    data class DriverQuery(val query: String) : TransportEvent
    data class OpenDriver(val user: TransportUser) : TransportEvent
    data object OpenMyDetails : TransportEvent
    data class DriverDetailsChanged(val editor: DriverDetailsEditor) : TransportEvent
    data class UploadLicence(val back: Boolean) : TransportEvent
    data object UploadDocuments : TransportEvent
    data class RemoveDocument(val media: StoredMedia) : TransportEvent
    data object SaveDriverDetails : TransportEvent
    data object CloseDriverDetails : TransportEvent
    data class UnassignTempDriver(val user: TransportUser) : TransportEvent
    data class LicenceReminder(val userId: String) : TransportEvent
    data class OpenDocumentReminder(val userId: String) : TransportEvent
    data class ReminderChanged(val message: String) : TransportEvent
    data object SendDocumentReminder : TransportEvent
    data object CancelReminder : TransportEvent
    data object OpenTempDrivers : TransportEvent
    data class TempDriversChanged(val dialog: TempDriverDialog) : TransportEvent
    data class ToggleTempDriver(val user: TransportUser, val on: Boolean) : TransportEvent
    data object CloseTempDrivers : TransportEvent

    /** Approve or reject a driver's licence change. */
    data class DecideLicence(val requestId: String, val approved: Boolean) : TransportEvent

    // My assignments
    data class SelectMyTab(val status: TripStatus) : TransportEvent
}

sealed interface TransportEffect {
    data class Notice(val text: String, val success: Boolean = true) : TransportEffect

    /** Open a URL in the browser — a passenger's address, the driver's last position. */
    data class OpenLink(val url: String) : TransportEffect
}
