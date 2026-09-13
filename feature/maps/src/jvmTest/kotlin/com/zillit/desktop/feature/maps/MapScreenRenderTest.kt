package com.zillit.desktop.feature.maps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.BoundaryMode
import com.zillit.desktop.feature.maps.domain.BoundaryStatus
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.RouteInfo
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.domain.buildBoundaryPrompt
import com.zillit.desktop.feature.maps.ui.AddCityState
import com.zillit.desktop.feature.maps.ui.AddressPick
import com.zillit.desktop.feature.maps.ui.ConfirmAction
import com.zillit.desktop.feature.maps.ui.DirectionsState
import com.zillit.desktop.feature.maps.ui.ListViewState
import com.zillit.desktop.feature.maps.ui.LocationFormState
import com.zillit.desktop.feature.maps.ui.MapDialog
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapPanel
import com.zillit.desktop.feature.maps.ui.MapScreen
import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.ui.RouteEnd
import com.zillit.desktop.feature.maps.ui.SearchState
import com.zillit.desktop.feature.maps.ui.ShareState
import com.zillit.desktop.feature.maps.ui.TypeFormState
import com.zillit.desktop.feature.maps.ui.TypesPanelState
import com.zillit.desktop.feature.maps.ui.ZoneFormState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every panel, bar and dialog of the map tool composed from a real-shaped
 * production. What these catch is a surface that throws on first composition
 * or loses its controls — the kind no view-model test reaches.
 */
@OptIn(ExperimentalTestApi::class)
class MapScreenRenderTest {

    private val base = MapFixtures.base

    private fun ComposeUiTest.shows(vararg texts: String) = texts.forEach { text ->
        assertTrue(
            onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty(),
            "\"$text\" should be on screen",
        )
    }

    private fun ComposeUiTest.open(initial: MapUiState, events: MutableList<MapEvent> = mutableListOf()): (MapUiState) -> Unit {
        var state by mutableStateOf(initial)
        setContent { ZillitTheme(animateThemeChange = false) { MapScreen(state, events::add) } }
        waitForIdle()
        return { next ->
            state = next
            waitForIdle()
        }
    }

    private fun ComposeUiTest.click(text: String) {
        onAllNodesWithText(text, substring = false).onFirst().performClick()
        waitForIdle()
    }

    @Test
    fun `the toolbar offers every control, and presses reach the view model`() = runComposeUiTest {
        val events = mutableListOf<MapEvent>()
        open(base, events)
        shows("Mumbai", "4 locations", "Fit All", "Search", "Filter", "Pin Location", "List View", "Zone: Andheri Zone", "LOC Types")

        click("Fit All")
        click("Pin Location")
        assertTrue(MapEvent.Toolbar.FitAll in events, events.toString())
        assertTrue(MapEvent.Toolbar.TogglePinMode in events, events.toString())
    }

    @Test
    fun `the bars under the toolbar`() = runComposeUiTest {
        val show = open(base.copy(pinMode = true, search = SearchState("marine")))
        shows("to place a pin", "Marine Drive Promenade", "4 / 4 locations")

        show(base.copy(filterOpen = true, typeFilters = setOf("Hotel")))
        shows("Filter by Type", "Clear All", "Base Camp", "Parking", "1 / 4 locations")

        show(
            base.copy(
                directions = DirectionsState(
                    pickup = RouteEnd(LatLng(19.05, 72.83), "Bandra West"),
                    drop = RouteEnd(LatLng(18.94, 72.82), "Marine Dr", "Marine Drive Promenade"),
                    pickupText = "Bandra West",
                    dropText = "Marine Dr",
                    route = RouteInfo("14.2 km", 14_200.0, "38 mins"),
                ),
            ),
        )
        shows("Get Directions", "14.2 km", "8.8 mi", "38 mins", "Call a Driver")

        show(base.copy(canvasError = "Google rejected this project's Maps key, so the map cannot load."))
        shows("Google rejected")
    }

    @Test
    fun `the Cities panel lists, counts and selects`() = runComposeUiTest {
        val events = mutableListOf<MapEvent>()
        open(base.copy(panels = listOf(MapPanel.Cities)), events)
        shows("Cities", "3 Cities", "Add Another City", "Pune", "Goa", "Maharashtra, India")

        click("Goa")
        assertTrue(MapEvent.Cities.Select("goa") in events, events.toString())
    }

    @Test
    fun `the location form, its type menu, and a location's details`() = runComposeUiTest {
        val l1 = MapFixtures.locations[0]
        val form = LocationFormState(
            editId = "l1",
            cityId = "mumbai",
            name = l1.name,
            type = "Hotel",
            subTypes = listOf("5 star"),
            description = l1.description,
            address = l1.address,
            point = l1.point,
            existing = l1.attachments,
        )
        val show = open(base.copy(panels = listOf(MapPanel.LocationForm), locationForm = form))
        shows("Edit Location", "Basic Information", "Location Details", "Budget", "Update Location")

        show(base.copy(panels = listOf(MapPanel.LocationForm), locationForm = form.copy(editId = null, typeMenuOpen = true)))
        shows("New Location", "Base Camp", "Shooting", "Save Location")

        show(base.copy(listView = ListViewState(), panels = listOf(MapPanel.LocationDetail("l3"))))
        shows("Locations", "New Location", "Film City Unit Base", "SC 42", "18.943100, 72.823000", "Copy")
    }

    @Test
    fun `the zone list, form and details`() = runComposeUiTest {
        val show = open(base.copy(panels = listOf(MapPanel.ZoneList)))
        shows("Studio Zones", "2 Zones", "Add Zone", "MG Road & Link Road Zone", "30 mi radius", "12.5 mi radius", "Clear Zone")

        val form = ZoneFormState(
            cityId = "mumbai",
            name = "MG Road & Link Road Zone",
            point = LatLng(19.16, 72.85),
            useCustom = true,
            customTouched = true,
            mode = CenterPointType.Intersection,
            street1 = "MG Road",
            street1Picked = true,
            street2 = "Link Road",
            street2Picked = true,
            intersection = "MG Rd & Link Rd, Goregaon West",
        )
        show(base.copy(panels = listOf(MapPanel.ZoneList, MapPanel.ZoneForm), zoneForm = form))
        shows("New Studio Zone", "Street Intersection", "Intersection Found", "Create Zone")

        show(base.copy(panels = listOf(MapPanel.ZoneList, MapPanel.ZoneDetail("z2"))))
        shows("Zone Details", "MG Road & Link Road", "12.5 miles")
    }

    @Test
    fun `the types panel and its form`() = runComposeUiTest {
        open(
            base.copy(
                panels = listOf(MapPanel.Types),
                typesPanel = TypesPanelState(form = TypeFormState(name = "Warehouse", subTypes = listOf("Props"), iconPickerOpen = true)),
            ),
        )
        shows("Location Types", "4 Types", "New Type", "Props", "Base Camp", "Tents", "Parking")
    }

    @Test
    fun `the dialogs`() = runComposeUiTest {
        val events = mutableListOf<MapEvent>()
        val outside = buildBoundaryPrompt(BoundaryStatus.OutsideCity, BoundaryMode.Add, cityName = "Mumbai", address = "Lonavala")!!
        val show = open(base.copy(dialog = MapDialog.Boundary(outside)), events)
        shows("Outside Mumbai", "Lonavala", "Pin Location in the Same City")
        click("Create Another City & Pin Location")
        assertTrue(MapEvent.Dialogs.Boundary(BoundaryChoice.CreateCity) in events, events.toString())

        show(
            base.copy(
                dialog = MapDialog.AddCity(
                    AddCityState(query = "Hyder", suggestions = listOf(PlacePrediction("p1", "Hyderabad, Telangana", "Hyderabad", "Telangana"))),
                ),
            ),
        )
        shows("Add City", "Hyderabad", "Telangana")

        show(
            base.copy(
                dialog = MapDialog.Share(
                    ShareState("Taj Lands End", "📍 Taj Lands End", "https://maps", listOf(SharePerson("u2", "Amy Rao", "Driver")), setOf("u2")),
                ),
            ),
        )
        shows("Send in Zillit", "Amy Rao", "Copy text", "Open in Google Maps", "Send (1)")

        show(base.copy(dialog = MapDialog.Confirm("Delete City", "Are you sure?", "Delete", true, ConfirmAction.DeleteCity("mumbai"))))
        shows("Delete City", "Are you sure?")

        show(base.copy(dialog = MapDialog.NewType(TypeFormState(name = "Warehouse", iconPickerOpen = true))))
        shows("New Type", "Create")

        show(base.copy(dialog = MapDialog.AddressOutside("Mumbai", AddressPick("Lonavala", "Lonavala, MH", LatLng(18.75, 73.4)))))
        shows("Outside Mumbai", "Lonavala, MH", "Use Anyway")

        show(base.copy(dialog = MapDialog.Photo(MapFixtures.photo, null)))
        shows("lobby.jpg")
    }

    @Test
    fun `nothing to show says why`() = runComposeUiTest {
        val show = open(MapUiState(viewer = MapFixtures.viewer, citiesLoaded = true))
        shows("No cities added yet", "Add City")

        show(base.copy(viewer = MapFixtures.viewer.copy(canView = false)))
        shows("You do not have access to the map tool.")
    }
}
