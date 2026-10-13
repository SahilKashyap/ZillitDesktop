package com.zillit.desktop.feature.transporthub

import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.HubBadgeUnits
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.HubScheduleEntry
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.ui.AllocationTab
import com.zillit.desktop.feature.transporthub.ui.DriverTab
import com.zillit.desktop.feature.transporthub.ui.HubSection
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.RideTab
import com.zillit.desktop.feature.transporthub.ui.TripTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that live in the state: who sees which rail, which tab takes
 * which row, and how the badge buckets divide two shared units.
 */
class HubStateTest {

    private val coordinator = HubViewer(userId = "u1", canView = true, canPost = true)
    private val passenger = HubViewer(userId = "u2", canView = true)
    private val driver = HubViewer(
        userId = "u3",
        canView = true,
        designation = "unit_driver_label",
        driverDesignations = listOf("driver_label", "unit_driver_label"),
    )

    // Roles ------------------------------------------------------------------

    @Test
    fun `posting rights are the only door to the coordinator's hub`() {
        assertEquals(HubRole.Coordinator, coordinator.role)
        // A designation must not open it: the rights grid has to be able to
        // revoke coordinator access from anyone, and a designation would
        // survive the revoke.
        val designatedCoordinator = HubViewer(
            userId = "u4",
            canView = true,
            designation = "transportation_coordinator_label",
        )
        assertEquals(HubRole.Passenger, designatedCoordinator.role)
    }

    @Test
    fun `a driver is decided by designation or by the temporary flag`() {
        assertEquals(HubRole.Driver, driver.role)
        assertEquals(
            HubRole.Driver,
            HubViewer(userId = "u5", canView = true, isTempDriver = true).role,
        )
        assertEquals(HubRole.Passenger, passenger.role)
    }

    @Test
    fun `posting rights win over a driver designation`() {
        val both = driver.copy(canPost = true)
        assertEquals(HubRole.Coordinator, both.role)
    }

    // The rail ---------------------------------------------------------------

    @Test
    fun `each role gets its own rail`() {
        assertEquals(HubSection.Trips, HubSection.homeFor(HubRole.Coordinator))
        assertEquals(HubSection.MyTrips, HubSection.homeFor(HubRole.Driver))
        assertEquals(HubSection.MyTrips, HubSection.homeFor(HubRole.Passenger))

        val coordinatorRail = HubSection.railFor(coordinator)
        assertTrue(HubSection.Fleet in coordinatorRail)
        assertFalse(HubSection.MyTrips in coordinatorRail)

        val passengerRail = HubSection.railFor(passenger)
        assertEquals(listOf(HubSection.MyTrips, HubSection.MyRides), passengerRail)
    }

    @Test
    fun `a viewer without view rights gets no rail at all`() {
        assertTrue(HubSection.railFor(HubViewer(userId = "u9")).isEmpty())
    }

    @Test
    fun `the rail keeps the sections phase 1 has not ported`() {
        val rail = HubSection.railFor(coordinator)
        assertTrue(HubSection.Shuttles in rail)
        assertFalse(HubSection.Shuttles.isPorted)
        assertTrue(HubSection.Fleet.isPorted)
    }

    // Badge buckets ----------------------------------------------------------

    @Test
    fun `the trip unit divides between Trips, Shuttles and Templates`() {
        assertEquals(HubBadgeUnits.TRIP, HubBadgeBucket.Trips.unit)
        assertEquals(HubBadgeUnits.TRIP, HubBadgeBucket.Shuttles.unit)
        assertTrue(HubBadgeBucket.Trips.counts("trip_request_created"))
        assertFalse(HubBadgeBucket.Trips.counts("shuttle_run_departed"))
        assertTrue(HubBadgeBucket.Shuttles.counts("shuttle_run_departed"))
        assertFalse(HubBadgeBucket.Shuttles.counts("trip_request_created"))
    }

    @Test
    fun `a driver's own trips page takes the whole trip unit`() {
        // One page, so every trip-unit notification they get belongs to it —
        // including the shuttle and template actions.
        assertTrue(HubBadgeBucket.MyTrips.counts("trip_request_created"))
        assertTrue(HubBadgeBucket.MyTrips.counts("shuttle_run_departed"))
        assertTrue(HubBadgeBucket.MyTrips.counts("something_added_next_year"))
    }

    @Test
    fun `the fleet unit divides three ways`() {
        assertTrue(HubBadgeBucket.Fleet.counts("vehicle_created"))
        assertFalse(HubBadgeBucket.Fleet.counts("driver_license_request"))
        assertTrue(HubBadgeBucket.Drivers.counts("driver_license_request"))
        assertTrue(HubBadgeBucket.Maintenance.counts("maintenance_overdue_alert"))
        assertFalse(HubBadgeBucket.Drivers.counts("maintenance_overdue_alert"))
    }

    @Test
    fun `the quiet ride actions raise no dot`() {
        assertTrue(HubBadgeBucket.Rides.counts("ride_request_created"))
        // A coordinator editing a request, or adding a stop, changes a card
        // already on screen — the socket refreshes it, so no dot is owed.
        assertFalse(HubBadgeBucket.Rides.counts("ride_request_edited"))
        assertFalse(HubBadgeBucket.Rides.counts("ride_request_stop_added"))
    }

    @Test
    fun `a section reads its own bucket's count`() {
        val state = HubUiState(
            viewer = coordinator,
            badges = mapOf(
                HubBadgeBucket.Trips.name to 4,
                HubBadgeBucket.Shuttles.name to 2,
            ),
        )
        assertEquals(4, state.badge(HubSection.Trips))
        assertEquals(2, state.badge(HubSection.Shuttles))
        // A section with no bucket shows nothing rather than borrowing one.
        assertEquals(0, state.badge(HubSection.LiveTracking))
    }

    // Tabs -------------------------------------------------------------------

    @Test
    fun `Pending holds the three statuses that mean no car yet`() {
        listOf(TripStatus.Pending, TripStatus.Draft, TripStatus.Approved).forEach { status ->
            assertTrue(TripTab.Pending.accepts(trip(status = status)), status.name)
        }
        assertFalse(TripTab.Pending.accepts(trip(status = TripStatus.Assigned)))
    }

    @Test
    fun `In Transit holds every in-flight status at once`() {
        listOf(
            TripStatus.EnRoutePickup,
            TripStatus.AtPickup,
            TripStatus.InTransit,
            TripStatus.AtDropoff,
        ).forEach { status ->
            assertTrue(TripTab.InTransit.accepts(trip(status = status)), status.name)
        }
    }

    @Test
    fun `All takes everything, including a status the build has not met`() {
        assertTrue(TripTab.All.accepts(trip(status = TripStatus.Unknown)))
    }

    @Test
    fun `a ride that is approved but not moving still counts as active`() {
        assertTrue(RideTab.Active.accepts(ride(status = RideStatus.Approved)))
        assertTrue(RideTab.Active.accepts(ride(status = RideStatus.AtPickup)))
        assertFalse(RideTab.Active.accepts(ride(status = RideStatus.Pending)))
    }

    @Test
    fun `Completed also holds the rides that were called off`() {
        assertTrue(RideTab.Completed.accepts(ride(status = RideStatus.Completed)))
        assertTrue(RideTab.Completed.accepts(ride(status = RideStatus.Cancelled)))
        // A refusal is its own tab: it needs chasing, a cancellation does not.
        assertFalse(RideTab.Completed.accepts(ride(status = RideStatus.Rejected)))
        assertTrue(RideTab.Rejected.accepts(ride(status = RideStatus.Rejected)))
    }

    // Scoping and counts -----------------------------------------------------

    @Test
    fun `my requests narrows the counts as well as the rows`() {
        val mine = ride(id = "r1", requester = "u1", status = RideStatus.Pending)
        val theirs = ride(id = "r2", requester = "u9", status = RideStatus.Pending)
        val state = HubUiState(viewer = coordinator, rides = listOf(mine, theirs))

        assertEquals(2, state.rideCounts[RideTab.Pending])
        val scoped = state.copy(onlyMyRides = true)
        // The tiles and the tabs must agree with the list under them.
        assertEquals(1, scoped.rideCounts[RideTab.Pending])
        assertEquals(listOf("r1"), scoped.visibleRides.map { it.id })
    }

    @Test
    fun `urgent counts only what is still pending`() {
        val state = HubUiState(
            viewer = coordinator,
            rides = listOf(
                ride(id = "r1", status = RideStatus.Pending, priority = RidePriority.Urgent),
                ride(id = "r2", status = RideStatus.Assigned, priority = RidePriority.Urgent),
            ),
        )
        assertEquals(1, state.urgentPendingRides)
    }

    @Test
    fun `a completed allocation is hidden, not counted`() {
        val state = HubUiState(
            viewer = coordinator,
            allocations = listOf(
                allocation(id = "p1", status = "active"),
                allocation(id = "p2", status = "completed"),
                allocation(id = "p3", status = "draft"),
            ),
        )
        assertEquals(listOf("p1", "p3"), state.visibleAllocations.map { it.id })
        assertEquals(2, state.allocationCounts[AllocationTab.All])
        assertEquals(1, state.allocationCounts[AllocationTab.Drafts])
    }

    @Test
    fun `the chase-list is its own rows, not a filter over the list`() {
        val onDuty = driverRow(id = "d1", onDuty = true)
        val offDuty = driverRow(id = "d2", onDuty = false)
        val chased = driverRow(id = "d3", onDuty = true, phone = "")
        val state = HubUiState(
            viewer = coordinator,
            drivers = listOf(onDuty, offDuty),
            incompleteDrivers = listOf(chased),
        )
        assertEquals(listOf("d1"), state.copy(driverTab = DriverTab.Available).visibleDrivers.map { it.id })
        assertEquals(listOf("d2"), state.copy(driverTab = DriverTab.Unavailable).visibleDrivers.map { it.id })
        // The chase-list's rows are not in the main list at all.
        assertEquals(listOf("d3"), state.copy(driverTab = DriverTab.Incomplete).visibleDrivers.map { it.id })
        assertEquals(1, state.driverCounts[DriverTab.Incomplete])
    }

    @Test
    fun `search matches a passenger's name, not just the row's own fields`() {
        val state = HubUiState(
            viewer = coordinator,
            trips = listOf(
                trip(id = "t1", passengerName = "Asha Menon"),
                trip(id = "t2", passengerName = "Dev Rao"),
            ),
        )
        assertEquals(listOf("t1"), state.copy(tripSearch = "asha").visibleTrips.map { it.id })
    }

    @Test
    fun `a driver is found by either id a reference might carry`() {
        val state = HubUiState(
            viewer = coordinator,
            drivers = listOf(driverRow(id = "d1", userId = "u7", name = "Asha")),
        )
        assertEquals("Asha", state.driverName("d1"))
        assertEquals("Asha", state.driverName("u7"))
        assertEquals("", state.driverName("nobody"))
        assertEquals("", state.driverName(null))
    }

    // Schedules --------------------------------------------------------------

    @Test
    fun `active days come back in calendar order, whatever order they arrived in`() {
        val trip = allocation(
            id = "p1",
            schedule = listOf(
                HubScheduleEntry(HubDay.Friday),
                HubScheduleEntry(HubDay.Monday),
                HubScheduleEntry(HubDay.Wednesday),
            ),
        )
        assertEquals(listOf("monday", "wednesday", "friday"), trip.activeDays.map { it.wire })
    }

    // Fixtures ---------------------------------------------------------------

    private fun trip(
        id: String = "t1",
        status: TripStatus = TripStatus.Pending,
        passengerName: String = "",
    ) = Trip(
        id = id,
        status = status,
        passengers = if (passengerName.isBlank()) {
            emptyList()
        } else {
            listOf(HubPassenger(name = passengerName))
        },
    )

    private fun ride(
        id: String = "r1",
        status: RideStatus = RideStatus.Pending,
        priority: RidePriority = RidePriority.Normal,
        requester: String? = null,
    ) = RideRequest(id = id, status = status, priority = priority, requesterUserId = requester)

    private fun allocation(
        id: String = "p1",
        status: String = "active",
        schedule: List<HubScheduleEntry> = emptyList(),
    ) = PermanentTrip(id = id, status = status, schedule = schedule)

    private fun driverRow(
        id: String = "d1",
        userId: String? = null,
        name: String = "Driver",
        onDuty: Boolean = false,
        phone: String = "900",
    ) = Driver(
        id = id,
        userId = userId,
        fullName = name,
        phone = phone,
        licenseNumber = "L1",
        isOnDuty = onDuty,
    )

    @Test
    fun `an allocation's state resolution is the one the page reads`() {
        assertEquals(PermanentState.Draft, allocation(status = "draft").state)
        assertEquals(PermanentState.Active, allocation(status = "active").state)
        assertEquals(
            PermanentState.Paused,
            PermanentTrip(id = "p1", status = "", tripStatus = "paused").state,
        )
    }

    @Test
    fun `an empty location is not worth sending`() {
        assertTrue(HubLocation.Empty.isEmpty)
    }
}
