package com.zillit.desktop.feature.transporthub

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDetailsPatch
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.DriverLocation
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvMy
import com.zillit.desktop.feature.transporthub.domain.AvVariant
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvDraft
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteDraft
import com.zillit.desktop.feature.transporthub.domain.ShuttleRun
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.MaintenanceDraft
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.MaintenanceType
import com.zillit.desktop.feature.transporthub.domain.RideScope
import com.zillit.desktop.feature.transporthub.domain.AvBadges
import com.zillit.desktop.feature.transporthub.domain.AvVehicleBadge
import com.zillit.desktop.feature.transporthub.domain.HubBadgeRead
import com.zillit.desktop.feature.transporthub.domain.HubHost
import com.zillit.desktop.feature.transporthub.domain.HubMediaKind
import com.zillit.desktop.feature.transporthub.domain.HubRepository
import com.zillit.desktop.feature.transporthub.domain.HubStop
import com.zillit.desktop.feature.transporthub.domain.PickedHubMedia
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentDraft
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RideDraft
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripDraft
import com.zillit.desktop.feature.transporthub.domain.TripEvent
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation
import com.zillit.desktop.feature.transporthub.domain.VehicleDraft
import com.zillit.desktop.feature.transporthub.domain.VehicleFilter
import com.zillit.desktop.feature.transporthub.domain.VehicleTypeOption
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubSection
import com.zillit.desktop.feature.transporthub.ui.AvFlow
import com.zillit.desktop.feature.transporthub.ui.AvTab
import com.zillit.desktop.feature.transporthub.ui.ShuttleTab
import com.zillit.desktop.feature.transporthub.ui.HubViewModel
import com.zillit.desktop.feature.transporthub.ui.TripPassengerRow
import com.zillit.desktop.feature.transporthub.ui.VehicleEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules the view model holds: what a save refuses, what asks first, and
 * the two places where one act has to become two calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One test per user act; the ViewModel has ninety.
class HubFlowsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val now = 1_789_000_000_000L

    // Saving a vehicle -------------------------------------------------------

    @Test
    fun `a vehicle without a plate is refused before any call`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewVehicle)
        vm.onEvent(HubEvent.VehicleEditorChanged(VehicleEditor(name = "Van", vehicleType = "Car")))
        vm.onEvent(HubEvent.SaveVehicle); runCurrent()

        assertTrue(repo.addedVehicles.isEmpty())
        // The form stays open with what was typed, so nothing is retyped.
        assertNotNull(vm.currentState.vehicleEditor)
    }

    @Test
    fun `a hired vehicle needs its owner's name and number`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewVehicle)
        vm.onEvent(
            HubEvent.VehicleEditorChanged(
                VehicleEditor(
                    name = "Van",
                    licensePlate = "MH01",
                    vehicleType = "Car",
                    isPrivate = false,
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveVehicle); runCurrent()
        assertTrue(repo.addedVehicles.isEmpty())

        // With both, it goes.
        vm.onEvent(
            HubEvent.VehicleEditorChanged(
                vm.currentState.vehicleEditor!!.copy(ownerName = "Ravi", ownerContactNumber = "900"),
            ),
        )
        vm.onEvent(HubEvent.SaveVehicle); runCurrent()
        assertEquals(1, repo.addedVehicles.size)
        assertNull(vm.currentState.vehicleEditor)
    }

    @Test
    fun `the production's own car needs no owner`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewVehicle)
        vm.onEvent(
            HubEvent.VehicleEditorChanged(
                VehicleEditor(name = "Van", licensePlate = "MH01", vehicleType = "Car"),
            ),
        )
        vm.onEvent(HubEvent.SaveVehicle); runCurrent()
        assertEquals(1, repo.addedVehicles.size)
    }

    @Test
    fun `deleting a vehicle asks first`() = runTest(dispatcher) {
        val repo = FakeHubRepository(vehicles = listOf(vehicle("v1")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteVehicle(vehicle("v1"))); runCurrent()
        assertNotNull(vm.currentState.confirm)
        assertTrue(repo.deletedVehicleIds.isEmpty())

        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()
        assertEquals(listOf("v1"), repo.deletedVehicleIds)
        assertNull(vm.currentState.confirm)
    }

    // Saving a driver --------------------------------------------------------

    @Test
    fun `a driver save refuses a blank licence but Ignore and create does not`() =
        runTest(dispatcher) {
            val repo = FakeHubRepository(
                people = listOf(ProjectPerson(userId = "u1", name = "Asha")),
            )
            val vm = viewModel(repo)
            vm.start(); runCurrent()

            vm.onEvent(HubEvent.NewDriver)
            vm.onEvent(HubEvent.PickDriverUser("u1")); runCurrent()
            vm.onEvent(HubEvent.SaveDriver); runCurrent()
            assertTrue(repo.addedDrivers.isEmpty())

            // "Ignore & create" exists to skip exactly that gate.
            vm.onEvent(HubEvent.SaveDriverIgnoringDetails); runCurrent()
            assertEquals(1, repo.addedDrivers.size)
            val saved = repo.addedDrivers.single()
            assertTrue(saved.pendingDetails)
            // Blank, but a string: the route answers 406 on anything else.
            assertEquals("", saved.phone)
            assertEquals("", saved.licenseNumber)
        }

    @Test
    fun `saving with the details pending lands on the chase-list`() = runTest(dispatcher) {
        val repo = FakeHubRepository(people = listOf(ProjectPerson(userId = "u1", name = "Asha")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.Drivers)); runCurrent()

        vm.onEvent(HubEvent.NewDriver)
        vm.onEvent(HubEvent.PickDriverUser("u1")); runCurrent()
        vm.onEvent(HubEvent.SaveDriverIgnoringDetails); runCurrent()

        // So the half-saved record is in front of them, not lost in the list.
        assertEquals(
            com.zillit.desktop.feature.transporthub.ui.DriverTab.Incomplete,
            vm.currentState.driverTab,
        )
    }

    @Test
    fun `a non-driver is locked to the temporary path`() = runTest(dispatcher) {
        val repo = FakeHubRepository(
            people = listOf(
                ProjectPerson(userId = "u1", name = "Asha", designation = "first_ad_label"),
                ProjectPerson(userId = "u2", name = "Ravi", designation = "driver_label"),
            ),
            designations = listOf("driver_label"),
        )
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewDriver)
        vm.onEvent(HubEvent.PickDriverUser("u1")); runCurrent()
        val adRow = vm.currentState.driverEditor!!
        assertTrue(adRow.isTempDriver)
        assertTrue(adRow.tempDriverLocked)

        // A real driver leaves the choice open.
        vm.onEvent(HubEvent.PickDriverUser("u2")); runCurrent()
        val driverRow = vm.currentState.driverEditor!!
        assertFalse(driverRow.isTempDriver)
        assertFalse(driverRow.tempDriverLocked)
    }

    @Test
    fun `removing a driver asks first`() = runTest(dispatcher) {
        val repo = FakeHubRepository(drivers = listOf(driver("d1")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteDriver(driver("d1"))); runCurrent()
        assertTrue(repo.deletedDriverIds.isEmpty())
        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()
        assertEquals(listOf("d1"), repo.deletedDriverIds)
    }

    @Test
    fun `completing a driver's details sends only what was filled in`() = runTest(dispatcher) {
        val repo = FakeHubRepository(drivers = listOf(driver("d1", phone = "")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.CompleteDriverDetails(driver("d1", phone = "")))
        val editor = vm.currentState.driverDetailsEditor!!
        vm.onEvent(HubEvent.DriverDetailsEditorChanged(editor.copy(phone = "9001")))
        vm.onEvent(HubEvent.SaveDriverDetails); runCurrent()

        val patch = repo.driverPatches.single().second
        assertEquals("9001", patch.phone)
        // Not sent: a patch that echoed every field back would overwrite
        // whatever another client had just changed.
        assertNull(patch.licenseFrontUrl)
    }

    // Trips ------------------------------------------------------------------

    @Test
    fun `creating a trip with a driver assigns in a second call`() = runTest(dispatcher) {
        val repo = FakeHubRepository(newTripId = "t9")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewTrip); runCurrent()
        vm.onEvent(
            HubEvent.TripEditorChanged(
                vm.currentState.tripEditor!!.copy(
                    passengers = listOf(passengerRow()),
                    driverId = "d1",
                    vehicleId = "v1",
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveTrip); runCurrent()

        assertEquals(1, repo.addedTrips.size)
        // `POST /trip` ignores the driver and answers `pending`, which hides
        // the trip from the driver's own feed — so the assignment is its own
        // call, straight after.
        assertEquals(listOf(Triple("t9", "d1", "v1" as String?)), repo.assignedTrips)
    }

    @Test
    fun `a trip created without a driver sends no assignment`() = runTest(dispatcher) {
        val repo = FakeHubRepository(newTripId = "t9")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewTrip); runCurrent()
        vm.onEvent(
            HubEvent.TripEditorChanged(
                vm.currentState.tripEditor!!.copy(passengers = listOf(passengerRow())),
            ),
        )
        vm.onEvent(HubEvent.SaveTrip); runCurrent()
        assertTrue(repo.assignedTrips.isEmpty())
    }

    @Test
    fun `a trip with no passenger is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewTrip); runCurrent()
        vm.onEvent(HubEvent.SaveTrip); runCurrent()
        assertTrue(repo.addedTrips.isEmpty())
    }

    @Test
    fun `a ride request in the trips list is cancelled, not deleted`() = runTest(dispatcher) {
        val row = Trip(id = "t1", kind = TripResourceKind.RideRequest, status = TripStatus.Assigned)
        val repo = FakeHubRepository(trips = listOf(row))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteTrip(row)); runCurrent()
        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()

        assertEquals(listOf("t1"), repo.cancelledRides.map { it.first })
        assertTrue(repo.deletedTripIds.isEmpty())
    }

    @Test
    fun `a trip the route cannot find falls back to cancelling the ride`() = runTest(dispatcher) {
        val row = Trip(id = "t1", kind = TripResourceKind.Trip, status = TripStatus.Assigned)
        // The row claims to be a trip, but the route says otherwise — which
        // is exactly what a ride request answers to `DELETE /trip/:id`.
        val repo = FakeHubRepository(trips = listOf(row), tripDeleteFails = "trip_not_found")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteTrip(row)); runCurrent()
        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()

        assertEquals(listOf("t1"), repo.deletedTripIds)
        assertEquals(listOf("t1"), repo.cancelledRides.map { it.first })
    }

    @Test
    fun `another failure on delete is not papered over with a cancel`() = runTest(dispatcher) {
        val row = Trip(id = "t1", status = TripStatus.Assigned)
        val repo = FakeHubRepository(trips = listOf(row), tripDeleteFails = "trip_locked")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteTrip(row)); runCurrent()
        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()

        assertTrue(repo.cancelledRides.isEmpty())
        assertNotNull(vm.currentState.error)
    }

    // Ride requests ----------------------------------------------------------

    @Test
    fun `a ride without both ends is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewRide)
        vm.onEvent(
            HubEvent.RideEditorChanged(
                vm.currentState.rideEditor!!.copy(
                    pickup = HubLocation(address = "Base"),
                    passengers = listOf(passenger()),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveRide); runCurrent()
        assertTrue(repo.addedRides.isEmpty())
    }

    @Test
    fun `a ride scheduled in the past is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewRide)
        vm.onEvent(
            HubEvent.RideEditorChanged(
                vm.currentState.rideEditor!!.copy(
                    pickup = HubLocation(address = "Base"),
                    dropoff = HubLocation(address = "Set"),
                    passengers = listOf(passenger()),
                    asSoonAsPossible = false,
                    // The picker defaults the day to today when only a time
                    // is set, so a past moment can slip past its own guard.
                    scheduledPickupTimeMs = now - 1,
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveRide); runCurrent()
        assertTrue(repo.addedRides.isEmpty())
    }

    @Test
    fun `as soon as possible sends now, not nothing`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewRide)
        vm.onEvent(
            HubEvent.RideEditorChanged(
                vm.currentState.rideEditor!!.copy(
                    pickup = HubLocation(address = "Base"),
                    dropoff = HubLocation(address = "Set"),
                    passengers = listOf(passenger()),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveRide); runCurrent()
        assertEquals(now, repo.addedRides.single().scheduledPickupTimeMs)
    }

    @Test
    fun `declining a ride sends the typed reason, or a default`() = runTest(dispatcher) {
        val repo = FakeHubRepository(rides = listOf(ride("r1")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.RejectRide(ride("r1")))
        vm.onEvent(HubEvent.SubmitReason); runCurrent()
        // Blank is allowed, but something is always sent — the server stores
        // it, and a row with no reason is unanswerable later.
        assertTrue(repo.rejectedRides.single().second.isNotBlank())
    }

    @Test
    fun `a passenger added without their own ends rides the request's`() = runTest(dispatcher) {
        val request = ride("r1").copy(
            pickup = HubLocation(address = "Base", lat = 1.0, long = 2.0),
            dropoff = HubLocation(address = "Set", lat = 3.0, long = 4.0),
        )
        val repo = FakeHubRepository(rides = listOf(request))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.RideRequests)); runCurrent()

        vm.onEvent(HubEvent.AddRidePassenger(request))
        vm.onEvent(
            HubEvent.RidePassengerEditorChanged(
                vm.currentState.ridePassengerEditor!!.copy(name = "Kim"),
            ),
        )
        vm.onEvent(HubEvent.SubmitRidePassenger); runCurrent()

        val added = repo.addedPassengers.single().second
        assertEquals("Base", added.pickup.address)
        assertEquals("Set", added.dropoff.address)
    }

    @Test
    fun `a stop with no address is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository(rides = listOf(ride("r1")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AddRideStop(ride("r1")))
        vm.onEvent(HubEvent.SubmitStop); runCurrent()
        assertTrue(repo.addedStops.isEmpty())
    }

    @Test
    fun `assigning a ride needs a driver`() = runTest(dispatcher) {
        val repo = FakeHubRepository(rides = listOf(ride("r1", RideStatus.Approved)))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AssignRide(ride("r1", RideStatus.Approved))); runCurrent()
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()
        assertTrue(repo.assignedRides.isEmpty())

        vm.onEvent(
            HubEvent.AssignEditorChanged(vm.currentState.assignEditor!!.copy(driverId = "d1")),
        )
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()
        assertEquals(1, repo.assignedRides.size)
    }

    // Allocations ------------------------------------------------------------

    @Test
    fun `an allocation needs both ends, a driver, a vehicle and some days`() =
        runTest(dispatcher) {
            val repo = FakeHubRepository()
            val vm = viewModel(repo)
            vm.start(); runCurrent()

            vm.onEvent(HubEvent.NewAllocation); runCurrent()
            vm.onEvent(HubEvent.SaveAllocation); runCurrent()
            assertTrue(repo.addedAllocations.isEmpty())

            vm.onEvent(
                HubEvent.AllocationEditorChanged(
                    vm.currentState.allocationEditor!!.copy(
                        pickup = HubLocation(address = "Base"),
                        dropoff = HubLocation(address = "Set"),
                        driverId = "d1",
                        vehicleId = "v1",
                    ),
                ),
            )
            vm.onEvent(HubEvent.SaveAllocation); runCurrent()
            assertEquals(1, repo.addedAllocations.size)
        }

    @Test
    fun `closing a half-filled allocation offers to keep it`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewAllocation); runCurrent()
        // Nothing typed: closing just closes.
        vm.onEvent(HubEvent.RequestCloseAllocation)
        assertNull(vm.currentState.allocationEditor)
        assertNull(vm.currentState.draftPrompt)

        vm.onEvent(HubEvent.NewAllocation); runCurrent()
        vm.onEvent(
            HubEvent.AllocationEditorChanged(
                vm.currentState.allocationEditor!!.copy(title = "Cast run"),
            ),
        )
        vm.onEvent(HubEvent.RequestCloseAllocation)
        assertNotNull(vm.currentState.draftPrompt)

        // A draft is allowed to be partial, so it skips the validation above.
        vm.onEvent(HubEvent.SaveAllocationAsDraft); runCurrent()
        assertEquals(1, repo.addedAllocations.size)
        assertEquals("Cast run", repo.addedAllocations.single().title)
    }

    @Test
    fun `editing an allocation is never offered as a draft`() = runTest(dispatcher) {
        val existing = PermanentTrip(id = "p1", title = "Cast run", status = "active")
        val repo = FakeHubRepository(allocations = listOf(existing))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.EditAllocation(existing)); runCurrent()
        vm.onEvent(HubEvent.RequestCloseAllocation)
        assertNull(vm.currentState.draftPrompt)
        assertNull(vm.currentState.allocationEditor)
    }

    @Test
    fun `promoting a draft re-sends the record, not the activate shortcut`() =
        runTest(dispatcher) {
            val draft = PermanentTrip(id = "p1", status = "draft", tripStatus = "permanent")
            val repo = FakeHubRepository(allocations = listOf(draft))
            val vm = viewModel(repo)
            vm.start(); runCurrent()

            vm.onEvent(HubEvent.ToggleAllocationState(draft)); runCurrent()
            // The shortcut skips the validator and would flip an unfinished
            // draft's runtime status while leaving it unfit to run.
            assertEquals(listOf("p1"), repo.promotedDrafts)
            assertTrue(repo.resumed.isEmpty())
        }

    @Test
    fun `pausing and resuming use their own routes`() = runTest(dispatcher) {
        val paused = PermanentTrip(id = "p1", status = "active", tripStatus = "paused")
        val active = PermanentTrip(id = "p2", status = "active", tripStatus = "active")
        val repo = FakeHubRepository(allocations = listOf(paused, active))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.ToggleAllocationState(paused)); runCurrent()
        assertEquals(listOf("p1"), repo.resumed)

        vm.onEvent(HubEvent.ToggleAllocationState(active)); runCurrent()
        assertEquals(listOf("p2"), repo.pausedIds)
    }

    @Test
    fun `a swap needs something to swap to`() = runTest(dispatcher) {
        val trip = PermanentTrip(id = "p1", status = "active")
        val repo = FakeHubRepository(allocations = listOf(trip))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.SwapAllocationDriver(trip)); runCurrent()
        vm.onEvent(HubEvent.SubmitSwap); runCurrent()
        assertTrue(repo.driverSwaps.isEmpty())

        vm.onEvent(HubEvent.SwapEditorChanged(vm.currentState.swapEditor!!.copy(pickedId = "d2")))
        vm.onEvent(HubEvent.SubmitSwap); runCurrent()
        assertEquals(listOf("p1" to "d2"), repo.driverSwaps.map { it.first to it.second })
    }

    @Test
    fun `deleting an allocation asks first`() = runTest(dispatcher) {
        val trip = PermanentTrip(id = "p1", status = "active")
        val repo = FakeHubRepository(allocations = listOf(trip))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DeleteAllocation(trip)); runCurrent()
        assertTrue(repo.deletedAllocationIds.isEmpty())
        vm.onEvent(HubEvent.Confirmed(vm.currentState.confirm!!.action)); runCurrent()
        assertEquals(listOf("p1"), repo.deletedAllocationIds)
    }

    // Loading and scope ------------------------------------------------------

    @Test
    fun `a driver's assigned rides are scoped to their driver record`() = runTest(dispatcher) {
        val repo = FakeHubRepository(
            drivers = listOf(driver("d7", userId = "u3")),
            designations = listOf("unit_driver_label"),
        )
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = {
                HubViewer(
                    userId = "u3",
                    canView = true,
                    designation = "unit_driver_label",
                    driverDesignations = listOf("unit_driver_label"),
                )
            },
            nowMillis = { now },
        )
        vm.start(); runCurrent()
        repo.rideScopes.clear()
        vm.onEvent(HubEvent.SelectSection(HubSection.AssignedRides)); runCurrent()
        // `me=true` here would answer the rides this driver *asked for*, not
        // the ones they are to drive.
        assertEquals(listOf(RideScope.ForDriver("d7")), repo.rideScopes.distinct())
    }

    @Test
    fun `a driver's own requests are scoped to them, not to their driver record`() =
        runTest(dispatcher) {
            val repo = FakeHubRepository(
            drivers = listOf(driver("d7", userId = "u3")),
            designations = listOf("unit_driver_label"),
        )
            val vm = HubViewModel(
                repository = repo,
                resolveViewer = {
                    HubViewer(
                        userId = "u3",
                        canView = true,
                        designation = "unit_driver_label",
                        driverDesignations = listOf("unit_driver_label"),
                    )
                },
                nowMillis = { now },
            )
            vm.start(); runCurrent()
            repo.rideScopes.clear()
            vm.onEvent(HubEvent.SelectSection(HubSection.MyRequests)); runCurrent()
            assertEquals(listOf(RideScope.Mine), repo.rideScopes.distinct())
        }

    @Test
    fun `a driver's trips are scoped to their driver record`() = runTest(dispatcher) {
        val repo = FakeHubRepository(
            drivers = listOf(driver("d7", userId = "u3")),
            designations = listOf("unit_driver_label"),
        )
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = {
                HubViewer(
                    userId = "u3",
                    canView = true,
                    designation = "unit_driver_label",
                    driverDesignations = listOf("unit_driver_label"),
                )
            },
            nowMillis = { now },
        )
        vm.start(); runCurrent()
        // Without the parameter the route answers the production's whole list.
        assertEquals(listOf("d7"), repo.tripScopes.filterNotNull().distinct())
    }

    @Test
    fun `a coordinator's trips are not scoped`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.Trips)); runCurrent()
        assertTrue(repo.tripScopes.all { it == null })
    }

    // Shuttles ---------------------------------------------------------------

    @Test
    fun `a route's name is the two ends joined`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewShuttleRoute); runCurrent()
        vm.onEvent(
            HubEvent.RouteEditorChanged(
                vm.currentState.routeEditor!!.copy(
                    from = "Base Camp",
                    to = "Unit Base",
                    stops = listOf(ShuttleStop(name = "Gate 3")),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveShuttleRoute); runCurrent()

        // The server stores one string; nobody should have to type the arrow.
        assertEquals("Base Camp → Unit Base", repo.addedRoutes.single().name)
    }

    @Test
    fun `a route with no stop at all is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.NewShuttleRoute); runCurrent()
        vm.onEvent(
            HubEvent.RouteEditorChanged(
                vm.currentState.routeEditor!!.copy(from = "A", to = "B", stops = emptyList()),
            ),
        )
        vm.onEvent(HubEvent.SaveShuttleRoute); runCurrent()
        assertTrue(repo.addedRoutes.isEmpty())
    }

    @Test
    fun `runs are refused for a route with no driver or vehicle`() = runTest(dispatcher) {
        val bare = ShuttleRoute(id = "r1", name = "Base → Set")
        val repo = FakeHubRepository(shuttleRoutes = listOf(bare))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.Shuttles)); runCurrent()

        vm.onEvent(HubEvent.GenerateShuttleRuns(bare)); runCurrent()
        // The server would otherwise spawn runs nobody can drive, and nothing
        // can be done with those afterwards.
        assertTrue(repo.generatedRuns.isEmpty())

        val staffed = bare.copy(driverId = "d1", vehicleId = "v1")
        vm.onEvent(HubEvent.GenerateShuttleRuns(staffed)); runCurrent()
        assertEquals(listOf("r1"), repo.generatedRuns)
        // The web moves to the runs tab so what was just created is on screen.
        assertEquals(ShuttleTab.Runs, vm.currentState.shuttleTab)
    }

    @Test
    fun `disabling a route dims it before the server answers`() = runTest(dispatcher) {
        val route = ShuttleRoute(id = "r1", name = "Base → Set", isActive = true)
        val repo = FakeHubRepository(shuttleRoutes = listOf(route))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.Shuttles)); runCurrent()

        vm.onEvent(HubEvent.ToggleShuttleRoute(route))
        // Optimistic: no full-page spinner for a one-field write.
        assertFalse(vm.currentState.shuttleRoutes.single().isActive)
        runCurrent()
        assertEquals(listOf("r1" to false), repo.routeToggles)
    }

    @Test
    fun `a run only offers the transitions its status allows`() {
        assertEquals(
            listOf(ShuttleRunStatus.Boarding, ShuttleRunStatus.Cancelled),
            ShuttleRunStatus.Scheduled.transitions,
        )
        assertEquals(listOf(ShuttleRunStatus.InTransit), ShuttleRunStatus.Boarding.transitions)
        assertEquals(listOf(ShuttleRunStatus.Completed), ShuttleRunStatus.InTransit.transitions)
        // `departed` is the server's own step; a coordinator never drives it.
        assertTrue(ShuttleRunStatus.Departed.transitions.isEmpty())
        assertTrue(ShuttleRunStatus.Completed.transitions.isEmpty())
    }

    // Action vehicles --------------------------------------------------------

    @Test
    fun `a request sends its options and no sourced car`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewActionVehicleRequest); runCurrent()
        vm.onEvent(
            HubEvent.AvEditorChanged(
                vm.currentState.avEditor!!.copy(
                    displayName = "Hero Car #1",
                    variants = listOf(AvVariant(make = "Ford", model = "Mustang", year = 1968)),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()

        val draft = repo.requestedVehicles.single()
        assertTrue(draft.isRequesterSubmit)
        assertEquals(1, draft.variants.size)
        // The scalar car fields belong to the sourcing; an array on /source 406s.
        assertEquals("", draft.make)
        // The reminder cron needs the requester's own zone, or it goes out in UTC.
        assertTrue(repo.timezones.single().isNotEmpty())
    }

    @Test
    fun `a request with no option at all is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.NewActionVehicleRequest); runCurrent()
        vm.onEvent(
            HubEvent.AvEditorChanged(vm.currentState.avEditor!!.copy(displayName = "Hero Car")),
        )
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()
        // The server answers `variants required`; the form says so first.
        assertTrue(repo.requestedVehicles.isEmpty())
    }

    @Test
    fun `sourcing sends the one car found`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(
            id = "a1",
            displayName = "Hero Car #1",
            status = AvStatus.Requested,
        )
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.SourceActionVehicle(vehicle)); runCurrent()
        vm.onEvent(
            HubEvent.AvEditorChanged(
                vm.currentState.avEditor!!.copy(
                    make = "Ford",
                    model = "Mustang",
                    ownerName = "City Classics",
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()

        val (id, draft) = repo.sourcedVehicles.single()
        assertEquals("a1", id)
        assertFalse(draft.isRequesterSubmit)
        assertEquals("Ford", draft.make)
    }

    @Test
    fun `sourcing a hired car without an owner is refused`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(id = "a1", displayName = "Hero Car", status = AvStatus.Requested)
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SourceActionVehicle(vehicle)); runCurrent()
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()
        assertTrue(repo.sourcedVehicles.isEmpty())
    }

    @Test
    fun `a finalized record sends only its two dates`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(
            id = "a1",
            displayName = "Hero Car",
            status = AvStatus.OnSet,
            make = "Ford",
            ownerName = "City Classics",
        )
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.EditActionVehicle(vehicle)); runCurrent()
        // The car and the money are agreed; only a shoot day still moves.
        assertTrue(vm.currentState.avEditor!!.datesOnly)
        vm.onEvent(
            HubEvent.AvEditorChanged(
                vm.currentState.avEditor!!.copy(needShootingDate = "2026-12-01"),
            ),
        )
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()

        val (id, _, datesOnly) = repo.updatedVehicles.single()
        assertEquals("a1", id)
        assertTrue(datesOnly)
    }

    @Test
    fun `editing an unsourced record still sends the requester's shape`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(
            id = "a1",
            displayName = "Hero Car",
            status = AvStatus.Rejected,
            variants = listOf(AvVariant(make = "Ford", model = "Mustang")),
        )
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.EditActionVehicle(vehicle)); runCurrent()
        vm.onEvent(HubEvent.SaveActionVehicle); runCurrent()

        // Nobody has put a car against it yet, so the options still travel —
        // the sourced shape would drop them and the server refuses a request
        // with no variants.
        val (_, draft, _) = repo.updatedVehicles.single()
        assertTrue(draft.isRequesterSubmit)
        assertEquals(1, draft.variants.size)
    }

    @Test
    fun `the register opens on the first tab the viewer has`() = runTest(dispatcher) {
        val mine = ActionVehicle(
            id = "a1",
            status = AvStatus.PendingApproval,
            my = AvMy(isApprover = true),
        )
        val repo = FakeHubRepository(actionVehicles = listOf(mine))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.ActionVehicles)); runCurrent()
        assertEquals(AvTab.Request, vm.currentState.avTab)

        // A tab somebody picked is theirs, and a reload does not move it.
        vm.onEvent(HubEvent.AvTabChanged(AvTab.Pending)); runCurrent()
        vm.onEvent(HubEvent.Refresh); runCurrent()
        assertEquals(AvTab.Pending, vm.currentState.avTab)
    }

    @Test
    fun `an approver's decision goes to the round it was opened for`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(
            id = "a1",
            status = AvStatus.ReturnPending,
            my = AvMy(isReturnApprover = true),
        )
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.DecideActionVehicle(vehicle, AvFlow.Return, isReject = true))
        vm.onEvent(
            HubEvent.AvDecisionChanged(vm.currentState.avDecision!!.copy(reason = "Still needed")),
        )
        vm.onEvent(HubEvent.SubmitAvDecision); runCurrent()

        // The forward route would decline the request, not the return.
        assertEquals(listOf(Triple("a1", "reject-return", "Still needed")), repo.decisions)
    }

    @Test
    fun `adding approvers to a live round sends ids, not objects`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(id = "a1", status = AvStatus.PendingApproval)
        val repo = FakeHubRepository(
            actionVehicles = listOf(vehicle),
            people = listOf(ProjectPerson(userId = "u7", name = "Dana")),
        )
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.PickAvApprovers(vehicle, AvFlow.Approval, addingToExisting = true))
        runCurrent()
        vm.onEvent(
            HubEvent.AvApproverPickerChanged(
                vm.currentState.avApproverPicker!!.copy(picked = listOf("u7")),
            ),
        )
        vm.onEvent(HubEvent.SubmitAvApprovers); runCurrent()

        // This route resolves names from project membership itself.
        val edit = repo.approverEdits.single()
        assertEquals(listOf("u7"), edit.add)
        assertFalse(edit.returnFlow)
        assertTrue(repo.sentForApproval.isEmpty())
    }

    @Test
    fun `a finished record takes no more comments`() = runTest(dispatcher) {
        val vehicle = ActionVehicle(id = "a1", status = AvStatus.Returned)
        val repo = FakeHubRepository(actionVehicles = listOf(vehicle))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.ActionVehicles)); runCurrent()

        vm.onEvent(HubEvent.OpenAvThread("a1"))
        vm.onEvent(HubEvent.AvThreadChanged(vm.currentState.avThread!!.copy(draft = "Hello")))
        vm.onEvent(HubEvent.SendAvComment); runCurrent()
        // Nothing more happens to a returned request, its thread included.
        assertTrue(repo.comments.isEmpty())
    }

    @Test
    fun `the tabs are the union of what the viewer may do`() = runTest(dispatcher) {
        val mine = ActionVehicle(
            id = "a1",
            status = AvStatus.PendingApproval,
            my = AvMy(isApprover = true),
        )
        val repo = FakeHubRepository(actionVehicles = listOf(mine))
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.ActionVehicles)); runCurrent()

        // A coordinator holds posting rights, so they may both request and
        // source — and this record makes them an approver too.
        assertEquals(
            listOf(AvTab.Request, AvTab.Identify, AvTab.InProgress, AvTab.Pending, AvTab.Finalized),
            vm.currentState.actionVehicleTabs,
        )
    }

    // Maintenance ------------------------------------------------------------

    @Test
    fun `a maintenance booking sends the day the validator wants`() = runTest(dispatcher) {
        val repo = FakeHubRepository(vehicles = listOf(vehicle("v1")))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewMaintenance); runCurrent()
        vm.onEvent(
            HubEvent.MaintenanceEditorChanged(
                vm.currentState.maintenanceEditor!!.copy(
                    vehicleId = "v1",
                    type = MaintenanceType.BrakeCheck,
                    estimatedCost = "1200",
                    odometerReading = "48000",
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveMaintenance); runCurrent()

        val draft = repo.addedMaintenance.single()
        assertEquals("v1", draft.vehicleId)
        assertEquals(MaintenanceType.BrakeCheck, draft.type)
        assertEquals(1200.0, draft.estimatedCost)
        assertEquals(48_000L, draft.odometerReading)
        assertTrue(draft.scheduledDateMs > 0)
    }

    @Test
    fun `a maintenance booking without a vehicle is refused`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.NewMaintenance); runCurrent()
        vm.onEvent(HubEvent.SaveMaintenance); runCurrent()
        assertTrue(repo.addedMaintenance.isEmpty())
    }

    // Trip form --------------------------------------------------------------

    @Test
    fun `a passenger's pickup time folds into the trip's own day`() = runTest(dispatcher) {
        val repo = FakeHubRepository(newTripId = "t9")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewTrip); runCurrent()
        vm.onEvent(
            HubEvent.TripEditorChanged(
                vm.currentState.tripEditor!!.copy(
                    date = "2026-11-02",
                    passengers = listOf(passengerRow().copy(time = "07:30")),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveTrip); runCurrent()

        val sent = repo.addedTrips.single().passengers.single()
        assertEquals("u5", sent.userId)
        // The day comes from the form's date and the time from the row; a
        // blank time leaves the field off entirely.
        assertTrue((sent.scheduledPickupTimeMs ?: 0) > 0)
    }

    @Test
    fun `a guest passenger carries no user id`() = runTest(dispatcher) {
        val repo = FakeHubRepository(newTripId = "t9")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.NewTrip); runCurrent()
        vm.onEvent(
            HubEvent.TripEditorChanged(
                vm.currentState.tripEditor!!.copy(
                    passengers = listOf(passengerRow().copy(userId = null, name = "Visitor")),
                ),
            ),
        )
        vm.onEvent(HubEvent.SaveTrip); runCurrent()

        // A blank `user_id` makes the required-field validator refuse the trip.
        assertNull(repo.addedTrips.single().passengers.single().userId)
    }

    @Test
    fun `assigning from the trip detail goes to the trip route`() = runTest(dispatcher) {
        val trip = Trip(id = "t1", status = TripStatus.Pending)
        val repo = FakeHubRepository(
            trips = listOf(trip),
            drivers = listOf(driver("d1")),
        )
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AssignTrip("t1")); runCurrent()
        vm.onEvent(
            HubEvent.AssignEditorChanged(vm.currentState.assignEditor!!.copy(driverId = "d1")),
        )
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()

        // The ride route would answer `ride_request_not_found` for a trip id.
        assertEquals(listOf(Triple("t1", "d1", null as String?)), repo.assignedTrips)
    }


    @Test
    fun `a passenger's ride list is scoped to their own`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = { HubViewer(userId = "u2", canView = true) },
            nowMillis = { now },
        )
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.MyRides)); runCurrent()
        // Without the scope the server answers a coordinator's whole list.
        assertEquals(listOf(RideScope.Mine), repo.rideScopes.distinct())
    }

    @Test
    fun `a coordinator's ride list is not scoped`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.RideRequests)); runCurrent()
        assertEquals(listOf(RideScope.All), repo.rideScopes.distinct())
    }

    @Test
    fun `opening a section marks its bucket read`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val seen = mutableListOf<String>()
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = { HubViewer(userId = "u1", canView = true, canPost = true) },
            nowMillis = { now },
            onSectionViewed = { bucket -> seen += bucket.name },
        )
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.Fleet)); runCurrent()
        assertEquals(listOf("Fleet"), seen)
    }

    // Badge reads ------------------------------------------------------------

    private fun badgeViewModel(
        repo: HubRepository,
        viewer: HubViewer,
        seen: MutableList<String>,
        reads: MutableList<HubBadgeRead>,
        host: HubHost = HubHost.None,
    ) = HubViewModel(
        repository = repo,
        resolveViewer = { viewer },
        nowMillis = { now },
        onSectionViewed = { bucket -> seen += bucket.name },
        onBadgeRead = { read -> reads += read },
        host = host,
    )

    @Test
    fun `the section the hub lands on is read`() = runTest(dispatcher) {
        val seen = mutableListOf<String>()
        val vm = badgeViewModel(FakeHubRepository(), passengerViewer, seen, mutableListOf())
        vm.start(); runCurrent()
        // Nobody picks the landing section, so it is read on landing.
        assertEquals(listOf("MyTrips"), seen)

        vm.onEvent(HubEvent.SelectSection(HubSection.MyRides)); runCurrent()
        // A request that became a trip finishes on the trip unit.
        assertEquals(listOf("MyTrips", "Rides", "MyTrips"), seen)
    }

    @Test
    fun `action vehicles read the tab, the record and the thread, never the unit`() =
        runTest(dispatcher) {
            val mine = ActionVehicle(id = "a1", status = AvStatus.PendingApproval, my = AvMy(isApprover = true))
            val seen = mutableListOf<String>()
            val reads = mutableListOf<HubBadgeRead>()
            val vm = badgeViewModel(FakeHubRepository(actionVehicles = listOf(mine)), coordinatorViewer, seen, reads)
            vm.start(); runCurrent()
            vm.onEvent(HubEvent.SelectSection(HubSection.ActionVehicles)); runCurrent()
            assertTrue(seen.isEmpty())
            assertEquals(listOf<HubBadgeRead>(HubBadgeRead.ActionVehicleTab("requested_vehicles")), reads)

            // A reload that settles on the same tab is not a second read.
            vm.onEvent(HubEvent.Refresh); runCurrent()
            assertEquals(1, reads.size)

            vm.onEvent(HubEvent.AvTabChanged(AvTab.Pending)); runCurrent()
            vm.onEvent(HubEvent.OpenActionVehicle("a1")); runCurrent()
            vm.onEvent(HubEvent.OpenAvThread("a1")); runCurrent()
            assertEquals(
                listOf(
                    HubBadgeRead.ActionVehicleTab("pending_approval"),
                    HubBadgeRead.ActionVehicleRecord("pending_approval", "a1"),
                    HubBadgeRead.ActionVehicleComments("a1"),
                ),
                reads.drop(1),
            )
            assertTrue(seen.isEmpty())
        }

    @Test
    fun `opening a trip reads that trip`() = runTest(dispatcher) {
        val reads = mutableListOf<HubBadgeRead>()
        val vm = badgeViewModel(FakeHubRepository(), coordinatorViewer, mutableListOf(), reads)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.OpenTrip("t1")); runCurrent()
        assertEquals(listOf<HubBadgeRead>(HubBadgeRead.TripRecord("t1")), reads)
    }

    @Test
    fun `a rise on the open section is read only while the window is in front`() =
        runTest(dispatcher) {
            val host = BadgeHost()
            val seen = mutableListOf<String>()
            val vm = badgeViewModel(FakeHubRepository(), passengerViewer, seen, mutableListOf(), host)
            vm.start(); runCurrent()
            seen.clear()

            // Behind another window: the row waits.
            host.counts.value = mapOf("MyTrips" to 1); runCurrent()
            assertTrue(seen.isEmpty())

            // Brought to the front: what counts on the open section is read.
            vm.onEvent(HubEvent.WindowFocus(true)); runCurrent()
            assertEquals(listOf("MyTrips"), seen)

            // And a row landing while somebody looks is read as it lands.
            host.counts.value = mapOf("MyTrips" to 2); runCurrent()
            assertEquals(listOf("MyTrips", "MyTrips"), seen)

            // A fall — the read's own echo — is not a trigger, nor is another section's rise.
            host.counts.value = mapOf("Rides" to 3); runCurrent()
            assertEquals(2, seen.size)
        }

    @Test
    fun `a comment landing on the open thread is read`() = runTest(dispatcher) {
        val host = BadgeHost()
        val vehicle = ActionVehicle(id = "a1", status = AvStatus.Requested)
        val reads = mutableListOf<HubBadgeRead>()
        val vm = badgeViewModel(
            FakeHubRepository(actionVehicles = listOf(vehicle)),
            coordinatorViewer,
            mutableListOf(),
            reads,
            host,
        )
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.WindowFocus(true))
        vm.onEvent(HubEvent.OpenAvThread("a1")); runCurrent()
        reads.clear()

        host.av.value = AvBadges(vehicles = mapOf("a1" to AvVehicleBadge(total = 1, comment = 1))); runCurrent()
        assertEquals(listOf<HubBadgeRead>(HubBadgeRead.ActionVehicleComments("a1")), reads)
        assertEquals(1, vm.currentState.avBadges.vehicle("a1").comment)
    }

    @Test
    fun `opening a section loads only that section`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        val before = repo.vehicleLoads
        vm.onEvent(HubEvent.SelectSection(HubSection.Shuttles)); runCurrent()
        assertEquals(HubSection.Shuttles, vm.currentState.section)
        // Shuttles has its own two reads; the fleet is not one of them.
        assertEquals(before, repo.vehicleLoads)
    }

    @Test
    fun `a viewer with no rights loads nothing`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = { HubViewer(userId = "u1") },
            nowMillis = { now },
        )
        vm.start(); runCurrent()
        assertEquals(0, repo.vehicleLoads)
    }

    @Test
    fun `the temporary-driver flag comes off the driver list and moves the rail`() =
        runTest(dispatcher) {
            val repo = FakeHubRepository(
                drivers = listOf(driver("d1", userId = "u2", temp = true)),
            )
            val vm = HubViewModel(
                repository = repo,
                resolveViewer = { HubViewer(userId = "u2", canView = true) },
                nowMillis = { now },
            )
            vm.start(); runCurrent()
            // A passenger a moment ago; a driver once the list landed, and the
            // rail must have moved with them.
            assertTrue(vm.currentState.viewer.isTempDriver)
            assertTrue(vm.currentState.section in vm.currentState.rail)
        }

    // Fixtures ---------------------------------------------------------------

    private fun viewModel(repo: HubRepository) = HubViewModel(
        repository = repo,
        resolveViewer = { HubViewer(userId = "u1", canView = true, canPost = true) },
        nowMillis = { now },
    )

    private val coordinatorViewer = HubViewer(userId = "u1", canView = true, canPost = true)
    private val passengerViewer = HubViewer(userId = "u2", canView = true)

    /** A host whose ledger the test moves by hand. */
    private class BadgeHost : HubHost {
        val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val av = MutableStateFlow(AvBadges.Empty)
        override val badges: Flow<Map<String, Int>> = counts
        override val actionVehicleBadges: Flow<AvBadges> = av
        override suspend fun pickAndStore(
            kind: HubMediaKind,
            multiple: Boolean,
        ): ZillitResult<List<PickedHubMedia>> = ZillitResult.Success(emptyList())
    }

    private fun vehicle(id: String) = Vehicle(id = id, name = "Van", licensePlate = "MH01")

    private fun driver(
        id: String,
        userId: String? = null,
        phone: String = "900",
        temp: Boolean = false,
    ) = Driver(
        id = id,
        userId = userId,
        fullName = "Asha",
        phone = phone,
        licenseNumber = "L1",
        isTempDriver = temp,
    )

    private fun ride(id: String, status: RideStatus = RideStatus.Pending) =
        RideRequest(id = id, status = status)

    /** A New Trip form row — a project member with both ends filled in. */
    private fun passengerRow() = TripPassengerRow(
        userId = "u5",
        name = "Kim",
        pickup = HubLocation(address = "Base"),
        dropoff = HubLocation(address = "Set"),
    )

    private fun passenger() = HubPassenger(
        userId = "u5",
        name = "Kim",
        pickup = HubLocation(address = "Base"),
        dropoff = HubLocation(address = "Set"),
    )

    @Test
    fun `approve and assign approves a pending ride before assigning it`() = runTest(dispatcher) {
        val ride = RideRequest(id = "r1", status = RideStatus.Pending)
        val repo = FakeHubRepository(rides = listOf(ride))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AssignRide(ride)); runCurrent()
        vm.onEvent(HubEvent.AssignEditorChanged(vm.currentState.assignEditor!!.copy(driverId = "d1")))
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()

        // The web's order: the server assigns only an approved request.
        assertEquals(listOf("approve:r1", "assign:r1"), repo.rideCalls)
        assertNull(vm.currentState.assignEditor)
    }

    @Test
    fun `assigning an approved ride sends no second approve`() = runTest(dispatcher) {
        val ride = RideRequest(id = "r1", status = RideStatus.Approved)
        val repo = FakeHubRepository(rides = listOf(ride))
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AssignRide(ride)); runCurrent()
        vm.onEvent(HubEvent.AssignEditorChanged(vm.currentState.assignEditor!!.copy(driverId = "d1")))
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()

        assertEquals(listOf("assign:r1"), repo.rideCalls)
    }

    @Test
    fun `a refused approve assigns nothing and keeps the picker open`() = runTest(dispatcher) {
        val ride = RideRequest(id = "r1", status = RideStatus.Pending)
        val repo = FakeHubRepository(rides = listOf(ride), rideApproveFails = "ride_request_not_pending")
        val vm = viewModel(repo)
        vm.start(); runCurrent()

        vm.onEvent(HubEvent.AssignRide(ride)); runCurrent()
        vm.onEvent(HubEvent.AssignEditorChanged(vm.currentState.assignEditor!!.copy(driverId = "d1")))
        vm.onEvent(HubEvent.SubmitAssign); runCurrent()

        assertEquals(listOf("approve:r1"), repo.rideCalls)
        // Still open with the driver picked, so a retry is one click.
        assertEquals("d1", vm.currentState.assignEditor?.driverId)
    }
}

/**
 * A repository that records what it was asked to do.
 *
 * Spelled out rather than mocked so each test reads as the sequence of calls
 * one act really makes — which is the point of several of them: a trip create
 * that must be followed by an assign, a trip delete that may have to fall back
 * to a ride cancel.
 */
// It implements the whole repository, and takes one seed list per entity the
// hub reads — a bundle would only move the same eleven somewhere else.
@Suppress("TooManyFunctions", "LongParameterList")
private class FakeHubRepository(
    private val vehicles: List<Vehicle> = emptyList(),
    private val drivers: List<Driver> = emptyList(),
    private val trips: List<Trip> = emptyList(),
    private val rides: List<RideRequest> = emptyList(),
    private val allocations: List<PermanentTrip> = emptyList(),
    private val people: List<ProjectPerson> = emptyList(),
    private val designations: List<String> = emptyList(),
    private val newTripId: String? = null,
    private val maintenance: List<MaintenanceRecord> = emptyList(),
    private val driverLocations: List<DriverLocation> = emptyList(),
    private val shuttleRoutes: List<ShuttleRoute> = emptyList(),
    private val shuttleRuns: List<ShuttleRun> = emptyList(),
    private val actionVehicles: List<ActionVehicle> = emptyList(),
    /** A server message the trip delete fails with, if any. */
    private val tripDeleteFails: String? = null,
    /** A server message a ride approve fails with, if any. */
    private val rideApproveFails: String? = null,
) : HubRepository {

    /** Approves and assigns of rides, in the order they were sent. */
    val rideCalls = mutableListOf<String>()

    var vehicleLoads = 0
    val rideScopes = mutableListOf<RideScope>()
    val tripScopes = mutableListOf<String?>()
    val addedMaintenance = mutableListOf<MaintenanceDraft>()
    val completedMaintenance = mutableListOf<String>()
    val deletedMaintenance = mutableListOf<String>()
    val addedRoutes = mutableListOf<ShuttleRouteDraft>()
    val updatedRoutes = mutableListOf<Pair<String, ShuttleRouteDraft>>()
    val routeToggles = mutableListOf<Pair<String, Boolean>>()
    val deletedRoutes = mutableListOf<String>()
    val generatedRuns = mutableListOf<String>()
    val runStatuses = mutableListOf<Pair<String, ShuttleRunStatus>>()
    val requestedVehicles = mutableListOf<AvDraft>()
    val timezones = mutableListOf<String>()
    val sourcedVehicles = mutableListOf<Pair<String, AvDraft>>()
    val updatedVehicles = mutableListOf<Triple<String, AvDraft, Boolean>>()
    val avStatuses = mutableListOf<Pair<String, AvStatus>>()
    val deletedVehicles = mutableListOf<String>()
    val sentForApproval = mutableListOf<Pair<String, List<AvApprover>>>()
    val decisions = mutableListOf<Triple<String, String, String>>()
    val returnRequests = mutableListOf<Triple<String, List<AvApprover>, String>>()
    val approverEdits = mutableListOf<ApproverEdit>()
    val reminders = mutableListOf<Pair<String, String>>()
    val comments = mutableListOf<Pair<String, String>>()
    val commentInvites = mutableListOf<Triple<String, List<String>, String>>()
    val addedVehicles = mutableListOf<VehicleDraft>()
    val deletedVehicleIds = mutableListOf<String>()
    val addedDrivers = mutableListOf<DriverDraft>()
    val deletedDriverIds = mutableListOf<String>()
    val driverPatches = mutableListOf<Pair<String, DriverDetailsPatch>>()
    val addedTrips = mutableListOf<TripDraft>()
    val assignedTrips = mutableListOf<Triple<String, String, String?>>()
    val deletedTripIds = mutableListOf<String>()
    val addedRides = mutableListOf<RideDraft>()
    val assignedRides = mutableListOf<Triple<String, String?, String?>>()
    val rejectedRides = mutableListOf<Pair<String, String>>()
    val cancelledRides = mutableListOf<Pair<String, String>>()
    val addedStops = mutableListOf<Pair<String, HubStop>>()
    val addedPassengers = mutableListOf<Pair<String, HubPassenger>>()
    val addedAllocations = mutableListOf<PermanentDraft>()
    val promotedDrafts = mutableListOf<String>()
    val resumed = mutableListOf<String>()
    val pausedIds = mutableListOf<String>()
    val driverSwaps = mutableListOf<Triple<String, String, String>>()
    val deletedAllocationIds = mutableListOf<String>()

    override suspend fun driverDesignations() = ZillitResult.Success(designations)
    override suspend fun projectPeople() = ZillitResult.Success(people)

    override suspend fun vehicles(filter: VehicleFilter): ZillitResult<List<Vehicle>> {
        vehicleLoads += 1
        return ZillitResult.Success(vehicles)
    }

    override suspend fun availableVehicles() = ZillitResult.Success(vehicles)
    override suspend fun vehicleTypes() =
        ZillitResult.Success(listOf(VehicleTypeOption("Car")))

    override suspend fun addVehicleType(label: String) = ZillitResult.Success(Unit)
    override suspend fun removeVehicleType(label: String) = ZillitResult.Success(Unit)

    override suspend fun addVehicle(draft: VehicleDraft): ZillitResult<Vehicle?> {
        addedVehicles += draft
        return ZillitResult.Success(null)
    }

    override suspend fun updateVehicle(id: String, draft: VehicleDraft): ZillitResult<Vehicle?> {
        addedVehicles += draft
        return ZillitResult.Success(null)
    }

    override suspend fun setVehicleAllocation(
        id: String,
        allocation: VehicleAllocation,
    ) = ZillitResult.Success(null)

    override suspend fun deleteVehicles(ids: List<String>): ZillitResult<Unit> {
        deletedVehicleIds += ids
        return ZillitResult.Success(Unit)
    }

    override suspend fun drivers() = ZillitResult.Success(drivers)
    override suspend fun availableDrivers() = ZillitResult.Success(drivers)
    override suspend fun incompleteDrivers() =
        ZillitResult.Success(drivers.filter { it.detailsIncomplete })

    override suspend fun addDriver(draft: DriverDraft): ZillitResult<Driver?> {
        addedDrivers += draft
        return ZillitResult.Success(null)
    }

    override suspend fun updateDriverDetails(
        id: String,
        patch: DriverDetailsPatch,
    ): ZillitResult<Driver?> {
        driverPatches += id to patch
        return ZillitResult.Success(null)
    }

    override suspend fun setDriverDuty(id: String, onDuty: Boolean) = ZillitResult.Success(null)

    override suspend fun deleteDrivers(ids: List<String>): ZillitResult<Unit> {
        deletedDriverIds += ids
        return ZillitResult.Success(Unit)
    }

    override suspend fun trips(driverId: String?): ZillitResult<List<Trip>> {
        tripScopes += driverId
        return ZillitResult.Success(trips)
    }
    override suspend fun trip(id: String) = ZillitResult.Success(trips.firstOrNull { it.id == id })
    override suspend fun tripEvents(id: String) = ZillitResult.Success(emptyList<TripEvent>())

    override suspend fun addTrip(draft: TripDraft): ZillitResult<String?> {
        addedTrips += draft
        return ZillitResult.Success(newTripId)
    }

    override suspend fun assignTrip(
        id: String,
        driverId: String,
        vehicleId: String?,
    ): ZillitResult<Unit> {
        assignedTrips += Triple(id, driverId, vehicleId)
        return ZillitResult.Success(Unit)
    }

    override suspend fun setTripStatus(id: String, status: TripStatus) = ZillitResult.Success(Unit)
    override suspend fun sendTripReminder(id: String) = ZillitResult.Success(Unit)

    override suspend fun deleteTrip(id: String): ZillitResult<Unit> {
        deletedTripIds += id
        return tripDeleteFails?.let {
            ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = it))
        } ?: ZillitResult.Success(Unit)
    }

    override suspend fun rideRequests(scope: RideScope): ZillitResult<List<RideRequest>> {
        rideScopes += scope
        return ZillitResult.Success(rides)
    }

    override suspend fun activeCars() = ZillitResult.Success(emptyList<ActiveCar>())

    override suspend fun insights() = ZillitResult.Success(HubInsights.Empty)

    override suspend fun pendingRideCount() = ZillitResult.Success(0)

    override suspend fun maintenance() = ZillitResult.Success(maintenance)

    override suspend fun addMaintenance(draft: MaintenanceDraft): ZillitResult<Unit> {
        addedMaintenance += draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun completeMaintenance(id: String): ZillitResult<Unit> {
        completedMaintenance += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteMaintenance(id: String): ZillitResult<Unit> {
        deletedMaintenance += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun activeLocations() = ZillitResult.Success(driverLocations)

    // Shuttles ---------------------------------------------------------------

    override suspend fun shuttleRoutes() = ZillitResult.Success(shuttleRoutes)

    override suspend fun addShuttleRoute(draft: ShuttleRouteDraft): ZillitResult<Unit> {
        addedRoutes += draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun updateShuttleRoute(
        id: String,
        draft: ShuttleRouteDraft,
    ): ZillitResult<Unit> {
        updatedRoutes += id to draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun setShuttleRouteActive(id: String, active: Boolean): ZillitResult<Unit> {
        routeToggles += id to active
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteShuttleRoute(id: String): ZillitResult<Unit> {
        deletedRoutes += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun generateShuttleRuns(id: String): ZillitResult<Unit> {
        generatedRuns += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun shuttleRuns() = ZillitResult.Success(shuttleRuns)

    override suspend fun setShuttleRunStatus(
        id: String,
        status: ShuttleRunStatus,
    ): ZillitResult<Unit> {
        runStatuses += id to status
        return ZillitResult.Success(Unit)
    }

    // Action vehicles --------------------------------------------------------

    override suspend fun actionVehicles() = ZillitResult.Success(actionVehicles)

    override suspend fun requestActionVehicle(
        draft: AvDraft,
        timezone: String,
    ): ZillitResult<Unit> {
        requestedVehicles += draft
        timezones += timezone
        return ZillitResult.Success(Unit)
    }

    override suspend fun sourceActionVehicle(id: String, draft: AvDraft): ZillitResult<Unit> {
        sourcedVehicles += id to draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun updateActionVehicle(
        id: String,
        draft: AvDraft,
        timezone: String,
        datesOnly: Boolean,
    ): ZillitResult<Unit> {
        updatedVehicles += Triple(id, draft, datesOnly)
        return ZillitResult.Success(Unit)
    }

    override suspend fun setActionVehicleStatus(
        id: String,
        status: AvStatus,
    ): ZillitResult<Unit> {
        avStatuses += id to status
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteActionVehicle(id: String): ZillitResult<Unit> {
        deletedVehicles += id
        return ZillitResult.Success(Unit)
    }

    override suspend fun sendActionVehicleForApproval(
        id: String,
        approvers: List<AvApprover>,
    ): ZillitResult<Unit> {
        sentForApproval += id to approvers
        return ZillitResult.Success(Unit)
    }

    override suspend fun approveActionVehicle(id: String): ZillitResult<Unit> {
        decisions += Triple(id, "approve", "")
        return ZillitResult.Success(Unit)
    }

    override suspend fun rejectActionVehicle(id: String, reason: String): ZillitResult<Unit> {
        decisions += Triple(id, "reject", reason)
        return ZillitResult.Success(Unit)
    }

    override suspend fun requestActionVehicleReturn(
        id: String,
        approvers: List<AvApprover>,
        note: String,
    ): ZillitResult<Unit> {
        returnRequests += Triple(id, approvers, note)
        return ZillitResult.Success(Unit)
    }

    override suspend fun approveActionVehicleReturn(id: String): ZillitResult<Unit> {
        decisions += Triple(id, "approve-return", "")
        return ZillitResult.Success(Unit)
    }

    override suspend fun rejectActionVehicleReturn(
        id: String,
        reason: String,
    ): ZillitResult<Unit> {
        decisions += Triple(id, "reject-return", reason)
        return ZillitResult.Success(Unit)
    }

    override suspend fun editActionVehicleApprovers(
        id: String,
        add: List<String>,
        remove: List<String>,
        returnFlow: Boolean,
    ): ZillitResult<Unit> {
        approverEdits += ApproverEdit(id, add, remove, returnFlow)
        return ZillitResult.Success(Unit)
    }

    override suspend fun remindActionVehicleApprover(
        id: String,
        userId: String,
    ): ZillitResult<Unit> {
        reminders += id to userId
        return ZillitResult.Success(Unit)
    }

    override suspend fun addActionVehicleComment(id: String, text: String): ZillitResult<Unit> {
        comments += id to text
        return ZillitResult.Success(Unit)
    }

    override suspend fun sendActionVehicleForComments(
        id: String,
        userIds: List<String>,
        message: String,
    ): ZillitResult<Unit> {
        commentInvites += Triple(id, userIds, message)
        return ZillitResult.Success(Unit)
    }

    override suspend fun addRideRequest(draft: RideDraft): ZillitResult<String?> {
        addedRides += draft
        return ZillitResult.Success("r9")
    }

    override suspend fun updateRideRequest(id: String, draft: RideDraft): ZillitResult<Unit> {
        addedRides += draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun approveRide(id: String): ZillitResult<Unit> {
        rideCalls += "approve:$id"
        return rideApproveFails?.let { ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = it)) }
            ?: ZillitResult.Success(Unit)
    }

    override suspend fun rejectRide(id: String, reason: String): ZillitResult<Unit> {
        rejectedRides += id to reason
        return ZillitResult.Success(Unit)
    }

    override suspend fun assignRide(
        id: String,
        driverId: String?,
        vehicleId: String?,
    ): ZillitResult<Unit> {
        assignedRides += Triple(id, driverId, vehicleId)
        rideCalls += "assign:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun setRideStatus(id: String, status: RideStatus) = ZillitResult.Success(Unit)

    override suspend fun cancelRide(id: String, reason: String): ZillitResult<Unit> {
        cancelledRides += id to reason
        return ZillitResult.Success(Unit)
    }

    override suspend fun addRideStop(id: String, stop: HubStop): ZillitResult<Unit> {
        addedStops += id to stop
        return ZillitResult.Success(Unit)
    }

    override suspend fun completeRideStop(id: String, index: Int) = ZillitResult.Success(Unit)

    override suspend fun addRidePassenger(
        id: String,
        passenger: HubPassenger,
    ): ZillitResult<Unit> {
        addedPassengers += id to passenger
        return ZillitResult.Success(Unit)
    }

    override suspend fun permanentTrips() = ZillitResult.Success(allocations)

    override suspend fun addPermanentTrip(draft: PermanentDraft): ZillitResult<PermanentTrip?> {
        addedAllocations += draft
        return ZillitResult.Success(null)
    }

    override suspend fun updatePermanentTrip(
        id: String,
        draft: PermanentDraft,
    ): ZillitResult<PermanentTrip?> {
        addedAllocations += draft
        return ZillitResult.Success(null)
    }

    override suspend fun activateDraft(trip: PermanentTrip): ZillitResult<PermanentTrip?> {
        promotedDrafts += trip.id
        return ZillitResult.Success(null)
    }

    override suspend fun resumePermanentTrip(id: String): ZillitResult<PermanentTrip?> {
        resumed += id
        return ZillitResult.Success(null)
    }

    override suspend fun pausePermanentTrip(id: String): ZillitResult<PermanentTrip?> {
        pausedIds += id
        return ZillitResult.Success(null)
    }

    override suspend fun swapPermanentDriver(
        id: String,
        driverId: String,
        reason: String,
    ): ZillitResult<PermanentTrip?> {
        driverSwaps += Triple(id, driverId, reason)
        return ZillitResult.Success(null)
    }

    override suspend fun swapPermanentVehicle(
        id: String,
        vehicleId: String,
        reason: String,
    ) = ZillitResult.Success(null)

    override suspend fun deletePermanentTrip(id: String): ZillitResult<Unit> {
        deletedAllocationIds += id
        return ZillitResult.Success(Unit)
    }
}

/** One call to the approver-edit route, as the fake recorded it. */
private data class ApproverEdit(
    val id: String,
    val add: List<String>,
    val remove: List<String>,
    val returnFlow: Boolean,
)
