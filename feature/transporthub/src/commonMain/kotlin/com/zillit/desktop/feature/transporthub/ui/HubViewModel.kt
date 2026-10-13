@file:Suppress("TooManyFunctions", "LargeClass") // One handler per user act; the web spreads them over 24 files.

package com.zillit.desktop.feature.transporthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDetailsPatch
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.HubHost
import com.zillit.desktop.feature.transporthub.domain.HubMediaKind
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRepository
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.HubSyncKind
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentDraft
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.RideDraft
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripDraft
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate

/**
 * The Transportation Hub: trips, ride requests, the fleet, the drivers and the
 * permanent allocations, as one window with a rail.
 *
 * The hub is the tool the web's Transportation tile has opened since April
 * 2026; the older `feature:transportation` screens are the page it replaced
 * and stay reachable while the rest of the hub is ported.
 */
class HubViewModel(
    private val repository: HubRepository,
    private val resolveViewer: () -> HubViewer,
    private val nowMillis: () -> Long,
    /** Marks a bucket's badge rows read — the web's `useReadHubBadges`. */
    private val onSectionViewed: suspend (bucket: HubBadgeBucket) -> Unit = {},
    private val host: HubHost = HubHost.None,
) : ZillitViewModel<HubUiState, HubEvent, HubEffect>(HubUiState()) {

    private var listening = false

    fun start() {
        val viewer = resolveViewer()
        setState {
            copy(
                viewer = viewer,
                // The rail depends on the role, so the landing section does
                // too: a driver has no Trips page to land on.
                section = HubSection.homeFor(viewer.role),
            )
        }
        refreshAll()
        listenOnce()
    }

    /**
     * Re-runs the load a socket frame touches.
     *
     * `conflate()` folds a burst into one reload: the server dual-emits the v2
     * and the legacy name for the same change, so two frames routinely arrive
     * for one edit. Guarded so reopening the window does not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { kind ->
                when (kind) {
                    HubSyncKind.Fleet -> loadVehicles()
                    HubSyncKind.Drivers -> loadDrivers()
                    HubSyncKind.Trips -> loadTrips()
                    HubSyncKind.Rides -> loadRides()
                    HubSyncKind.Permanent -> loadAllocations()
                }
            }
        }
        launch {
            host.badges.collect { counts -> setState { copy(badges = counts) } }
        }
    }

    override fun onEvent(event: HubEvent) {
        when (event) {
            is HubEvent.SelectSection -> selectSection(event.section)
            HubEvent.Refresh -> refreshAll()
            HubEvent.DismissError -> setState { copy(error = null) }
            HubEvent.DismissConfirm -> setState { copy(confirm = null) }
            is HubEvent.Confirmed -> {
                setState { copy(confirm = null) }
                onEvent(event.action)
            }

            else -> onSectionEvent(event)
        }
    }

    /**
     * The rest of the events, split out so the `when` over a sealed interface
     * with ninety members stays inside detekt's complexity budget without
     * anyone having to read a single ninety-branch block.
     */
    private fun onSectionEvent(event: HubEvent) {
        when (event) {
            is HubEvent.FleetEvent -> onFleetEvent(event)
            is HubEvent.DriverEvent -> onDriverEvent(event)
            is HubEvent.TripEvent -> onTripEvent(event)
            is HubEvent.RideEvent -> onRideEvent(event)
            is HubEvent.AllocationEvent -> onAllocationEvent(event)
            else -> Unit
        }
    }

    // Loading ----------------------------------------------------------------

    private fun refreshAll() {
        if (!currentState.viewer.canView) return
        setState { copy(loading = true, error = null) }
        launch {
            coroutineScope {
                // The directories back every section's pickers, so they load
                // whichever section is open.
                val vehicles = async { repository.vehicles(currentState.vehicleFilter) }
                val drivers = async { repository.drivers() }
                val people = async { repository.projectPeople() }
                val types = async { repository.vehicleTypes() }
                // Which designations count as a driver's is the server's to
                // say, and the role depends on it — so the viewer is patched
                // once the answer lands rather than guessed at from rights.
                val designations = async { repository.driverDesignations() }

                vehicles.await().onOk { rows -> setState { copy(vehicles = rows) } }
                drivers.await().onOk { rows ->
                    setState {
                        copy(
                            drivers = rows,
                            // Whether the viewer is a temporary driver is on
                            // their driver record, not on their profile or
                            // their crew row — and it is one of the two doors
                            // to the driver's hub.
                            viewer = viewer.copy(
                                isTempDriver = rows.any {
                                    it.userId == viewer.userId && it.isTempDriver
                                },
                            ),
                        )
                    }
                }
                people.await().onOk { rows -> setState { copy(people = rows) } }
                types.await().onOk { rows -> setState { copy(vehicleTypes = rows) } }
                designations.await().onOk { names ->
                    setState { copy(viewer = viewer.copy(driverDesignations = names)) }
                }
            }
            // The rail depends on the role, and the role can have changed
            // under us: a driver whose designation only just resolved was a
            // passenger a moment ago, and would be left on a section their
            // rail no longer has.
            if (currentState.section !in currentState.rail) {
                setState { copy(section = HubSection.homeFor(role)) }
            }
            loadSection(currentState.section)
            setState { copy(loading = false) }
        }
    }

    private fun selectSection(section: HubSection) {
        if (section == currentState.section) return
        setState { copy(section = section, error = null) }
        if (!section.isPorted) return
        launch {
            loadSection(section)
            // Opening a section clears its unread rows, as the web does on
            // mount. A bucket that owns its whole unit clears the unit; one
            // with actions clears only the actions it owns.
            section.badgeBucket?.let { bucket -> onSectionViewed(bucket) }
        }
    }

    private suspend fun loadSection(section: HubSection) {
        when (section) {
            HubSection.Trips, HubSection.MyTrips -> loadTrips()
            HubSection.RideRequests, HubSection.MyRides -> loadRides()
            HubSection.Fleet -> loadVehicles()
            HubSection.Drivers -> loadDrivers()
            HubSection.Allocations -> loadAllocations()
            else -> Unit
        }
    }

    private suspend fun loadVehicles() {
        repository.vehicles(currentState.vehicleFilter).fold(
            ok = { rows -> setState { copy(vehicles = rows) } },
        )
    }

    private suspend fun loadDrivers() {
        coroutineScope {
            val all = async { repository.drivers() }
            val incomplete = async { repository.incompleteDrivers() }
            all.await().fold(ok = { rows -> setState { copy(drivers = rows) } })
            // The chase-list is allowed to fail on its own: a deployment
            // without the `details` query should cost the card, not the page.
            incomplete.await().onOk { rows -> setState { copy(incompleteDrivers = rows) } }
        }
    }

    private suspend fun loadTrips() {
        repository.trips().fold(ok = { rows -> setState { copy(trips = rows) } })
    }

    private suspend fun loadRides() {
        // A driver or a passenger must send the scope: without it the server
        // answers a coordinator's whole list.
        val onlyMine = currentState.role != HubRole.Coordinator
        repository.rideRequests(onlyMine).fold(ok = { rows -> setState { copy(rides = rows) } })
    }

    private suspend fun loadAllocations() {
        repository.permanentTrips().fold(ok = { rows -> setState { copy(allocations = rows) } })
    }

    /** The pickers on the assign and create forms want only what is free. */
    private suspend fun loadAssignable() {
        coroutineScope {
            val drivers = async { repository.availableDrivers() }
            val vehicles = async { repository.availableVehicles() }
            drivers.await().onOk { rows -> setState { copy(availableDrivers = rows) } }
            vehicles.await().onOk { rows -> setState { copy(availableVehicles = rows) } }
        }
    }

    // Fleet ------------------------------------------------------------------

    private fun onFleetEvent(event: HubEvent.FleetEvent) {
        when (event) {
            is HubEvent.VehicleSearchChanged -> {
                setState { copy(vehicleSearch = event.query, vehicleFilter = vehicleFilter.copy(search = event.query)) }
                launch { loadVehicles() }
            }

            is HubEvent.VehicleFilterChanged -> {
                setState { copy(vehicleFilter = event.filter, vehicleSearch = event.filter.search) }
                launch { loadVehicles() }
            }

            HubEvent.NewVehicle -> setState { copy(vehicleEditor = VehicleEditor()) }
            is HubEvent.EditVehicle -> setState { copy(vehicleEditor = VehicleEditor.from(event.vehicle)) }
            is HubEvent.VehicleEditorChanged -> setState { copy(vehicleEditor = event.editor) }
            HubEvent.CloseVehicleEditor -> setState { copy(vehicleEditor = null) }
            HubEvent.PickVehiclePhotos -> pickVehiclePhotos()
            is HubEvent.RemoveVehiclePhoto -> setState {
                copy(vehicleEditor = vehicleEditor?.copy(photos = vehicleEditor.photos - event.slot))
            }

            HubEvent.SaveVehicle -> saveVehicle()
            is HubEvent.SetVehicleAllocation -> setVehicleAllocation(event.id, event.allocation)
            is HubEvent.DeleteVehicle -> confirmDeleteVehicle(event.vehicle)
            is HubEvent.DeleteVehicleConfirmed -> deleteVehicle(event.id)
        }
    }

    private fun pickVehiclePhotos() {
        val editor = currentState.vehicleEditor ?: return
        val room = VehicleEditor.MAX_PHOTOS - editor.photos.size
        if (room <= 0) {
            notice(str(S.desktop_hub_photo_limit), success = false)
            return
        }
        launch {
            host.pickAndStore(HubMediaKind.Image, multiple = true).onOk { picked ->
                if (picked.isEmpty()) return@onOk
                val slots = picked.take(room).map { PhotoSlot(it.attachment, it.bytes) }
                setState {
                    copy(vehicleEditor = vehicleEditor?.let { it.copy(photos = it.photos + slots) })
                }
                if (picked.size > room) notice(str(S.desktop_hub_photo_limit), success = false)
            }
        }
    }

    private fun saveVehicle() {
        val editor = currentState.vehicleEditor ?: return
        validateVehicle(editor)?.let { problem ->
            notice(problem, success = false)
            return
        }
        val draft = VehicleDraft(
            name = editor.name.trim(),
            licensePlate = editor.licensePlate.trim(),
            vehicleType = editor.vehicleType,
            capacity = editor.capacity.trim().toIntOrNull(),
            fuelType = editor.fuelType.trim(),
            isPrivate = editor.isPrivate,
            ownerName = editor.ownerName.trim(),
            ownerContactNumber = editor.ownerContactNumber.trim(),
            make = editor.make.trim(),
            model = editor.model.trim(),
            year = editor.year.trim().toIntOrNull(),
            color = editor.color.trim(),
            attachments = editor.photos.map { it.attachment },
        )
        setState { copy(vehicleEditor = editor.copy(saving = true)) }
        launch {
            val result = editor.id?.let { repository.updateVehicle(it, draft) }
                ?: repository.addVehicle(draft)
            result.fold(
                ok = {
                    setState { copy(vehicleEditor = null) }
                    notice(str(S.desktop_hub_vehicle_saved))
                    loadVehicles()
                },
                bad = { setState { copy(vehicleEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun validateVehicle(editor: VehicleEditor): String? = when {
        editor.licensePlate.isBlank() -> str(S.desktop_hub_plate_required)
        editor.vehicleType.isBlank() -> str(S.desktop_hub_type_required)
        // Required together, and only when the production does not own the car.
        !editor.isPrivate && (editor.ownerName.isBlank() || editor.ownerContactNumber.isBlank()) ->
            str(S.desktop_hub_owner_required)

        else -> null
    }

    private fun setVehicleAllocation(id: String, allocation: VehicleAllocation) {
        setState { copy(busyRowId = id) }
        launch {
            // No optimistic flip: the server applies its own rules — a car
            // booked to a ride cannot go into maintenance — and may refuse.
            repository.setVehicleAllocation(id, allocation).fold(ok = { loadVehicles() })
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteVehicle(vehicle: com.zillit.desktop.feature.transporthub.domain.Vehicle) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_vehicle_title),
                    body = str(S.desktop_hub_delete_vehicle_body),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteVehicleConfirmed(vehicle.id),
                ),
            )
        }
    }

    private fun deleteVehicle(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deleteVehicles(listOf(id)).fold(
                ok = {
                    notice(str(S.desktop_hub_vehicle_deleted))
                    loadVehicles()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Drivers ----------------------------------------------------------------

    private fun onDriverEvent(event: HubEvent.DriverEvent) {
        when (event) {
            is HubEvent.DriverTabChanged -> setState { copy(driverTab = event.tab) }
            is HubEvent.DriverSearchChanged -> setState { copy(driverSearch = event.query) }
            is HubEvent.ToggleDriverDuty -> toggleDuty(event.driver)
            is HubEvent.DeleteDriver -> confirmDeleteDriver(event.driver)
            is HubEvent.DeleteDriverConfirmed -> deleteDriver(event.id)
            else -> onDriverFormEvent(event)
        }
    }

    /** The create form, and the chase-list's "fill in the rest". */
    private fun onDriverFormEvent(event: HubEvent.DriverEvent) {
        when (event) {
            HubEvent.NewDriver -> setState { copy(driverEditor = DriverEditor()) }
            is HubEvent.DriverEditorChanged -> setState { copy(driverEditor = event.editor) }
            is HubEvent.PickDriverUser -> pickDriverUser(event.userId)
            is HubEvent.PickLicencePhoto -> pickLicencePhoto(event.isFront, forDetails = false)
            HubEvent.SaveDriver -> saveDriver(pendingDetails = false)
            HubEvent.SaveDriverIgnoringDetails -> saveDriver(pendingDetails = true)
            HubEvent.CloseDriverEditor -> setState { copy(driverEditor = null) }
            is HubEvent.CompleteDriverDetails -> setState {
                copy(
                    driverDetailsEditor = DriverDetailsEditor(
                        driverId = event.driver.id,
                        driverName = event.driver.displayName,
                        phone = event.driver.phone,
                        licenseNumber = event.driver.licenseNumber,
                    ),
                )
            }

            is HubEvent.DriverDetailsEditorChanged -> setState { copy(driverDetailsEditor = event.editor) }
            is HubEvent.PickDetailsLicencePhoto -> pickLicencePhoto(event.isFront, forDetails = true)
            HubEvent.SaveDriverDetails -> saveDriverDetails()
            HubEvent.CloseDriverDetailsEditor -> setState { copy(driverDetailsEditor = null) }
            else -> Unit
        }
    }

    /**
     * Fills the form from the picked project member, and decides the
     * temporary-driver flag for the coordinator.
     *
     * A member who holds no driver designation can only be added through the
     * temporary-driver path, so the flag is set **and locked**. A real driver
     * leaves it unlocked, so a coordinator can still mark one temporary.
     */
    private fun pickDriverUser(userId: String) {
        val person = currentState.people.firstOrNull { it.userId == userId } ?: return
        val isDriverDesignation = currentState.viewer.driverDesignations
            .any { it.equals(person.designation, ignoreCase = true) }
        setState {
            copy(
                driverEditor = (driverEditor ?: DriverEditor()).copy(
                    userId = userId,
                    fullName = person.name,
                    phone = person.phone.ifBlank { driverEditor?.phone.orEmpty() },
                    designation = person.designation,
                    isTempDriver = !isDriverDesignation,
                    tempDriverLocked = !isDriverDesignation,
                ),
            )
        }
    }

    private fun pickLicencePhoto(isFront: Boolean, forDetails: Boolean) {
        launch {
            host.pickAndStore(HubMediaKind.ImageOrPdf, multiple = false).onOk { picked ->
                val slot = picked.firstOrNull()?.let { PhotoSlot(it.attachment, it.bytes) }
                    ?: return@onOk
                setState {
                    if (forDetails) {
                        copy(
                            driverDetailsEditor = driverDetailsEditor?.let {
                                if (isFront) it.copy(licenseFront = slot) else it.copy(licenseBack = slot)
                            },
                        )
                    } else {
                        copy(
                            driverEditor = driverEditor?.let {
                                if (isFront) it.copy(licenseFront = slot) else it.copy(licenseBack = slot)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun saveDriver(pendingDetails: Boolean) {
        val editor = currentState.driverEditor ?: return
        if (editor.userId.isBlank() || editor.fullName.isBlank()) {
            notice(str(S.desktop_hub_driver_user_required), success = false)
            return
        }
        // The licence gate applies to a full save only: "Ignore & create"
        // exists precisely to skip it and chase the details later.
        if (!pendingDetails && editor.licenseNumber.isBlank()) {
            notice(str(S.desktop_hub_licence_required), success = false)
            return
        }
        setState { copy(driverEditor = editor.copy(saving = true)) }
        launch {
            repository.addDriver(editor.toDraft(pendingDetails)).fold(
                ok = {
                    setState {
                        copy(
                            driverEditor = null,
                            // Land on the chase-list so the half-saved record
                            // is visible rather than lost among the others.
                            driverTab = if (pendingDetails) DriverTab.Incomplete else driverTab,
                        )
                    }
                    notice(
                        if (pendingDetails) {
                            str(S.desktop_hub_driver_pending_saved)
                        } else {
                            str(S.desktop_hub_driver_saved)
                        },
                    )
                    loadDrivers()
                },
                bad = { setState { copy(driverEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun saveDriverDetails() {
        val editor = currentState.driverDetailsEditor ?: return
        setState { copy(driverDetailsEditor = editor.copy(saving = true)) }
        launch {
            repository.updateDriverDetails(
                editor.driverId,
                DriverDetailsPatch(
                    phone = editor.phone.trim().takeIf { it.isNotBlank() },
                    licenseNumber = editor.licenseNumber.trim().takeIf { it.isNotBlank() },
                    licenseFrontUrl = editor.licenseFront?.attachment?.media,
                    licenseBackUrl = editor.licenseBack?.attachment?.media,
                ),
            ).fold(
                ok = {
                    setState { copy(driverDetailsEditor = null) }
                    notice(str(S.desktop_hub_driver_details_saved))
                    loadDrivers()
                },
                bad = { setState { copy(driverDetailsEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun toggleDuty(driver: Driver) {
        setState { copy(busyRowId = driver.id) }
        launch {
            repository.setDriverDuty(driver.id, !driver.isOnDuty).fold(ok = { loadDrivers() })
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteDriver(driver: Driver) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_driver_title),
                    body = str(S.desktop_hub_delete_driver_body),
                    confirmLabel = str(S.remove),
                    action = HubEvent.DeleteDriverConfirmed(driver.id),
                ),
            )
        }
    }

    private fun deleteDriver(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deleteDrivers(listOf(id)).fold(
                ok = {
                    notice(str(S.desktop_hub_driver_deleted))
                    loadDrivers()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Trips ------------------------------------------------------------------

    private fun onTripEvent(event: HubEvent.TripEvent) {
        when (event) {
            is HubEvent.TripTabChanged -> setState { copy(tripTab = event.tab) }
            is HubEvent.TripSearchChanged -> setState { copy(tripSearch = event.query) }
            is HubEvent.TripDateChanged -> setState { copy(tripDateMs = event.dayStartMs) }
            HubEvent.NewTrip -> {
                setState { copy(tripEditor = TripEditor(dateMs = nowMillis())) }
                launch { loadAssignable() }
            }

            is HubEvent.TripEditorChanged -> setState { copy(tripEditor = event.editor) }
            HubEvent.SaveTrip -> saveTrip()
            HubEvent.CloseTripEditor -> setState { copy(tripEditor = null) }
            is HubEvent.OpenTrip -> openTrip(event.id)
            HubEvent.CloseTrip -> setState { copy(openTrip = null, openTripEvents = emptyList()) }
            is HubEvent.SetTripStatus -> setTripStatus(event.id, event.status)
            is HubEvent.RemindTrip -> remindTrip(event.id)
            is HubEvent.DeleteTrip -> confirmDeleteTrip(event.trip)
            is HubEvent.DeleteTripConfirmed -> deleteTripRow(event.id, event.kind)
        }
    }

    private fun saveTrip() {
        val editor = currentState.tripEditor ?: return
        validateTrip(editor)?.let { problem ->
            notice(problem, success = false)
            return
        }
        setState { copy(tripEditor = editor.copy(saving = true)) }
        launch {
            val draft = TripDraft(
                title = editor.title,
                tripType = editor.tripType,
                priority = editor.priority,
                passengers = editor.passengers,
                notes = editor.notes,
                driverId = editor.driverId,
                vehicleId = editor.vehicleId,
            )
            repository.addTrip(draft).fold(
                ok = { newId ->
                    // `POST /trip` ignores the driver and answers `pending`,
                    // which hides the trip from the driver's own feed — so the
                    // assignment goes out as its own call. Best effort: the
                    // trip exists either way and can be assigned by hand.
                    val driverId = editor.driverId
                    if (!newId.isNullOrBlank() && !driverId.isNullOrBlank()) {
                        val assigned = repository.assignTrip(newId, driverId, editor.vehicleId)
                        if (assigned is ZillitResult.Failure) {
                            notice(str(S.desktop_hub_trip_assign_failed), success = false)
                        }
                    }
                    setState { copy(tripEditor = null) }
                    notice(str(S.desktop_hub_trip_created))
                    loadTrips()
                },
                bad = { setState { copy(tripEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun validateTrip(editor: TripEditor): String? = when {
        editor.passengers.isEmpty() -> str(S.desktop_hub_passenger_required)
        editor.passengers.any { it.name.isBlank() } -> str(S.desktop_hub_passenger_name_required)
        editor.passengers.any { it.pickup.address.isBlank() || it.dropoff.address.isBlank() } ->
            str(S.desktop_hub_ends_required)

        else -> null
    }

    private fun openTrip(id: String) {
        launch {
            repository.trip(id).fold(
                ok = { trip -> setState { copy(openTrip = trip) } },
            )
            // The lifecycle log is allowed to fail on its own: a shuttle run
            // has none and the route answers 400 for its ids.
            repository.tripEvents(id).onOk { rows -> setState { copy(openTripEvents = rows) } }
        }
    }

    private fun setTripStatus(id: String, status: TripStatus) {
        setState { copy(busyRowId = id) }
        launch {
            repository.setTripStatus(id, status).fold(
                ok = {
                    notice(str(S.desktop_hub_status_updated))
                    loadTrips()
                    if (currentState.openTrip?.id == id) openTrip(id)
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun remindTrip(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.sendTripReminder(id).fold(ok = { notice(str(S.desktop_hub_reminder_sent)) })
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteTrip(trip: Trip) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = if (trip.kind == TripResourceKind.RideRequest) {
                        str(S.desktop_csync_cleaning_cancel_confirm)
                    } else {
                        str(S.desktop_hub_delete_trip_title)
                    },
                    body = str(S.desktop_hub_delete_trip_body),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteTripConfirmed(trip.id, trip.kind),
                ),
            )
        }
    }

    /**
     * Removes a row from the Trips list, by whichever route owns it.
     *
     * The list mixes real trips and ride requests, and they are deleted
     * differently: a ride request is soft-cancelled through
     * `DELETE /ride-request/:id`, a trip through `DELETE /trip/:id`. A row
     * whose kind the server did not state is tried as a trip first and falls
     * back on `trip_not_found` — which is exactly what a ride request answers.
     */
    private fun deleteTripRow(id: String, kind: TripResourceKind) {
        setState { copy(busyRowId = id) }
        launch {
            val reason = str(S.desktop_hub_reason_default_coordinator)
            val result = if (kind == TripResourceKind.RideRequest) {
                repository.cancelRide(id, reason)
            } else {
                val first = repository.deleteTrip(id)
                if (first is ZillitResult.Failure && first.looksLikeMissingTrip()) {
                    repository.cancelRide(id, reason)
                } else {
                    first
                }
            }
            result.fold(
                ok = {
                    notice(
                        if (kind == TripResourceKind.RideRequest) {
                            str(S.desktop_hub_ride_cancelled)
                        } else {
                            str(S.desktop_hub_trip_deleted)
                        },
                    )
                    setState { copy(openTrip = null, trips = trips.filterNot { it.id == id }) }
                    loadTrips()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Ride requests ----------------------------------------------------------

    private fun onRideEvent(event: HubEvent.RideEvent) {
        // Four handlers rather than one: the list's own controls, the raise
        // form, the actions on a row, and the small forms those actions open.
        when (event) {
            is HubEvent.RideTabChanged -> setState { copy(rideTab = event.tab) }
            is HubEvent.RideSearchChanged -> setState { copy(rideSearch = event.query) }
            is HubEvent.OnlyMyRidesChanged -> setState { copy(onlyMyRides = event.onlyMine) }
            HubEvent.ShowActiveCars -> {
                setState { copy(showActiveCars = true) }
                launch { repository.activeCars().onOk { rows -> setState { copy(activeCars = rows) } } }
            }

            HubEvent.HideActiveCars -> setState { copy(showActiveCars = false) }
            HubEvent.NewRide -> setState { copy(rideEditor = RideEditor()) }
            is HubEvent.EditRide -> setState { copy(rideEditor = event.ride.toEditor()) }
            is HubEvent.RideEditorChanged -> setState { copy(rideEditor = event.editor) }
            HubEvent.SaveRide -> saveRide()
            HubEvent.CloseRideEditor -> setState { copy(rideEditor = null) }
            else -> onRideRowEvent(event)
        }
    }

    /** What a coordinator or a driver does to one row. */
    private fun onRideRowEvent(event: HubEvent.RideEvent) {
        when (event) {
            is HubEvent.ApproveRide -> runOnRide(event.ride.id, str(S.desktop_hub_ride_approved)) {
                repository.approveRide(event.ride.id)
            }

            is HubEvent.RejectRide -> setState {
                copy(reasonEditor = ReasonEditor(event.ride.id, isCancel = false))
            }

            is HubEvent.CancelRide -> setState {
                copy(reasonEditor = ReasonEditor(event.ride.id, isCancel = true))
            }

            is HubEvent.AssignRide -> {
                setState {
                    copy(
                        assignEditor = AssignEditor(
                            rideId = event.ride.id,
                            driverId = event.ride.assignedDriverId,
                            vehicleId = event.ride.assignedVehicleId,
                        ),
                    )
                }
                launch { loadAssignable() }
            }

            is HubEvent.StartRide -> runOnRide(event.ride.id, str(S.desktop_hub_status_updated)) {
                repository.setRideStatus(event.ride.id, RideStatus.InTransit)
            }

            is HubEvent.CompleteRide -> runOnRide(event.ride.id, str(S.desktop_hub_status_updated)) {
                repository.setRideStatus(event.ride.id, RideStatus.Completed)
            }

            is HubEvent.CompleteStop -> runOnRide(event.rideId, str(S.desktop_hub_stop_completed)) {
                repository.completeRideStop(event.rideId, event.index)
            }

            is HubEvent.AddRideStop -> setState { copy(stopEditor = StopEditor(event.ride.id)) }
            is HubEvent.AddRidePassenger -> setState {
                copy(ridePassengerEditor = RidePassengerEditor(event.ride.id))
            }

            else -> onRideFormEvent(event)
        }
    }

    /** The small forms a row's actions open: a reason, an assignment, a stop, a passenger. */
    private fun onRideFormEvent(event: HubEvent.RideEvent) {
        when (event) {
            is HubEvent.ReasonEditorChanged -> setState { copy(reasonEditor = event.editor) }
            HubEvent.SubmitReason -> submitReason()
            HubEvent.CloseReasonEditor -> setState { copy(reasonEditor = null) }
            is HubEvent.AssignEditorChanged -> setState { copy(assignEditor = event.editor) }
            HubEvent.SubmitAssign -> submitAssign()
            HubEvent.CloseAssignEditor -> setState { copy(assignEditor = null) }
            is HubEvent.StopEditorChanged -> setState { copy(stopEditor = event.editor) }
            HubEvent.SubmitStop -> submitStop()
            HubEvent.CloseStopEditor -> setState { copy(stopEditor = null) }
            is HubEvent.RidePassengerEditorChanged -> setState { copy(ridePassengerEditor = event.editor) }
            HubEvent.SubmitRidePassenger -> submitRidePassenger()
            HubEvent.CloseRidePassengerEditor -> setState { copy(ridePassengerEditor = null) }
            else -> Unit
        }
    }

    private fun RideRequest.toEditor(): RideEditor = RideEditor(
        id = id,
        pickup = pickup,
        dropoff = dropoff,
        passengers = passengers,
        stops = stops,
        priority = priority,
        notes = notes,
        asSoonAsPossible = scheduledPickupTimeMs == null,
        scheduledPickupTimeMs = scheduledPickupTimeMs,
    )

    private fun saveRide() {
        val editor = currentState.rideEditor ?: return
        validateRide(editor)?.let { problem ->
            notice(problem, success = false)
            return
        }
        val draft = RideDraft(
            pickup = editor.pickup,
            dropoff = editor.dropoff,
            passengers = editor.passengers,
            stops = editor.stops,
            priority = editor.priority,
            notes = editor.notes,
            scheduledPickupTimeMs = if (editor.asSoonAsPossible) {
                nowMillis()
            } else {
                editor.scheduledPickupTimeMs
            },
        )
        setState { copy(rideEditor = editor.copy(saving = true)) }
        launch {
            val existingId = editor.id
            if (existingId != null) {
                repository.updateRideRequest(existingId, draft).fold(
                    ok = {
                        setState { copy(rideEditor = null) }
                        notice(str(S.desktop_hub_ride_updated))
                        loadRides()
                    },
                    bad = { setState { copy(rideEditor = editor.copy(saving = false)) } },
                )
            } else {
                repository.addRideRequest(draft).fold(
                    ok = {
                        setState { copy(rideEditor = null) }
                        notice(str(S.desktop_hub_ride_created))
                        loadRides()
                    },
                    bad = { setState { copy(rideEditor = editor.copy(saving = false)) } },
                )
            }
        }
    }

    private fun validateRide(editor: RideEditor): String? = when {
        editor.pickup.address.isBlank() || editor.dropoff.address.isBlank() ->
            str(S.desktop_hub_ends_required)

        editor.passengers.none { it.name.isNotBlank() || it.userId != null } ->
            str(S.desktop_hub_passenger_required)

        // The time picker defaults the day to today when only a time is set,
        // so a past moment can slip through its own guard.
        !editor.asSoonAsPossible && (editor.scheduledPickupTimeMs ?: 0) in 1 until nowMillis() ->
            str(S.desktop_hub_time_in_past)

        else -> null
    }

    private fun submitReason() {
        val editor = currentState.reasonEditor ?: return
        val reason = editor.reason.trim().ifBlank {
            if (editor.isCancel) {
                str(S.desktop_transport_trip_cancelled)
            } else {
                str(S.desktop_hub_reason_default_reject)
            }
        }
        setState { copy(reasonEditor = editor.copy(saving = true)) }
        launch {
            val result = if (editor.isCancel) {
                repository.cancelRide(editor.rideId, reason)
            } else {
                repository.rejectRide(editor.rideId, reason)
            }
            result.fold(
                ok = {
                    setState { copy(reasonEditor = null) }
                    notice(
                        if (editor.isCancel) {
                            str(S.desktop_hub_ride_cancelled)
                        } else {
                            str(S.desktop_hub_ride_rejected)
                        },
                    )
                    loadRides()
                },
                bad = { setState { copy(reasonEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun submitAssign() {
        val editor = currentState.assignEditor ?: return
        if (editor.driverId.isNullOrBlank()) {
            notice(str(S.desktop_hub_pick_a_driver), success = false)
            return
        }
        setState { copy(assignEditor = editor.copy(saving = true)) }
        launch {
            repository.assignRide(editor.rideId, editor.driverId, editor.vehicleId).fold(
                ok = {
                    setState { copy(assignEditor = null) }
                    notice(str(S.desktop_hub_ride_assigned))
                    loadRides()
                },
                bad = { setState { copy(assignEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun submitStop() {
        val editor = currentState.stopEditor ?: return
        if (editor.location.address.isBlank()) {
            notice(str(S.desktop_hub_stop_address_required), success = false)
            return
        }
        setState { copy(stopEditor = editor.copy(saving = true)) }
        launch {
            val name = editor.passengerName.trim()
            val stop = HubStop(
                location = editor.location,
                action = editor.action,
                passengerName = name,
                passengers = if (name.isBlank()) emptyList() else listOf(HubPassenger(name = name)),
            )
            repository.addRideStop(editor.rideId, stop).fold(
                ok = {
                    setState { copy(stopEditor = null) }
                    notice(str(S.desktop_hub_stop_added))
                    loadRides()
                },
                bad = { setState { copy(stopEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun submitRidePassenger() {
        val editor = currentState.ridePassengerEditor ?: return
        if (editor.name.isBlank()) {
            notice(str(S.desktop_hub_passenger_name_required), success = false)
            return
        }
        val ride = currentState.rides.firstOrNull { it.id == editor.rideId }
        setState { copy(ridePassengerEditor = editor.copy(saving = true)) }
        launch {
            // A passenger added without their own ends rides the request's,
            // so the row the server stores is never left without a route.
            val passenger = HubPassenger(
                userId = editor.userId,
                name = editor.name.trim(),
                contactNumber = editor.contactNumber.trim(),
                pickup = editor.pickup.takeIf { !it.isEmpty } ?: ride?.pickup.orEmpty(),
                dropoff = editor.dropoff.takeIf { !it.isEmpty } ?: ride?.dropoff.orEmpty(),
            )
            repository.addRidePassenger(editor.rideId, passenger).fold(
                ok = {
                    setState { copy(ridePassengerEditor = null) }
                    notice(str(S.desktop_hub_passenger_added))
                    loadRides()
                },
                bad = { setState { copy(ridePassengerEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun runOnRide(id: String, success: String, block: suspend () -> ZillitResult<Unit>) {
        setState { copy(busyRowId = id) }
        launch {
            block().fold(
                ok = {
                    notice(success)
                    loadRides()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Allocations ------------------------------------------------------------

    private fun onAllocationEvent(event: HubEvent.AllocationEvent) {
        when (event) {
            is HubEvent.AllocationTabChanged -> setState { copy(allocationTab = event.tab) }
            is HubEvent.AllocationSearchChanged -> setState { copy(allocationSearch = event.query) }
            HubEvent.NewAllocation -> {
                setState { copy(allocationEditor = AllocationEditor()) }
                launch { loadAssignable() }
            }

            is HubEvent.EditAllocation -> {
                setState { copy(allocationEditor = AllocationEditor.from(event.trip)) }
                launch { loadAssignable() }
            }

            is HubEvent.AllocationEditorChanged -> setState { copy(allocationEditor = event.editor) }
            HubEvent.SaveAllocation -> saveAllocation(asDraft = false)
            HubEvent.RequestCloseAllocation -> requestCloseAllocation()
            HubEvent.SaveAllocationAsDraft -> saveAllocation(asDraft = true)
            HubEvent.DiscardAllocation -> setState { copy(allocationEditor = null, draftPrompt = null) }
            is HubEvent.ToggleAllocationState -> toggleAllocationState(event.trip)
            is HubEvent.DeleteAllocation -> confirmDeleteAllocation(event.trip)
            is HubEvent.DeleteAllocationConfirmed -> deleteAllocation(event.id)
            else -> onSwapEvent(event)
        }
    }

    /** Swapping the driver or the vehicle out of a running allocation. */
    private fun onSwapEvent(event: HubEvent.AllocationEvent) {
        when (event) {
            is HubEvent.SwapAllocationDriver -> {
                setState { copy(swapEditor = SwapEditor(event.trip.id, isDriver = true)) }
                launch { loadAssignable() }
            }

            is HubEvent.SwapAllocationVehicle -> {
                setState { copy(swapEditor = SwapEditor(event.trip.id, isDriver = false)) }
                launch { loadAssignable() }
            }

            is HubEvent.SwapEditorChanged -> setState { copy(swapEditor = event.editor) }
            HubEvent.SubmitSwap -> submitSwap()
            HubEvent.CloseSwapEditor -> setState { copy(swapEditor = null) }
            else -> Unit
        }
    }

    /**
     * Closes the allocation form, offering to keep a half-filled one.
     *
     * Only when creating: there is no meaningful "draft" of an edit to an
     * allocation that already exists.
     */
    private fun requestCloseAllocation() {
        val editor = currentState.allocationEditor ?: return
        if (editor.isEdit || !editor.hasContent) {
            setState { copy(allocationEditor = null) }
            return
        }
        setState { copy(draftPrompt = editor) }
    }

    private fun saveAllocation(asDraft: Boolean) {
        val editor = if (asDraft) currentState.draftPrompt else currentState.allocationEditor
        if (editor == null) return
        // A draft is allowed to be partial by design, so it skips validation.
        if (!asDraft) {
            validateAllocation(editor)?.let { problem ->
                notice(problem, success = false)
                return
            }
        }
        val draft = PermanentDraft(
            title = editor.title.trim(),
            pickup = editor.pickup,
            dropoff = editor.dropoff,
            driverId = editor.driverId,
            vehicleId = editor.vehicleId,
            passengers = editor.passengers,
            startDateMs = editor.startDateMs,
            endDateMs = editor.endDateMs,
            scheduledTimeMs = editor.scheduledTimeMs,
            activeDays = editor.activeDays,
            isFullDayTrip = editor.isFullDayTrip,
            selfManage = editor.selfManage,
            isExclusive = editor.isExclusive,
            notes = editor.notes,
            state = if (asDraft) PermanentState.Draft else PermanentState.Active,
        )
        setState { copy(allocationEditor = allocationEditor?.copy(saving = true)) }
        launch {
            val existingId = editor.id
            val result = if (existingId != null && !asDraft) {
                repository.updatePermanentTrip(existingId, draft)
            } else {
                repository.addPermanentTrip(draft)
            }
            result.fold(
                ok = {
                    setState { copy(allocationEditor = null, draftPrompt = null) }
                    notice(
                        if (asDraft) {
                            str(S.desktop_hub_allocation_draft_saved)
                        } else {
                            str(S.desktop_hub_allocation_saved)
                        },
                    )
                    loadAllocations()
                },
                bad = { setState { copy(allocationEditor = allocationEditor?.copy(saving = false)) } },
            )
        }
    }

    private fun validateAllocation(editor: AllocationEditor): String? = when {
        editor.pickup.address.isBlank() || editor.dropoff.address.isBlank() ->
            str(S.desktop_hub_ends_required)

        editor.driverId.isNullOrBlank() -> str(S.desktop_hub_pick_a_driver)
        editor.vehicleId.isNullOrBlank() -> str(S.desktop_hub_pick_a_vehicle)
        editor.activeDays.isEmpty() -> str(S.desktop_hub_active_days)
        else -> null
    }

    /**
     * Pause, resume, or promote a draft.
     *
     * Promoting is the odd one: it re-sends the whole record so the server's
     * validator runs, because `/activate` would flip an unfinished draft's
     * runtime status and leave it unfit to run. The validator's refusal is
     * what tells the coordinator which field is still missing.
     */
    private fun toggleAllocationState(trip: PermanentTrip) {
        setState { copy(busyRowId = trip.id) }
        launch {
            val result = when (trip.state) {
                PermanentState.Draft -> repository.activateDraft(trip)
                PermanentState.Paused -> repository.resumePermanentTrip(trip.id)
                else -> repository.pausePermanentTrip(trip.id)
            }
            result.fold(ok = { loadAllocations() })
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteAllocation(trip: PermanentTrip) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_allocation_title),
                    body = str(S.desktop_hub_delete_allocation_body),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteAllocationConfirmed(trip.id),
                ),
            )
        }
    }

    private fun deleteAllocation(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deletePermanentTrip(id).fold(
                ok = {
                    notice(str(S.desktop_hub_allocation_deleted))
                    loadAllocations()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun submitSwap() {
        val editor = currentState.swapEditor ?: return
        val picked = editor.pickedId
        if (picked.isNullOrBlank()) {
            notice(
                if (editor.isDriver) {
                    str(S.desktop_hub_pick_a_driver)
                } else {
                    str(S.desktop_hub_pick_a_vehicle)
                },
                success = false,
            )
            return
        }
        setState { copy(swapEditor = editor.copy(saving = true)) }
        launch {
            val result = if (editor.isDriver) {
                repository.swapPermanentDriver(editor.tripId, picked, editor.reason.trim())
            } else {
                repository.swapPermanentVehicle(editor.tripId, picked, editor.reason.trim())
            }
            result.fold(
                ok = {
                    setState { copy(swapEditor = null) }
                    notice(
                        if (editor.isDriver) {
                            str(S.desktop_hub_driver_swapped)
                        } else {
                            str(S.desktop_hub_vehicle_swapped)
                        },
                    )
                    loadAllocations()
                },
                bad = { setState { copy(swapEditor = editor.copy(saving = false)) } },
            )
        }
    }

    // Plumbing ---------------------------------------------------------------

    private fun notice(text: String, success: Boolean = true) {
        sendEffect(HubEffect.Notice(text, success))
    }

    /**
     * Routes a result: [ok] on success, [bad] plus a visible message on
     * failure. The server's `message` is a label key, so it is translated
     * rather than shown raw.
     */
    private inline fun <T> ZillitResult<T>.fold(
        ok: (T) -> Unit,
        bad: () -> Unit = {},
    ) {
        when (this) {
            is ZillitResult.Success -> ok(data)
            is ZillitResult.Failure -> {
                bad()
                val message = error.userMessage.localised()
                setState { copy(error = message) }
                notice(message, success = false)
            }
        }
    }

    /** Like [fold] but silent on failure — for a load allowed to come up empty. */
    private inline fun <T> ZillitResult<T>.onOk(ok: (T) -> Unit) {
        (this as? ZillitResult.Success)?.let { ok(it.data) }
    }
}

/** `trip_not_found` is what a ride request answers to `DELETE /trip/:id`. */
private fun ZillitResult.Failure.looksLikeMissingTrip(): Boolean =
    error.userMessage.contains("trip_not_found", ignoreCase = true)

private fun com.zillit.desktop.feature.transporthub.domain.HubLocation?.orEmpty() =
    this ?: com.zillit.desktop.feature.transporthub.domain.HubLocation.Empty
