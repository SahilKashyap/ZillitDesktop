package com.zillit.desktop.feature.maps

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapScreen
import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.ui.PinEditor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pin editor's Address searches a place by name.
 *
 * The canvas's click-to-place already proposes a pin at a clicked point
 * (`MapViewModel.proposePin`), but only for a host that HAS a canvas, and only
 * for somewhere already on screen. The web's pin form carries both routes —
 * a map and an address autocomplete that fills address, lat and lng together
 * (`map-module/hooks/useLocationForm.js:96-98`) — and the pin wire carries all
 * three (`location_address`, `location.lat`, `location.long`,
 * MapRepositoryImpl.kt:210), so a pick persists.
 */
@OptIn(ExperimentalTestApi::class)
class PinAddressPickRenderTest {

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        var openedWith: PickedLocation? = null

        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            openedWith = initial
            return place
        }
    }

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Marine Drive, Mumbai",
        lat = 18.94,
        lng = 72.82,
    )

    @Test
    fun `picking a place fills the address and both coordinates`() = runComposeUiTest {
        val events = mutableListOf<MapEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    MapScreen(MapUiState(pinEditor = PinEditor(name = "Unit base")), events::add)
                }
            }
        }

        onNodeWithText("Pick on map").performClick()
        waitForIdle()

        val changed = events.filterIsInstance<MapEvent.PinChanged>().last()
        assertEquals("12 Marine Drive, Mumbai", changed.address)
        assertEquals("18.94", changed.latText)
        assertEquals("72.82", changed.lngText)
        // The pin's own name is left alone, as the web's autocomplete leaves it.
        assertEquals(null, changed.name)
    }

    @Test
    fun `the map opens where the pin already stands`() = runComposeUiTest {
        val picker = FakePicker(aria)
        val placed = PinEditor(name = "Unit base", address = "Pinewood", latText = "1.5", lngText = "-2.25")
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    MapScreen(MapUiState(pinEditor = placed), onEvent = {})
                }
            }
        }

        onNodeWithText("Pick on map").performClick()
        waitForIdle()

        assertEquals(PickedLocation("Unit base", "Pinewood", 1.5, -2.25), picker.openedWith)
    }

    @Test
    fun `a studio zone has no address to pick`() = runComposeUiTest {
        // A zone is a centre and a radius; the web sends it no `location_address`.
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    MapScreen(MapUiState(pinEditor = PinEditor(isZone = true)), onEvent = {})
                }
            }
        }

        onAllNodesWithText("Pick on map").assertCountEquals(0)
        onNodeWithText("Radius (miles)").assertExists()
    }

    @Test
    fun `with no picker wired the coordinates are still typed`() = runComposeUiTest {
        setContent { ZillitTheme { MapScreen(MapUiState(pinEditor = PinEditor()), onEvent = {}) } }

        onAllNodesWithText("Pick on map").assertCountEquals(0)
        onNodeWithText("Latitude", useUnmergedTree = true).assertExists()
        onNodeWithText("Longitude", useUnmergedTree = true).assertExists()
    }
}
