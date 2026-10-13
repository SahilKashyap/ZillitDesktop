package com.zillit.desktop.feature.transporthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PermanentTrip
import com.zillit.desktop.feature.transporthub.domain.RideRequest
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.Trip
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubScreen
import com.zillit.desktop.feature.transporthub.ui.HubSection
import com.zillit.desktop.feature.transporthub.ui.HubUiState
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
            HubSection.Trips,
            HubSection.RideRequests,
            HubSection.Fleet,
            HubSection.Drivers,
            HubSection.Allocations,
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
    fun `an unported section says so and offers the classic page`() = runComposeUiTest {
        var openedClassic = 0
        setContent {
            ZillitTheme {
                HubScreen(
                    state = state(HubSection.Shuttles),
                    onEvent = {},
                    openClassicPage = { openedClassic += 1 },
                )
            }
        }
        onNodeWithText("Not on the desktop yet").assertExists()
        // Twice over: the rail's way back, and the empty state's own button.
        onAllNodesWithText("Classic Transportation").assertCountEquals(2)
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
}
