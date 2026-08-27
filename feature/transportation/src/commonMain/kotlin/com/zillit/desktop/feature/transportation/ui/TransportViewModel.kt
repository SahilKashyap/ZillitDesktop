@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.transportation.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.TransportRepository
import com.zillit.desktop.feature.transportation.domain.TransportSyncKind
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate

/**
 * Transportation: pickup requests, vehicles, permanent allocations, drivers,
 * and a driver's own assignments — the web's tile-and-modal page as one
 * window with sections.
 */
class TransportViewModel(
    private val repository: TransportRepository,
    private val resolveViewer: () -> TransportViewer,
    private val nowMillis: () -> Long,
    /** The web's `notification:read` for a request segment. */
    private val onSegmentViewed: suspend (segment: String) -> Unit = {},
) : ZillitViewModel<TransportUiState, TransportEvent, TransportEffect>(TransportUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
    }

    /**
     * Re-runs the load a socket frame touches, as the web's handlers do:
     * Fleet reloads crew and vehicles (they back every section's pickers),
     * Trips reloads whichever request list is on screen, and Permanent
     * reloads the allocations plus the crew — the web refetches ALL users
     * on a permanent request too (ZL-13708). Guarded so a second start
     * (the window reopening) does not stack collectors; `conflate()` folds
     * a burst into one reload.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { kind ->
                when (kind) {
                    TransportSyncKind.Fleet -> reloadCrewAndVehicles()
                    TransportSyncKind.Trips -> when (state.value.section) {
                        TransportSection.Requests -> loadTrips()
                        TransportSection.MyAssignments -> loadMine()
                        else -> Unit
                    }
                    TransportSyncKind.Permanent -> {
                        reloadCrewAndVehicles()
                        if (state.value.section == TransportSection.Permanent) loadPermanent()
                    }
                }
            }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.SelectSection -> {
                setState { copy(section = event.section) }
                loadSection()
            }
            TransportEvent.Refresh -> refresh()
            TransportEvent.DismissError -> setState { copy(error = null) }
            is TransportEvent.SelectTripStatus -> {
                setState { copy(tripStatus = event.status) }
                loadTrips()
            }
            is TransportEvent.OpenTrip -> setState {
                copy(openTrip = TripView(event.trip, event.trip.vehicleId, event.trip.driverId))
            }
            TransportEvent.CloseTrip -> setState { copy(openTrip = null) }
            is TransportEvent.TripPick -> setState {
                copy(openTrip = openTrip?.copy(vehicleId = event.vehicleId ?: openTrip.vehicleId,
                    driverId = event.driverId ?: openTrip.driverId))
            }
            is TransportEvent.TripAct -> actOnTrip(event.action)
            TransportEvent.SendReminder -> sendReminder()
            TransportEvent.NewRequest -> setState { copy(raise = RaiseEditor()) }
            is TransportEvent.RaiseChanged -> setState {
                copy(
                    raise = raise?.copy(
                        priority = event.priority ?: raise.priority,
                        selfAssign = event.selfAssign ?: raise.selfAssign,
                        vehicleId = event.vehicleId ?: raise.vehicleId,
                        driverId = event.driverId ?: raise.driverId,
                        ccUsers = event.toggleCc?.let { id -> if (id in raise.ccUsers) raise.ccUsers - id else raise
                            .ccUsers + id }
                            ?: raise.ccUsers,
                        passenger = event.passenger ?: raise.passenger,
                    ),
                )
            }
            TransportEvent.AddPassenger -> addPassenger()
            is TransportEvent.RemovePassenger -> setState {
                copy(raise = raise?.copy(passengers = raise.passengers.filterNot { it.userId == event.userId }))
            }
            TransportEvent.SubmitRaise -> submitRaise()
            TransportEvent.CancelRaise -> setState { copy(raise = null) }
            TransportEvent.NewVehicle -> setState {
                copy(vehicleEditor = VehicleEditor(type = vehicleTypes.firstOrNull().orEmpty()))
            }
            is TransportEvent.EditVehicle -> setState { copy(vehicleEditor = VehicleEditor.from(event.vehicle)) }
            is TransportEvent.VehicleChanged -> setState { copy(vehicleEditor = event.editor) }
            TransportEvent.SaveVehicle -> saveVehicle()
            TransportEvent.CancelVehicle -> setState { copy(vehicleEditor = null) }
            is TransportEvent.DeleteVehicles -> setState { copy(confirmDeleteVehicles = event.ids) }
            TransportEvent.ConfirmDeleteVehicles -> deleteVehicles()
            TransportEvent.CancelDeleteVehicles -> setState { copy(confirmDeleteVehicles = null) }
            is TransportEvent.AssignDriver -> setState { copy(assignDriverFor = event.vehicle) }
            is TransportEvent.PickDriver -> assignDriver(event.userId)
            TransportEvent.CancelAssign -> setState { copy(assignDriverFor = null) }
            is TransportEvent.SelectPermanentTab -> {
                setState { copy(permanentTab = event.status) }
                loadPermanent()
            }
            TransportEvent.NewPermanent -> setState { copy(permanentEditor = PermanentEditor()) }
            is TransportEvent.EditPermanent -> setState { copy(permanentEditor = PermanentEditor.from(event.trip)) }
            is TransportEvent.PermanentChanged -> setState { copy(permanentEditor = event.editor) }
            is TransportEvent.SavePermanent -> savePermanent(event.asDraft)
            TransportEvent.CancelPermanent -> setState { copy(permanentEditor = null) }
            is TransportEvent.UnassignPermanent -> run("Allocation ended") { repository.unassignPermanent(event.trip) }
            is TransportEvent.DeletePermanent -> run("Draft deleted") { repository.deletePermanent(event.trip.id) }
            is TransportEvent.SelfManage -> run(if (event.on) "Self-managed" else "Managed by production") {
                repository.setSelfManage(event.trip.id, event.on)
            }
            is TransportEvent.ToggleTempDriver -> run(if (event
                .on) "Temporary driver added" else "Temporary driver removed") {
                repository.updateDriver(event.userId, isTempDriver = event.on)
            }
            is TransportEvent.SetAvailability -> run("Availability updated") {
                repository.updateDriver(event.userId, isTripAssigned = !event.available)
            }
            is TransportEvent.DocumentReminder -> run("Reminder sent") {
                repository.pendingDocumentReminder(event.userId, event.type, null)
            }
            is TransportEvent.DecideLicence -> run(
                if (event.approved) "Licence approved" else "Licence rejected",
            ) {
                repository.decideLicenceRequest(event.requestId, event.approved)
            }
            is TransportEvent.SelectMyTab -> {
                setState { copy(myTab = event.status) }
                loadMine()
            }
        }
    }

    // Loading ----------------------------------------------------------------

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            coroutineScope {
                val crew = async { repository.crew() }
                val designations = async { repository.driverDesignations() }
                val vehicles = async { repository.vehicles() }
                val types = async { repository.vehicleTypes() }
                val coordinators = async { repository.postingRightUsers() }
                val crewList = crew.await().orError()
                val designationList = designations.await().orError()
                val vehicleList = vehicles.await().orError()
                val typeList = types.await().orError()
                // Failing soft: an unreadable coordinator list must not empty
                // the picker, so the last known set stands.
                val coordinatorList = coordinators.await().orError()
                setState {
                    copy(
                        loading = false,
                        crew = crewList ?: this.crew,
                        driverDesignations = designationList ?: driverDesignations,
                        coordinatorIds = coordinatorList ?: coordinatorIds,
                        vehicles = vehicleList ?: this.vehicles,
                        vehicleTypes = typeList ?: vehicleTypes,
                    )
                }
            }
            loadSection()
        }
    }

    private fun loadSection() {
        when (state.value.section) {
            TransportSection.Requests -> loadTrips()
            TransportSection.Permanent -> loadPermanent()
            TransportSection.MyAssignments -> loadMine()
            TransportSection.Vehicles, TransportSection.Drivers -> reloadCrewAndVehicles()
        }
    }

    private fun reloadCrewAndVehicles() {
        launch {
            val crewList = repository.crew().orError()
            val vehicleList = repository.vehicles().orError()
            setState { copy(crew = crewList ?: crew, vehicles = vehicleList ?: vehicles) }
        }
        loadLicenceRequests()
    }

    /**
     * The licence-change queue.
     *
     * Only a coordinator can answer one, so only a coordinator is asked for
     * the list. A failure costs the queue, never the screen — the same
     * bargain the crew and vehicle reloads make.
     */
    private fun loadLicenceRequests() {
        if (!currentState.viewer.isCoordinator) return
        launch {
            repository.licenceRequests().orError()?.let { rows ->
                setState { copy(licenceRequests = rows.filter { it.verified == null }) }
            }
        }
    }

    private fun loadTrips() {
        val s = state.value
        setState { copy(loading = true) }
        launch {
            val rows = repository.trips(s.tripStatus, asDriver = false, s.viewer.userId,
                s.viewer.isCoordinator).orError()
            setState { copy(loading = false, trips = rows ?: trips) }
            onSegmentViewed("transportation_trip_request_${s.tripStatus.wire}_label")
        }
    }

    private fun loadPermanent() {
        val s = state.value
        setState { copy(loading = true) }
        launch {
            val rows = if (s.viewer.isCoordinator) {
                repository.permanentTrips(asDriver = false, s.viewer.userId, coordinator = true,
                    status = s.permanentTab)
            } else {
                repository.myPermanentTrips(asDriver = s.isDriver, s.viewer.userId)
            }.orError()
            setState { copy(loading = false, permanent = rows ?: permanent) }
            onSegmentViewed("transportation_permanent_allocation_request_label")
        }
    }

    private fun loadMine() {
        val s = state.value
        setState { copy(loading = true) }
        launch {
            val rows = repository.trips(s.myTab, asDriver = true, s.viewer.userId, coordinator = false).orError()
            setState { copy(loading = false, myTrips = rows ?: myTrips) }
            onSegmentViewed("transportation_trip_request_driver_assignment_label")
        }
    }

    // Requests ---------------------------------------------------------------

    /** What stops a passenger being added, in the order the web checks. */
    private fun passengerProblem(passenger: TripPassenger?): String? = when {
        passenger == null -> "The pickup needs a date (YYYY-MM-DD) and a time (HH:mm)"
        passenger.pickupMs <= nowMillis() -> "The pickup time is in the past"
        // The server refuses a place without coordinates (406, "passengers
        // required"); the web always has them because its picker is a map.
        !passenger.pickup.hasCoordinates || !passenger.dropOff.hasCoordinates ->
            "Pickup and drop-off need latitude and longitude (from Google Maps)"
        else -> null
    }

    private fun addPassenger() {
        val s = state.value
        val editor = s.raise ?: return
        val user = s.crew.firstOrNull { it.userId == editor.passenger.userId } ?: run {
            setState { copy(error = "Pick a passenger") }
            return
        }
        val passenger = editor.passenger.toPassenger(user)
        val problem = passengerProblem(passenger)
        if (passenger == null || problem != null) {
            setState { copy(error = problem ?: "Cannot add this passenger") }
            return
        }
        setState {
            copy(
                raise = raise?.copy(
                    passengers = raise.passengers.filterNot { it.userId == passenger.userId } + passenger,
                    passenger = PassengerEditor(dateYmd = raise.passenger.dateYmd),
                ),
            )
        }
    }

    /** The web's `validateRaise`, in the same order it reports problems. */
    private fun raiseProblem(editor: RaiseEditor, coordinator: Boolean, vehicles: List<Vehicle>): String? {
        val seats = vehicles.firstOrNull { it.id == editor.vehicleId }?.seats ?: Int.MAX_VALUE
        return when {
            editor.passengers.isEmpty() -> "Add at least one passenger"
            coordinator && editor.vehicleId == null -> "Assign a vehicle"
            coordinator && editor.driverId == null -> "Assign a driver"
            coordinator && seats < editor.passengers.size -> "The vehicle has too few seats for these passengers"
            editor.passengers.any { it.pickupMs <= nowMillis() } -> "A pickup time is in the past"
            else -> null
        }
    }

    private fun submitRaise() {
        val s = state.value
        val editor = s.raise ?: return
        val coordinator = s.viewer.isCoordinator
        val problem = raiseProblem(editor, coordinator, s.vehicles)
        if (problem != null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(raise = editor.copy(saving = true), busy = true) }
        launch {
            when (val result = repository.raiseTrip(editor.toDraft(), coordinator)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, raise = editor.copy(saving = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            busy = false,
                            raise = null,
                            tripStatus = if (coordinator) TripStatus.Assigned else TripStatus.Pending,
                        )
                    }
                    sendEffect(TransportEffect.Notice("Pickup request raised"))
                    loadTrips()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun actOnTrip(action: TripAction) {
        val s = state.value
        val open = s.openTrip ?: return
        val driverRides = open.driverId != null && open.trip.passengers.any { it.userId == open.driverId }
        if (action == TripAction.Update && driverRides) {
            setState { copy(error = "Driver and passengers cannot be the same") }
            return
        }
        setState { copy(openTrip = open.copy(busy = true)) }
        launch {
            val update = TripUpdate(
                tripId = open.trip.id,
                action = action,
                passengers = open.trip.passengers,
                ccUsers = open.trip.ccUsers,
                current = open.trip,
                vehicleId = open.vehicleId,
                driverId = open.driverId,
            )
            when (val result = repository.updateTrip(update, nowMillis())) {
                is ZillitResult.Failure -> setState { copy(openTrip = open.copy(busy = false),
                    error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(openTrip = null) }
                    sendEffect(TransportEffect.Notice(action.notice()))
                    loadTrips()
                    if (s.section == TransportSection.MyAssignments) loadMine()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun TripAction.notice() = when (this) {
        TripAction.Approve -> "Request approved"
        TripAction.Reject -> "Request rejected"
        TripAction.Start -> "Trip started"
        TripAction.End -> "Trip completed"
        TripAction.Cancel -> "Trip cancelled"
        TripAction.Update -> "Request updated"
    }

    private fun sendReminder() {
        val open = state.value.openTrip ?: return
        run("Reminder sent") { repository.sendReminder(open.trip.id) }
    }

    // Vehicles ---------------------------------------------------------------

    private fun saveVehicle() {
        val editor = state.value.vehicleEditor ?: return
        val draft = editor.toDraft()
        draft.problem()?.let { message ->
            setState { copy(error = message) }
            return
        }
        setState { copy(vehicleEditor = editor.copy(saving = true), busy = true) }
        launch {
            val id = editor.id
            val result = if (id == null) repository.addVehicle(draft) else repository.updateVehicle(id, draft)
            when (result) {
                is ZillitResult.Failure -> setState { copy(busy = false, vehicleEditor = editor.copy(saving = false),
                    error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, vehicleEditor = null) }
                    sendEffect(TransportEffect.Notice(if (id == null) "Vehicle added" else "Vehicle updated"))
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun deleteVehicles() {
        val ids = state.value.confirmDeleteVehicles ?: return
        setState { copy(confirmDeleteVehicles = null) }
        run("Vehicle removed") { repository.deleteVehicles(ids) }
    }

    private fun assignDriver(userId: String) {
        val vehicle = state.value.assignDriverFor ?: return
        setState { copy(assignDriverFor = null) }
        run("Driver assigned") { repository.updateDriver(userId, vehicleId = vehicle.id) }
    }

    // Permanent --------------------------------------------------------------

    private fun savePermanent(asDraft: Boolean) {
        val s = state.value
        val editor = s.permanentEditor ?: return
        val draft = editor.toDraft(asDraft)
        draft.problem(s.vehicles.firstOrNull { it.id == draft.vehicleId }?.seats)?.let { message ->
            setState { copy(error = message) }
            return
        }
        setState { copy(permanentEditor = editor.copy(saving = true), busy = true) }
        launch {
            val id = editor.id
            val status = if (asDraft) PermanentStatus.Draft else PermanentStatus.Permanent
            val result = if (id == null) repository.createPermanent(draft) else repository.updatePermanent(id, draft,
                status)
            when (result) {
                is ZillitResult.Failure -> setState { copy(busy = false, permanentEditor = editor.copy(saving = false),
                    error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, permanentEditor = null, permanentTab = status) }
                    sendEffect(TransportEffect.Notice(if (asDraft) "Draft saved" else "Allocation saved"))
                    loadPermanent()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    // Shared -----------------------------------------------------------------

    private fun run(notice: String, block: suspend () -> ZillitResult<Unit>) {
        setState { copy(busy = true) }
        launch {
            when (val result = block()) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(TransportEffect.Notice(notice))
                    reloadCrewAndVehicles()
                    loadSection()
                }
            }
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.localised()
            setState { copy(error = message) }
            null
        }
    }
}
