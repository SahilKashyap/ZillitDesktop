package com.zillit.desktop.feature.transporthub

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.DriverDetailsPatch
import com.zillit.desktop.feature.transporthub.domain.DriverDraft
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRepository
import com.zillit.desktop.feature.transporthub.domain.HubStop
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
import com.zillit.desktop.feature.transporthub.ui.HubViewModel
import com.zillit.desktop.feature.transporthub.ui.VehicleEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
                    passengers = listOf(passenger()),
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
                vm.currentState.tripEditor!!.copy(passengers = listOf(passenger())),
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
        assertEquals(listOf(true), repo.rideScopes.distinct())
    }

    @Test
    fun `a coordinator's ride list is not scoped`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val vm = viewModel(repo)
        vm.start(); runCurrent()
        vm.onEvent(HubEvent.SelectSection(HubSection.RideRequests)); runCurrent()
        assertEquals(listOf(false), repo.rideScopes.distinct())
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

    @Test
    fun `an unported section is opened without a load or a read`() = runTest(dispatcher) {
        val repo = FakeHubRepository()
        val seen = mutableListOf<String>()
        val vm = HubViewModel(
            repository = repo,
            resolveViewer = { HubViewer(userId = "u1", canView = true, canPost = true) },
            nowMillis = { now },
            onSectionViewed = { bucket -> seen += bucket.name },
        )
        vm.start(); runCurrent()
        val before = repo.vehicleLoads
        vm.onEvent(HubEvent.SelectSection(HubSection.Shuttles)); runCurrent()
        assertEquals(HubSection.Shuttles, vm.currentState.section)
        assertEquals(before, repo.vehicleLoads)
        assertTrue(seen.isEmpty())
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

    private fun passenger() = HubPassenger(
        userId = "u5",
        name = "Kim",
        pickup = HubLocation(address = "Base"),
        dropoff = HubLocation(address = "Set"),
    )
}

/**
 * A repository that records what it was asked to do.
 *
 * Spelled out rather than mocked so each test reads as the sequence of calls
 * one act really makes — which is the point of several of them: a trip create
 * that must be followed by an assign, a trip delete that may have to fall back
 * to a ride cancel.
 */
@Suppress("TooManyFunctions") // It implements the whole repository.
private class FakeHubRepository(
    private val vehicles: List<Vehicle> = emptyList(),
    private val drivers: List<Driver> = emptyList(),
    private val trips: List<Trip> = emptyList(),
    private val rides: List<RideRequest> = emptyList(),
    private val allocations: List<PermanentTrip> = emptyList(),
    private val people: List<ProjectPerson> = emptyList(),
    private val designations: List<String> = emptyList(),
    private val newTripId: String? = null,
    /** A server message the trip delete fails with, if any. */
    private val tripDeleteFails: String? = null,
) : HubRepository {

    var vehicleLoads = 0
    val rideScopes = mutableListOf<Boolean>()
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

    override suspend fun trips() = ZillitResult.Success(trips)
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

    override suspend fun rideRequests(onlyMine: Boolean): ZillitResult<List<RideRequest>> {
        rideScopes += onlyMine
        return ZillitResult.Success(rides)
    }

    override suspend fun activeCars() = ZillitResult.Success(emptyList<ActiveCar>())

    override suspend fun addRideRequest(draft: RideDraft): ZillitResult<String?> {
        addedRides += draft
        return ZillitResult.Success("r9")
    }

    override suspend fun updateRideRequest(id: String, draft: RideDraft): ZillitResult<Unit> {
        addedRides += draft
        return ZillitResult.Success(Unit)
    }

    override suspend fun approveRide(id: String) = ZillitResult.Success(Unit)

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
