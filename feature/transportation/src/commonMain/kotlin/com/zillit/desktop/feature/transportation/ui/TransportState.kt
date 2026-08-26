package com.zillit.desktop.feature.transportation.ui

import com.zillit.desktop.feature.transportation.domain.GeoPlace
import com.zillit.desktop.feature.transportation.domain.PermanentDraft
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PermanentTrip
import com.zillit.desktop.feature.transportation.domain.LicenceRequest
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

/** The tool's sections — the web's tile grid, as a side nav. */
enum class TransportSection(val label: String) {
    Requests("Pickup requests"),
    Vehicles("Vehicles"),
    Permanent("Permanent allocations"),
    Drivers("Drivers"),
    MyAssignments("My assignments"),
}

/** One passenger under entry: text fields, parsed on add. */
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
) {
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
        )
    }
}

/** The raise-pickup-request dialog. */
data class RaiseEditor(
    val priority: TripPriority = TripPriority.Medium,
    val selfAssign: Boolean = false,
    val passengers: List<TripPassenger> = emptyList(),
    val ccUsers: List<String> = emptyList(),
    val vehicleId: String? = null,
    val driverId: String? = null,
    val passenger: PassengerEditor = PassengerEditor(),
    val saving: Boolean = false,
) {
    fun toDraft() = TripDraft(priority, selfAssign, passengers, ccUsers, vehicleId, driverId)
}

/** The open trip: its record plus the coordinator's reassignments. */
data class TripView(
    val trip: TripRequest,
    val vehicleId: String?,
    val driverId: String?,
    val busy: Boolean = false,
)

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
    val saving: Boolean = false,
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
        )
    }
}

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
    val saving: Boolean = false,
) {
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
        fun from(trip: PermanentTrip) = PermanentEditor(
            id = trip.id,
            status = trip.status,
            passengers = trip.passengers,
            ccUsers = trip.ccUsers,
            vehicleId = trip.vehicleId,
            driverId = trip.driverId,
            startYmd = TransportClock.ymd(trip.startMs),
            endYmd = TransportClock.ymd(trip.endMs),
            fullDay = trip.fullDay,
        )
    }
}

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
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleTypes: List<String> = emptyList(),
    val tripStatus: TripStatus = TripStatus.Pending,
    val trips: List<TripRequest> = emptyList(),
    val openTrip: TripView? = null,
    val raise: RaiseEditor? = null,
    val vehicleEditor: VehicleEditor? = null,
    val assignDriverFor: Vehicle? = null,
    val confirmDeleteVehicles: List<String>? = null,
    val permanentTab: PermanentStatus = PermanentStatus.Permanent,
    val permanent: List<PermanentTrip> = emptyList(),
    val permanentEditor: PermanentEditor? = null,
    val myTab: TripStatus = TripStatus.Assigned,
    val myTrips: List<TripRequest> = emptyList(),
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

    /** Passengers and CC candidates: accepted crew, not themselves. */
    val passengerCandidates: List<TransportUser>
        get() = crew.filter { it.isAccepted && it.userId != viewer.userId }

    val sections: List<TransportSection>
        get() = buildList {
            add(TransportSection.Requests)
            if (viewer.isCoordinator) {
                add(TransportSection.Vehicles)
                add(TransportSection.Drivers)
            }
            add(TransportSection.Permanent)
            if (isDriver) add(TransportSection.MyAssignments)
        }

    /** A user no longer on the production shows as "Unknown", never as a raw id. */
    fun userName(id: String?): String =
        crew.firstOrNull { it.userId == id }?.fullName ?: if (id.isNullOrBlank()) "—" else "Unknown"
    fun vehicleLabel(id: String?): String =
        vehicles.firstOrNull { it.id == id }?.let { "${it.name} · ${it.number}" } ?: "—"
}

sealed interface TransportEvent {
    data class SelectSection(val section: TransportSection) : TransportEvent
    data object Refresh : TransportEvent
    data object DismissError : TransportEvent

    // Requests
    data class SelectTripStatus(val status: TripStatus) : TransportEvent
    data class OpenTrip(val trip: TripRequest) : TransportEvent
    data object CloseTrip : TransportEvent
    data class TripPick(val vehicleId: String? = null, val driverId: String? = null) : TransportEvent
    data class TripAct(val action: TripAction) : TransportEvent
    data object SendReminder : TransportEvent
    data object NewRequest : TransportEvent
    data class RaiseChanged(
        val priority: TripPriority? = null,
        val selfAssign: Boolean? = null,
        val vehicleId: String? = null,
        val driverId: String? = null,
        val toggleCc: String? = null,
        val passenger: PassengerEditor? = null,
    ) : TransportEvent
    data object AddPassenger : TransportEvent
    data class RemovePassenger(val userId: String) : TransportEvent
    data object SubmitRaise : TransportEvent
    data object CancelRaise : TransportEvent

    // Vehicles
    data object NewVehicle : TransportEvent
    data class EditVehicle(val vehicle: Vehicle) : TransportEvent
    data class VehicleChanged(val editor: VehicleEditor) : TransportEvent
    data object SaveVehicle : TransportEvent
    data object CancelVehicle : TransportEvent
    data class DeleteVehicles(val ids: List<String>) : TransportEvent
    data object ConfirmDeleteVehicles : TransportEvent
    data object CancelDeleteVehicles : TransportEvent
    data class AssignDriver(val vehicle: Vehicle) : TransportEvent
    data class PickDriver(val userId: String) : TransportEvent
    data object CancelAssign : TransportEvent

    // Permanent
    data class SelectPermanentTab(val status: PermanentStatus) : TransportEvent
    data object NewPermanent : TransportEvent
    data class EditPermanent(val trip: PermanentTrip) : TransportEvent
    data class PermanentChanged(val editor: PermanentEditor) : TransportEvent
    data class SavePermanent(val asDraft: Boolean) : TransportEvent
    data object CancelPermanent : TransportEvent
    data class UnassignPermanent(val trip: PermanentTrip) : TransportEvent
    data class DeletePermanent(val trip: PermanentTrip) : TransportEvent
    data class SelfManage(val trip: PermanentTrip, val on: Boolean) : TransportEvent

    // Drivers
    data class ToggleTempDriver(val userId: String, val on: Boolean) : TransportEvent
    data class SetAvailability(val userId: String, val available: Boolean) : TransportEvent
    data class DocumentReminder(val userId: String, val type: String) : TransportEvent

    /** Approve or reject a driver's licence change. */
    data class DecideLicence(val requestId: String, val approved: Boolean) : TransportEvent

    // My assignments
    data class SelectMyTab(val status: TripStatus) : TransportEvent
}

sealed interface TransportEffect {
    data class Notice(val text: String) : TransportEffect
}
