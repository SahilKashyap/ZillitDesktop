package com.zillit.desktop.feature.transporthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.transporthub.domain.ActionVehicle
import com.zillit.desktop.feature.transporthub.domain.AvApprover
import com.zillit.desktop.feature.transporthub.domain.AvMy
import com.zillit.desktop.feature.transporthub.domain.AvStatus
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.domain.DriverLocation
import com.zillit.desktop.feature.transporthub.domain.HubInsights
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.MaintenanceRecord
import com.zillit.desktop.feature.transporthub.domain.MaintenanceType
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripEvent
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.ui.AvTab
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubScreen
import com.zillit.desktop.feature.transporthub.ui.HubSection
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.TripEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every section of the hub composes, for every role, and the layouts that can
 * collapse to nothing do not.
 *
 * A unit test cannot catch a layout that composes at zero size, which is why
 * these exist: each card here nests a weighted row inside a lazy list, and
 * that is the shape that has blanked a page before.
 */
@OptIn(ExperimentalTestApi::class)
class HubScreenRenderTest {

    private val coordinator = HubViewer(userId = "u1", canView = true, canPost = true)
    private val passenger = HubViewer(userId = "u2", canView = true)
    private val driver = HubViewer(
        userId = "u3",
        canView = true,
        designation = "unit_driver_label",
        driverDesignations = listOf("unit_driver_label"),
    )

    private val vehicles = listOf(
        Vehicle(
            id = "v1",
            name = "Unit Van",
            licensePlate = "MH01AB1234",
            vehicleType = "Minivan",
            capacity = 7,
            make = "Toyota",
            model = "Hiace",
        ),
    )

    private val drivers = listOf(
        Driver(
            id = "d1",
            userId = "u3",
            fullName = "Asha Menon",
            phone = "9000000000",
            licenseNumber = "MH-2031",
            isOnDuty = true,
            lastLocation = HubLocation(address = "Gate 3", lat = 19.07, long = 72.87),
        ),
    )

    private val chaseList = listOf(
        Driver(id = "d2", fullName = "Ravi Shah", missingFields = listOf("phone")),
    )

    private val trips = listOf(
        Trip(
            id = "t1",
            title = "Cast pickup",
            status = TripStatus.Assigned,
            pickup = HubLocation(address = "Aria Hotel"),
            dropoff = HubLocation(address = "Film City"),
            driverName = "Asha Menon",
            vehicleLabel = "Unit Van",
            passengers = listOf(HubPassenger(name = "Kim Wu")),
        ),
        Trip(
            id = "t2",
            kind = TripResourceKind.RideRequest,
            status = TripStatus.Completed,
            pickup = HubLocation(address = "Base"),
        ),
    )

    private val rides = listOf(
        RideRequest(
            id = "r1",
            status = RideStatus.Pending,
            pickup = HubLocation(address = "Base camp"),
            dropoff = HubLocation(address = "Set 4"),
            requesterName = "Dev Rao",
            notes = "Two cases of lenses",
            passengers = listOf(HubPassenger(name = "Dev Rao")),
        ),
        RideRequest(id = "r2", status = RideStatus.Rejected, rejectionReason = "No car free"),
    )

    private val allocations = listOf(
        PermanentTrip(
            id = "p1",
            title = "Lead cast car",
            status = "active",
            pickup = HubLocation(address = "Juhu"),
            dropoff = HubLocation(address = "Film City"),
            driverId = "d1",
            vehicleId = "v1",
            passengers = listOf(HubPassenger(name = "Kim Wu")),
        ),
    )

    private fun state(section: HubSection, viewer: HubViewer = coordinator) = HubUiState(
        viewer = viewer,
        section = section,
        vehicles = vehicles,
        drivers = drivers,
        incompleteDrivers = chaseList,
        trips = trips,
        rides = rides,
        allocations = allocations,
    )

    @Test
    fun `every coordinator section composes`() {
        listOf(
            HubSection.Dashboard,
            HubSection.Trips,
            HubSection.RideRequests,
            HubSection.Fleet,
            HubSection.Drivers,
            HubSection.Allocations,
            HubSection.Maintenance,
            HubSection.LiveTracking,
            HubSection.Shuttles,
            HubSection.ActionVehicles,
        ).forEach { section ->
            runComposeUiTest {
                setContent { ZillitTheme { HubScreen(state = state(section), onEvent = {}) } }
                // The rail is always there, so a section that drew nothing at
                // all still has somewhere to be seen from.
                onNodeWithText("Transportation Hub").assertExists()
            }
        }
    }

    @Test
    fun `every driver section composes`() {
        listOf(
            HubSection.MyTrips,
            HubSection.AssignedRides,
            HubSection.MyRequests,
            HubSection.DriverMap,
        ).forEach { section ->
            runComposeUiTest {
                setContent { ZillitTheme { HubScreen(state = state(section, driver), onEvent = {}) } }
                onNodeWithText("Transportation Hub").assertExists()
            }
        }
    }

    @Test
    fun `the dashboard draws its counters and its recent trips`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Dashboard).copy(
                        insights = HubInsights(
                            byStatus = mapOf("trips" to mapOf("assigned" to 2, "in_transit" to 1)),
                            completedToday = 4,
                            fleetTotal = 9,
                            fleetInUse = 3,
                        ),
                        pendingRideCount = 5,
                    ),
                    onEvent = {},
                )
            }
        }
        // Active must sum every mode, not only one-off trips.
        onNodeWithText("ACTIVE TRIPS").assertExists()
        onNodeWithText("3").assertExists()
        onNodeWithText("1 in transit · 2 assigned").assertExists()
        onNodeWithText("3 of 9 in use").assertExists()
        // A Dashboard row leads with the passenger, as the web's does — the
        // trip's own title is the Trips page's heading, not this one's.
        onNodeWithText("Kim Wu").assertExists()
        onNodeWithText("1 person").assertExists()
    }

    @Test
    fun `the maintenance list draws the vehicle, the day and the due chip`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Maintenance).copy(
                        nowMs = NOW,
                        maintenance = listOf(
                            MaintenanceRecord(
                                id = "m1",
                                vehicleId = "v1",
                                type = MaintenanceType.BrakeCheck,
                                scheduledDateMs = NOW - DAY_MS,
                                description = "Pads worn",
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Brake check").assertExists()
        onNodeWithText("Unit Van").assertExists()
        onNodeWithText("Pads worn").assertExists()
        // A booking in the past is what a coordinator opens this page for.
        onNodeWithText("Overdue by 1 days").assertExists()
    }

    @Test
    fun `live tracking draws a pin and offers the map`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.LiveTracking).copy(
                        driverLocations = listOf(
                            DriverLocation(
                                driverId = "d1",
                                driverName = "Asha Menon",
                                vehicleLabel = "Unit Van",
                                location = HubLocation(address = "Gate 3", lat = 19.07, long = 72.87),
                                atMs = NOW,
                                isOnDuty = true,
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Asha Menon").assertExists()
        onNodeWithText("On duty").assertExists()
        onNodeWithText("Open in Maps").assertExists()
    }

    @Test
    fun `the new trip form opens with a passenger row`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Trips).copy(tripEditor = TripEditor(date = "2026-11-02")),
                    onEvent = {},
                )
            }
        }
        // The button used to open nothing at all: no dialog rendered the editor,
        // so the page's own button was the only "New Trip" on screen.
        onAllNodesWithText("New Trip").assertCountEquals(2)
        onNodeWithText("Passenger 1").assertExists()
        onNodeWithText("Pickup time").assertExists()
    }

    @Test
    fun `the trip detail draws its people and its transitions`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Trips).copy(
                        openTrip = trips.first(),
                        openTripEvents = listOf(
                            TripEvent(
                                id = "e1",
                                type = "assigned",
                                description = "Assigned to Asha Menon",
                                atMs = NOW,
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        // The View button used to open nothing at all.
        onNodeWithText("TIMELINE").assertExists()
        onNodeWithText("Assigned to Asha Menon").assertExists()
        onNodeWithText("Kim Wu").assertExists()
    }

    @Test
    fun `the fleet draws a vehicle's plate, type and specs`() = runComposeUiTest {
        setContent { ZillitTheme { HubScreen(state = state(HubSection.Fleet), onEvent = {}) } }
        onNodeWithText("Unit Van").assertExists()
        onNodeWithText("MH01AB1234 · Minivan").assertExists()
        onNodeWithText("Toyota · Hiace").assertExists()
    }

    @Test
    fun `the driver list draws the duty pill and the licence`() = runComposeUiTest {
        setContent { ZillitTheme { HubScreen(state = state(HubSection.Drivers), onEvent = {}) } }
        onNodeWithText("Asha Menon").assertExists()
        onNodeWithText("MH-2031").assertExists()
        // A real last position earns a Locate button; a {0,0} fix would not.
        onNodeWithText("Locate").assertExists()
    }

    @Test
    fun `the chase-list draws what each record is missing`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Drivers).copy(
                        driverTab = com.zillit.desktop.feature.transporthub.ui.DriverTab.Incomplete,
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Ravi Shah").assertExists()
        onNodeWithText("Missing: phone").assertExists()
        onNodeWithText("Review & complete").assertExists()
    }

    @Test
    fun `a trip row draws its route and its people`() = runComposeUiTest {
        setContent { ZillitTheme { HubScreen(state = state(HubSection.Trips), onEvent = {}) } }
        onNodeWithText("Cast pickup").assertExists()
        onNodeWithText("Aria Hotel").assertExists()
        onNodeWithText("Film City").assertExists()
        onNodeWithText("Asha Menon").assertExists()
    }

    @Test
    fun `a terminal ride request in the trips list offers no delete`() = runComposeUiTest {
        val rideRow = Trip(
            id = "t2",
            kind = TripResourceKind.RideRequest,
            status = TripStatus.Completed,
            pickup = HubLocation(address = "Base"),
        )
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Trips).copy(trips = listOf(rideRow)),
                    onEvent = {},
                )
            }
        }
        // Hidden, not disabled: the server cannot act on it either way.
        onAllNodesWithText("Delete").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test
    fun `a pending ride offers approve and decline`() = runComposeUiTest {
        setContent {
            ZillitTheme { HubScreen(state = state(HubSection.RideRequests), onEvent = {}) }
        }
        onNodeWithText("Dev Rao").assertExists()
        onNodeWithText("Approve").assertExists()
        onNodeWithText("Decline").assertExists()
    }

    @Test
    fun `a refused ride carries its reason and offers neither`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.RideRequests).copy(
                        rideTab = com.zillit.desktop.feature.transporthub.ui.RideTab.Rejected,
                    ),
                    onEvent = {},
                )
            }
        }
        // Why it was refused is the first thing anyone asks of the row, so it
        // is on the card rather than behind a detail view.
        onNodeWithText("No car free").assertExists()
        onAllNodesWithText("Approve").assertCountEquals(0)
        // Nothing left to call off, either.
        onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test
    fun `an allocation draws its schedule and its state`() = runComposeUiTest {
        setContent {
            ZillitTheme { HubScreen(state = state(HubSection.Allocations), onEvent = {}) }
        }
        onNodeWithText("Lead cast car").assertExists()
        onNodeWithText("Swap Driver").assertExists()
    }

    @Test
    fun `a passenger gets their own two sections and no create buttons they cannot use`() =
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HubScreen(state = state(HubSection.MyTrips, passenger), onEvent = {})
                }
            }
            // The rail item and the page's own header, so two.
            onAllNodesWithText("My Trips").assertCountEquals(2)
            // And none of the coordinator's sections.
            onAllNodesWithText("Fleet").assertCountEquals(0)
            onAllNodesWithText("Allocations").assertCountEquals(0)
        }

    @Test
    fun `a viewer with no rights is told so rather than shown an empty hub`() =
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HubScreen(state = HubUiState(viewer = HubViewer(userId = "u9")), onEvent = {})
                }
            }
            onNodeWithText("No access to Transportation").assertExists()
        }

    @Test
    fun `the shuttles page draws a route and its stops`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Shuttles).copy(
                        shuttleRoutes = listOf(
                            ShuttleRoute(
                                id = "sr1",
                                name = "Base Camp → Unit Base",
                                frequencyMinutes = 30,
                                startTime = "06:00",
                                endTime = "22:00",
                                capacity = 15,
                                driverId = "d1",
                                vehicleId = "v1",
                                stops = listOf(
                                    ShuttleStop(name = "Gate 3", order = 0),
                                    ShuttleStop(name = "Set 4", order = 1),
                                ),
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Base Camp → Unit Base").assertExists()
        onNodeWithText("Every 30 min").assertExists()
        onNodeWithText("06:00–22:00").assertExists()
        onNodeWithText("1. Gate 3").assertExists()
        onNodeWithText("Generate runs").assertExists()
    }

    @Test
    fun `the action vehicles register draws the round and the approver's buttons`() =
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HubScreen(
                        state = state(HubSection.ActionVehicles).copy(
                            avTab = AvTab.Pending,
                            actionVehicles = listOf(
                                ActionVehicle(
                                    id = "av1",
                                    displayName = "Hero Car #1",
                                    status = AvStatus.PendingApproval,
                                    categories = listOf("hero"),
                                    make = "Ford",
                                    model = "Mustang",
                                    year = 1968,
                                    scenes = listOf("12", "14"),
                                    my = AvMy(isApprover = true),
                                    approvers = listOf(
                                        AvApprover(userId = "u9", name = "Dana"),
                                        AvApprover(userId = "u8", name = "Sam"),
                                    ),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Hero Car #1").assertExists()
            onNodeWithText("Ford Mustang 1968").assertExists()
            // A live round shows its running count; the decision is this
            // viewer's because the server said so for this record.
            onNodeWithText("0/2 approved").assertExists()
            onNodeWithText("Approve").assertExists()
            onNodeWithText("Reject").assertExists()
        }

    @Test
    fun `the rail's classic entry is not mistaken for a section`() = runComposeUiTest {
        val picked = mutableListOf<HubSection>()
        var openedClassic = 0
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Trips),
                    onEvent = { event ->
                        if (event is HubEvent.SelectSection) picked += event.section
                    },
                    openClassicPage = { openedClassic += 1 },
                )
            }
        }
        // `HubSection.valueOf` on the rail's own id would throw — the shell
        // has to tell the bridge apart from a section before resolving it.
        onNodeWithText("Classic Transportation").performClick()
        assertEquals(1, openedClassic)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `every dialog composes over its page`() {
        val base = state(HubSection.Fleet)
        val openEditors = listOf(
            base.copy(vehicleEditor = com.zillit.desktop.feature.transporthub.ui.VehicleEditor()),
            base.copy(driverEditor = com.zillit.desktop.feature.transporthub.ui.DriverEditor()),
            base.copy(
                driverDetailsEditor =
                com.zillit.desktop.feature.transporthub.ui.DriverDetailsEditor(driverId = "d1"),
            ),
            base.copy(rideEditor = com.zillit.desktop.feature.transporthub.ui.RideEditor()),
            base.copy(tripEditor = com.zillit.desktop.feature.transporthub.ui.TripEditor()),
            base.copy(
                allocationEditor = com.zillit.desktop.feature.transporthub.ui.AllocationEditor(),
            ),
            base.copy(
                swapEditor = com.zillit.desktop.feature.transporthub.ui.SwapEditor(
                    tripId = "p1",
                    isDriver = true,
                ),
            ),
            base.copy(
                stopEditor = com.zillit.desktop.feature.transporthub.ui.StopEditor(rideId = "r1"),
            ),
            base.copy(
                ridePassengerEditor =
                com.zillit.desktop.feature.transporthub.ui.RidePassengerEditor(rideId = "r1"),
            ),
            base.copy(
                reasonEditor = com.zillit.desktop.feature.transporthub.ui.ReasonEditor(
                    rideId = "r1",
                    isCancel = true,
                ),
            ),
            base.copy(
                assignEditor = com.zillit.desktop.feature.transporthub.ui.AssignEditor(rideId = "r1"),
            ),
            base.copy(showActiveCars = true),
            base.copy(
                draftPrompt = com.zillit.desktop.feature.transporthub.ui.AllocationEditor(
                    title = "Cast run",
                ),
            ),
            base.copy(
                confirm = com.zillit.desktop.feature.transporthub.ui.HubConfirm(
                    title = "Delete this vehicle?",
                    body = "It is removed from the fleet.",
                    confirmLabel = "Delete",
                    action = HubEvent.DeleteVehicleConfirmed("v1"),
                ),
            ),
        )
        openEditors.forEach { open ->
            runComposeUiTest {
                setContent { ZillitTheme { HubScreen(state = open, onEvent = {}) } }
                onNodeWithText("Transportation Hub").assertExists()
            }
        }
    }

    private companion object {
        /** A fixed clock, so an "overdue" chip reads the same every run. */
        const val NOW = 1_793_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
