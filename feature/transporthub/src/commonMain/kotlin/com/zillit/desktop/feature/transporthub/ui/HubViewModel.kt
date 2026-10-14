// One handler per user act; the web spreads them over 24 files.
@file:Suppress("TooManyFunctions", "LargeClass", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transporthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvInsuranceStatus
import com.zillit.desktop.feature.transporthub.domain.AvSourceType
import com.zillit.desktop.feature.transporthub.domain.AvDraft
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.AvVariant
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteDraft
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDetailsPatch
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.HubHost
import com.zillit.desktop.feature.transporthub.domain.HubMediaKind
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRepository
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.MaintenanceDraft
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.HubSyncKind
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentDraft
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.RideDraft
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideScope
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripDraft
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleDraft
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.data.startOfDay
import com.zillit.desktop.feature.transporthub.data.toEpochMillisOrNull
import com.zillit.desktop.feature.transporthub.data.timeZoneId
import com.zillit.desktop.feature.transporthub.data.toIsoDate
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

    private companion object {
        /** Where a position is opened, the older tool's own link shape. */
        const val MAPS_URL = "https://www.google.com/maps/search/?api=1&query="

        /** A picture vehicle nobody has put a car against yet. */
        val UNSOURCED = setOf(AvStatus.Requested, AvStatus.Rejected)
    }

    fun start() {
        val viewer = resolveViewer()
        setState {
            copy(
                viewer = viewer,
                nowMs = nowMillis(),
                // The rail depends on the role, so the landing section does
                // too: a driver has no Dashboard to land on.
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
                    HubSyncKind.Maintenance -> loadMaintenance()
                    // Every moving car emits this one, so it is only acted on
                    // while the page that draws it is open.
                    HubSyncKind.Tracking ->
                        if (currentState.section.showsLocations) loadLocations()

                    HubSyncKind.ShuttleRoutes -> loadShuttleRoutes()
                    HubSyncKind.ShuttleRuns -> loadShuttleRuns()
                    HubSyncKind.ActionVehicles -> loadActionVehicles()
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
            is HubEvent.SetHubTheme -> setState { copy(hubDark = event.dark) }
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
            is HubEvent.DashboardEvent -> onDashboardEvent(event)
            is HubEvent.MaintenanceEvent -> onMaintenanceEvent(event)
            is HubEvent.TrackingEvent -> onTrackingEvent(event)
            is HubEvent.ShuttleEvent -> onShuttleEvent(event)
            is HubEvent.SelfEvent -> onSelfEvent(event)
            is HubEvent.ActionVehicleEvent -> onActionVehicleEvent(event)
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
        setState { copy(loading = true, error = null, nowMs = nowMillis()) }
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
                    // An empty answer is "could not say", not "there are
                    // none": replacing a list already in hand with it would
                    // demote a driver to a passenger mid-session.
                    if (names.isNotEmpty()) {
                        setState { copy(viewer = viewer.copy(driverDesignations = names)) }
                    }
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
            HubSection.Dashboard -> loadDashboard()
            HubSection.Trips, HubSection.MyTrips -> loadTrips()
            HubSection.RideRequests, HubSection.MyRides,
            HubSection.AssignedRides, HubSection.MyRequests,
            -> loadRides()

            HubSection.Fleet -> loadVehicles()
            HubSection.Drivers -> loadDrivers()
            HubSection.Allocations -> loadAllocations()
            HubSection.Maintenance -> loadMaintenance()
            HubSection.LiveTracking, HubSection.DriverMap -> loadLocations()
            HubSection.Shuttles -> loadShuttles()
            HubSection.ActionVehicles -> loadActionVehicles()
        }
    }

    /** Both shuttle tabs, so switching between them does not wait on a load. */
    private suspend fun loadShuttles() {
        coroutineScope {
            val routes = async { repository.shuttleRoutes() }
            val runs = async { repository.shuttleRuns() }
            routes.await().fold(ok = { rows -> setState { copy(shuttleRoutes = rows) } })
            // The runs route is the newer of the two and lives under a
            // different prefix: let it fail on its own rather than costing
            // the routes tab as well.
            runs.await().onOk { rows -> setState { copy(shuttleRuns = rows) } }
        }
    }

    private suspend fun loadShuttleRoutes() {
        repository.shuttleRoutes().fold(ok = { rows -> setState { copy(shuttleRoutes = rows) } })
    }

    private suspend fun loadShuttleRuns() {
        repository.shuttleRuns().onOk { rows -> setState { copy(shuttleRuns = rows) } }
    }

    private suspend fun loadActionVehicles() {
        repository.actionVehicles().fold(
            ok = { rows ->
                setState {
                    copy(
                        actionVehicles = rows,
                        // The tabs depend on the records — an approver's tab
                        // only exists while they have something to decide — so
                        // one that has just gone away must not stay open. And
                        // until somebody picks a tab, the first of theirs is
                        // the one to show, which is the web's own rule.
                        avTab = when {
                            avTabPicked && avTab in actionVehicleTabs -> avTab
                            else -> actionVehicleTabs.firstOrNull() ?: AvTab.Finalized
                        },
                    )
                }
            },
        )
    }

    /**
     * The Dashboard's four reads.
     *
     * Each is allowed to fail on its own — the web runs them through
     * `Promise.allSettled` for exactly this reason: `/insights/dashboard` is
     * the newest of the four routes and a deployment without it must still
     * leave the trip list and the fleet count on screen.
     */
    private suspend fun loadDashboard() {
        coroutineScope {
            val trips = async { repository.trips() }
            val insights = async { repository.insights() }
            val pending = async { repository.pendingRideCount() }
            val vehicles = async { repository.availableVehicles() }
            trips.await().onOk { rows -> setState { copy(trips = rows) } }
            insights.await().onOk { data -> setState { copy(insights = data) } }
            pending.await().onOk { count -> setState { copy(pendingRideCount = count) } }
            vehicles.await().onOk { rows -> setState { copy(availableVehicles = rows) } }
        }
    }

    private suspend fun loadMaintenance() {
        repository.maintenance().fold(ok = { rows -> setState { copy(maintenance = rows) } })
    }

    private suspend fun loadLocations() {
        repository.activeLocations().fold(
            ok = { rows ->
                setState { copy(driverLocations = rows, trackingUpdatedMs = nowMillis()) }
            },
        )
        // A driver's own map shows only their pin; the coordinator's Rides tab
        // needs the cars, which are the active ride requests.
        if (currentState.role == HubRole.Coordinator) loadRides()
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

    /**
     * The trips the open section wants.
     *
     * A driver's My Trips is scoped to their own driver record. Without that
     * parameter the route answers the production's whole list — which is what
     * a driver used to be shown.
     */
    private suspend fun loadTrips() {
        val driverId = if (currentState.role == HubRole.Driver) currentState.myDriverId else null
        repository.trips(driverId).fold(
            ok = { rows ->
                setState {
                    val next = copy(trips = rows)
                    // A shuttle-only driver should not open onto an empty
                    // Rides segment: the first load lands on whichever has
                    // something, until the driver picks one themselves.
                    if (driverTripViewPicked) {
                        next
                    } else {
                        next.copy(
                            driverTripView = when {
                                next.driverRides.isEmpty() && next.driverShuttleGroups.isNotEmpty() ->
                                    DriverTripView.Routes

                                else -> DriverTripView.Rides
                            },
                        )
                    }
                }
            },
        )
    }

    /**
     * The rides the open section wants, on that section's own scope.
     *
     * The three pages are three different lists, and the one that is easy to
     * get wrong is a driver's Assigned Rides: `me=true` there answers the
     * rides that driver *asked for*, not the ones they are to drive.
     */
    private suspend fun loadRides() {
        repository.rideRequests(currentState.rideScope)
            .fold(ok = { rows -> setState { copy(rides = rows) } })
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

    // Dashboard --------------------------------------------------------------

    private fun onDashboardEvent(event: HubEvent.DashboardEvent) {
        when (event) {
            is HubEvent.DashboardFilterChanged -> setState { copy(dashboardFilter = event.filter) }
            is HubEvent.DashboardSearchChanged -> setState { copy(dashboardSearch = event.query) }
            HubEvent.OpenAllTrips -> selectSection(HubSection.Trips)
        }
    }

    // Maintenance ------------------------------------------------------------

    private fun onMaintenanceEvent(event: HubEvent.MaintenanceEvent) {
        when (event) {
            is HubEvent.MaintenanceTabChanged -> setState { copy(maintenanceTab = event.tab) }
            is HubEvent.MaintenanceSearchChanged -> setState { copy(maintenanceSearch = event.query) }
            HubEvent.NewMaintenance -> setState {
                copy(
                    // The booking cannot be in the past, so today is both the
                    // default and the earliest the field offers.
                    maintenanceEditor = MaintenanceEditor(scheduledDate = nowMillis().toIsoDate()),
                )
            }

            is HubEvent.MaintenanceEditorChanged -> setState { copy(maintenanceEditor = event.editor) }
            HubEvent.SaveMaintenance -> saveMaintenance()
            HubEvent.CloseMaintenanceEditor -> setState { copy(maintenanceEditor = null) }
            is HubEvent.CompleteMaintenance -> completeMaintenance(event.record)
            is HubEvent.DeleteMaintenance -> confirmDeleteMaintenance(event.record)
            is HubEvent.DeleteMaintenanceConfirmed -> deleteMaintenance(event.id)
        }
    }

    private fun saveMaintenance() {
        val editor = currentState.maintenanceEditor ?: return
        val day = editor.scheduledDate.toEpochMillisOrNull()
        val problem = when {
            editor.vehicleId.isBlank() -> str(S.desktop_hub_pick_a_vehicle)
            day == null -> str(S.desktop_hub_pick_a_date)
            day.startOfDay() < nowMillis().startOfDay() -> str(S.desktop_hub_date_in_the_past)
            else -> null
        }
        if (problem != null) {
            notice(problem, success = false)
            return
        }
        setState { copy(maintenanceEditor = editor.copy(saving = true)) }
        launch {
            val draft = MaintenanceDraft(
                vehicleId = editor.vehicleId,
                type = editor.type,
                scheduledDateMs = day ?: 0,
                description = editor.description,
                estimatedCost = editor.estimatedCost.trim().toDoubleOrNull(),
                odometerReading = editor.odometerReading.trim().toLongOrNull(),
            )
            repository.addMaintenance(draft).fold(
                ok = {
                    setState { copy(maintenanceEditor = null) }
                    notice(str(S.desktop_hub_maintenance_scheduled))
                    loadMaintenance()
                },
                bad = { setState { copy(maintenanceEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun completeMaintenance(record: MaintenanceRecord) {
        setState { copy(busyRowId = record.id) }
        launch {
            repository.completeMaintenance(record.id).fold(
                ok = {
                    notice(str(S.desktop_hub_maintenance_completed))
                    loadMaintenance()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteMaintenance(record: MaintenanceRecord) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_maintenance),
                    body = str(S.desktop_hub_delete_maintenance_body),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteMaintenanceConfirmed(record.id),
                ),
            )
        }
    }

    private fun deleteMaintenance(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deleteMaintenance(id).fold(
                ok = {
                    notice(str(S.desktop_hub_maintenance_deleted))
                    setState { copy(maintenance = maintenance.filterNot { it.id == id }) }
                    loadMaintenance()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Live tracking ----------------------------------------------------------

    private fun onTrackingEvent(event: HubEvent.TrackingEvent) {
        when (event) {
            is HubEvent.TrackingTabChanged -> setState { copy(trackingTab = event.tab) }
            HubEvent.RefreshTracking -> launch { loadLocations() }
            is HubEvent.TrackDriver -> openDriverOnMap(event.driverId)
        }
    }

    /**
     * Opens a driver's last reported position in the browser's map.
     *
     * The web draws a Google map inside the page; the desktop has no map
     * widget, so the position is handed to the browser — the same seam the
     * older Transportation tool uses for "track driver".
     */
    private fun openDriverOnMap(driverId: String) {
        val point = currentState.driverLocations.firstOrNull { it.driverId == driverId }
            ?.location
            ?: currentState.drivers.firstOrNull { it.id == driverId }?.lastLocation
        if (point == null || !point.hasCoordinates) {
            notice(str(S.desktop_hub_no_position_yet), success = false)
            return
        }
        sendEffect(HubEffect.OpenLink("$MAPS_URL${point.lat},${point.long}"))
    }

    // A passenger's and a driver's own pages ---------------------------------

    private fun onSelfEvent(event: HubEvent.SelfEvent) {
        when (event) {
            is HubEvent.RideFiltersChanged -> setState { copy(rideFilters = event.filters) }
            is HubEvent.TripFiltersChanged -> setState { copy(tripFilters = event.filters) }
            HubEvent.ToggleFiltersExpanded -> setState { copy(filtersExpanded = !filtersExpanded) }
            HubEvent.CallADriver -> setState {
                // The caller is always the first passenger, by construction —
                // the web's picker enforces it, and a request with nobody on it
                // is refused.
                val me = viewer.userId.takeIf { it.isNotBlank() }?.let { id ->
                    HubPassenger(userId = id, name = viewer.fullName)
                }
                copy(rideEditor = RideEditor(isSelf = true, passengers = listOfNotNull(me)))
            }

            is HubEvent.DriverTripViewChanged -> setState {
                copy(driverTripView = event.view, driverTripViewPicked = true)
            }

            is HubEvent.DriverRideTabChanged -> setState { copy(driverRideTab = event.tab) }
            is HubEvent.AdvanceTrip -> advanceTrip(event.trip)
            is HubEvent.AdvanceRide -> advanceRide(event.ride)
            is HubEvent.OpenRide -> setState { copy(openRide = event.id) }
            HubEvent.CloseRide -> setState { copy(openRide = null) }
            is HubEvent.TrackRideDriver -> event.ride.assignedDriverId?.let { openDriverOnMap(it) }
                ?: notice(str(S.desktop_hub_no_position_yet), success = false)
        }
    }

    /**
     * The driver's next step on one row of their My Trips.
     *
     * Three routes, picked by what the row is — never by its status alone,
     * because a shuttle run and a trip both say `in_transit` and mean
     * different next steps: a run goes to `/shuttles/runs/:id`, a ride request
     * to `/ride-request/:id/status`, and a trip to `/trip/:id/status`.
     */
    private fun advanceTrip(trip: Trip) {
        val step = DriverStep.forTrip(trip) ?: return
        setState { copy(busyRowId = trip.id) }
        launch {
            val result = when {
                trip.isShuttleRun ->
                    repository.setShuttleRunStatus(trip.id, ShuttleRunStatus.from(step.next))

                trip.kind == TripResourceKind.RideRequest ->
                    repository.setRideStatus(trip.id, RideStatus.from(step.next))

                else -> repository.setTripStatus(trip.id, TripStatus.from(step.next))
            }
            result.fold(
                ok = {
                    notice(str(S.desktop_hub_step_done, step.label()))
                    loadTrips()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun advanceRide(ride: RideRequest) {
        val step = DriverStep.forRide(ride) ?: return
        setState { copy(busyRowId = ride.id) }
        launch {
            repository.setRideStatus(ride.id, RideStatus.from(step.next)).fold(
                ok = {
                    notice(str(S.desktop_hub_step_done, step.label()))
                    loadRides()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Shuttles ---------------------------------------------------------------

    private fun onShuttleEvent(event: HubEvent.ShuttleEvent) {
        when (event) {
            is HubEvent.ShuttleTabChanged -> setState { copy(shuttleTab = event.tab) }
            is HubEvent.ShuttleSearchChanged -> setState { copy(shuttleSearch = event.query) }
            is HubEvent.RunRouteFilterChanged -> setState { copy(runRouteFilter = event.routeId) }
            HubEvent.NewShuttleRoute -> {
                setState { copy(routeEditor = ShuttleRouteEditor()) }
                launch { loadAssignable() }
            }

            is HubEvent.EditShuttleRoute -> {
                setState { copy(routeEditor = ShuttleRouteEditor.from(event.route)) }
                launch { loadAssignable() }
            }

            is HubEvent.RouteEditorChanged -> setState { copy(routeEditor = event.editor) }
            HubEvent.SaveShuttleRoute -> saveShuttleRoute()
            HubEvent.CloseRouteEditor -> setState { copy(routeEditor = null) }
            is HubEvent.ToggleShuttleRoute -> toggleShuttleRoute(event.route)
            is HubEvent.GenerateShuttleRuns -> generateShuttleRuns(event.route)
            is HubEvent.DeleteShuttleRoute -> confirmDeleteRoute(event.route)
            is HubEvent.DeleteShuttleRouteConfirmed -> deleteShuttleRoute(event.id)
            is HubEvent.SetShuttleRunStatus -> setRunStatus(event.run.id, event.status)
        }
    }

    private fun saveShuttleRoute() {
        val editor = currentState.routeEditor ?: return
        val problem = when {
            editor.composedName.isBlank() -> str(S.desktop_hub_route_ends_required)
            editor.stops.none { !it.location.isEmpty || it.name.isNotBlank() } ->
                str(S.desktop_hub_route_stop_required)

            else -> null
        }
        if (problem != null) {
            notice(problem, success = false)
            return
        }
        setState { copy(routeEditor = editor.copy(saving = true)) }
        launch {
            val draft = ShuttleRouteDraft(
                name = editor.composedName,
                type = editor.type,
                capacity = editor.capacity.trim().toIntOrNull(),
                notes = editor.notes,
                frequencyMinutes = editor.frequencyMinutes.trim().toIntOrNull(),
                startTime = editor.startTime.trim(),
                endTime = editor.endTime.trim(),
                daysActive = editor.daysActive,
                // A blank row is somebody who added a stop and left it: drop
                // it rather than sending a stop with no address at all.
                stops = editor.stops.filter { !it.location.isEmpty || it.name.isNotBlank() },
                driverId = editor.driverId,
                vehicleId = editor.vehicleId,
            )
            val result = editor.id?.let { repository.updateShuttleRoute(it, draft) }
                ?: repository.addShuttleRoute(draft)
            result.fold(
                ok = {
                    setState { copy(routeEditor = null) }
                    notice(str(S.desktop_hub_route_saved))
                    loadShuttleRoutes()
                },
                bad = { setState { copy(routeEditor = editor.copy(saving = false)) } },
            )
        }
    }

    private fun toggleShuttleRoute(route: ShuttleRoute) {
        val next = !route.isActive
        // Optimistic: the card dims or brightens at once, and no full-page
        // spinner flashes for a one-field write. Rolled back on refusal.
        setState {
            copy(
                busyRowId = route.id,
                shuttleRoutes = shuttleRoutes.map {
                    if (it.id == route.id) it.copy(isActive = next) else it
                },
            )
        }
        launch {
            repository.setShuttleRouteActive(route.id, next).fold(
                ok = { loadShuttleRoutes() },
                bad = {
                    setState {
                        copy(
                            shuttleRoutes = shuttleRoutes.map {
                                if (it.id == route.id) it.copy(isActive = route.isActive) else it
                            },
                        )
                    }
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    /**
     * Generates a route's runs.
     *
     * Refused without both a driver and a vehicle, and the refusal says which
     * is missing: the server would otherwise create runs with nobody to drive
     * them, and nothing can be done with those afterwards.
     */
    private fun generateShuttleRuns(route: ShuttleRoute) {
        if (!route.canGenerateRuns) {
            notice(
                str(
                    S.desktop_hub_route_needs_assignment,
                    route.displayName,
                    when {
                        route.driverId.isNullOrBlank() && route.vehicleId.isNullOrBlank() ->
                            str(S.desktop_hub_a_driver_and_a_vehicle)

                        route.driverId.isNullOrBlank() -> str(S.driver)
                        else -> str(S.vehicle)
                    },
                ),
                success = false,
            )
            return
        }
        setState { copy(busyRowId = route.id) }
        launch {
            repository.generateShuttleRuns(route.id).fold(
                ok = {
                    notice(str(S.desktop_hub_runs_generated, route.displayName))
                    // The web moves to the runs tab so the coordinator can
                    // see what was just created.
                    setState { copy(shuttleTab = ShuttleTab.Runs) }
                    loadShuttleRuns()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteRoute(route: ShuttleRoute) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_route),
                    // The server drops every future run of this route in the
                    // same transaction, which is worth saying before the fact.
                    body = str(S.desktop_hub_delete_route_body, route.displayName),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteShuttleRouteConfirmed(route.id),
                ),
            )
        }
    }

    private fun deleteShuttleRoute(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deleteShuttleRoute(id).fold(
                ok = {
                    notice(str(S.desktop_hub_route_deleted))
                    // The cascade also removed this route's future runs, so
                    // both lists are stale.
                    loadShuttles()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun setRunStatus(id: String, status: ShuttleRunStatus) {
        setState { copy(busyRowId = id) }
        launch {
            repository.setShuttleRunStatus(id, status).fold(
                ok = {
                    notice(str(S.desktop_hub_status_updated))
                    loadShuttleRuns()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    // Action vehicles --------------------------------------------------------

    private fun onActionVehicleEvent(event: HubEvent.ActionVehicleEvent) {
        when (event) {
            is HubEvent.AvTabChanged -> setState { copy(avTab = event.tab, avTabPicked = true) }
            is HubEvent.AvSearchChanged -> setState { copy(avSearch = event.query) }
            is HubEvent.AvCategoryChanged -> setState { copy(avCategory = event.category) }
            HubEvent.NewActionVehicleRequest -> setState {
                copy(avEditor = AvEditor(mode = AvFormMode.Request))
            }

            is HubEvent.SourceActionVehicle -> setState {
                copy(avEditor = event.vehicle.toEditor(AvFormMode.Source))
            }

            is HubEvent.EditActionVehicle -> setState {
                copy(avEditor = event.vehicle.toEditor(AvFormMode.Edit))
            }

            is HubEvent.AvEditorChanged -> setState { copy(avEditor = event.editor) }
            HubEvent.SaveActionVehicle -> saveActionVehicle()
            HubEvent.CloseAvEditor -> setState { copy(avEditor = null) }
            HubEvent.PickAvPhotos -> pickAvPhotos()
            is HubEvent.RemoveAvPhoto -> setState {
                copy(avEditor = avEditor?.copy(images = avEditor.images - event.slot))
            }

            is HubEvent.OpenActionVehicle -> setState { copy(openActionVehicle = event.id) }
            HubEvent.CloseActionVehicle -> setState { copy(openActionVehicle = null) }
            is HubEvent.AvTrackerFlowChanged -> setState { copy(avTrackerFlow = event.flow) }
            else -> onActionVehicleWrite(event)
        }
    }

    /** The acts that go out to the server, split out for the complexity budget. */
    private fun onActionVehicleWrite(event: HubEvent.ActionVehicleEvent) {
        when (event) {
            is HubEvent.SetActionVehicleStatus -> moveActionVehicle(event.id, event.status)
            is HubEvent.DeleteActionVehicle -> confirmDeleteActionVehicle(event.vehicle)
            is HubEvent.DeleteActionVehicleConfirmed -> deleteActionVehicle(event.id)
            is HubEvent.PickAvApprovers -> {
                setState {
                    copy(
                        avApproverPicker = AvApproverPicker(
                            vehicleId = event.vehicle.id,
                            flow = event.flow,
                            addingToExisting = event.addingToExisting,
                        ),
                    )
                }
                launch { if (currentState.people.isEmpty()) loadPeople() }
            }

            is HubEvent.AvApproverPickerChanged -> setState { copy(avApproverPicker = event.picker) }
            HubEvent.SubmitAvApprovers -> submitAvApprovers()
            HubEvent.CloseAvApproverPicker -> setState { copy(avApproverPicker = null) }
            is HubEvent.RemoveAvApprover ->
                removeAvApprover(event.vehicleId, event.userId, event.flow)

            is HubEvent.RemindAvApprover -> remindAvApprover(event.vehicleId, event.userId)
            is HubEvent.DecideActionVehicle -> setState {
                copy(
                    avDecision = AvDecision(
                        vehicleId = event.vehicle.id,
                        flow = event.flow,
                        isReject = event.isReject,
                    ),
                )
            }

            is HubEvent.AvDecisionChanged -> setState { copy(avDecision = event.decision) }
            HubEvent.SubmitAvDecision -> submitAvDecision()
            HubEvent.CloseAvDecision -> setState { copy(avDecision = null) }
            is HubEvent.OpenAvThread -> setState { copy(avThread = AvThread(event.vehicleId)) }
            is HubEvent.AvThreadChanged -> setState { copy(avThread = event.thread) }
            HubEvent.SendAvComment -> sendAvComment()
            HubEvent.CloseAvThread -> setState { copy(avThread = null) }
            is HubEvent.PickAvCommenters -> {
                setState {
                    copy(
                        avCommentPicker = AvCommentPicker(
                            vehicleId = event.vehicle.id,
                            // The existing audience is pre-picked, minus the
                            // viewer: re-opening reads as "add more people"
                            // and shows who is already in.
                            picked = event.vehicle.commentAudience.filter { it != viewerId },
                        ),
                    )
                }
                launch { if (currentState.people.isEmpty()) loadPeople() }
            }

            is HubEvent.AvCommentPickerChanged -> setState { copy(avCommentPicker = event.picker) }
            HubEvent.SubmitAvCommenters -> submitAvCommenters()
            HubEvent.CloseAvCommentPicker -> setState { copy(avCommentPicker = null) }
            else -> Unit
        }
    }

    private val viewerId: String get() = currentState.viewer.userId

    private suspend fun loadPeople() {
        repository.projectPeople().onOk { rows -> setState { copy(people = rows) } }
    }

    /**
     * A record as the form holds it.
     *
     * A finalized record is frozen apart from its two shoot dates, and that is
     * carried on the editor so the form can grey the rest rather than letting
     * somebody type into fields the server will ignore.
     */
    private fun ActionVehicle.toEditor(mode: AvFormMode): AvEditor = AvEditor(
        id = id,
        mode = mode,
        displayName = displayName,
        categories = categories,
        vehicleTypes = vehicleTypes,
        variants = variants.ifEmpty { listOf(AvVariant()) },
        make = make,
        model = model,
        color = color,
        year = year?.toString().orEmpty(),
        description = description,
        condition = condition,
        modifications = modifications,
        useType = useType,
        scenes = scenes,
        location = location,
        needRehearsalDate = needRehearsalMs.toIsoDate(),
        needShootingDate = needShootingMs.toIsoDate(),
        reminderDays = reminderDays?.toString().orEmpty(),
        approvedDrivers = approvedDrivers,
        notes = notes,
        sourceType = sourceType ?: AvSourceType.Rented,
        ownerName = ownerName,
        ownerContactNumber = ownerContactNumber,
        rate = rate?.toString().orEmpty(),
        ratePeriod = ratePeriod,
        agreementStartDate = agreementStartMs.toIsoDate(),
        agreementEndDate = agreementEndMs.toIsoDate(),
        insuranceStatus = insuranceStatus ?: AvInsuranceStatus.Pending,
        policyRef = policyRef,
        replacementValue = replacementValue?.toString().orEmpty(),
        licensePlate = licensePlate,
        permittedUses = permittedUses,
        propPlates = propPlates,
        images = images.map { PhotoSlot(attachment = it) },
        // An edit of a record nobody has sourced yet is still the requester's
        // own submit, options and all.
        requesterSubmit = mode == AvFormMode.Request ||
            (mode == AvFormMode.Edit && status in UNSOURCED),
    ).let { editor ->
        if (mode == AvFormMode.Edit && status.isFinalized) editor.frozen() else editor
    }

    private fun saveActionVehicle() {
        val editor = currentState.avEditor ?: return
        validateActionVehicle(editor)?.let { problem ->
            notice(problem, success = false)
            return
        }
        setState { copy(avEditor = editor.copy(saving = true)) }
        launch {
            val draft = editor.toDraft()
            val result = when {
                editor.mode == AvFormMode.Request || editor.id == null ->
                    repository.requestActionVehicle(draft, timeZoneId())

                editor.mode == AvFormMode.Source ->
                    repository.sourceActionVehicle(editor.id, draft)

                else -> repository.updateActionVehicle(
                    id = editor.id,
                    draft = draft,
                    timezone = timeZoneId(),
                    datesOnly = editor.datesOnly,
                )
            }
            result.fold(
                ok = {
                    setState { copy(avEditor = null) }
                    notice(
                        when (editor.mode) {
                            AvFormMode.Request -> str(S.desktop_hub_av_requested)
                            AvFormMode.Source -> str(S.desktop_hub_av_sourced)
                            AvFormMode.Edit -> str(S.saved)
                        },
                    )
                    loadActionVehicles()
                },
                bad = { setState { copy(avEditor = editor.copy(saving = false)) } },
            )
        }
    }

    /**
     * What the form refuses before the server does.
     *
     * The two that matter: a request with no option at all (the server
     * answers `variants required`), and a sourcing with no owner on a vehicle
     * that is not the production's own.
     */
    private fun validateActionVehicle(editor: AvEditor): String? = when {
        editor.displayName.isBlank() -> str(S.desktop_hub_av_name_required)
        editor.mode == AvFormMode.Request && editor.variants.all { it.isEmpty } ->
            str(S.desktop_hub_av_option_required)

        editor.showsSourcing && !editor.datesOnly &&
            !editor.sourceType.isOwned && editor.ownerName.isBlank() ->
            str(S.desktop_hub_av_owner_required)

        else -> null
    }

    private fun moveActionVehicle(id: String, status: AvStatus) {
        setState { copy(busyRowId = id) }
        launch {
            repository.setActionVehicleStatus(id, status).fold(
                ok = {
                    notice(str(S.desktop_hub_status_updated))
                    loadActionVehicles()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    private fun confirmDeleteActionVehicle(vehicle: ActionVehicle) {
        setState {
            copy(
                confirm = HubConfirm(
                    title = str(S.desktop_hub_delete_action_vehicle),
                    body = str(S.desktop_hub_delete_action_vehicle_body, vehicle.title),
                    confirmLabel = str(S.delete),
                    action = HubEvent.DeleteActionVehicleConfirmed(vehicle.id),
                ),
            )
        }
    }

    private fun deleteActionVehicle(id: String) {
        setState { copy(busyRowId = id) }
        launch {
            repository.deleteActionVehicle(id).fold(
                ok = {
                    notice(str(S.desktop_hub_av_removed))
                    setState { copy(openActionVehicle = null) }
                    loadActionVehicles()
                },
            )
            setState { copy(busyRowId = "") }
        }
    }

    /**
     * Sends a record to the people who decide on it.
     *
     * Three routes behind one picker, and they do not take the same body:
     * starting a round sends the whole approver objects, a return sends them
     * plus a note, and adding to a round in flight sends **ids only** because
     * that route resolves the names itself.
     */
    private fun submitAvApprovers() {
        val picker = currentState.avApproverPicker ?: return
        if (picker.picked.isEmpty()) {
            notice(str(S.desktop_hub_pick_an_approver), success = false)
            return
        }
        setState { copy(avApproverPicker = picker.copy(saving = true)) }
        launch {
            val approvers = picker.picked.mapNotNull { userId ->
                currentState.people.firstOrNull { it.userId == userId }?.let { person ->
                    AvApprover(
                        userId = person.userId,
                        name = person.name,
                        designation = person.designation,
                    )
                }
            }
            val result = when {
                picker.addingToExisting -> repository.editActionVehicleApprovers(
                    id = picker.vehicleId,
                    add = picker.picked,
                    returnFlow = picker.flow == AvFlow.Return,
                )

                picker.flow == AvFlow.Return -> repository.requestActionVehicleReturn(
                    id = picker.vehicleId,
                    approvers = approvers,
                    note = picker.note,
                )

                else -> repository.sendActionVehicleForApproval(picker.vehicleId, approvers)
            }
            result.fold(
                ok = {
                    setState { copy(avApproverPicker = null) }
                    notice(
                        when {
                            picker.addingToExisting -> str(S.desktop_hub_approvers_updated)
                            picker.flow == AvFlow.Return -> str(S.desktop_hub_sent_for_return)
                            else -> str(S.desktop_hub_sent_for_approval)
                        },
                    )
                    loadActionVehicles()
                },
                bad = { setState { copy(avApproverPicker = picker.copy(saving = false)) } },
            )
        }
    }

    private fun removeAvApprover(vehicleId: String, userId: String, flow: AvFlow) {
        launch {
            repository.editActionVehicleApprovers(
                id = vehicleId,
                remove = listOf(userId),
                returnFlow = flow == AvFlow.Return,
            ).fold(
                ok = {
                    notice(str(S.desktop_hub_approver_removed))
                    loadActionVehicles()
                },
            )
        }
    }

    private fun remindAvApprover(vehicleId: String, userId: String) {
        launch {
            repository.remindActionVehicleApprover(vehicleId, userId)
                .fold(ok = { notice(str(S.desktop_hub_reminder_sent)) })
        }
    }

    private fun submitAvDecision() {
        val decision = currentState.avDecision ?: return
        setState { copy(avDecision = decision.copy(saving = true)) }
        launch {
            val id = decision.vehicleId
            val result = when {
                decision.flow == AvFlow.Return && decision.isReject ->
                    repository.rejectActionVehicleReturn(id, decision.reason)

                decision.flow == AvFlow.Return -> repository.approveActionVehicleReturn(id)
                decision.isReject -> repository.rejectActionVehicle(id, decision.reason)
                else -> repository.approveActionVehicle(id)
            }
            result.fold(
                ok = {
                    setState { copy(avDecision = null) }
                    notice(
                        when {
                            decision.flow == AvFlow.Return && decision.isReject ->
                                str(S.desktop_hub_return_declined)

                            decision.flow == AvFlow.Return -> str(S.desktop_hub_return_approved)
                            decision.isReject -> str(S.desktop_hub_sent_back_to_transport)
                            else -> str(S.desktop_hub_av_approved)
                        },
                    )
                    loadActionVehicles()
                },
                bad = { setState { copy(avDecision = decision.copy(saving = false)) } },
            )
        }
    }

    private fun sendAvComment() {
        val thread = currentState.avThread ?: return
        val text = thread.draft.trim()
        if (text.isEmpty()) return
        // A finished record's thread closes with it: nothing more happens to a
        // returned or cancelled request, comments included.
        if (currentState.actionVehicle(thread.vehicleId)?.status?.isTerminal == true) {
            notice(str(S.desktop_hub_thread_closed), success = false)
            return
        }
        setState { copy(avThread = thread.copy(sending = true)) }
        launch {
            repository.addActionVehicleComment(thread.vehicleId, text).fold(
                ok = {
                    setState { copy(avThread = thread.copy(draft = "", sending = false)) }
                    loadActionVehicles()
                },
                bad = { setState { copy(avThread = thread.copy(sending = false)) } },
            )
        }
    }

    private fun submitAvCommenters() {
        val picker = currentState.avCommentPicker ?: return
        if (picker.picked.isEmpty()) {
            notice(str(S.desktop_hub_pick_someone), success = false)
            return
        }
        setState { copy(avCommentPicker = picker.copy(saving = true)) }
        launch {
            repository.sendActionVehicleForComments(
                id = picker.vehicleId,
                userIds = picker.picked,
                message = picker.message,
            ).fold(
                ok = {
                    setState { copy(avCommentPicker = null) }
                    notice(str(S.desktop_hub_thread_shared))
                    loadActionVehicles()
                },
                bad = { setState { copy(avCommentPicker = picker.copy(saving = false)) } },
            )
        }
    }

    private fun pickAvPhotos() {
        val editor = currentState.avEditor ?: return
        launch {
            host.pickAndStore(HubMediaKind.Image, multiple = true).fold(
                ok = { picked ->
                    if (picked.isEmpty()) return@fold
                    setState {
                        copy(
                            avEditor = avEditor?.let { open ->
                                open.copy(
                                    images = open.images + picked.map {
                                        PhotoSlot(attachment = it.attachment, bytes = it.bytes)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    // Trips ------------------------------------------------------------------

    private fun onTripEvent(event: HubEvent.TripEvent) {
        when (event) {
            is HubEvent.TripTabChanged -> setState { copy(tripTab = event.tab) }
            is HubEvent.TripSearchChanged -> setState { copy(tripSearch = event.query) }
            is HubEvent.TripDateChanged -> setState { copy(tripDateMs = event.dayStartMs) }
            HubEvent.NewTrip -> {
                setState { copy(tripEditor = TripEditor(date = nowMillis().toIsoDate())) }
                launch { loadAssignable() }
            }

            is HubEvent.TripEditorChanged -> setState { copy(tripEditor = event.editor) }
            HubEvent.SaveTrip -> saveTrip()
            HubEvent.CloseTripEditor -> setState { copy(tripEditor = null) }
            is HubEvent.OpenTrip -> openTrip(event.id)
            HubEvent.CloseTrip -> setState { copy(openTrip = null, openTripEvents = emptyList()) }
            is HubEvent.SetTripStatus -> setTripStatus(event.id, event.status)
            is HubEvent.RemindTrip -> remindTrip(event.id)
            is HubEvent.AssignTrip -> {
                val trip = currentState.openTrip?.takeIf { it.id == event.id }
                    ?: currentState.trips.firstOrNull { it.id == event.id }
                setState {
                    copy(
                        assignEditor = AssignEditor(
                            rideId = event.id,
                            driverId = trip?.driverId,
                            vehicleId = trip?.vehicleId,
                            isTrip = true,
                        ),
                    )
                }
                launch { loadAssignable() }
            }
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
                passengers = editor.passengers.map { it.toPassenger(editor.date) },
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

        editor.date.toEpochMillisOrNull() == null -> str(S.desktop_hub_pick_a_date)
        else -> null
    }

    /**
     * One form row as the server wants a passenger.
     *
     * The day and the `HH:mm` are joined here, which is the only place that
     * knows both: the row carries the time and the trip carries the day. A
     * blank time leaves `scheduled_pickup_time` off, and the server then
     * schedules the trip for the day itself.
     */
    private fun TripPassengerRow.toPassenger(day: String): HubPassenger = HubPassenger(
        userId = userId?.takeIf { it.isNotBlank() },
        name = name.trim(),
        contactNumber = contactNumber.trim(),
        pickupName = pickup.address,
        pickup = pickup,
        dropoff = dropoff,
        scheduledPickupTimeMs = time.trim().takeIf { it.isNotBlank() }
            ?.let { "${day}T$it".toEpochMillisOrNull() },
    )

    /**
     * Opens a trip's detail.
     *
     * The row in hand is shown at once — the web's passenger and driver
     * detail is the row itself — and only a real trip is then re-read: a ride
     * request or a shuttle run in the same list is not a `/trip/:id`, and
     * fetching one answers `trip_not_found` over a dialog that was fine.
     */
    private fun openTrip(id: String) {
        val row = currentState.trips.firstOrNull { it.id == id }
        row?.let { setState { copy(openTrip = it) } }
        if (row != null && (row.kind != TripResourceKind.Trip || row.isShuttleRun)) return
        launch {
            repository.trip(id).fold(
                ok = { trip -> setState { copy(openTrip = trip ?: openTrip) } },
                bad = { if (row == null) setState { copy(openTrip = null) } },
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
            is HubEvent.EditRide -> setState {
                copy(rideEditor = event.ride.toEditor().copy(isSelf = role != HubRole.Coordinator))
            }
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
                            approveFirst = event.ride.status == RideStatus.Pending,
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
            // Members first, then guests — a guest row carries no user id,
            // because the server refuses an empty one.
            passengers = editor.passengers + editor.guests.filter { it.name.isNotBlank() },
            stops = editor.stops,
            priority = editor.priority,
            notes = editor.notes,
            scheduledPickupTimeMs = if (editor.asSoonAsPossible) {
                nowMillis()
            } else {
                editor.scheduledPickupTimeMs
            },
            callType = when {
                // An edit sends no call type, as the web's update does not.
                editor.id != null -> null
                editor.isSelf -> RideDraft.CALL_SELF
                else -> RideDraft.CALL_COORDINATOR
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
            val result = when {
                editor.isTrip -> repository.assignTrip(editor.rideId, editor.driverId, editor.vehicleId)
                // An approve that fails stops here; its error is the one shown.
                editor.approveFirst -> repository.approveRide(editor.rideId).flatMap {
                    repository.assignRide(editor.rideId, editor.driverId, editor.vehicleId)
                }

                else -> repository.assignRide(editor.rideId, editor.driverId, editor.vehicleId)
            }
            result.fold(
                ok = {
                    setState { copy(assignEditor = null) }
                    notice(str(S.desktop_hub_ride_assigned))
                    if (editor.isTrip) {
                        loadTrips()
                        if (currentState.openTrip?.id == editor.rideId) openTrip(editor.rideId)
                    } else {
                        loadRides()
                    }
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
