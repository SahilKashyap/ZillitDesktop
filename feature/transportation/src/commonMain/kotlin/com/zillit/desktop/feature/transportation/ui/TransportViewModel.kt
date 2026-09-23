@file:Suppress("TooManyFunctions", "LargeClass") // One handler per user act; the web spreads them over 40 files.

package com.zillit.desktop.feature.transportation.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.domain.PickedMedia
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportHost
import com.zillit.desktop.feature.transportation.domain.TransportMediaKind
import com.zillit.desktop.feature.transportation.domain.TransportRepository
import com.zillit.desktop.feature.transportation.domain.TransportSyncKind
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.TripUpdate
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Transportation: pickup requests, vehicles, permanent allocations, drivers,
 * and a driver's own assignments and details — the web's tile-and-modal
 * page as one window with sections.
 */
class TransportViewModel(
    private val repository: TransportRepository,
    private val resolveViewer: () -> TransportViewer,
    private val nowMillis: () -> Long,
    /** The web's `notification:read` for a request segment. */
    private val onSegmentViewed: suspend (segment: String) -> Unit = {},
    private val host: TransportHost = TransportHost.None,
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
        launch { host.badges.collect { counts -> setState { copy(badges = counts) } } }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.SelectSection -> selectSection(event.section)
            TransportEvent.Refresh -> refresh()
            TransportEvent.DismissError -> setState { copy(error = null) }
            TransportEvent.CancelConfirm -> setState { copy(confirm = null) }
            TransportEvent.AcceptConfirm -> acceptConfirm()
            TransportEvent.DeclineConfirm -> declineConfirm()

            is TransportEvent.SelectTripStatus -> {
                setState { copy(tripStatus = event.status) }
                loadTrips()
            }
            is TransportEvent.OpenTrip -> setState { copy(openTrip = TripView.of(event.trip)) }
            TransportEvent.CloseTrip -> setState { copy(openTrip = null) }
            is TransportEvent.TripAct -> tripAct(event.action)
            TransportEvent.TripDismissConfirm -> setState { copy(openTrip = openTrip?.copy(confirm = null)) }
            is TransportEvent.TripSelfAssign -> tripSelfAssign(event.on)
            is TransportEvent.TripRemovePassenger -> setState {
                copy(openTrip = openTrip?.let { it.copy(passengers = it.passengers.filterNot { p -> p.userId == event
                    .userId }) })
            }
            is TransportEvent.TripRemoveCc -> setState {
                copy(openTrip = openTrip?.let { it.copy(ccUsers = it.ccUsers - event.userId) })
            }
            TransportEvent.SendReminder -> sendReminder()
            TransportEvent.TrackTrip -> trackTrip()
            TransportEvent.NewRequest -> setState {
                copy(raise = RaiseEditor(pickupYmd = TransportClock.today(nowMillis())))
            }
            is TransportEvent.RaiseChanged -> setState { copy(raise = event.editor) }
            is TransportEvent.RaiseSelfAssign -> raiseSelfAssign(event.on)
            is TransportEvent.RaiseRemovePassenger -> setState {
                copy(raise = raise?.copy(passengers = raise.passengers.filterNot { it.userId == event.userId }))
            }
            is TransportEvent.RaiseRemoveCc -> setState {
                copy(raise = raise?.copy(ccUsers = raise.ccUsers - event.userId))
            }
            TransportEvent.SubmitRaise -> submitRaise()
            TransportEvent.CancelRaise -> setState { copy(raise = null) }

            is TransportEvent.OpenPassenger -> openPassenger(event.forOpenTrip, event.edit, selfAssign = false)
            is TransportEvent.PassengerChanged -> setState { copy(passengerDialog = event.editor) }
            TransportEvent.SubmitPassenger -> submitPassenger()
            TransportEvent.CancelPassenger -> setState { copy(passengerDialog = null) }

            is TransportEvent.OpenPeoplePicker -> setState { copy(peoplePicker = PeoplePicker(event.purpose)) }
            is TransportEvent.PeoplePickerChanged -> setState { copy(peoplePicker = event.picker) }
            TransportEvent.SubmitPeoplePicker -> submitPeoplePicker()
            TransportEvent.CancelPeoplePicker -> setState { copy(peoplePicker = null) }
            is TransportEvent.OpenVehiclePicker -> setState { copy(vehiclePicker = event.target) }
            is TransportEvent.PickVehicle -> pickVehicle(event.vehicleId)
            TransportEvent.CancelVehiclePicker -> setState { copy(vehiclePicker = null) }
            is TransportEvent.OpenDriverPicker -> openDriverPicker(event.target)
            is TransportEvent.DriverPickerQuery -> setState { copy(driverPickerQuery = event.query) }
            is TransportEvent.PickDriver -> pickDriver(event.userId)
            TransportEvent.CancelDriverPicker -> setState { copy(driverPicker = null, driverPickerQuery = "") }

            is TransportEvent.VehicleQuery -> setState { copy(vehicleQuery = event.query) }
            TransportEvent.ToggleVehicleSelection -> setState {
                copy(vehicleSelection = if (vehicleSelection == null) emptySet() else null)
            }
            is TransportEvent.ToggleVehicleSelected -> setState {
                val current = vehicleSelection ?: emptySet()
                copy(vehicleSelection = if (event.vehicleId in current) current - event.vehicleId else current + event
                    .vehicleId)
            }
            TransportEvent.DeleteSelectedVehicles -> state.value.vehicleSelection?.takeIf { it.isNotEmpty() }?.let {
                setState { copy(confirm = TransportConfirm.DeleteVehicles(it.toList())) }
            }
            TransportEvent.NewVehicle -> newVehicle()
            is TransportEvent.EditVehicle -> setState { copy(vehicleEditor = VehicleEditor.from(event.vehicle)) }
            is TransportEvent.VehicleChanged -> setState { copy(vehicleEditor = event.editor) }
            TransportEvent.AddVehicleImages -> addVehicleImages()
            is TransportEvent.RemoveVehicleImage -> setState {
                copy(vehicleEditor = vehicleEditor?.let { it.copy(attachments = it.attachments - event.media) })
            }
            TransportEvent.SaveVehicle -> saveVehicle()
            TransportEvent.CancelVehicle -> setState { copy(vehicleEditor = null) }
            is TransportEvent.DeleteVehicle -> setState {
                copy(confirm = TransportConfirm.DeleteVehicles(listOf(event.vehicle.id)))
            }
            is TransportEvent.OpenVehicle -> setState { copy(vehicleDetails = VehicleDetails(event.vehicle.id)) }
            TransportEvent.CloseVehicle -> setState { copy(vehicleDetails = null) }
            TransportEvent.SubmitVehicleDriver -> submitVehicleDriver()

            is TransportEvent.SelectPermanentTab -> {
                setState { copy(permanentTab = event.status) }
                loadPermanent()
            }
            is TransportEvent.SelectPermanentRole -> {
                setState { copy(permanentAsDriver = event.asDriver) }
                loadPermanent()
            }
            TransportEvent.NewPermanent -> setState { copy(permanentEditor = PermanentEditor()) }
            is TransportEvent.EditPermanent -> setState {
                copy(permanentEditor = PermanentEditor.from(event.trip, viewOnly = event.viewOnly))
            }
            is TransportEvent.PermanentChanged -> setState { copy(permanentEditor = event.editor) }
            is TransportEvent.PermanentSelfAssign -> permanentSelfAssign(event.on)
            is TransportEvent.PermanentRemovePassenger -> setState {
                copy(permanentEditor = permanentEditor?.let { it.copy(passengers = it.passengers.filterNot { p -> p
                    .userId == event.userId }) })
            }
            is TransportEvent.PermanentRemoveCc -> setState {
                copy(permanentEditor = permanentEditor?.let { it.copy(ccUsers = it.ccUsers - event.userId) })
            }
            is TransportEvent.SavePermanent -> savePermanent(event.asDraft)
            TransportEvent.CancelPermanent -> setState { copy(permanentEditor = null) }
            is TransportEvent.UnassignPermanent -> setState {
                copy(confirm = TransportConfirm.UnassignPermanent(event.trip))
            }
            is TransportEvent.DeletePermanent -> setState {
                copy(confirm = TransportConfirm.DeletePermanent(event.trip))
            }
            is TransportEvent.SelfManage -> selfManage(event.on)

            is TransportEvent.SelectDriverFilter -> setState { copy(driverFilter = event.filter) }
            is TransportEvent.SelectAllocationFilter -> setState { copy(allocationFilter = event.filter) }
            is TransportEvent.DriverQuery -> setState { copy(driverQuery = event.query) }
            is TransportEvent.OpenDriver -> openDriver(event.user, self = false)
            TransportEvent.OpenMyDetails -> state.value.me?.let { openDriver(it, self = true) }
            is TransportEvent.DriverDetailsChanged -> setState { copy(driverDetails = event.editor) }
            is TransportEvent.UploadLicence -> uploadLicence(event.back)
            TransportEvent.UploadDocuments -> uploadDocuments()
            is TransportEvent.RemoveDocument -> setState {
                copy(driverDetails = driverDetails?.let { it.copy(documents = it.documents - event.media) })
            }
            TransportEvent.SaveDriverDetails -> saveDriverDetails()
            TransportEvent.CloseDriverDetails -> setState { copy(driverDetails = null) }
            is TransportEvent.UnassignTempDriver -> setState {
                copy(confirm = TransportConfirm.UnassignTempDriver(event.user))
            }
            is TransportEvent.LicenceReminder -> run(str(S.desktop_transport_licence_reminder_sent)) {
                repository.pendingDocumentReminder(event.userId, "license", null)
            }
            is TransportEvent.OpenDocumentReminder -> setState { copy(reminder = ReminderEditor(event.userId)) }
            is TransportEvent.ReminderChanged -> setState { copy(reminder = reminder?.copy(message = event.message)) }
            TransportEvent.SendDocumentReminder -> sendDocumentReminder()
            TransportEvent.CancelReminder -> setState { copy(reminder = null) }
            TransportEvent.OpenTempDrivers -> setState { copy(tempDrivers = TempDriverDialog()) }
            is TransportEvent.TempDriversChanged -> setState { copy(tempDrivers = event.dialog) }
            is TransportEvent.ToggleTempDriver -> toggleTempDriver(event.user, event.on)
            TransportEvent.CloseTempDrivers -> setState { copy(tempDrivers = null) }
            is TransportEvent.DecideLicence -> run(
                str(
                    if (event.approved) S.desktop_transport_licence_approved
                    else S.desktop_transport_licence_rejected,
                ),
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

    private fun selectSection(section: TransportSection) {
        setState { copy(section = section) }
        if (section == TransportSection.MyDetails) {
            state.value.me?.let { openDriver(it, self = true) }
        }
        loadSection()
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            coroutineScope {
                val crew = async { repository.crew() }
                val designations = async { repository.driverDesignations() }
                val vehicles = async { repository.vehicles() }
                val types = async { repository.vehicleTypes() }
                val coordinators = async { repository.postingRightUsers() }
                val viewers = async { repository.viewingRightUserIds() }
                val countries = async { host.countries() }
                val crewList = crew.await().orError()
                val designationList = designations.await().orError()
                val vehicleList = vehicles.await().orError()
                val typeList = types.await().orError()
                // Failing soft AND quietly: `posting-rights-users` answers 404
                // on dev, so an error banner here would greet every open of
                // the tool. The last known set stands; the rights list and
                // the dialling codes make the same bargain.
                val coordinatorList = (coordinators.await() as? ZillitResult.Success)?.data
                val viewerIds = (viewers.await() as? ZillitResult.Success)?.data
                val countryList = countries.await()
                setState {
                    copy(
                        loading = false,
                        crew = crewList ?: this.crew,
                        driverDesignations = designationList ?: driverDesignations,
                        coordinatorIds = coordinatorList ?: coordinatorIds,
                        viewingRightIds = viewerIds ?: viewingRightIds,
                        countries = countryList.ifEmpty { this.countries },
                        vehicles = vehicleList ?: this.vehicles,
                        // The web's own type list stands in when the preset answers nothing.
                        vehicleTypes = typeList?.ifEmpty { null } ?: vehicleTypes.ifEmpty { DEFAULT_VEHICLE_TYPES },
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
            TransportSection.Vehicles -> {
                reloadCrewAndVehicles()
                launch { onSegmentViewed(TransportUiState.VEHICLE_BADGE) }
            }
            TransportSection.Drivers -> reloadCrewAndVehicles()
            TransportSection.MyDetails -> reloadCrewAndVehicles()
        }
    }

    private fun reloadCrewAndVehicles() {
        launch {
            val crewList = repository.crew().orError()
            val vehicleList = repository.vehicles().orError()
            setState {
                copy(
                    crew = crewList ?: crew,
                    vehicles = vehicleList ?: vehicles,
                    // A record open in a dialog reads the fresh row, as the
                    // web splices `driver:updated` into every open modal.
                    driverDetails = driverDetails?.let { editor ->
                        crewList?.firstOrNull { it.userId == editor.userId }?.let { user ->
                            editor.copy(
                                vehicleId = if (editor.self) user.vehicleId else editor.vehicleId,
                                licenceFront = editor.licenceFront ?: user.licencePictures.firstOrNull {
                                    it.caption.equals("front", ignoreCase = true)
                                },
                                licenceBack = editor.licenceBack ?: user.licencePictures.firstOrNull {
                                    it.caption.equals("back", ignoreCase = true)
                                },
                            )
                        } ?: editor
                    },
                )
            }
        }
        loadLicenceRequests()
    }

    /**
     * The licence-change queue.
     *
     * Only a coordinator can answer one, so only a coordinator is asked for
     * the list. A failure costs the queue and nothing else — not even a
     * banner: the dev server routes `driver/change-requests` as
     * `driver/:id` and answers 500 ("Cast to ObjectId failed for value
     * 'change-requests'"), and the web never calls it (its tile is
     * commented out), so a loud failure here would fire on every crew
     * reload for a queue nobody can see anyway.
     */
    private fun loadLicenceRequests() {
        if (!currentState.viewer.isCoordinator) return
        launch {
            (repository.licenceRequests() as? ZillitResult.Success)?.data?.let { rows ->
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
            onSegmentViewed(TransportUiState.tripStatusBadge(s.tripStatus))
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
                repository.myPermanentTrips(asDriver = s.isDriver && s.permanentAsDriver, s.viewer.userId)
            }.orError()
            setState { copy(loading = false, permanent = rows ?: permanent) }
            // The web's `buildReadPayload`: a driver reads the tab they are on, everyone else the whole unit.
            onSegmentViewed(
                when {
                    !s.isDriver || s.viewer.isCoordinator -> TransportUiState.PERMANENT_BADGE
                    s.permanentAsDriver -> TransportUiState.PERMANENT_AS_DRIVER_BADGE
                    else -> TransportUiState.PERMANENT_AS_PASSENGER_BADGE
                },
            )
        }
    }

    private fun loadMine() {
        val s = state.value
        setState { copy(loading = true) }
        launch {
            val rows = repository.trips(s.myTab, asDriver = true, s.viewer.userId, coordinator = false).orError()
            setState { copy(loading = false, myTrips = rows ?: myTrips) }
            onSegmentViewed(TransportUiState.DRIVER_ASSIGNMENT_BADGE)
        }
    }

    // Requests ---------------------------------------------------------------

    private fun openPassenger(forOpenTrip: Boolean, edit: TripPassenger?, selfAssign: Boolean) {
        val s = state.value
        val existing = if (forOpenTrip) s.openTrip?.passengers.orEmpty() else s.raise?.passengers.orEmpty()
        val date = if (forOpenTrip) {
            TransportClock.ymd(s.openTrip?.trip?.firstPickupMs ?: 0L)
        } else {
            s.raise?.pickupYmd.orEmpty()
        }
        if (!forOpenTrip && date.isBlank()) {
            setState { copy(error = str(S.desktop_transport_pick_pickup_date_first)) }
            return
        }
        val editor = when {
            edit != null -> PassengerEditor.from(edit, editing = true, forOpenTrip = forOpenTrip)
            // The next passenger starts from the last one's time and places —
            // most of a party shares a car from the same hotel.
            existing.isNotEmpty() && !forOpenTrip -> PassengerEditor.from(existing.last(), editing = false,
                forOpenTrip = false)
            else -> PassengerEditor(forOpenTrip = forOpenTrip)
        }.copy(dateYmd = date.ifBlank { TransportClock.today(nowMillis()) })
        val me = s.viewer.userId
        setState {
            copy(passengerDialog = if (selfAssign) editor.copy(userId = me, selfAssign = true) else editor)
        }
    }

    /** What stops a passenger being added, in the order the web checks. */
    private fun passengerProblem(passenger: TripPassenger?): String? = when {
        passenger == null -> str(S.desktop_transport_pickup_needs_date_time)
        passenger.pickup.address.isBlank() -> str(S.desktop_transport_pick_pickup_location)
        passenger.dropOff.address.isBlank() -> str(S.desktop_transport_pick_dropoff_location)
        passenger.pickupMs < nowMillis() -> str(S.desktop_transport_pickup_time_in_past)
        // The server refuses a place without coordinates (406, "passengers
        // required"); the web always has them because its picker is a map.
        !passenger.pickup.hasCoordinates || !passenger.dropOff.hasCoordinates ->
            str(S.desktop_transport_places_need_coordinates)
        else -> null
    }

    private fun submitPassenger() {
        val s = state.value
        val editor = s.passengerDialog ?: return
        val user = s.crew.firstOrNull { it.userId == editor.userId } ?: run {
            setState { copy(error = str(S.desktop_transport_pick_passenger)) }
            return
        }
        val passenger = editor.toPassenger(user)
        val problem = passengerProblem(passenger)
        if (passenger == null || problem != null) {
            setState { copy(error = problem ?: str(S.desktop_transport_cannot_add_passenger)) }
            return
        }
        val replaced = editor.editingUserId ?: passenger.userId
        setState {
            if (editor.forOpenTrip) {
                copy(
                    passengerDialog = null,
                    openTrip = openTrip?.let { open ->
                        open.copy(
                            passengers = open.passengers.filterNot { it.userId == replaced } + passenger,
                            ccUsers = open.ccUsers - passenger.userId,
                        )
                    },
                )
            } else {
                copy(
                    passengerDialog = null,
                    raise = raise?.copy(
                        passengers = raise.passengers.filterNot { it.userId == replaced } + passenger,
                        ccUsers = raise.ccUsers - passenger.userId,
                    ),
                )
            }
        }
    }

    private fun raiseSelfAssign(on: Boolean) {
        val me = state.value.viewer.userId
        if (on) {
            openPassenger(forOpenTrip = false, edit = null, selfAssign = true)
        } else {
            setState { copy(raise = raise?.copy(passengers = raise.passengers.filterNot { it.userId == me })) }
        }
    }

    private fun tripSelfAssign(on: Boolean) {
        val me = state.value.viewer.userId
        if (on) {
            openPassenger(forOpenTrip = true, edit = null, selfAssign = true)
        } else {
            setState {
                copy(openTrip = openTrip?.let { it.copy(passengers = it.passengers.filterNot { p -> p.userId == me }) })
            }
        }
    }

    /** The web's `handlePickUpRequest`, in the same order it reports problems. */
    private fun raiseProblem(editor: RaiseEditor, coordinator: Boolean, vehicles: List<Vehicle>): String? {
        val seats = vehicles.firstOrNull { it.id == editor.vehicleId }?.seats ?: Int.MAX_VALUE
        return when {
            editor.passengers.isEmpty() -> str(S.desktop_transport_add_at_least_one_passenger)
            coordinator && editor.vehicleId == null -> str(S.desktop_transport_assign_vehicle_required)
            coordinator && editor.driverId == null -> str(S.desktop_transport_assign_driver_required)
            coordinator && seats < editor.passengers.size -> str(S.desktop_transport_too_few_seats)
            editor.passengers.any { it.pickupMs <= nowMillis() } -> str(S.desktop_transport_a_pickup_time_in_past)
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
            when (val result = repository.raiseTrip(editor.toDraft(s.viewer.userId), coordinator)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, raise = editor.copy(saving = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            busy = false,
                            raise = null,
                            section = TransportSection.Requests,
                            tripStatus = if (coordinator) TripStatus.Assigned else TripStatus.Pending,
                        )
                    }
                    sendEffect(TransportEffect.Notice(str(S.desktop_transport_request_raised)))
                    loadTrips()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    /** A reject or cancel asks first; everything else goes straight through. */
    private fun tripAct(action: TripAction) {
        val open = state.value.openTrip ?: return
        val needsConfirm = action == TripAction.Reject || action == TripAction.Cancel
        if (needsConfirm && open.confirm != action) {
            setState { copy(openTrip = open.copy(confirm = action)) }
            return
        }
        actOnTrip(action)
    }

    /** The web's `handleUpdateTripRequest` guards, in its order. */
    private fun tripProblem(open: TripView, action: TripAction): String? {
        val rejecting = action == TripAction.Reject || action == TripAction.Cancel
        return when {
            open.passengers.isEmpty() && !rejecting -> str(S.desktop_transport_add_at_least_one_passenger)
            !rejecting && open.driverId != null && open.passengers.any { it.userId == open.driverId } ->
                str(S.desktop_transport_driver_passenger_same)
            action == TripAction.Approve && open.vehicleId == null -> str(S.desktop_transport_assign_vehicle_required)
            action == TripAction.Approve && open.driverId == null -> str(S.desktop_transport_assign_driver_required)
            else -> null
        }
    }

    private fun actOnTrip(action: TripAction) {
        val s = state.value
        val open = s.openTrip ?: return
        tripProblem(open, action)?.let { message ->
            setState { copy(error = message, openTrip = open.copy(confirm = null)) }
            return
        }
        setState { copy(openTrip = open.copy(busy = true, confirm = null)) }
        launch {
            val update = TripUpdate(
                tripId = open.trip.id,
                action = action,
                passengers = open.passengers,
                ccUsers = open.ccUsers,
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
                    if (action == TripAction.Approve || action == TripAction.Reject) {
                        onSegmentViewed(TransportUiState.tripStatusBadge(TripStatus.Pending))
                    }
                    loadTrips()
                    if (s.section == TransportSection.MyAssignments) loadMine()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun TripAction.notice() = when (this) {
        TripAction.Approve -> str(S.desktop_transport_request_approved)
        TripAction.Reject -> str(S.desktop_transport_request_rejected)
        TripAction.Start -> str(S.desktop_transport_trip_started)
        TripAction.End -> str(S.desktop_transport_trip_completed)
        TripAction.Cancel -> str(S.desktop_transport_trip_cancelled)
        TripAction.Update -> str(S.desktop_transport_request_updated)
    }

    private fun sendReminder() {
        val open = state.value.openTrip ?: return
        run(str(S.docusign_resend_success)) { repository.sendReminder(open.trip.id) }
    }

    /**
     * Track — the web draws the driver on a Google map; this client has no
     * map widget, so the driver's last reported position opens in the
     * browser, which is where the web's passenger addresses go too.
     */
    private fun trackTrip() {
        val open = state.value.openTrip ?: return
        val url = open.trip.driverLocation?.mapsUrl
            ?: state.value.user(open.driverId)?.lastLocation?.mapsUrl
        if (url == null) {
            setState { copy(error = str(S.desktop_transport_driver_no_location)) }
        } else {
            sendEffect(TransportEffect.OpenLink(url))
        }
    }

    // Pickers ----------------------------------------------------------------

    private fun submitPeoplePicker() {
        val picker = state.value.peoplePicker ?: return
        if (picker.chosen.isEmpty()) {
            setState { copy(error = str(S.av_validation_pick_one)) }
            return
        }
        val ids = picker.chosen.toList()
        setState {
            when (picker.purpose) {
                PickPurpose.RaiseCc -> copy(raise = raise?.copy(ccUsers = (raise.ccUsers + ids).distinct()))
                PickPurpose.TripCc -> copy(openTrip = openTrip?.let {
                    it.copy(ccUsers = (it.ccUsers + ids).distinct())
                })
                PickPurpose.PermanentCc -> copy(permanentEditor = permanentEditor?.let {
                    it.copy(ccUsers = (it.ccUsers + ids).distinct())
                })
                PickPurpose.PermanentPassengers -> copy(permanentEditor = permanentEditor?.let { editor ->
                    val added = ids.mapNotNull { id -> user(id)?.let { PermanentPassenger(it.userId, it.fullName) } }
                    editor.copy(
                        passengers = (editor.passengers + added).distinctBy { it.userId },
                        ccUsers = editor.ccUsers - ids.toSet(),
                    )
                })
            }.copy(peoplePicker = null)
        }
    }

    /**
     * A picked vehicle brings its driver along when the form has none yet —
     * the web's `useEffect` on `assignedVehicle` in every form. A trip that
     * already names a driver keeps them; so does an allocation being edited.
     */
    private fun pickVehicle(vehicleId: String) {
        val s = state.value
        val target = s.vehiclePicker ?: return
        val vehicle = s.vehicle(vehicleId) ?: return
        val riding = vehicle.driverId?.let { id -> s.drivers.firstOrNull { it.userId == id } }?.userId
        setState { withVehicle(target, vehicleId, riding).copy(vehiclePicker = null) }
    }

    private fun TransportUiState.withVehicle(target: AssignTarget, vehicleId: String, riding: String?) =
        when (target) {
            AssignTarget.Raise -> copy(raise = raise?.let {
                it.copy(vehicleId = vehicleId, driverId = riding ?: it.driverId)
            })
            AssignTarget.Trip -> copy(openTrip = openTrip?.let {
                it.copy(vehicleId = vehicleId, driverId = it.driverId.orRiding(riding, keep = it.trip.driverId != null))
            })
            AssignTarget.Permanent -> copy(permanentEditor = permanentEditor?.let {
                val keep = !it.isNew && it.driverId != null
                it.copy(vehicleId = vehicleId, driverId = it.driverId.orRiding(riding, keep))
            })
            AssignTarget.DriverDetails -> copy(driverDetails = driverDetails?.copy(vehicleId = vehicleId))
            AssignTarget.VehicleDetails -> this
        }

    /** The vehicle's own driver takes an empty seat; a driver already named keeps it. */
    private fun String?.orRiding(riding: String?, keep: Boolean): String? = if (keep) this else riding ?: this

    /**
     * Changing the driver of a personal car takes it away from its owner —
     * the web confirms first (`private_vehicle_driver_change_message`).
     */
    private fun openDriverPicker(target: AssignTarget) {
        val s = state.value
        val (vehicleId, driverId) = when (target) {
            AssignTarget.Raise -> s.raise?.vehicleId to s.raise?.driverId
            AssignTarget.Trip -> s.openTrip?.vehicleId to s.openTrip?.driverId
            AssignTarget.Permanent -> s.permanentEditor?.vehicleId to s.permanentEditor?.driverId
            else -> null to null
        }
        val vehicle = s.vehicle(vehicleId)
        val ownersCar = vehicle?.isPrivate == true && driverId != null
        if (ownersCar && vehicle?.driverId == driverId) {
            setState { copy(confirm = TransportConfirm.ChangePrivateDriver(target)) }
        } else {
            setState { copy(driverPicker = target, driverPickerQuery = "") }
        }
    }

    /** A picked driver leaves the passenger and CC lists — one person, one seat. */
    private fun pickDriver(userId: String) {
        val target = state.value.driverPicker ?: return
        setState {
            when (target) {
                AssignTarget.Raise -> copy(raise = raise?.copy(
                    driverId = userId,
                    passengers = raise.passengers.filterNot { it.userId == userId },
                    ccUsers = raise.ccUsers - userId,
                ))
                AssignTarget.Trip -> copy(openTrip = openTrip?.let {
                    it.copy(driverId = userId, passengers = it.passengers.filterNot { p -> p.userId == userId },
                        ccUsers = it.ccUsers - userId)
                })
                AssignTarget.Permanent -> copy(permanentEditor = permanentEditor?.let {
                    it.copy(driverId = userId, passengers = it.passengers.filterNot { p -> p.userId == userId },
                        ccUsers = it.ccUsers - userId)
                })
                AssignTarget.VehicleDetails -> copy(vehicleDetails = vehicleDetails?.copy(pendingDriverId = userId))
                AssignTarget.DriverDetails -> this
            }.copy(driverPicker = null, driverPickerQuery = "")
        }
    }

    // Vehicles ---------------------------------------------------------------

    /** The web's `addContactDetails`: a new vehicle starts with the fleet's usual owner. */
    private fun newVehicle() {
        val s = state.value
        val owner = s.vehicles.firstOrNull { it.isLive }
        setState {
            copy(
                vehicleEditor = VehicleEditor(
                    type = vehicleTypes.firstOrNull().orEmpty(),
                    ownerName = owner?.ownerName.orEmpty(),
                    ownerContact = owner?.ownerContact.orEmpty(),
                    ownerAddress = owner?.ownerAddress.orEmpty(),
                    countryCode = owner?.countryCode.orEmpty(),
                ),
            )
        }
    }

    private fun addVehicleImages() {
        val editor = state.value.vehicleEditor ?: return
        if (editor.attachments.size >= VehicleDraft.IMAGES_MAX) {
            setState { copy(error = str(S.desktop_transport_at_most_images, VehicleDraft.IMAGES_MAX)) }
            return
        }
        setState { copy(vehicleEditor = editor.copy(uploading = true)) }
        launch {
            val picked = pickMedia(TransportMediaKind.Image, multiple = true)
            setState {
                copy(
                    vehicleEditor = vehicleEditor?.let { current ->
                        val room = VehicleDraft.IMAGES_MAX - current.attachments.size
                        current.copy(
                            uploading = false,
                            attachments = current.attachments + picked.take(room).map { it.stored },
                        )
                    },
                    error = if (picked.size > VehicleDraft.IMAGES_MAX - editor.attachments.size) {
                        str(S.desktop_transport_at_most_images, VehicleDraft.IMAGES_MAX)
                    } else {
                        error
                    },
                )
            }
        }
    }

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
            val tempDriver = editor.forTempDriverId
            val result = when {
                tempDriver != null -> repository.createTempDriverVehicle(tempDriver, draft)
                id == null -> repository.addVehicle(draft)
                else -> repository.updateVehicle(id, draft)
            }
            when (result) {
                is ZillitResult.Failure -> setState { copy(busy = false, vehicleEditor = editor.copy(saving = false),
                    error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, vehicleEditor = null) }
                    sendEffect(
                        TransportEffect.Notice(
                            when {
                                tempDriver != null -> str(S.desktop_transport_temp_driver_added_with_vehicle)
                                id == null -> str(S.desktop_transport_vehicle_added)
                                else -> str(S.desktop_transport_vehicle_updated)
                            },
                        ),
                    )
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun deleteVehicles(ids: List<String>) {
        setState { copy(vehicleSelection = null, vehicleDetails = null) }
        run(str(S.desktop_transport_vehicle_removed)) { repository.deleteVehicles(ids) }
    }

    private fun submitVehicleDriver() {
        val details = state.value.vehicleDetails ?: return
        val driverId = details.pendingDriverId ?: return
        setState { copy(vehicleDetails = details.copy(busy = true)) }
        launch {
            when (val result = repository.updateDriver(driverId, vehicleId = details.vehicleId)) {
                is ZillitResult.Failure -> setState {
                    copy(vehicleDetails = details.copy(busy = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(vehicleDetails = details.copy(busy = false, pendingDriverId = null)) }
                    sendEffect(TransportEffect.Notice(str(S.desktop_transport_driver_assigned)))
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    // Permanent --------------------------------------------------------------

    private fun permanentSelfAssign(on: Boolean) {
        val me = state.value.me ?: return
        setState {
            copy(
                permanentEditor = permanentEditor?.let { editor ->
                    editor.copy(
                        passengers = if (on) {
                            (editor.passengers + PermanentPassenger(me.userId, me.fullName)).distinctBy { it.userId }
                        } else {
                            editor.passengers.filterNot { it.userId == me.userId }
                        },
                    )
                },
            )
        }
    }

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
                    sendEffect(
                        TransportEffect.Notice(
                            str(if (asDraft) S.ah_draft_saved_msg else S.desktop_transport_allocation_saved),
                        ),
                    )
                    loadPermanent()
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun selfManage(on: Boolean) {
        val editor = state.value.permanentEditor ?: return
        val id = editor.id ?: return
        setState { copy(permanentEditor = editor.copy(selfManage = on), busy = true) }
        launch {
            when (val result = repository.setSelfManage(id, on)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, permanentEditor = permanentEditor?.copy(selfManage = !on),
                        error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(
                        TransportEffect.Notice(
                            str(
                                if (on) S.desktop_transport_you_manage_driver
                                else S.desktop_transport_managed_by_project,
                            ),
                        ),
                    )
                    loadPermanent()
                }
            }
        }
    }

    // Drivers ----------------------------------------------------------------

    private fun openDriver(user: TransportUser, self: Boolean) {
        val coordinator = state.value.viewer.isCoordinator
        setState { copy(driverDetails = DriverDetailsEditor.from(user, self = self, coordinator = coordinator)) }
    }

    private fun uploadLicence(back: Boolean) {
        val editor = state.value.driverDetails ?: return
        setState { copy(driverDetails = editor.copy(uploading = true)) }
        launch {
            val picked = pickMedia(TransportMediaKind.Image, multiple = false).firstOrNull()
                ?.let { it.stored.copy(caption = if (back) "Back" else "Front") }
            setState {
                copy(
                    driverDetails = driverDetails?.let { current ->
                        when {
                            picked == null -> current.copy(uploading = false)
                            back -> current.copy(uploading = false, licenceBack = picked)
                            else -> current.copy(uploading = false, licenceFront = picked)
                        }
                    },
                )
            }
        }
    }

    private fun uploadDocuments() {
        val editor = state.value.driverDetails ?: return
        val room = DriverDetailsUpdate.DOCUMENTS_MAX - editor.documents.size
        if (room <= 0) {
            setState { copy(error = str(S.desktop_transport_at_most_documents, DriverDetailsUpdate.DOCUMENTS_MAX)) }
            return
        }
        setState { copy(driverDetails = editor.copy(uploading = true)) }
        launch {
            val picked = pickMedia(TransportMediaKind.ImageOrPdf, multiple = true)
            setState {
                copy(
                    driverDetails = driverDetails?.let { current ->
                        current.copy(
                            uploading = false,
                            documents = current.documents + picked.take(room).map { it.stored },
                        )
                    },
                    error = if (picked.size > room) {
                        str(S.desktop_transport_at_most_documents, DriverDetailsUpdate.DOCUMENTS_MAX)
                    } else {
                        error
                    },
                )
            }
        }
    }

    private fun saveDriverDetails() {
        val s = state.value
        val editor = s.driverDetails ?: return
        val update = editor.toUpdate()
        val problem = update.problem() ?: if (!editor.self && s.viewer.isCoordinator && editor.vehicleId == null) {
            // The coordinator's save is the vehicle assignment; the web refuses one without it.
            str(S.desktop_transport_assign_vehicle_required)
        } else {
            null
        }
        if (problem != null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(driverDetails = editor.copy(saving = true), busy = true) }
        launch {
            when (val result = repository.updateDriverDetails(update)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, driverDetails = editor.copy(saving = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        copy(busy = false, driverDetails = if (editor.self) editor.copy(saving = false) else null)
                    }
                    sendEffect(TransportEffect.Notice(str(S.desktop_transport_details_updated)))
                    if (editor.self && (editor.licencePictures.isNotEmpty() || editor.documents.isNotEmpty())) {
                        onSegmentViewed(TransportUiState.DRIVER_REMINDER_BADGE)
                    }
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    private fun sendDocumentReminder() {
        val reminder = state.value.reminder ?: return
        if (reminder.message.isBlank()) {
            setState { copy(error = str(S.desktop_transport_enter_document_names)) }
            return
        }
        setState { copy(reminder = reminder.copy(sending = true)) }
        launch {
            when (val result = repository.pendingDocumentReminder(reminder.userId, "document", reminder.message)) {
                is ZillitResult.Failure -> setState {
                    copy(reminder = reminder.copy(sending = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(reminder = null) }
                    sendEffect(TransportEffect.Notice(str(S.desktop_transport_document_reminder_sent)))
                }
            }
        }
    }

    /**
     * The web's `handleTempDriverChange`: switching someone ON asks whether
     * they bring their own car; OFF unmakes them and deletes that car.
     */
    private fun toggleTempDriver(user: TransportUser, on: Boolean) {
        if (on) {
            setState { copy(confirm = TransportConfirm.TempDriverVehicle(user)) }
        } else {
            unassignTempDriver(user)
        }
    }

    private fun makeTempDriver(user: TransportUser, withVehicle: Boolean) {
        if (withVehicle) {
            setState {
                copy(
                    vehicleEditor = VehicleEditor(
                        type = vehicleTypes.firstOrNull().orEmpty(),
                        ownerName = user.fullName,
                        ownerContact = user.phone,
                        ownerAddress = user.address,
                        countryCode = user.countryCode,
                        forTempDriverId = user.userId,
                    ),
                )
            }
        } else {
            run(str(S.desktop_transport_temp_driver_added)) { repository.updateDriver(user.userId,
                isTempDriver = true) }
        }
    }

    private fun unassignTempDriver(user: TransportUser) {
        setState { copy(busy = true, driverDetails = null) }
        launch {
            when (val result = repository.updateDriver(user.userId, isTempDriver = false)) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
                is ZillitResult.Success -> {
                    // Their personal car leaves with them; the fleet's cars stay.
                    val private = user.vehicleId?.let { id -> state.value.vehicle(id) }?.takeIf { it.isPrivate }
                    if (private != null) repository.deleteVehicles(listOf(private.id))
                    setState { copy(busy = false) }
                    sendEffect(TransportEffect.Notice(str(S.desktop_transport_temp_driver_removed)))
                    reloadCrewAndVehicles()
                }
            }
        }
    }

    // Confirmations ----------------------------------------------------------

    private fun acceptConfirm() {
        val confirm = state.value.confirm ?: return
        setState { copy(confirm = null) }
        when (confirm) {
            is TransportConfirm.DeleteVehicles -> deleteVehicles(confirm.ids)
            is TransportConfirm.UnassignPermanent -> run(str(S.desktop_transport_allocation_ended)) {
                repository.unassignPermanent(confirm.trip)
            }
            is TransportConfirm.DeletePermanent ->
                run(str(S.desktop_email_draft_deleted)) { repository.deletePermanent(confirm.trip.id) }
            is TransportConfirm.ChangePrivateDriver -> setState { copy(driverPicker = confirm.target) }
            is TransportConfirm.TempDriverVehicle -> makeTempDriver(confirm.user, withVehicle = true)
            is TransportConfirm.UnassignTempDriver -> unassignTempDriver(confirm.user)
        }
    }

    /** The "No" of the temp-driver question is still a yes to the driver — just without a car. */
    private fun declineConfirm() {
        val confirm = state.value.confirm ?: return
        setState { copy(confirm = null) }
        if (confirm is TransportConfirm.TempDriverVehicle) makeTempDriver(confirm.user, withVehicle = false)
    }

    // Shared -----------------------------------------------------------------

    private suspend fun pickMedia(kind: TransportMediaKind, multiple: Boolean): List<PickedMedia> =
        when (val result = host.pickAndStore(kind, multiple)) {
            is ZillitResult.Failure -> {
                setState { copy(error = result.error.localised()) }
                emptyList()
            }
            is ZillitResult.Success -> {
                val picked = result.data
                if (picked.isNotEmpty()) {
                    setState { copy(localMedia = localMedia + picked.associate { it.stored.media to it.bytes }) }
                }
                picked
            }
        }

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

/** The types the web's `getVehicleImage` knows — the fallback when `vehicle-types` answers nothing. */
private val DEFAULT_VEHICLE_TYPES = listOf("Car", "SUV", "Minivan", "Minibus", "Bus", "Vanity Van")

/** A stored file's bytes, from what was picked this session — the UI's first stop before the store. */
fun TransportUiState.localBytes(media: StoredMedia): ByteArray? = localMedia[media.media]
